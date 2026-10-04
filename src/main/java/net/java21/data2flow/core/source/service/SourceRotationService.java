package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.SourceRotationProgress;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.source.domain.ConnectionStates;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.source.repository.SourceRotationRepository;
import net.java21.data2flow.core.source.repository.SourceRotationRepository.RotationRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 무중단 자격증명 교체(DSC-07.02, BR-DSC-09, AT-DSC-05.1·05.2).
 * <ol>
 *   <li>ACTIVE 소스에 연결된 ingress 인스턴스가 있고 바꾸는 종류의 이전 값이 있으면, 새 값을 {@code pending}으로만 저장하고 교체를 시작한다
 *       (ROTATING). 실행 설정(API-DSC-50)의 {@code rotation{rotationId, secrets}}로 ingress에 알린다</li>
 *   <li>ingress는 인스턴스를 하나씩 새 값으로 다시 연결하고 EVT-DSC-08 {@code source.rotation.progress}로 결과를 보고한다</li>
 *   <li>첫 보고가 실패면 교체를 멈추고 pending을 버린다(이전 값 유지, FAILED {@code SOURCE_ROTATION_FAILED}). 지금 연결된 인스턴스가 모두
 *       성공하면 새 값을 확정한다(DONE)</li>
 *   <li>10분 안에 끝나지 않으면 성공한 인스턴스가 있으면 확정, 없으면 실패로 끝낸다(주기 작업)</li>
 * </ol>
 * 그 밖의 경우(DRAFT·PAUSED, 연결된 인스턴스 없음, 처음 넣는 종류)와 기능이 꺼진 경우({@code data2flow.core.source.zero-downtime-rotation=false},
 * ingress가 교체 프로토콜을 지원하기 전 기본값)는 바로 저장하고 DONE을 돌려준다.
 */
@Service
public class SourceRotationService {

    public static final String ROTATING = "ROTATING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, Map<String, Object>>> INSTANCES = new TypeReference<>() {
    };

    private final SourceRotationRepository rotations;
    private final DataSourceRepository sources;
    private final SourceHealthRepository health;
    private final SourceSecrets secrets;
    private final SourceStateService states;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;
    private volatile boolean enabled;

    public SourceRotationService(SourceRotationRepository rotations, DataSourceRepository sources, SourceHealthRepository health,
                                 SourceSecrets secrets, SourceStateService states, RoleChecker roleChecker, Audits audits, JsonMapper json,
                                 Clock clock, @Value("${data2flow.core.source.zero-downtime-rotation:false}") boolean enabled) {
        this.rotations = rotations;
        this.sources = sources;
        this.health = health;
        this.secrets = secrets;
        this.states = states;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 무중단 교체를 켜고 끈다(설정값의 런타임 대체. 통합 시험이 Spring 컨텍스트를 하나 더 만들지 않으려고 쓴다) */
    public void enabled(boolean value) {
        this.enabled = value;
    }

    /** 교체 결과 */
    public record Started(String rotationId, String state, Map<String, String> fingerprints) {
    }

    /** 교체 상태(API-DSC-05b 응답) */
    public record RotationStatus(String rotationId, String state, List<String> kinds, List<InstanceResult> instances, String error,
                                 Instant startedAt, Instant finishedAt) {
    }

    public record InstanceResult(String instanceId, boolean ok, String error, Instant at) {
    }

    /** 바로 저장하거나 무중단 교체를 시작한다. 호출자가 소스 행을 잠근 트랜잭션 안에서 부른다 */
    public Started apply(long orgId, DataSource s, Map<String, Secret> incoming, CurrentUser user) {
        Instant now = clock.instant();
        String rotationId = UUID.randomUUID().toString();
        Set<String> existing = sources.findSecretMeta(orgId, s.id()).stream().map(SecretMeta::kind).collect(Collectors.toSet());
        boolean zeroDowntime = enabled && SourceModels.ACTIVE.equals(s.lifecycle()) && !liveInstances(orgId, s.id(), now).isEmpty()
                && existing.containsAll(incoming.keySet());
        if (!zeroDowntime) {
            Map<String, String> fp = secrets.store(orgId, s.id(), incoming, now);
            rotations.insert(orgId, s.id(), rotationId, DONE, incoming.keySet(), user.userId(), now, now);
            return new Started(rotationId, DONE, fp);
        }
        if (rotations.lockActive(orgId, s.id()).isPresent()) {
            throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
        }
        Map<String, String> fp = secrets.storePending(orgId, s.id(), incoming, now);
        rotations.insert(orgId, s.id(), rotationId, ROTATING, incoming.keySet(), user.userId(), now, null);
        return new Started(rotationId, ROTATING, fp);
    }

    /** API-DSC-05b 교체 상태 — SRC_READ */
    @Transactional(readOnly = true)
    public RotationStatus status(long sourceId, String rotationId) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        sources.findById(orgId, sourceId).orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
        RotationRow row = rotations.findByRotationId(orgId, sourceId, rotationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return toStatus(row);
    }

    /** EVT-DSC-08 처리(한 메시지 한 트랜잭션). 진행 중인 그 교체가 아니면 무시 */
    @Transactional
    public void progress(long orgId, SourceRotationProgress p) {
        RotationRow row = rotations.lockActive(orgId, p.sourceId()).orElse(null);
        if (row == null || !row.rotationId().equals(p.rotationId())) {
            return;
        }
        Instant now = clock.instant();
        Map<String, Map<String, Object>> instances = instances(row);
        boolean anyOkBefore = instances.values().stream().anyMatch(i -> Boolean.TRUE.equals(i.get("ok")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", p.ok());
        result.put("error", p.error());
        result.put("at", now.toString());
        instances.put(p.instanceId(), result);
        rotations.updateInstances(orgId, row.id(), json.writeValueAsString(instances));
        if (!p.ok() && !anyOkBefore) {
            finish(orgId, row, FAILED, p.error() == null ? "첫 인스턴스가 새 값으로 연결하지 못함" : p.error(), now);
            return;
        }
        Set<String> live = liveInstances(orgId, p.sourceId(), now).stream().map(RuntimeRow::instanceId).collect(Collectors.toSet());
        boolean allOk = !live.isEmpty() && live.stream().allMatch(id -> instances.containsKey(id) && Boolean.TRUE.equals(instances.get(id).get("ok")));
        if (allOk) {
            finish(orgId, row, DONE, null, now);
        }
    }

    /** 10분 넘게 끝나지 않은 교체 정리. 끝낸 수 */
    @Transactional
    public int expireStale() {
        Instant now = clock.instant();
        int done = 0;
        for (RotationRow row : rotations.listStale(now.minus(TIMEOUT))) {
            RotationRow locked = rotations.lockActive(row.organizationId(), row.sourceId()).orElse(null);
            if (locked == null || locked.id() != row.id()) {
                continue;
            }
            boolean anyOk = instances(locked).values().stream().anyMatch(i -> Boolean.TRUE.equals(i.get("ok")));
            finish(row.organizationId(), locked, anyOk ? DONE : FAILED, anyOk ? null : "시간 초과: 인스턴스 보고 없음", now);
            done++;
        }
        return done;
    }

    private void finish(long orgId, RotationRow row, String state, String error, Instant now) {
        if (DONE.equals(state)) {
            sources.updatePendingCommitted(orgId, row.sourceId(), now);
        } else {
            sources.updatePendingDiscarded(orgId, row.sourceId(), now);
        }
        rotations.updateFinished(orgId, row.id(), state, error, now);
        audits.record(audits.event(orgId, DONE.equals(state) ? "SOURCE_SECRET_ROTATED" : "SOURCE_SECRET_ROTATION_FAILED")
                .target("SOURCE", Long.toString(row.sourceId())).detail("rotationId", row.rotationId()).detail("kinds", row.kinds())
                .detail("error", error));
        DataSource s = sources.findById(orgId, row.sourceId()).orElse(null);
        states.configChanged(orgId, row.sourceId(), s == null ? 0 : s.version());
    }

    private List<RuntimeRow> liveInstances(long orgId, long sourceId, Instant now) {
        return health.findRuntimes(orgId, sourceId).stream()
                .filter(r -> ConnectionStates.fresh(r, now) && ConnectionStates.CONNECTED.equals(r.state())).toList();
    }

    private Map<String, Map<String, Object>> instances(RotationRow row) {
        return row.instances() == null || row.instances().isBlank() ? new LinkedHashMap<>()
                : new LinkedHashMap<>(json.readValue(row.instances(), INSTANCES));
    }

    private RotationStatus toStatus(RotationRow row) {
        List<InstanceResult> list = new ArrayList<>();
        instances(row).forEach((id, v) -> list.add(new InstanceResult(id, Boolean.TRUE.equals(v.get("ok")),
                v.get("error") instanceof String e ? e : null, v.get("at") instanceof String at ? Instant.parse(at) : null)));
        return new RotationStatus(row.rotationId(), row.state(), row.kinds(), list, row.error(), row.startedAt(), row.finishedAt());
    }
}
