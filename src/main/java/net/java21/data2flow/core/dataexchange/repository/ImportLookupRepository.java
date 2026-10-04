package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 가져오기 대상 찾기(TSD-04.02, BR-TSD-16): 기기 식별(ID·외부 ID·이름, 삭제 제외), 측정 항목 키(별칭 포함), 이미 있는 점 수(중복 예상).
 * 시계열은 pipeline 소유라 읽기만 한다(쓰기는 API-TSD-52).
 */
@Repository
public class ImportLookupRepository {

    private final JdbcClient jdbc;

    public ImportLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 식별 방식별 기기 표(키 → ID). 외부 ID·이름은 소문자 */
    public Map<String, Long> findDeviceKeys(long organizationId, String deviceKey) {
        String column = switch (deviceKey) {
            case "ID" -> "CAST(id AS text)";
            case "NAME" -> "lower(name)";
            default -> "lower(external_id)";
        };
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("SELECT " + column + " AS k, id FROM data2flow_core.devices WHERE organization_id = :org AND status <> 'DELETED'")
                .param("org", organizationId).query((rs, n) -> out.putIfAbsent(rs.getString("k"), rs.getLong("id"))).list();
        return out;
    }

    /** 측정 항목 키와 별칭 → 키 */
    public Map<String, String> findMetricKeys(long organizationId) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT key FROM data2flow_core.metrics WHERE organization_id = :org AND status <> 'IGNORED'")
                .param("org", organizationId).query((rs, n) -> out.put(rs.getString("key").toLowerCase(Locale.ROOT), rs.getString("key"))).list();
        jdbc.sql("SELECT alias, metric_key FROM data2flow_core.metric_aliases WHERE organization_id = :org")
                .param("org", organizationId)
                .query((rs, n) -> out.putIfAbsent(rs.getString("alias").toLowerCase(Locale.ROOT), rs.getString("metric_key"))).list();
        return out;
    }

    /** 이미 있는 점(기기, 측정 항목, 시각) 수 — 미리 실행의 중복 예상(BR-TSD-15) */
    public long countExisting(long organizationId, List<Long> deviceIds, List<String> metrics, List<Instant> times) {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_pipeline.telemetry t
                          JOIN unnest(CAST(:d AS bigint[]), CAST(:m AS text[]), CAST(:t AS timestamptz[])) AS x(d, m, ts)
                            ON t.device_id = x.d AND t.metric_key = x.m AND t.time = x.ts
                         WHERE t.organization_id = :org""")
                .param("org", organizationId).param("d", Pg.bigintArray(deviceIds)).param("m", Pg.textArray(metrics))
                .param("t", "{" + String.join(",", times.stream().map(i -> "\"" + i + "\"").toList()) + "}")
                .query(Long.class).single();
    }

    /** 고정 기기 ID 확인 */
    public Set<Long> findDeviceIds(long organizationId, List<Long> ids) {
        return new HashSet<>(jdbc.sql("""
                        SELECT id FROM data2flow_core.devices WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))
                           AND status <> 'DELETED'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(Long.class).list());
    }
}
