package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** payload 스키마 원문({@code data2flow_core.payload_schemas}, DSC-09.07·ADR-056). 참조는 불변이라 고치지 않고 새로 넣는다 */
@Repository
public class PayloadSchemaRepository {

    private final JdbcClient jdbc;

    public PayloadSchemaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SchemaRow(long id, long organizationId, long sourceId, String schemaRef, String format, String fileName, byte[] content,
                            List<String> messageTypes, String messageType, Instant createdAt) {
    }

    public void insert(long organizationId, long sourceId, String schemaRef, String format, String fileName, byte[] content, String sha256,
                       List<String> messageTypes, String messageType, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.payload_schemas (organization_id, source_id, schema_ref, format, file_name, content,
                               content_sha256, message_types, message_type, created_by, created_at)
                        VALUES (:org, :source, :ref, :format, :file, :content, :sha, CAST(:types AS text[]), :type, :by, :now)""")
                .param("org", organizationId).param("source", sourceId).param("ref", schemaRef).param("format", format)
                .param("file", fileName).param("content", content).param("sha", sha256).param("types", Pg.textArray(messageTypes))
                .param("type", messageType).param("by", userId).param("now", Pg.ts(now)).update();
    }

    public boolean exists(long organizationId, String schemaRef) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.payload_schemas WHERE organization_id = :org AND schema_ref = :ref)")
                .param("org", organizationId).param("ref", schemaRef).query(Boolean.class).single();
    }

    /** API-DSC-81: 참조로 찾는다(조직은 참조가 정한다, ingress가 소스 조직과 비교) */
    @OrganizationScopeExempt("불변 스키마 참조(난수)가 조직을 정한다. ingress 내부 API-DSC-81, 응답의 organizationId로 소스 조직과 비교")
    public Optional<SchemaRow> findByRef(String schemaRef) {
        return jdbc.sql("""
                        SELECT id, organization_id, source_id, schema_ref, format, file_name, content, message_types, message_type, created_at
                          FROM data2flow_core.payload_schemas WHERE schema_ref = :ref""")
                .param("ref", schemaRef).query(PayloadSchemaRepository::row).optional();
    }

    static SchemaRow row(ResultSet rs, int n) throws SQLException {
        return new SchemaRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("source_id"), rs.getString("schema_ref"),
                rs.getString("format"), rs.getString("file_name"), rs.getBytes("content"), Pg.stringList(rs, "message_types"),
                rs.getString("message_type"), Pg.instant(rs, "created_at"));
    }
}
