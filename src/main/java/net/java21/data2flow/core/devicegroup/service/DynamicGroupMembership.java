package net.java21.data2flow.core.devicegroup.service;

import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.GroupMembershipChanged;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.devicegroup.domain.DeviceGroup;
import net.java21.data2flow.core.devicegroup.domain.GroupCriteria;
import net.java21.data2flow.core.devicegroup.repository.DeviceGroupRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 그룹 구성원 계산(DEV-06.02, BR-DEV-13). 기기가 바뀌면 같은 트랜잭션에서 그 기기만 다시 판정하고(1분 이내 요구를 즉시로 만족),
 * 1시간마다 조직 전체를 다시 계산한다(빠뜨린 변경 보정). 바뀌면 EVT-DEV-07 {@code group.membership.changed}와
 * 설정 변경(GROUP)을 아웃박스로 보내고 GROUPS 설정 버전을 올린다.
 */
@Service
public class DynamicGroupMembership {

    private static final Logger log = LoggerFactory.getLogger(DynamicGroupMembership.class);
    /** advisory lock 키: "d2fgrp" */
    static final long LOCK_KEY = 0x6432_6667_7270L;
    /** 동적 그룹 계산 때 한 번에 읽는 최대 기기 수(1,000대 한도보다 크게: 넘었는지 알 수 있게) */
    static final int SCAN_LIMIT = 100_000;

    private final DeviceGroupRepository groups;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final DeploymentOrganization deployment;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DynamicGroupMembership(DeviceGroupRepository groups, CoreEventPublisher publisher, ConfigVersions configVersions,
                                  DeploymentOrganization deployment, JsonMapper json, JdbcClient jdbc,
                                  PlatformTransactionManager txManager, Clock clock) {
        this.groups = groups;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.deployment = deployment;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 기기 하나가 바뀌었을 때: 모든 동적 그룹에서 이 기기의 소속을 다시 판정한다(호출자 트랜잭션 안) */
    public void refreshDevice(long organizationId, long deviceId) {
        for (DeviceGroup group : groups.findDynamic(organizationId)) {
            GroupCriteria criteria = criteria(group);
            boolean matches = !groups.findMatchingDeviceIds(organizationId, criteria, deviceId, null, 1).isEmpty();
            boolean member = groups.isMember(organizationId, group.id(), deviceId);
            if (matches && !member) {
                if (group.memberCount() >= DeviceGroup.MAX_MEMBERS) {
                    log.warn("동적 그룹 {} 구성원이 {}대에 이르러 기기 {}를 넣지 않음(BR-DEV-12)", group.id(), DeviceGroup.MAX_MEMBERS, deviceId);
                    continue;
                }
                changed(group, groups.addMembers(organizationId, group.id(), List.of(deviceId), DeviceGroup.DYNAMIC, clock.instant()),
                        List.of());
            } else if (!matches && member) {
                changed(group, List.of(), groups.removeMembers(organizationId, group.id(), List.of(deviceId)));
            }
        }
    }

    /** 삭제한 기기를 모든 그룹(정적 포함)에서 뺀다 */
    public void removeDevice(long organizationId, long deviceId) {
        groups.findGroupIdsOfDevice(organizationId, deviceId).forEach(groupId -> groups.findById(organizationId, groupId).ifPresent(group ->
                        changed(group, List.of(), groups.removeMembers(organizationId, groupId, List.of(deviceId)))));
    }

    /** 그룹 하나 전체 다시 계산(동적 그룹 저장 직후, 1시간 작업). 바뀐 내용을 돌려준다 */
    public GroupMembershipChanged recompute(DeviceGroup group) {
        if (!group.dynamic()) {
            return new GroupMembershipChanged(group.id(), List.of(), List.of());
        }
        long org = group.organizationId();
        List<Long> target = groups.findMatchingDeviceIds(org, criteria(group), null, null, SCAN_LIMIT);
        if (target.size() > DeviceGroup.MAX_MEMBERS) {
            log.warn("동적 그룹 {} 조건에 맞는 기기가 {}대로 한도 {}대를 넘음 — 앞의 {}대만 둠(BR-DEV-12)", group.id(), target.size(),
                    DeviceGroup.MAX_MEMBERS, DeviceGroup.MAX_MEMBERS);
            target = target.subList(0, DeviceGroup.MAX_MEMBERS);
        }
        Set<Long> current = new HashSet<>(groups.findMemberIds(org, group.id()));
        Set<Long> wanted = new HashSet<>(target);
        List<Long> toAdd = new ArrayList<>(target.stream().filter(id -> !current.contains(id)).toList());
        List<Long> toRemove = current.stream().filter(id -> !wanted.contains(id)).sorted().toList();
        List<Long> added = groups.addMembers(org, group.id(), toAdd, DeviceGroup.DYNAMIC, clock.instant());
        List<Long> removed = groups.removeMembers(org, group.id(), toRemove);
        changed(group, added, removed);
        return new GroupMembershipChanged(group.id(), added, removed);
    }

    /** 정적 그룹 구성원 직접 변경 뒤 */
    public void membersChanged(DeviceGroup group, List<Long> added, List<Long> removed) {
        changed(group, added, removed);
    }

    /** 1시간 전체 재계산. 다른 파드가 돌고 있으면 false */
    public boolean recomputeAll() {
        Boolean ran = tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            int changedGroups = 0;
            for (Long org : groups.findOrganizationsWithDynamicGroups(deployment.restriction())) {
                for (DeviceGroup group : groups.findDynamic(org)) {
                    GroupMembershipChanged result = recompute(group);
                    if (!result.added().isEmpty() || !result.removed().isEmpty()) {
                        changedGroups++;
                    }
                }
            }
            log.info("동적 그룹 재계산: 바뀐 그룹 {}", changedGroups);
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    private void changed(DeviceGroup group, List<Long> added, List<Long> removed) {
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        long org = group.organizationId();
        groups.updateMemberCount(org, group.id());
        publisher.event(EventType.GROUP_MEMBERSHIP_CHANGED, org, new GroupMembershipChanged(group.id(), added, removed));
        publisher.configChanged(EntityType.GROUP, group.id(), group.version(), org);
        configVersions.bump(org, ConfigVersions.GROUPS);
    }

    GroupCriteria criteria(DeviceGroup group) {
        return GroupCriteria.parse(json.readTree(group.criteria()), "criteria");
    }

    /** 1시간마다 전체 재계산(BR-DEV-13). 테스트는 data2flow.core.jobs.enabled=false로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final DynamicGroupMembership membership;

        Schedule(DynamicGroupMembership membership) {
            this.membership = membership;
        }

        @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
        void run() {
            membership.recomputeAll();
        }
    }
}
