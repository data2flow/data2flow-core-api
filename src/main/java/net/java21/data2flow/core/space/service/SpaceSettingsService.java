package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.space.domain.ScheduleSlot;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.ModeResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.ScheduleRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.ScheduleResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.Slot;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetItem;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsResponse;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository.TargetRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공간 목표 범위(DEV-01.04, API-DEV-06)·운영 시간표(DEV-11.01, API-DEV-07)·운영 모드 조회(API-DEV-08 GET)와 내부 조회(API-DEV-126).
 * 하위 공간이 따로 정하지 않으면 가장 가까운 상위 값을 쓴다(BR-DEV-04). 바꾸면 공간 버전을 올리고 EVT-DEV-05·설정 변경 SPACE를 낸다.
 */
@Service
public class SpaceSettingsService {

    static final int MAX_TARGETS = 50;
    static final int MAX_SLOTS = 70;

    private final SpaceRepository spaces;
    private final SpaceSettingsRepository settings;
    private final SpaceSupport support;
    private final DeploymentOrganization deployment;
    private final java.time.Clock clock;

    public SpaceSettingsService(SpaceRepository spaces, SpaceSettingsRepository settings, SpaceSupport support,
                                DeploymentOrganization deployment, java.time.Clock clock) {
        this.spaces = spaces;
        this.settings = settings;
        this.support = support;
        this.deployment = deployment;
        this.clock = clock;
    }

    /** 목표 조회(GET …/targets, 사전 작업으로 추가) */
    @Transactional(readOnly = true)
    public TargetsResponse targets(long spaceId) {
        return targetsOf(support.visible(spaceId, Permission.DEV_READ));
    }

    private TargetsResponse targetsOf(Space space) {
        List<Space> chain = support.chain(space);
        return SpaceInheritance.targets(chain, settings.findTargets(space.organizationId(), chain.stream().map(Space::id).toList()));
    }

    /** API-DEV-06 목표 저장: inherit=true면 이 공간 값을 모두 지우고 상위 값을 쓴다. 아니면 items로 통째로 바꾼다 */
    @Transactional
    public TargetsResponse replaceTargets(long spaceId, TargetsRequest req) {
        Space space = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = space.organizationId();
        boolean inherit = req != null && Boolean.TRUE.equals(req.inherit());
        List<TargetItem> items = inherit || req == null || req.items() == null ? List.of() : req.items();
        if (items.size() > MAX_TARGETS) {
            throw SpaceSupport.invalid("items", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        List<TargetRow> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TargetItem item = items.get(i);
            String field = "items[" + i + "]";
            String key = item == null || item.metricKey() == null ? "" : item.metricKey().strip();
            if (key.isEmpty()) {
                errors.add(new FieldErrorDetail(field + ".metricKey", "NotBlank", null));
                continue;
            }
            if (!keys.add(key)) {
                errors.add(new FieldErrorDetail(field + ".metricKey", "DUPLICATE", null));
            }
            Double min = item.min();
            Double max = item.max();
            if (min == null && max == null) {
                errors.add(new FieldErrorDetail(field, "NotNull", "min|max"));
            } else if ((min != null && !Double.isFinite(min)) || (max != null && !Double.isFinite(max))) {
                errors.add(new FieldErrorDetail(field, "Type", null));
            } else if (min != null && max != null && min >= max) {
                errors.add(new FieldErrorDetail(field + ".min", "MIN_NOT_LESS_THAN_MAX", null));
            }
            rows.add(new TargetRow(space.id(), key, min, max));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Set<String> existing = settings.findExistingMetricKeys(org, keys);
        if (!existing.containsAll(keys)) {
            throw new BusinessException(SpaceErrorCode.METRIC_NOT_FOUND);
        }
        Instant now = clock.instant();
        settings.replaceTargets(org, space.id(), rows, support.user().userId(), now);
        int version = spaces.touch(org, space.id(), support.user().userId(), now);
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, space.id(), Map.of("targets", keys, "inherit", inherit));
        support.changed(org, space.id(), space.path(), "UPDATED", version);
        return targetsOf(space);
    }

    /** 시간표 조회(GET …/schedule, 사전 작업으로 추가) */
    @Transactional(readOnly = true)
    public ScheduleResponse schedule(long spaceId) {
        return scheduleOf(support.visible(spaceId, Permission.DEV_READ));
    }

    private ScheduleResponse scheduleOf(Space space) {
        List<Space> chain = support.chain(space);
        SpaceInheritance.EffectiveSchedule effective = SpaceInheritance.schedule(chain,
                settings.findSlots(space.organizationId(), chain.stream().map(Space::id).toList()));
        return SpaceInheritance.scheduleResponse(chain, effective);
    }

    /**
     * API-DEV-07 시간표 저장. inherit=true면 이 공간 구간을 지우고 상위를 따른다. inherit=false면 slots로 바꾼다(빈 목록이면 운영 시간 없음).
     * 같은 요일 구간이 겹치면 400 SCHEDULE_OVERLAP(AT-DEV-02.3). 맞닿은 구간(09:00~12:00, 12:00~13:00)은 겹침이 아니다.
     */
    @Transactional
    public ScheduleResponse replaceSchedule(long spaceId, ScheduleRequest req) {
        Space space = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = space.organizationId();
        boolean inherit = req != null && Boolean.TRUE.equals(req.inherit());
        List<Slot> input = inherit || req.slots() == null ? List.of() : req.slots();
        if (input.size() > MAX_SLOTS) {
            throw SpaceSupport.invalid("slots", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<ScheduleSlot> slots = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Slot slot = input.get(i);
            String field = "slots[" + i + "]";
            if (slot == null || slot.dayOfWeek() == null || slot.dayOfWeek() < 1 || slot.dayOfWeek() > 7) {
                errors.add(new FieldErrorDetail(field + ".dayOfWeek", "Range", "1~7"));
                continue;
            }
            if (!ScheduleSlot.validTime(slot.start()) || !ScheduleSlot.validTime(slot.end()) || "24:00".equals(slot.start())) {
                errors.add(new FieldErrorDetail(field, "Pattern", "HH:mm"));
                continue;
            }
            ScheduleSlot parsed = new ScheduleSlot(slot.dayOfWeek(), slot.start(), slot.end());
            if (parsed.startMinute() >= parsed.endMinute()) {
                errors.add(new FieldErrorDetail(field + ".end", "START_AFTER_END", null));
                continue;
            }
            slots.add(parsed);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).overlaps(slots.get(j))) {
                    throw new BusinessException(SpaceErrorCode.SCHEDULE_OVERLAP);
                }
            }
        }
        Instant now = clock.instant();
        settings.replaceSlots(org, space.id(), slots, now);
        spaces.updateScheduleInherit(org, space.id(), inherit);
        int version = spaces.touch(org, space.id(), support.user().userId(), now);
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, space.id(), Map.of("schedule", slots.size(), "inherit", inherit));
        support.changed(org, space.id(), space.path(), "UPDATED", version);
        return scheduleOf(spaces.findById(org, space.id()).orElseThrow());
    }

    /** API-DEV-08 운영 모드 조회 */
    @Transactional(readOnly = true)
    public ModeResponse mode(long spaceId) {
        return modeOf(support.visible(spaceId, Permission.DEV_READ));
    }

    private ModeResponse modeOf(Space space) {
        List<Space> chain = support.chain(space);
        List<ScheduleSlot> slots = SpaceInheritance.schedule(chain,
                settings.findSlots(space.organizationId(), chain.stream().map(Space::id).toList())).slots();
        return SpaceInheritance.modeResponse(SpaceInheritance.mode(chain, slots, clock.instant()));
    }

    /** API-DEV-126 내부: 운영 모드(flow-engine·analytics). 조직은 공간 행에서 정하고, 배포 조직 밖은 404 */
    @Transactional(readOnly = true)
    public ModeResponse internalMode(long spaceId) {
        return modeOf(internalSpace(spaceId));
    }

    /** API-DEV-126 내부: 유효 목표 범위 */
    @Transactional(readOnly = true)
    public TargetsResponse internalTargets(long spaceId) {
        return targetsOf(internalSpace(spaceId));
    }

    Space internalSpace(long spaceId) {
        return spaces.findForInternal(spaceId, deployment.restriction())
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND));
    }
}
