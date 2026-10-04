package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsThresholdItem;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsThresholdsRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsThresholdsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ThresholdsRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ThresholdsResponse;
import net.java21.data2flow.core.ingest.repository.IngestSettingsRepository;
import net.java21.data2flow.core.ingest.repository.IngestSettingsRepository.IngestThresholdRow;
import net.java21.data2flow.core.ingest.repository.IngestSettingsRepository.OpsThresholdRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수집·운영 알람 기준(ADMIN, OPS_MANAGE).
 * <ul>
 *   <li>API-ING-04 수집 알람 기준(ING-07.04·07.05): lagWarnSec 10~3600, lagCriticalSec는 lagWarnSec 초과 ~ 7200, heartbeatCriticalSec 10~600.
 *       기본 60/300/30초(BR-ING-16, ING-07.05). 저장하면 감사 INGEST_THRESHOLD_CHANGED와 설정 변경 메시지(SETTING)를 낸다 —
 *       지연 경보를 판정하는 pipeline이 다시 읽는다</li>
 *   <li>API-OPS-05 운영 알람 기준(OPS-01.05, BR-OPS-02·03): {@code ops_thresholds} 6종. 조직 전체 판 번호 하나(version)로 낙관적 잠금한다.
 *       형식·범위 오류는 400 SETTING_INVALID {필드}(TC-OPS-017)</li>
 * </ul>
 */
@Service
public class IngestSettingsService {

    static final String AUDIT_INGEST_THRESHOLD_CHANGED = "INGEST_THRESHOLD_CHANGED";
    static final String AUDIT_OPS_THRESHOLD_CHANGED = "OPS_THRESHOLD_CHANGED";
    static final IngestThresholdRow DEFAULT_INGEST = new IngestThresholdRow(60, 300, 30, 0, null, null);

    /** 운영 알람 기준 기본값·허용 범위·기본 심각도(OPS domain-model §2.13) */
    record OpsDefault(String key, double value, double min, double max, String severity) {
    }

    static final List<OpsDefault> OPS_DEFAULTS = List.of(
            new OpsDefault("BACKUP_FAILED", 1, 1, 10, "CRITICAL"),
            new OpsDefault("DISK_FREE_PERCENT", 20, 1, 99, "MAJOR"),
            new OpsDefault("DLQ_GROWTH_PER_10MIN", 100, 1, 1_000_000, "WARNING"),
            new OpsDefault("HEARTBEAT_DELAY_SEC", 30, 10, 600, "CRITICAL"),
            new OpsDefault("INGEST_ZERO_MINUTES", 5, 1, 1440, "CRITICAL"),
            new OpsDefault("STREAM_LAG_MESSAGES", 10_000, 1, 10_000_000, "WARNING"));
    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "MAJOR", "CRITICAL");

    private final IngestSettingsRepository repository;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final CoreEventPublisher events;
    private final Clock clock;

    public IngestSettingsService(IngestSettingsRepository repository, RoleChecker roleChecker, Audits audits, CoreEventPublisher events,
                                 Clock clock) {
        this.repository = repository;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.events = events;
        this.clock = clock;
    }

    /** 경고 판정에 쓰는 값(권한 검사 없음, 서비스 내부용) */
    public record AlertSettings(int lagWarnSec, int lagCriticalSec, boolean zeroEnabled, double zeroMinutes, boolean dlqEnabled,
                                double dlqPer10Min) {
    }

    @Transactional(readOnly = true)
    public AlertSettings alertSettings(long orgId) {
        IngestThresholdRow ingest = repository.findIngestThresholds(orgId).orElse(DEFAULT_INGEST);
        Map<String, OpsThresholdRow> ops = currentOps(orgId);
        OpsThresholdRow zero = ops.get("INGEST_ZERO_MINUTES");
        OpsThresholdRow dlq = ops.get("DLQ_GROWTH_PER_10MIN");
        return new AlertSettings(ingest.lagWarnSec(), ingest.lagCriticalSec(), zero.enabled(), zero.value(), dlq.enabled(), dlq.value());
    }

    /** 운영 알람 기준 하나(권한 검사 없음, 서비스 내부용. 저장 전이면 기본값). 예: DISK_FREE_PERCENT(OPS-01.03) */
    @Transactional(readOnly = true)
    public OpsThresholdRow opsThreshold(long orgId, String key) {
        return currentOps(orgId).get(key);
    }

    // ---------------------------------------------------------------- API-ING-04

    /** API-ING-04 조회(문서에 없던 GET, 웹 수집 모니터의 기준 편집 폼이 쓴다). 저장 전이면 기본값·version 0 */
    @Transactional(readOnly = true)
    public ThresholdsResponse ingestThresholds() {
        roleChecker.require(Permission.OPS_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        return toResponse(repository.findIngestThresholds(orgId).orElse(DEFAULT_INGEST));
    }

    /** API-ING-04 변경 */
    @Transactional
    public ThresholdsResponse updateIngestThresholds(ThresholdsRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (req == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        range(errors, "lagWarnSec", req.lagWarnSec(), 10, 3600);
        range(errors, "lagCriticalSec", req.lagCriticalSec(), 11, 7200);
        range(errors, "heartbeatCriticalSec", req.heartbeatCriticalSec(), 10, 600);
        if (errors.isEmpty() && req.lagCriticalSec() <= req.lagWarnSec()) {
            errors.add(new FieldErrorDetail("lagCriticalSec", "ORDER", "> lagWarnSec"));
        }
        if (req.baseVersion() == null) {
            errors.add(new FieldErrorDetail("baseVersion", "REQUIRED", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        IngestThresholdRow before = repository.findIngestThresholds(orgId).orElse(DEFAULT_INGEST);
        VersionCheck.require(req.baseVersion(), before.version());
        Instant now = clock.instant();
        if (before.version() == 0) {
            VersionCheck.requireUpdated(repository.insertIngestThresholds(orgId, req.lagWarnSec(), req.lagCriticalSec(),
                    req.heartbeatCriticalSec(), user.userId(), now));
        } else {
            VersionCheck.requireUpdated(repository.updateIngestThresholds(orgId, req.baseVersion(), req.lagWarnSec(), req.lagCriticalSec(),
                    req.heartbeatCriticalSec(), user.userId(), now));
        }
        IngestThresholdRow after = repository.findIngestThresholds(orgId).orElseThrow();
        audits.record(audits.event(orgId, AUDIT_INGEST_THRESHOLD_CHANGED).actor(user).target("INGEST_ALERT_THRESHOLDS", Long.toString(orgId))
                .detail("before", thresholdMap(before)).detail("after", thresholdMap(after)));
        events.configChanged(EntityType.SETTING, orgId, after.version(), orgId);
        return toResponse(after);
    }

    private static Map<String, Object> thresholdMap(IngestThresholdRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lagWarnSec", row.lagWarnSec());
        map.put("lagCriticalSec", row.lagCriticalSec());
        map.put("heartbeatCriticalSec", row.heartbeatCriticalSec());
        return map;
    }

    private static ThresholdsResponse toResponse(IngestThresholdRow row) {
        return new ThresholdsResponse(row.lagWarnSec(), row.lagCriticalSec(), row.heartbeatCriticalSec(), row.version(),
                row.updatedBy() == null ? null : row.updatedBy().toString(), row.updatedAt());
    }

    private static void range(List<FieldErrorDetail> errors, String field, Integer value, int min, int max) {
        if (value == null) {
            errors.add(new FieldErrorDetail(field, "REQUIRED", null));
        } else if (value < min || value > max) {
            errors.add(new FieldErrorDetail(field, "RANGE", min + "~" + max));
        }
    }

    // ---------------------------------------------------------------- API-OPS-05

    /** API-OPS-05 조회. 저장 전 항목은 기본값 */
    @Transactional(readOnly = true)
    public OpsThresholdsResponse opsThresholds() {
        roleChecker.require(Permission.OPS_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        Map<String, OpsThresholdRow> current = currentOps(orgId);
        return toOpsResponse(current);
    }

    /** API-OPS-05 변경: 보낸 항목만 바꾸고 나머지는 그대로. baseVersion 필수 */
    @Transactional
    public OpsThresholdsResponse updateOpsThresholds(OpsThresholdsRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "items");
        }
        if (req.baseVersion() == null) {
            throw new BusinessException(CoreErrorCode.SETTING_INVALID, "baseVersion");
        }
        Map<String, OpsDefault> defaults = new LinkedHashMap<>();
        OPS_DEFAULTS.forEach(d -> defaults.put(d.key(), d));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < req.items().size(); i++) {
            OpsThresholdItem item = req.items().get(i);
            String prefix = "items[" + i + "]";
            if (item == null || item.key() == null || !defaults.containsKey(item.key()) || !seen.add(item.key())) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, prefix + ".key");
            }
            OpsDefault d = defaults.get(item.key());
            if (item.value() != null && (item.value() < d.min() || item.value() > d.max() || item.value() != Math.rint(item.value()))) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, prefix + ".value");
            }
            if (item.severity() != null && !SEVERITIES.contains(item.severity())) {
                throw new BusinessException(CoreErrorCode.SETTING_INVALID, prefix + ".severity");
            }
            if (item.channelIds() != null) {
                for (String id : item.channelIds()) {
                    if (id == null || !id.matches("\\d{1,18}")) {
                        throw new BusinessException(CoreErrorCode.SETTING_INVALID, prefix + ".channelIds");
                    }
                }
            }
        }
        int version = repository.lockOpsThresholdVersion(orgId);
        VersionCheck.require(req.baseVersion(), version);
        Map<String, OpsThresholdRow> before = currentOps(orgId);
        Map<String, OpsThresholdRow> after = new LinkedHashMap<>(before);
        Instant now = clock.instant();
        for (OpsThresholdItem item : req.items()) {
            OpsThresholdRow old = before.get(item.key());
            after.put(item.key(), new OpsThresholdRow(item.key(), item.value() == null ? old.value() : item.value(),
                    item.enabled() == null ? old.enabled() : item.enabled(), item.severity() == null ? old.severity() : item.severity(),
                    item.channelIds() == null ? old.channelIds() : item.channelIds().stream().map(Long::parseLong).toList(), version + 1));
        }
        for (OpsThresholdRow row : after.values()) {
            repository.upsertOpsThreshold(orgId, new OpsThresholdRow(row.key(), row.value(), row.enabled(), row.severity(), row.channelIds(),
                    version + 1), user.userId(), now);
        }
        audits.record(audits.event(orgId, AUDIT_OPS_THRESHOLD_CHANGED).actor(user).target("OPS_THRESHOLDS", Long.toString(orgId))
                .detail("items", req.items().stream().map(i -> i.key()).toList()));
        return toOpsResponse(currentOps(orgId));
    }

    /** 저장된 값 + 저장 전 항목은 기본값(판 번호 0) */
    private Map<String, OpsThresholdRow> currentOps(long orgId) {
        Map<String, OpsThresholdRow> rows = new LinkedHashMap<>();
        Map<String, OpsThresholdRow> saved = new LinkedHashMap<>();
        repository.findOpsThresholds(orgId).forEach(r -> saved.put(r.key(), r));
        int version = saved.values().stream().mapToInt(OpsThresholdRow::version).max().orElse(0);
        for (OpsDefault d : OPS_DEFAULTS) {
            OpsThresholdRow row = saved.get(d.key());
            rows.put(d.key(), row != null ? row : new OpsThresholdRow(d.key(), d.value(), true, d.severity(), List.of(), version));
        }
        return rows;
    }

    private static OpsThresholdsResponse toOpsResponse(Map<String, OpsThresholdRow> rows) {
        int version = rows.values().stream().mapToInt(OpsThresholdRow::version).max().orElse(0);
        return new OpsThresholdsResponse(rows.values().stream()
                .map(r -> new OpsThresholdItem(r.key(), r.enabled(), r.value(), r.severity(),
                        r.channelIds().stream().map(String::valueOf).toList()))
                .toList(), version);
    }
}
