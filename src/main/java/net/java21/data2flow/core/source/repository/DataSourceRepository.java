package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.domain.SourceModels.SecretRow;
import net.java21.data2flow.core.source.domain.SourceModels.SourceTopic;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 데이터 소스 집합체 저장소: {@code data_sources}, {@code source_topics}, {@code source_secrets}(암호문만). DSC domain-model §2.1·2.3·2.4.
 * 조직 전체를 도는 내부 조회(API-DSC-50)는 배포 조직 조건(ADR-030)으로 좁힌다.
 */
@Repository
public class DataSourceRepository {

    private static final String COLUMNS = """
            s.id, s.organization_id, s.code, s.name, s.type, s.connector_key, s.connector_version, s.lifecycle,
            s.connection::text AS connection, s.tls::text AS tls, s.payload::text AS payload, s.is_dev, s.decoder_key,
            s.decoder_config::text AS decoder_config, s.decode_script_id, s.unknown_device_policy, s.default_model_id,
            s.default_space_id, s.autoreg_limit_per_hour, s.no_data_alarm_after_sec, s.site_id, s.archived_at, s.version,
            s.created_at, s.updated_at, s.topic_template""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DataSourceRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<DataSource> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources s WHERE s.organization_id = :org AND s.id = :id")
                .param("org", organizationId).param("id", id).query(this::map).optional();
    }

    /** 수정·상태 변경용 행 잠금 */
    public Optional<DataSource> lockById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources s WHERE s.organization_id = :org AND s.id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(this::map).optional();
    }

    /** 내부 조회(API-ING-21)용: 조직을 모르는 호출자. 배포 조직 조건으로 좁힌다 */
    @OrganizationScopeExempt("pipeline이 소스 ID로 수집 맥락을 묻는다(API-ING-21). 배포 조직(ADR-030)으로 좁힌다")
    public Optional<DataSource> findInternal(long id, OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.data_sources s
                        WHERE s.id = :id AND (CAST(:org AS bigint) IS NULL OR s.organization_id = :org)""")
                .param("id", id).param("org", org).query(this::map).optional();
    }

    /** ingress 실행 설정(API-DSC-50): 상태가 맞는 모든 조직의 소스(배포 조직으로 좁힘) */
    @OrganizationScopeExempt("ingress가 모든 조직의 실행 설정을 읽는다(API-DSC-50). 배포 조직(ADR-030)으로 좁힌다")
    public List<DataSource> listForRuntime(Collection<String> lifecycles, OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.data_sources s
                        WHERE s.lifecycle = ANY(CAST(:lifecycles AS text[]))
                          AND (CAST(:org AS bigint) IS NULL OR s.organization_id = :org)
                        ORDER BY s.organization_id, s.id""")
                .param("lifecycles", Pg.textArray(lifecycles)).param("org", org).query(this::map).list();
    }

    /** 목록 검색 조건 */
    public record Filter(long organizationId, String keyword, List<String> types, List<String> lifecycles, String state) {
    }

    public List<DataSource> search(Filter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources s " + where() + " ORDER BY s.name, s.id LIMIT :limit OFFSET :offset")
                .params(params(f)).param("limit", limit).param("offset", offset).query(this::map).list();
    }

    public long count(Filter f) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.data_sources s " + where()).params(params(f)).query(Long.class).single();
    }

    private static String where() {
        return """
                LEFT JOIN data2flow_core.source_states st ON st.source_id = s.id
                WHERE s.organization_id = :org
                  AND (CAST(:kw AS text) IS NULL OR s.name ILIKE '%' || CAST(:kw AS text) || '%' OR s.code ILIKE '%' || CAST(:kw AS text) || '%')
                  AND (cardinality(CAST(:types AS text[])) = 0 OR s.type = ANY(CAST(:types AS text[])))
                  AND s.lifecycle = ANY(CAST(:lifecycles AS text[]))
                  AND (CAST(:state AS text) IS NULL
                       OR (s.lifecycle <> 'ACTIVE' AND CAST(:state AS text) = 'DISABLED')
                       OR (s.lifecycle = 'ACTIVE' AND coalesce(st.connection_state, 'CONNECTING') = CAST(:state AS text)))
                """;
    }

    private static Map<String, Object> params(Filter f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", f.organizationId());
        p.put("kw", f.keyword());
        p.put("types", Pg.textArray(f.types()));
        p.put("lifecycles", Pg.textArray(f.lifecycles()));
        p.put("state", f.state());
        return p;
    }

    /** 조직의 보관되지 않은 소스 수(BR-DSC-06) */
    public long countNotArchived(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.data_sources WHERE organization_id = :org AND lifecycle <> 'ARCHIVED'")
                .param("org", organizationId).query(Long.class).single();
    }

    /** Webhook 수신 키는 모든 조직에서 하나뿐이다(ingress가 경로의 키만으로 소스를 찾는다, API-DSC-54) */
    @OrganizationScopeExempt("수신 경로 키 중복 검사. 결과는 있음·없음만 쓴다")
    public boolean existsWebhookSourceKey(String sourceKey, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.data_sources
                                        WHERE type = 'WEBHOOK' AND connection->>'sourceKey' = :key
                                          AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("key", sourceKey).param("except", exceptId).query(Boolean.class).single();
    }

    public boolean existsCode(long organizationId, String code) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.data_sources WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code).query(Long.class).single() > 0;
    }

    /** 조직의 다른 소스(보관 제외)의 connection·code — client-id base 중복 검사(BR-DSC-01) */
    public List<DataSource> listOthersNotArchived(long organizationId, Long exceptId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.data_sources s
                        WHERE s.organization_id = :org AND s.lifecycle <> 'ARCHIVED' AND (CAST(:except AS bigint) IS NULL OR s.id <> :except)""")
                .param("org", organizationId).param("except", exceptId).query(this::map).list();
    }

    /** 새 소스. 생성한 ID */
    public long insert(long organizationId, String code, String name, String type, String connectorKey, String connectorVersion,
                       String lifecycle, String connectionJson, boolean isDev, String decoderKey, String decoderConfigJson,
                       Long decodeScriptId, String unknownDevicePolicy, Long defaultModelId, Long defaultSpaceId, int autoregLimitPerHour,
                       int noDataAlarmAfterSec, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, connector_key, connector_version, lifecycle,
                            connection, is_dev, decoder_key, decoder_config, decode_script_id, unknown_device_policy, default_model_id,
                            default_space_id, autoreg_limit_per_hour, no_data_alarm_after_sec, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :code, :name, :type, :ck, :cv, :lifecycle, CAST(:conn AS jsonb), :dev, :dk, CAST(:dc AS jsonb), :script,
                            :policy, :model, :space, :autoreg, :nodata, :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("code", code).param("name", name).param("type", type).param("ck", connectorKey)
                .param("cv", connectorVersion).param("lifecycle", lifecycle).param("conn", connectionJson).param("dev", isDev)
                .param("dk", decoderKey).param("dc", decoderConfigJson).param("script", decodeScriptId).param("policy", unknownDevicePolicy)
                .param("model", defaultModelId).param("space", defaultSpaceId).param("autoreg", autoregLimitPerHour)
                .param("nodata", noDataAlarmAfterSec).param("by", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 설정 수정(PATCH). 낙관적 잠금 */
    public int update(DataSource s, String connectionJson, String decoderConfigJson, int baseVersion, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources
                           SET name = :name, connection = CAST(:conn AS jsonb), is_dev = :dev, decoder_key = :dk,
                               decoder_config = CAST(:dc AS jsonb), decode_script_id = :script, unknown_device_policy = :policy,
                               default_model_id = :model, default_space_id = :space, autoreg_limit_per_hour = :autoreg,
                               no_data_alarm_after_sec = :nodata, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", s.name()).param("conn", connectionJson).param("dev", s.isDev()).param("dk", s.decoderKey())
                .param("dc", decoderConfigJson).param("script", s.decodeScriptId()).param("policy", s.unknownDevicePolicy())
                .param("model", s.defaultModelId()).param("space", s.defaultSpaceId()).param("autoreg", s.autoregLimitPerHour())
                .param("nodata", s.noDataAlarmAfterSec()).param("by", userId).param("now", Pg.ts(now))
                .param("org", s.organizationId()).param("id", s.id()).param("base", baseVersion).update();
    }

    /** payload 형식·토픽 템플릿 저장(DSC-09.07·09.08). 같은 트랜잭션의 다른 저장이 판을 올리므로 판은 그대로 */
    public int updatePayload(long organizationId, long id, String payloadJson, String topicTemplate) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources SET payload = CAST(:payload AS jsonb), topic_template = :template
                         WHERE organization_id = :org AND id = :id""")
                .param("payload", payloadJson).param("template", topicTemplate).param("org", organizationId).param("id", id).update();
    }

    /** payload만 바꾸고 판을 올린다(스키마 업로드 API-DSC-59) */
    public int updatePayloadAndBump(long organizationId, long id, String payloadJson, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources SET payload = CAST(:payload AS jsonb), version = version + 1, updated_by = :by,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("payload", payloadJson).param("by", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .update();
    }

    /** lifecycle 변경. 낙관적 잠금 */
    public int updateLifecycle(long organizationId, long id, int baseVersion, String lifecycle, Instant archivedAt, long userId,
                               Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources
                           SET lifecycle = :lifecycle, archived_at = :archivedAt, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("lifecycle", lifecycle).param("archivedAt", Pg.ts(archivedAt)).param("by", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    /** 비밀값만 바뀐 때 버전을 올린다(ingress가 다시 읽게) */
    public int touch(long organizationId, long id, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources SET version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("by", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    // ---- 토픽 ----

    public List<SourceTopic> findTopics(long organizationId, long sourceId) {
        return jdbc.sql("SELECT topic, qos FROM data2flow_core.source_topics WHERE organization_id = :org AND source_id = :id ORDER BY created_at, topic")
                .param("org", organizationId).param("id", sourceId)
                .query((rs, n) -> new SourceTopic(rs.getString("topic"), rs.getInt("qos"))).list();
    }

    /** 소스 여러 개의 토픽(실행 설정) */
    @OrganizationScopeExempt("ingress 실행 설정(API-DSC-50). 이미 배포 조직으로 좁힌 소스 ID만 넘어온다")
    public Map<Long, List<SourceTopic>> findTopicsOf(Collection<Long> sourceIds) {
        Map<Long, List<SourceTopic>> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT source_id, topic, qos FROM data2flow_core.source_topics WHERE source_id = ANY(CAST(:ids AS bigint[])) ORDER BY source_id, created_at, topic")
                .param("ids", Pg.bigintArray(sourceIds))
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getLong("source_id"), k -> new ArrayList<>())
                            .add(new SourceTopic(rs.getString("topic"), rs.getInt("qos")));
                    return null;
                }).list();
        return result;
    }

    public void replaceTopics(long organizationId, long sourceId, List<SourceTopic> topics, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.source_topics WHERE organization_id = :org AND source_id = :id")
                .param("org", organizationId).param("id", sourceId).update();
        Instant t = now;
        for (SourceTopic topic : topics) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.source_topics (source_id, organization_id, topic, qos, created_at)
                            VALUES (:id, :org, :topic, :qos, :now)""")
                    .param("id", sourceId).param("org", organizationId).param("topic", topic.topic()).param("qos", topic.qos())
                    .param("now", Pg.ts(t)).update();
            t = t.plusNanos(1000); // 입력 순서를 지킨다
        }
    }

    // ---- 비밀값 ----

    public List<SecretMeta> findSecretMeta(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT kind, kid, fingerprint, rotated_at, updated_at, pending_ciphertext IS NOT NULL AS rotating, cert_not_after
                          FROM data2flow_core.source_secrets WHERE organization_id = :org AND source_id = :id ORDER BY kind""")
                .param("org", organizationId).param("id", sourceId)
                .query((rs, n) -> new SecretMeta(rs.getString("kind"), rs.getString("kid"), rs.getString("fingerprint"),
                        Pg.instant(rs, "rotated_at"), Pg.instant(rs, "updated_at"), rs.getBoolean("rotating"),
                        Pg.instant(rs, "cert_not_after"))).list();
    }

    public List<SecretRow> findSecrets(long organizationId, long sourceId) {
        return jdbc.sql("SELECT kind, ciphertext, kid, fingerprint FROM data2flow_core.source_secrets WHERE organization_id = :org AND source_id = :id")
                .param("org", organizationId).param("id", sourceId)
                .query((rs, n) -> new SecretRow(rs.getString("kind"), rs.getBytes("ciphertext"), rs.getString("kid"),
                        rs.getString("fingerprint"))).list();
    }

    /** 여러 소스의 비밀값(실행 설정, 내부 전용) */
    @OrganizationScopeExempt("ingress 실행 설정(API-DSC-50). 이미 배포 조직으로 좁힌 소스 ID만 넘어온다")
    public Map<Long, List<SecretRow>> findSecretsOf(Collection<Long> sourceIds) {
        Map<Long, List<SecretRow>> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT source_id, kind, ciphertext, kid, fingerprint FROM data2flow_core.source_secrets WHERE source_id = ANY(CAST(:ids AS bigint[]))")
                .param("ids", Pg.bigintArray(sourceIds))
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getLong("source_id"), k -> new ArrayList<>())
                            .add(new SecretRow(rs.getString("kind"), rs.getBytes("ciphertext"), rs.getString("kid"), rs.getString("fingerprint")));
                    return null;
                }).list();
        return result;
    }

    public void upsertSecret(long organizationId, long sourceId, String kind, byte[] ciphertext, String kid, String fingerprint,
                             Instant certNotAfter, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_secrets (source_id, organization_id, kind, ciphertext, kid, fingerprint, rotated_at,
                            cert_not_after, created_at, updated_at)
                        VALUES (:id, :org, :kind, :ct, :kid, :fp, :now, :na, :now, :now)
                        ON CONFLICT (source_id, kind) DO UPDATE
                           SET ciphertext = EXCLUDED.ciphertext, kid = EXCLUDED.kid, fingerprint = EXCLUDED.fingerprint,
                               pending_ciphertext = NULL, pending_kid = NULL, pending_fingerprint = NULL, cert_not_after = EXCLUDED.cert_not_after,
                               rotated_at = EXCLUDED.rotated_at, updated_at = EXCLUDED.updated_at""")
                .param("id", sourceId).param("org", organizationId).param("kind", kind).param("ct", ciphertext).param("kid", kid)
                .param("fp", fingerprint).param("na", certNotAfter == null ? null : Pg.ts(certNotAfter)).param("now", Pg.ts(now)).update();
    }

    /** 무중단 교체: 있는 종류에만 새 값을 pending으로 둔다. 바뀐 행 수(0이면 그 종류가 아직 없음) */
    public int setPendingSecret(long organizationId, long sourceId, String kind, byte[] ciphertext, String kid, String fingerprint,
                                Instant certNotAfter, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.source_secrets
                           SET pending_ciphertext = :ct, pending_kid = :kid, pending_fingerprint = :fp, updated_at = :now
                         WHERE organization_id = :org AND source_id = :id AND kind = :kind""")
                .param("ct", ciphertext).param("kid", kid).param("fp", fingerprint).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", sourceId).param("kind", kind).update();
    }

    /** 교체 확정: pending → 현재 값 */
    public int updatePendingCommitted(long organizationId, long sourceId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.source_secrets
                           SET ciphertext = pending_ciphertext, kid = pending_kid, fingerprint = pending_fingerprint, pending_ciphertext = NULL,
                               pending_kid = NULL, pending_fingerprint = NULL, rotated_at = :now, updated_at = :now
                         WHERE organization_id = :org AND source_id = :id AND pending_ciphertext IS NOT NULL""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).update();
    }

    /** 교체 실패: pending 버리기(이전 값 유지) */
    public int updatePendingDiscarded(long organizationId, long sourceId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.source_secrets SET pending_ciphertext = NULL, pending_kid = NULL, pending_fingerprint = NULL,
                               updated_at = :now
                         WHERE organization_id = :org AND source_id = :id AND pending_ciphertext IS NOT NULL""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).update();
    }

    /** 여러 소스의 교체 중 새 값(실행 설정, 내부 전용) */
    @OrganizationScopeExempt("ingress 실행 설정(API-DSC-50). 이미 배포 조직으로 좁힌 소스 ID만 넘어온다")
    public Map<Long, List<net.java21.data2flow.core.source.domain.SourceModels.PendingRow>> findPendingOf(Collection<Long> sourceIds) {
        Map<Long, List<net.java21.data2flow.core.source.domain.SourceModels.PendingRow>> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT source_id, kind, pending_ciphertext, pending_fingerprint FROM data2flow_core.source_secrets
                         WHERE source_id = ANY(CAST(:ids AS bigint[])) AND pending_ciphertext IS NOT NULL""")
                .param("ids", Pg.bigintArray(sourceIds))
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getLong("source_id"), k -> new ArrayList<>())
                            .add(new net.java21.data2flow.core.source.domain.SourceModels.PendingRow(rs.getString("kind"), rs.getBytes("pending_ciphertext"), rs.getString("pending_fingerprint")));
                    return null;
                }).list();
        return result;
    }

    public int deleteSecretsExcept(long organizationId, long sourceId, Collection<String> keepKinds) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.source_secrets
                         WHERE organization_id = :org AND source_id = :id AND NOT (kind = ANY(CAST(:keep AS text[])))""")
                .param("org", organizationId).param("id", sourceId).param("keep", Pg.textArray(keepKinds)).update();
    }

    // ---- 참조 ----

    /** 소스를 쓰는 기기·게이트웨이 수(BR-DSC-04) */
    public long countDevices(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org AND source_id = :id)
                             + (SELECT count(*) FROM data2flow_core.gateways WHERE organization_id = :org AND source_id = :id)""")
                .param("org", organizationId).param("id", sourceId).query(Long.class).single();
    }

    /** 소스별 기기 수(목록) */
    public Map<Long, Long> countDevicesOf(long organizationId, Collection<Long> sourceIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT source_id, count(*) AS n FROM data2flow_core.devices
                         WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'
                         GROUP BY source_id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds))
                .query((rs, n) -> result.put(rs.getLong("source_id"), rs.getLong("n"))).list();
        return result;
    }

    private DataSource map(ResultSet rs, int n) throws SQLException {
        return new DataSource(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("type"), rs.getString("connector_key"), rs.getString("connector_version"), rs.getString("lifecycle"),
                node(rs.getString("connection")), node(rs.getString("tls")), node(rs.getString("payload")), rs.getBoolean("is_dev"),
                rs.getString("decoder_key"), node(rs.getString("decoder_config")), Pg.longOrNull(rs, "decode_script_id"),
                rs.getString("unknown_device_policy"), Pg.longOrNull(rs, "default_model_id"), Pg.longOrNull(rs, "default_space_id"),
                rs.getInt("autoreg_limit_per_hour"), rs.getInt("no_data_alarm_after_sec"), Pg.longOrNull(rs, "site_id"),
                Pg.instant(rs, "archived_at"), rs.getInt("version"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                rs.getString("topic_template"));
    }

    private JsonNode node(String text) {
        return text == null ? null : json.readTree(text);
    }
}
