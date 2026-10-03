package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.source.domain.SourceModels.SourceLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 소스가 참조하거나 함께 쓰는 다른 테이블 읽기(쓰기는 각 소유 기능이 한다): 공간·모델(기본값 확인, DSC-01.07),
 * 스크립트(디코드 스크립트 확인, DSC-01.06), 측정 항목·별칭·스크립트 연결(수집 맥락 API-ING-21), 자동 등록 무시 목록(API-DSC-13),
 * 조직 한도(source_limits, DSC-07.03).
 */
@Repository
public class SourceReferenceRepository {

    private final JdbcClient jdbc;

    public SourceReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** ACTIVE 공간이 있는가 */
    public boolean existsSpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'")
                .param("org", organizationId).param("id", spaceId).query(Long.class).single() > 0;
    }

    public boolean existsModel(long organizationId, long modelId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).query(Long.class).single() > 0;
    }

    /** 스크립트 종류(DECODE·TRANSFORM). 없으면 빈 값 */
    public Optional<String> findScriptKind(long organizationId, long scriptId) {
        return jdbc.sql("SELECT kind FROM data2flow_core.scripts WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", scriptId).query(String.class).optional();
    }

    /** 활성 버전 번호(디코더 키 {@code script:{id}@v{n}}). 없으면 빈 값 */
    public Optional<Integer> findActiveScriptVersion(long organizationId, long scriptId) {
        return jdbc.sql("""
                        SELECT v.version_no FROM data2flow_core.scripts s JOIN data2flow_core.script_versions v ON v.id = s.active_version_id
                         WHERE s.organization_id = :org AND s.id = :id""")
                .param("org", organizationId).param("id", scriptId).query(Integer.class).optional();
    }

    /** 소스에 연결된 스크립트 ID(script_bindings target SOURCE) */
    public List<Long> findSourceScriptIds(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT script_id FROM data2flow_core.script_bindings
                         WHERE organization_id = :org AND target_type = 'SOURCE' AND target_id = :target AND enabled
                         ORDER BY kind, script_id""")
                .param("org", organizationId).param("target", Long.toString(sourceId)).query(Long.class).list();
    }

    /** 측정 항목 정의와 별칭(수집 맥락). IGNORED는 뺀다 */
    public List<MetricDef> findMetrics(long organizationId) {
        Map<String, MetricDef> byKey = new LinkedHashMap<>();
        jdbc.sql("SELECT id, key, unit, value_type, status FROM data2flow_core.metrics WHERE organization_id = :org AND status <> 'IGNORED' ORDER BY key")
                .param("org", organizationId)
                .query((rs, n) -> byKey.put(rs.getString("key"), new MetricDef(rs.getLong("id"), rs.getString("key"), rs.getString("unit"),
                        rs.getString("value_type"), rs.getString("status"), new ArrayList<>())))
                .list();
        jdbc.sql("SELECT alias, metric_key FROM data2flow_core.metric_aliases WHERE organization_id = :org ORDER BY alias")
                .param("org", organizationId)
                .query((rs, n) -> {
                    MetricDef def = byKey.get(rs.getString("metric_key"));
                    if (def != null) {
                        def.aliases().add(rs.getString("alias"));
                    }
                    return null;
                }).list();
        return List.copyOf(byKey.values());
    }

    public record MetricDef(long id, String key, String unit, String valueType, String status, List<String> aliases) {
    }

    // ---- 무시 목록 ----

    public List<Map<String, Object>> findIgnoreEntries(long organizationId, long sourceId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT external_id, reason, created_by, created_at FROM data2flow_core.source_ignore_entries
                         WHERE organization_id = :org AND source_id = :id ORDER BY created_at DESC, external_id LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("id", sourceId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("externalId", rs.getString("external_id"));
                    m.put("reason", rs.getString("reason"));
                    m.put("createdBy", Long.toString(rs.getLong("created_by")));
                    m.put("createdAt", Pg.instant(rs, "created_at"));
                    return m;
                }).list();
    }

    public long countIgnoreEntries(long organizationId, long sourceId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.source_ignore_entries WHERE organization_id = :org AND source_id = :id")
                .param("org", organizationId).param("id", sourceId).query(Long.class).single();
    }

    public int deleteIgnoreEntry(long organizationId, long sourceId, String externalId) {
        return jdbc.sql("DELETE FROM data2flow_core.source_ignore_entries WHERE organization_id = :org AND source_id = :id AND external_id = :ext")
                .param("org", organizationId).param("id", sourceId).param("ext", externalId).update();
    }

    // ---- 한도 ----

    public SourceLimits findLimits(long organizationId) {
        return jdbc.sql("SELECT * FROM data2flow_core.source_limits WHERE organization_id = :org")
                .param("org", organizationId)
                .query((rs, n) -> new SourceLimits(rs.getInt("max_sources"), rs.getInt("max_topics_per_source"),
                        rs.getInt("max_message_bytes"), rs.getInt("max_messages_per_sec"), rs.getInt("version"),
                        Pg.instant(rs, "updated_at")))
                .optional().orElse(SourceLimits.DEFAULT);
    }

    /** 한도 저장(없으면 만든다). 낙관적 잠금: 기존 버전이 base와 같을 때만 */
    public int upsertLimits(long organizationId, SourceLimits l, int baseVersion, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.source_limits (organization_id, max_sources, max_topics_per_source, max_message_bytes,
                            max_messages_per_sec, version, updated_by, updated_at)
                        VALUES (:org, :sources, :topics, :bytes, :rate, 1, :by, :now)
                        ON CONFLICT (organization_id) DO UPDATE
                           SET max_sources = EXCLUDED.max_sources, max_topics_per_source = EXCLUDED.max_topics_per_source,
                               max_message_bytes = EXCLUDED.max_message_bytes, max_messages_per_sec = EXCLUDED.max_messages_per_sec,
                               version = data2flow_core.source_limits.version + 1, updated_by = EXCLUDED.updated_by,
                               updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.source_limits.version = :base""")
                .param("org", organizationId).param("sources", l.maxSources()).param("topics", l.maxTopicsPerSource())
                .param("bytes", l.maxMessageBytes()).param("rate", l.maxMessagesPerSec()).param("by", userId).param("now", Pg.ts(now))
                .param("base", baseVersion).update();
    }

    /** 실행 설정에 싣는 여러 조직의 한도 */
    public Map<Long, SourceLimits> limitsOf(List<Long> organizationIds) {
        Map<Long, SourceLimits> result = new LinkedHashMap<>();
        for (Long orgId : organizationIds) {
            result.put(orgId, findLimits(orgId));
        }
        return result;
    }
}
