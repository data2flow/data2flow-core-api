package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 드라이버 정의({@code data2flow_core.drivers}, API-ACT-30)와 모델 연결({@code driver_bindings}, DEV-03.03·API-ACT-31).
 * 모델당 드라이버는 하나다(uq_driver_bindings_model_id).
 */
@Repository
public class DriverRepository {

    private static final String COLUMNS = """
            d.id, d.organization_id, d.name, d.type, d.config::text AS config, d.secret_enc IS NOT NULL AS has_secret, d.polling_sec,
            d.ack_timeout_sec, d.apply_timeout_sec, d.retry::text AS retry, d.circuit::text AS circuit, d.status, d.capabilities,
            d.version, d.created_at, d.updated_at,
            (SELECT count(*) FROM data2flow_core.driver_bindings b
               JOIN data2flow_core.devices dv ON dv.model_id = b.model_id AND dv.organization_id = b.organization_id
              WHERE b.driver_id = d.id AND b.organization_id = d.organization_id AND dv.status <> 'DELETED') AS device_count""";

    private final JdbcClient jdbc;

    public DriverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DriverRow(long id, long organizationId, String name, String type, String config, boolean hasSecret, int pollingSec,
                            int ackTimeoutSec, int applyTimeoutSec, String retry, String circuit, String status,
                            List<String> capabilities, int version, Instant createdAt, Instant updatedAt, long deviceCount) {
    }

    /** 모델 연결 정보(API-DEV-41 package.driverId, 내부 제어 정보) */
    public record BindingRow(long modelId, long driverId, Long encoderScriptId) {
    }

    public List<DriverRow> list(long organizationId, String type, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.drivers d WHERE d.organization_id = :org"
                        + " AND (CAST(:type AS varchar) IS NULL OR d.type = :type) ORDER BY d.name LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("type", type).param("limit", limit).param("offset", offset)
                .query(DriverRepository::map).list();
    }

    public long count(long organizationId, String type) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.drivers WHERE organization_id = :org"
                        + " AND (CAST(:type AS varchar) IS NULL OR type = :type)")
                .param("org", organizationId).param("type", type).query(Long.class).single();
    }

    public Optional<DriverRow> findById(long organizationId, long driverId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.drivers d WHERE d.organization_id = :org AND d.id = :id")
                .param("org", organizationId).param("id", driverId).query(DriverRepository::map).optional();
    }

    /** 조직의 첫 가상(VIRTUAL) 드라이버 */
    public Optional<DriverRow> findFirstByType(long organizationId, String type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.drivers d WHERE d.organization_id = :org AND d.type = :type"
                        + " ORDER BY d.id LIMIT 1")
                .param("org", organizationId).param("type", type).query(DriverRepository::map).optional();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.drivers WHERE organization_id = :org AND name = :name"
                        + " AND (CAST(:except AS bigint) IS NULL OR id <> :except))")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insert(long organizationId, String name, String type, String config, byte[] secretEnc, int pollingSec,
                       int ackTimeoutSec, int applyTimeoutSec, String retry, String circuit, List<String> capabilities, long userId,
                       Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.drivers (organization_id, name, type, config, secret_enc, polling_sec, ack_timeout_sec,
                               apply_timeout_sec, retry, circuit, capabilities, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :type, CAST(:config AS jsonb), :secret, :polling, :ack, :apply, CAST(:retry AS jsonb),
                                CAST(:circuit AS jsonb), CAST(:caps AS text[]), :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("type", type).param("config", config).param("secret", secretEnc)
                .param("polling", pollingSec).param("ack", ackTimeoutSec).param("apply", applyTimeoutSec).param("retry", retry)
                .param("circuit", circuit).param("caps", Pg.textArray(capabilities)).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public int update(long organizationId, long driverId, int baseVersion, String name, String config, int pollingSec,
                      int ackTimeoutSec, int applyTimeoutSec, String retry, String circuit, List<String> capabilities, long userId,
                      Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.drivers SET name = :name, config = CAST(:config AS jsonb), polling_sec = :polling,
                               ack_timeout_sec = :ack, apply_timeout_sec = :apply, retry = CAST(:retry AS jsonb),
                               circuit = CAST(:circuit AS jsonb), capabilities = CAST(:caps AS text[]), status = 'UNTESTED',
                               version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("config", config).param("polling", pollingSec).param("ack", ackTimeoutSec)
                .param("apply", applyTimeoutSec).param("retry", retry).param("circuit", circuit).param("caps", Pg.textArray(capabilities))
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", driverId)
                .param("base", baseVersion).update();
    }

    public void updateSecret(long organizationId, long driverId, byte[] secretEnc) {
        jdbc.sql("UPDATE data2flow_core.drivers SET secret_enc = :secret WHERE organization_id = :org AND id = :id")
                .param("secret", secretEnc).param("org", organizationId).param("id", driverId).update();
    }

    public void updateStatus(long organizationId, long driverId, String status, Instant now) {
        jdbc.sql("UPDATE data2flow_core.drivers SET status = :status, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("status", status).param("now", Pg.ts(now)).param("org", organizationId).param("id", driverId).update();
    }

    public int delete(long organizationId, long driverId) {
        return jdbc.sql("DELETE FROM data2flow_core.drivers WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", driverId).update();
    }

    public long countBindings(long organizationId, long driverId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.driver_bindings WHERE organization_id = :org AND driver_id = :id")
                .param("org", organizationId).param("id", driverId).query(Long.class).single();
    }

    public Optional<BindingRow> findBinding(long organizationId, long modelId) {
        return jdbc.sql("SELECT model_id, driver_id, encoder_script_id FROM data2flow_core.driver_bindings"
                        + " WHERE organization_id = :org AND model_id = :model")
                .param("org", organizationId).param("model", modelId)
                .query((rs, n) -> new BindingRow(rs.getLong("model_id"), rs.getLong("driver_id"), Pg.longOrNull(rs, "encoder_script_id")))
                .optional();
    }

    /** 모델의 드라이버 연결을 바꾼다(모델당 하나). driverId가 null이면 끊는다 */
    public void replaceBinding(long organizationId, long modelId, Long driverId, Long encoderScriptId, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.driver_bindings WHERE organization_id = :org AND model_id = :model")
                .param("org", organizationId).param("model", modelId).update();
        if (driverId != null) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.driver_bindings (model_id, driver_id, organization_id, encoder_script_id, created_at)
                            VALUES (:model, :driver, :org, :script, :now)""")
                    .param("model", modelId).param("driver", driverId).param("org", organizationId).param("script", encoderScriptId)
                    .param("now", Pg.ts(now)).update();
        }
    }

    /** 이 드라이버에 연결된 모델들의 기능 jsonb */
    public List<String> listBoundModelCapabilities(long organizationId, long driverId) {
        return jdbc.sql("""
                        SELECT m.capabilities::text FROM data2flow_core.driver_bindings b
                          JOIN data2flow_core.device_models m ON m.id = b.model_id AND m.organization_id = b.organization_id
                         WHERE b.organization_id = :org AND b.driver_id = :id""")
                .param("org", organizationId).param("id", driverId).query(String.class).list();
    }

    public Optional<byte[]> findSecret(long organizationId, long driverId) {
        return jdbc.sql("SELECT secret_enc FROM data2flow_core.drivers WHERE organization_id = :org AND id = :id AND secret_enc IS NOT NULL")
                .param("org", organizationId).param("id", driverId).query((rs, n) -> rs.getBytes(1)).optional();
    }

    public List<Long> listBoundModelIds(long organizationId, long driverId) {
        return jdbc.sql("SELECT model_id FROM data2flow_core.driver_bindings WHERE organization_id = :org AND driver_id = :id")
                .param("org", organizationId).param("id", driverId).query(Long.class).list();
    }

    /** 모델의 기능 jsonb(없는 모델이면 빈 값) */
    public Optional<String> findModelCapabilities(long organizationId, long modelId) {
        return jdbc.sql("SELECT capabilities::text FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).query(String.class).optional();
    }

    public boolean existsScript(long organizationId, long scriptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.scripts WHERE organization_id = :org AND id = :id)")
                .param("org", organizationId).param("id", scriptId).query(Boolean.class).single();
    }

    private static DriverRow map(ResultSet rs, int row) throws SQLException {
        return new DriverRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("type"),
                rs.getString("config"), rs.getBoolean("has_secret"), rs.getInt("polling_sec"), rs.getInt("ack_timeout_sec"),
                rs.getInt("apply_timeout_sec"), rs.getString("retry"), rs.getString("circuit"), rs.getString("status"),
                Pg.stringList(rs, "capabilities"), rs.getInt("version"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                rs.getLong("device_count"));
    }
}
