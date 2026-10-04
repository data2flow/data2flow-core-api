package net.java21.data2flow.core.alarm.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 알람({@code data2flow_core.alarms}, RUL-02). 열린 알람은 alarm_key당 하나(부분 UNIQUE {@code uq_alarms_alarm_key_open}, BR-RUL-02).
 * 조회는 기기 이름·공간 경로·사용자 이름을 붙여 읽는다.
 */
@Repository
public class AlarmRepository {

    static final String OPEN = "('ACTIVE','ACKNOWLEDGED','SUPPRESSED')";

    static final String SELECT = """
            SELECT a.id, a.organization_id, a.alarm_key, a.source_type, a.rule_id, a.flow_id, a.node_id, a.severity, a.title, a.status,
                   a.flapping, a.device_id, d.name AS device_name, d.is_virtual AS device_virtual, a.space_id, s.name AS space_name,
                   s.path AS space_path,
                   (SELECT string_agg(ps.name, ' / ' ORDER BY ps.depth) FROM data2flow_core.spaces ps
                     WHERE ps.organization_id = a.organization_id AND s.path LIKE ps.path || '%') AS space_names,
                   a.metric_key, a.trigger_value, a.peak_value, a.last_value, a.threshold::text AS threshold, a.occurrence_count,
                   a.raised_at, a.last_raised_at, a.acked_by, ua.name AS acked_by_name, a.acked_at, a.cleared_at, a.clear_reason,
                   a.assignee_id, uz.name AS assignee_name, a.parent_alarm_id,
                   (SELECT count(*) FROM data2flow_core.alarms c WHERE c.parent_alarm_id = a.id) AS child_count,
                   a.space_event_id, a.suppressed_reason, a.last_notified_at, a.notify_seq, a.version, a.updated_at,
                   r.name AS rule_name
              FROM data2flow_core.alarms a
              LEFT JOIN data2flow_core.devices d ON d.id = a.device_id AND d.organization_id = a.organization_id
              LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id AND s.organization_id = a.organization_id
              LEFT JOIN data2flow_core.app_users ua ON ua.id = a.acked_by AND ua.organization_id = a.organization_id
              LEFT JOIN data2flow_core.app_users uz ON uz.id = a.assignee_id AND uz.organization_id = a.organization_id
              LEFT JOIN data2flow_core.rules r ON r.id = a.rule_id AND r.organization_id = a.organization_id""";

    private final JdbcClient jdbc;

    public AlarmRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AlarmRow(long id, long organizationId, String alarmKey, String sourceType, Long ruleId, UUID flowId, String nodeId,
                           String severity, String title, String status, boolean flapping, Long deviceId, String deviceName,
                           boolean deviceVirtual, Long spaceId, String spaceName, String spacePath, String spaceNames, String metricKey,
                           Double triggerValue, Double peakValue, Double lastValue, String threshold, int occurrenceCount,
                           Instant raisedAt, Instant lastRaisedAt, Long ackedBy, String ackedByName, Instant ackedAt, Instant clearedAt,
                           String clearReason, Long assigneeId, String assigneeName, Long parentAlarmId, int childCount,
                           Long spaceEventId, String suppressedReason, Instant lastNotifiedAt, int notifySeq, int version,
                           Instant updatedAt, String ruleName) {

        public boolean open() {
            return "ACTIVE".equals(status) || "ACKNOWLEDGED".equals(status) || "SUPPRESSED".equals(status);
        }
    }

    /** 새 알람 값(발생) */
    public record NewAlarm(long organizationId, String alarmKey, String sourceType, Long ruleId, UUID flowId, String nodeId,
                           String severity, String title, String status, Long deviceId, Long spaceId, String metricKey, Double value,
                           String thresholdJson, Instant raisedAt, Long parentAlarmId, String suppressedReason) {
    }

    /** 목록 조건 */
    public record Search(long organizationId, Collection<String> statuses, Collection<String> severities, String spacePathPrefix,
                         Long ruleId, String sourceType, Instant from, Instant to, Collection<String> scopePaths, Long deviceId,
                         Long parentAlarmId) {
    }

    public Optional<AlarmRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.id = :id").param("org", organizationId).param("id", id)
                .query(AlarmRepository::map).optional();
    }

    /** 처리 트랜잭션에서 행 잠금 */
    public Optional<AlarmRow> lockById(long organizationId, long id) {
        Optional<Long> locked = jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(Long.class).optional();
        return locked.flatMap(x -> findById(organizationId, id));
    }

    /** alarm_key의 열린 알람(잠금) */
    public Optional<AlarmRow> lockOpenByKey(long organizationId, String alarmKey) {
        Optional<Long> id = jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = :key AND status IN "
                        + OPEN + " FOR UPDATE")
                .param("org", organizationId).param("key", alarmKey).query(Long.class).optional();
        return id.flatMap(x -> findById(organizationId, x));
    }

    /**
     * 새 알람. 같은 키의 열린 알람이 이미 있으면(동시 처리) 넣지 않고 빈 값(부분 UNIQUE, BR-RUL-02).
     */
    public Optional<Long> insertIfNoOpen(NewAlarm a) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.alarms (organization_id, alarm_key, source_type, rule_id, flow_id, node_id, severity, title,
                               status, device_id, space_id, metric_key, trigger_value, peak_value, last_value, threshold, raised_at,
                               last_raised_at, parent_alarm_id, suppressed_reason, created_at, updated_at)
                        VALUES (:org, :key, :source, :rule, :flow, :node, :severity, :title, :status, :device, :space, :metric, :value, :value,
                                :value, CAST(:threshold AS jsonb), :at, :at, :parent, :suppressed, :at, :at)
                        ON CONFLICT (organization_id, alarm_key) WHERE status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED') DO NOTHING
                        RETURNING id""")
                .param("org", a.organizationId()).param("key", a.alarmKey()).param("source", a.sourceType()).param("rule", a.ruleId())
                .param("flow", a.flowId()).param("node", a.nodeId()).param("severity", a.severity()).param("title", a.title())
                .param("status", a.status()).param("device", a.deviceId()).param("space", a.spaceId()).param("metric", a.metricKey())
                .param("value", a.value()).param("threshold", a.thresholdJson()).param("at", Pg.ts(a.raisedAt()))
                .param("parent", a.parentAlarmId()).param("suppressed", a.suppressedReason())
                .query(Long.class).optional();
    }

    /**
     * 재발생(BR-RUL-02): 횟수 +1, 최고값(심각한 쪽)·마지막 값·마지막 발생 시각만 갱신.
     *
     * @param lowerIsWorse 낮을수록 나쁜 조건(&lt;, &lt;=)이면 최솟값을 최고값으로 본다
     */
    public void updateReraised(long organizationId, long id, Double value, boolean lowerIsWorse, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET occurrence_count = occurrence_count + 1, last_value = coalesce(:value, last_value),
                               peak_value = CASE WHEN CAST(:value AS double precision) IS NULL THEN peak_value
                                                 WHEN peak_value IS NULL THEN :value
                                                 WHEN :lower THEN least(peak_value, :value) ELSE greatest(peak_value, :value) END,
                               last_raised_at = greatest(last_raised_at, :at), version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("value", value).param("lower", lowerIsWorse).param("at", Pg.ts(at)).param("org", organizationId).param("id", id)
                .update();
    }

    public void updateStatus(long organizationId, long id, String status, String suppressedReason, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET status = :status, suppressed_reason = :reason, version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("reason", suppressedReason).param("at", Pg.ts(at)).param("org", organizationId)
                .param("id", id).update();
    }

    public void updateAcked(long organizationId, long id, String status, long userId, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET status = :status, acked_by = :user, acked_at = :at, version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("user", userId).param("at", Pg.ts(at)).param("org", organizationId).param("id", id).update();
    }

    public void updateCleared(long organizationId, long id, String reason, Double lastValue, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET status = 'CLEARED', cleared_at = :at, clear_reason = :reason,
                               last_value = coalesce(:value, last_value), version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("at", Pg.ts(at)).param("reason", reason).param("value", lastValue).param("org", organizationId).param("id", id)
                .update();
    }

    public void updateAssignee(long organizationId, long id, Long assigneeId, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET assignee_id = :assignee, version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("assignee", assigneeId).param("at", Pg.ts(at)).param("org", organizationId).param("id", id).update();
    }

    public void updateFlapping(long organizationId, long id, boolean flapping, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET flapping = :flapping, version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("flapping", flapping).param("at", Pg.ts(at)).param("org", organizationId).param("id", id).update();
    }

    public void updateParent(long organizationId, long id, Long parentAlarmId, Instant at) {
        jdbc.sql("""
                        UPDATE data2flow_core.alarms SET parent_alarm_id = :parent, version = version + 1, updated_at = :at
                         WHERE organization_id = :org AND id = :id""")
                .param("parent", parentAlarmId).param("at", Pg.ts(at)).param("org", organizationId).param("id", id).update();
    }

    public void updateSpaceEvent(long organizationId, long id, long spaceEventId) {
        jdbc.sql("UPDATE data2flow_core.alarms SET space_event_id = :event WHERE organization_id = :org AND id = :id")
                .param("event", spaceEventId).param("org", organizationId).param("id", id).update();
    }

    /** 알림 요청을 보낸 뒤: 순번 +1, 마지막 알림 시각. 새 순번 */
    public int updateNotified(long organizationId, long id, Instant at) {
        return jdbc.sql("""
                        UPDATE data2flow_core.alarms SET notify_seq = notify_seq + 1, last_notified_at = :at
                         WHERE organization_id = :org AND id = :id RETURNING notify_seq""")
                .param("at", Pg.ts(at)).param("org", organizationId).param("id", id).query(Integer.class).single();
    }

    /** 같은 alarm_key의 최근 발생 시각들(행 발생 + 재발생 이벤트, BR-RUL-10 플래핑) */
    public List<Instant> listRaiseTimes(long organizationId, String alarmKey, Instant since) {
        return jdbc.sql("""
                        SELECT e.at FROM data2flow_core.alarm_events e JOIN data2flow_core.alarms a ON a.id = e.alarm_id
                         WHERE a.organization_id = :org AND a.alarm_key = :key AND e.type IN ('RAISED','RERAISED','SUPPRESSED')
                           AND e.at >= :since""")
                .param("org", organizationId).param("key", alarmKey).param("since", Pg.ts(since))
                .query((rs, n) -> Pg.instant(rs, "at")).list();
    }

    /** 플래핑 중인 열린 알람과 마지막 상태 변화 시각(FLAPPING_OFF 판정) */
    public Map<Long, Instant> listFlappingLastChange(long organizationId) {
        Map<Long, Instant> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.id, (SELECT max(e.at) FROM data2flow_core.alarm_events e JOIN data2flow_core.alarms k ON k.id = e.alarm_id
                                       WHERE k.organization_id = a.organization_id AND k.alarm_key = a.alarm_key
                                         AND e.type IN ('RAISED','RERAISED','CLEARED','SUPPRESSED')) AS last_change
                          FROM data2flow_core.alarms a WHERE a.organization_id = :org AND a.flapping""")
                .param("org", organizationId)
                .query(rs -> {
                    result.put(rs.getLong("id"), Pg.instant(rs, "last_change"));
                });
        return result;
    }

    /** 열린 하위 알람(상위 해제 시 PARENT_CLEARED, BR-RUL-09) */
    public List<Long> listOpenChildren(long organizationId, long parentAlarmId) {
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND parent_alarm_id = :parent AND status IN "
                        + OPEN + " ORDER BY id")
                .param("org", organizationId).param("parent", parentAlarmId).query(Long.class).list();
    }

    /** 기기의 열린 알람 ID(키 접두어로 거름. null이면 모두) */
    public List<Long> listOpenByDevices(long organizationId, Collection<Long> deviceIds, String keyPrefix) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND device_id = ANY(CAST(:devices AS bigint[]))"
                        + " AND status IN " + OPEN + " AND (CAST(:prefix AS text) IS NULL OR alarm_key LIKE :prefix || '%') ORDER BY id")
                .param("org", organizationId).param("devices", Pg.bigintArray(deviceIds)).param("prefix", keyPrefix)
                .query(Long.class).list();
    }

    /** 규칙의 열린 알람 */
    public List<Long> listOpenByRule(long organizationId, long ruleId) {
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND rule_id = :rule AND status IN " + OPEN
                        + " ORDER BY id")
                .param("org", organizationId).param("rule", ruleId).query(Long.class).list();
    }

    /** 유지보수 대상(기기 또는 공간 경로 접두어)의 열린 알람 ID와 상태 */
    public List<AlarmRow> listOpenInScope(long organizationId, Long deviceId, String spacePathPrefix) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.status IN " + OPEN
                        + " AND ((CAST(:device AS bigint) IS NOT NULL AND a.device_id = :device)"
                        + "   OR (CAST(:prefix AS text) IS NOT NULL AND s.path LIKE :prefix || '%')) ORDER BY a.id")
                .param("org", organizationId).param("device", deviceId).param("prefix", spacePathPrefix)
                .query(AlarmRepository::map).list();
    }

    /** 같은 공간의 최근 다른 출처 알람 발생 시각(BR-RUL-11) */
    public Optional<Instant> findRecentOtherSource(long organizationId, long spaceId, String alarmKeyPrefix, long exceptId, Instant since) {
        List<Instant> found = jdbc.sql("""
                        SELECT max(raised_at) AS latest FROM data2flow_core.alarms
                         WHERE organization_id = :org AND space_id = :space AND id <> :except AND raised_at >= :since
                           AND NOT (alarm_key LIKE :prefix || '%')""")
                .param("org", organizationId).param("space", spaceId).param("except", exceptId).param("since", Pg.ts(since))
                .param("prefix", alarmKeyPrefix).query((rs, n) -> Pg.instant(rs, "latest")).list();
        return found.isEmpty() || found.getFirst() == null ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** 같은 공간의 최근 알람(묶음 시작 때 함께 묶을 것) */
    public List<Long> listRecentInSpace(long organizationId, long spaceId, Instant since) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND space_id = :space AND raised_at >= :since
                           AND space_event_id IS NULL ORDER BY id""")
                .param("org", organizationId).param("space", spaceId).param("since", Pg.ts(since)).query(Long.class).list();
    }

    public List<AlarmRow> search(Search s, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(s, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(SELECT + where + " ORDER BY a.last_raised_at DESC, a.id DESC LIMIT :limit OFFSET :offset").params(params)
                .query(AlarmRepository::map).list();
    }

    public long count(Search s) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM data2flow_core.alarms a LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id"
                + " AND s.organization_id = a.organization_id" + where(s, params)).params(params).query(Long.class).single();
    }

    /** 상태·심각도별 개수(목록 counts, 필터의 상태·심각도는 빼고 센다) */
    public Map<String, Map<String, Long>> countByStatusAndSeverity(Search s) {
        Search base = new Search(s.organizationId(), null, null, s.spacePathPrefix(), s.ruleId(), s.sourceType(), s.from(), s.to(),
                s.scopePaths(), s.deviceId(), s.parentAlarmId());
        Map<String, Object> params = new HashMap<>();
        String where = where(base, params);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        jdbc.sql("SELECT a.status, a.severity, count(*) AS n FROM data2flow_core.alarms a LEFT JOIN data2flow_core.spaces s"
                        + " ON s.id = a.space_id AND s.organization_id = a.organization_id" + where + " GROUP BY a.status, a.severity")
                .params(params).query(rs -> {
                    long n = rs.getLong("n");
                    byStatus.merge(rs.getString("status"), n, Long::sum);
                    if (s.statuses() == null || s.statuses().isEmpty() || s.statuses().contains(rs.getString("status"))) {
                        bySeverity.merge(rs.getString("severity"), n, Long::sum);
                    }
                });
        return Map.of("byStatus", byStatus, "bySeverity", bySeverity);
    }

    private static String where(Search s, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE a.organization_id = :org");
        params.put("org", s.organizationId());
        if (s.statuses() != null && !s.statuses().isEmpty()) {
            sql.append(" AND a.status = ANY(CAST(:statuses AS text[]))");
            params.put("statuses", Pg.textArray(s.statuses()));
        }
        if (s.severities() != null && !s.severities().isEmpty()) {
            sql.append(" AND a.severity = ANY(CAST(:severities AS text[]))");
            params.put("severities", Pg.textArray(s.severities()));
        }
        if (s.spacePathPrefix() != null) {
            sql.append(" AND s.path LIKE :spacePrefix || '%'");
            params.put("spacePrefix", s.spacePathPrefix());
        }
        if (s.ruleId() != null) {
            sql.append(" AND a.rule_id = :rule");
            params.put("rule", s.ruleId());
        }
        if (s.deviceId() != null) {
            sql.append(" AND a.device_id = :device");
            params.put("device", s.deviceId());
        }
        if (s.parentAlarmId() != null) {
            sql.append(" AND a.parent_alarm_id = :parent");
            params.put("parent", s.parentAlarmId());
        }
        if (s.sourceType() != null) {
            sql.append(" AND a.source_type = :sourceType");
            params.put("sourceType", s.sourceType());
        }
        if (s.from() != null) {
            sql.append(" AND a.last_raised_at >= :from");
            params.put("from", Pg.ts(s.from()));
        }
        if (s.to() != null) {
            sql.append(" AND a.raised_at < :to");
            params.put("to", Pg.ts(s.to()));
        }
        if (s.scopePaths() != null) {
            // 공간 범위 제한 사용자(IAM-04.05): 범위 공간 경로 아래의 알람만. 공간 없는 알람은 보이지 않는다
            sql.append(" AND EXISTS (SELECT 1 FROM unnest(CAST(:scopePaths AS text[])) p WHERE s.path LIKE p || '%')");
            params.put("scopePaths", Pg.textArray(s.scopePaths()));
        }
        return sql.toString();
    }

    /** 공간의 경로(하위 조회용). 없으면 빈 값 */
    public Optional<String> findSpacePath(long organizationId, long spaceId) {
        return jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(String.class).optional();
    }

    public Optional<String> findSpaceName(long organizationId, long spaceId) {
        return jdbc.sql("SELECT name FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(String.class).optional();
    }

    /** 공간 범위(공간 ID들)의 경로 목록 */
    public List<String> listSpacePaths(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds)).query(String.class).list();
    }

    /** 열린 알람 심각도별 수(홈 요약 DSH-01.01). 범위 경로가 null이면 조직 전체 */
    public Map<String, Long> countOpenBySeverity(long organizationId, Collection<String> scopePaths) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        String scope = "";
        if (scopePaths != null) {
            scope = " AND EXISTS (SELECT 1 FROM unnest(CAST(:scopePaths AS text[])) p WHERE s.path LIKE p || '%')";
            params.put("scopePaths", Pg.textArray(scopePaths));
        }
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("SELECT a.severity, count(*) AS n FROM data2flow_core.alarms a LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id"
                        + " AND s.organization_id = a.organization_id WHERE a.organization_id = :org AND a.status IN ('ACTIVE','ACKNOWLEDGED')"
                        + scope + " GROUP BY a.severity")
                .params(params).query(rs -> {
                    result.put(rs.getString("severity"), rs.getLong("n"));
                });
        return result;
    }

    /** 기기의 마지막 수신 상태가 오프라인인가(data2flow_pipeline.device_state 읽기, conventions §6) */
    public boolean isDeviceOffline(long organizationId, long deviceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_pipeline.device_state WHERE organization_id = :org AND device_id = :id"
                        + " AND connectivity = 'OFFLINE')")
                .param("org", organizationId).param("id", deviceId).query(Boolean.class).single();
    }

    /** 기기의 공간·가상 여부·마지막 게이트웨이 */
    public record DeviceInfo(long id, String name, Long spaceId, boolean virtual, String gatewayEui, Long sourceId) {
    }

    public Optional<DeviceInfo> findDeviceInfo(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.is_virtual, ds.best_gateway_eui, d.source_id
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_pipeline.device_state ds ON ds.device_id = d.id AND ds.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.id = :id""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new DeviceInfo(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                        rs.getBoolean("is_virtual"), rs.getString("best_gateway_eui"), Pg.longOrNull(rs, "source_id")))
                .optional();
    }

    /** 게이트웨이 아래 기기(마지막 수신 게이트웨이가 그 EUI인 같은 소스 기기) */
    public List<Long> listDevicesUnderGateway(long organizationId, long sourceId, String gatewayEui) {
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.devices d
                          JOIN data2flow_pipeline.device_state ds ON ds.device_id = d.id AND ds.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.source_id = :source AND ds.best_gateway_eui = :eui AND d.status = 'ACTIVE'""")
                .param("org", organizationId).param("source", sourceId).param("eui", gatewayEui).query(Long.class).list();
    }

    /** 게이트웨이(소스·EUI) */
    public record GatewayRef(long id, long sourceId, String gatewayEui, String name, Long spaceId) {
    }

    public Optional<GatewayRef> findGateway(long organizationId, long gatewayId) {
        return jdbc.sql("SELECT id, source_id, gateway_eui, name, space_id FROM data2flow_core.gateways WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", gatewayId)
                .query((rs, n) -> new GatewayRef(rs.getLong("id"), rs.getLong("source_id"), rs.getString("gateway_eui"), rs.getString("name"),
                        Pg.longOrNull(rs, "space_id"))).optional();
    }

    /** 기기들의 열린 연결 계열 알람(측정 항목이 없는 무수신·오프라인 알람, 게이트웨이 알람 제외) */
    public List<Long> listOpenConnectivityByDevices(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND device_id = ANY(CAST(:devices AS bigint[]))"
                        + " AND status IN " + OPEN + " AND metric_key IS NULL AND alarm_key NOT LIKE 'system:GATEWAY_OFFLINE:%' ORDER BY id")
                .param("org", organizationId).param("devices", Pg.bigintArray(deviceIds)).query(Long.class).list();
    }

    /** 열린 게이트웨이 알람(system:GATEWAY_OFFLINE:{gatewayId}) — 기기의 게이트웨이로 찾는다 */
    public Optional<Long> findOpenGatewayAlarm(long organizationId, long sourceId, String gatewayEui) {
        return jdbc.sql("""
                        SELECT a.id FROM data2flow_core.alarms a
                          JOIN data2flow_core.gateways g ON a.alarm_key = 'system:GATEWAY_OFFLINE:' || g.id AND g.organization_id = a.organization_id
                         WHERE a.organization_id = :org AND g.source_id = :source AND g.gateway_eui = :eui AND a.status IN """ + OPEN)
                .param("org", organizationId).param("source", sourceId).param("eui", gatewayEui).query(Long.class).optional();
    }

    /** 공간이나 그 상위가 유지보수 중인가, 기기가 유지보수 중인가(ACTIVE 창) */
    public boolean isUnderMaintenance(long organizationId, Long deviceId, Long spaceId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM data2flow_core.maintenance_windows w
                           WHERE w.organization_id = :org AND w.status = 'ACTIVE'
                             AND ((w.target_type = 'DEVICE' AND w.target_id = CAST(:device AS bigint))
                               OR (w.target_type = 'SPACE' AND EXISTS (
                                    SELECT 1 FROM data2flow_core.spaces t JOIN data2flow_core.spaces s ON s.organization_id = t.organization_id
                                     WHERE t.organization_id = w.organization_id AND t.id = w.target_id
                                       AND s.id = CAST(:space AS bigint) AND s.path LIKE t.path || '%'))))""")
                .param("org", organizationId).param("device", deviceId).param("space", spaceId).query(Boolean.class).single();
    }

    /** 최근 알람(홈 타임라인·대시보드) */
    public List<AlarmRow> listRecent(long organizationId, Collection<String> scopePaths, Instant since, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        params.put("since", Pg.ts(since));
        params.put("limit", limit);
        String scope = "";
        if (scopePaths != null) {
            scope = " AND EXISTS (SELECT 1 FROM unnest(CAST(:scopePaths AS text[])) p WHERE s.path LIKE p || '%')";
            params.put("scopePaths", Pg.textArray(scopePaths));
        }
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND (a.raised_at >= :since OR a.cleared_at >= :since)" + scope
                        + " ORDER BY greatest(a.raised_at, coalesce(a.cleared_at, a.raised_at)) DESC LIMIT :limit")
                .params(params).query(AlarmRepository::map).list();
    }

    /** 조직 확인용(이벤트로 받은 알람 ID) */
    @OrganizationScopeExempt("내부 API가 알람 ID(전역 고유)로 조직을 찾은 뒤 그 조직 조건으로 다시 읽는다")
    public Optional<Long> findOrganization(long alarmId) {
        return jdbc.sql("SELECT organization_id FROM data2flow_core.alarms WHERE id = :id").param("id", alarmId).query(Long.class).optional();
    }

    static AlarmRow map(ResultSet rs, int n) throws SQLException {
        return new AlarmRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("alarm_key"), rs.getString("source_type"),
                Pg.longOrNull(rs, "rule_id"), rs.getObject("flow_id", UUID.class), rs.getString("node_id"), rs.getString("severity"),
                rs.getString("title"), rs.getString("status"), rs.getBoolean("flapping"), Pg.longOrNull(rs, "device_id"),
                rs.getString("device_name"), rs.getBoolean("device_virtual"), Pg.longOrNull(rs, "space_id"), rs.getString("space_name"),
                rs.getString("space_path"), rs.getString("space_names"), rs.getString("metric_key"), doubleOrNull(rs, "trigger_value"),
                doubleOrNull(rs, "peak_value"), doubleOrNull(rs, "last_value"), rs.getString("threshold"), rs.getInt("occurrence_count"),
                Pg.instant(rs, "raised_at"), Pg.instant(rs, "last_raised_at"), Pg.longOrNull(rs, "acked_by"), rs.getString("acked_by_name"),
                Pg.instant(rs, "acked_at"), Pg.instant(rs, "cleared_at"), rs.getString("clear_reason"), Pg.longOrNull(rs, "assignee_id"),
                rs.getString("assignee_name"), Pg.longOrNull(rs, "parent_alarm_id"), rs.getInt("child_count"),
                Pg.longOrNull(rs, "space_event_id"), rs.getString("suppressed_reason"), Pg.instant(rs, "last_notified_at"),
                rs.getInt("notify_seq"), rs.getInt("version"), Pg.instant(rs, "updated_at"), rs.getString("rule_name"));
    }

    static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
