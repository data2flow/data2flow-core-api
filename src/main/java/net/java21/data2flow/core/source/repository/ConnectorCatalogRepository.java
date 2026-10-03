package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 커넥터 카탈로그({@code connector_catalogs}, 시스템 공용 — 조직 구분 없음)와 템플릿({@code connector_templates}, 플랫폼 공용
 * organization_id 0 + 조직 템플릿). DSC-09.01·09.12, EVT-DSC-09.
 */
@Repository
@OrganizationScopeExempt("커넥터 카탈로그는 시스템 공용 테이블이다(조직 열 없음). 템플릿 조회는 조직 0(플랫폼)과 요청 조직만 본다")
public class ConnectorCatalogRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ConnectorCatalogRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Connector> findAll() {
        return jdbc.sql("SELECT *, config_schema::text AS schema_text FROM data2flow_core.connector_catalogs ORDER BY category, connector_key")
                .query(this::map).list();
    }

    public Optional<Connector> findByKey(String key) {
        return jdbc.sql("SELECT *, config_schema::text AS schema_text FROM data2flow_core.connector_catalogs WHERE connector_key = :key")
                .param("key", key).query(this::map).optional();
    }

    /**
     * ingress 보고 반영(BR-DSC-23): 버전·스키마·지원 범위를 최신으로. 운영 판단으로 끈 것(enabled·disabled_reason)과 화면 표시
     * 열(standard·transports)은 그대로 둔다.
     */
    public void upsertReported(String key, String name, String version, String category, String schemaJson, List<String> authMethods,
                               List<String> payloadFormats, String ackMode, String scaling, boolean supportsSend, String instanceId,
                               Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.connector_catalogs (connector_key, version, category, config_schema, auth_methods,
                            payload_formats, ack_mode, scaling, supports_send, name, reported_by, reported_at, created_at, updated_at)
                        VALUES (:key, :version, :category, CAST(:schema AS jsonb), CAST(:auth AS text[]), CAST(:formats AS text[]), :ack,
                            :scaling, :send, :name, :by, :now, :now, :now)
                        ON CONFLICT (connector_key) DO UPDATE
                           SET version = EXCLUDED.version, category = EXCLUDED.category, config_schema = EXCLUDED.config_schema,
                               auth_methods = EXCLUDED.auth_methods, payload_formats = EXCLUDED.payload_formats,
                               ack_mode = EXCLUDED.ack_mode, scaling = EXCLUDED.scaling, supports_send = EXCLUDED.supports_send,
                               name = coalesce(EXCLUDED.name, data2flow_core.connector_catalogs.name),
                               reported_by = EXCLUDED.reported_by, reported_at = EXCLUDED.reported_at, updated_at = EXCLUDED.updated_at""")
                .param("key", key).param("version", version).param("category", category).param("schema", schemaJson)
                .param("auth", Pg.textArray(authMethods)).param("formats", Pg.textArray(payloadFormats)).param("ack", ackMode)
                .param("scaling", scaling).param("send", supportsSend).param("name", name).param("by", instanceId)
                .param("now", Pg.ts(now)).update();
    }

    /** 플랫폼 템플릿(조직 0)과 조직 템플릿 */
    public List<Template> findTemplates(long organizationId) {
        return jdbc.sql("""
                        SELECT *, preset::text AS preset_text FROM data2flow_core.connector_templates
                         WHERE organization_id IN (0, :org) ORDER BY organization_id, name""")
                .param("org", organizationId).query(this::template).list();
    }

    /** 키로 템플릿(조직 템플릿이 플랫폼 템플릿보다 우선) */
    public Optional<Template> findTemplate(long organizationId, String key) {
        return jdbc.sql("""
                        SELECT *, preset::text AS preset_text FROM data2flow_core.connector_templates
                         WHERE organization_id IN (0, :org) AND template_key = :key ORDER BY organization_id DESC LIMIT 1""")
                .param("org", organizationId).param("key", key).query(this::template).optional();
    }

    private Connector map(ResultSet rs, int n) throws SQLException {
        return new Connector(rs.getString("connector_key"), rs.getString("version"), rs.getString("category"),
                json.readTree(rs.getString("schema_text")), Pg.stringList(rs, "auth_methods"), Pg.stringList(rs, "payload_formats"),
                rs.getString("ack_mode"), rs.getString("scaling"), rs.getBoolean("supports_send"), rs.getBoolean("enabled"),
                rs.getString("disabled_reason"), rs.getString("name"), rs.getString("standard"), Pg.stringList(rs, "transports"));
    }

    private Template template(ResultSet rs, int n) throws SQLException {
        return new Template(rs.getString("template_key"), rs.getString("connector_key"), rs.getString("name"), rs.getString("description"),
                json.readTree(rs.getString("preset_text")), rs.getString("decoder_key"), rs.getString("docs_url"), rs.getBoolean("builtin"));
    }

    /** 카탈로그 한 행 */
    public record Connector(String key, String version, String category, JsonNode schema, List<String> authMethods,
                            List<String> payloadFormats, String ackMode, String scaling, boolean supportsSend, boolean enabled,
                            String disabledReason, String name, String standard, List<String> transports) {
    }

    /** 템플릿 한 행 */
    public record Template(String key, String connectorKey, String name, String description, JsonNode preset, String decoderKey,
                           String docsUrl, boolean builtin) {
    }
}
