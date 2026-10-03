package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 기기 속성({@code device_attributes})과 변경 이력({@code device_attribute_history}) — DEV-07.01·07.04 */
@Repository
public class DeviceAttributeRepository {

    private static final String COLUMNS = """
            scope, key, value::text AS value, desired_value::text AS desired_value, reported_value::text AS reported_value, updated_at""";

    private final JdbcClient jdbc;

    public DeviceAttributeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AttributeRow> findByDevice(long organizationId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_attributes WHERE organization_id = :org AND device_id = :device"
                        + " ORDER BY scope, key")
                .param("org", organizationId).param("device", deviceId).query(DeviceAttributeRepository::map).list();
    }

    public Optional<AttributeRow> find(long organizationId, long deviceId, String scope, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_attributes WHERE organization_id = :org AND device_id = :device"
                        + " AND scope = :scope AND key = :key")
                .param("org", organizationId).param("device", deviceId).param("scope", scope).param("key", key)
                .query(DeviceAttributeRepository::map).optional();
    }

    /** 같은 값인지 DB의 jsonb 비교로 본다(키 순서·공백 무관) */
    public boolean existsSameValue(long organizationId, long deviceId, String scope, String key, String valueJson) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.device_attributes
                                        WHERE organization_id = :org AND device_id = :device AND scope = :scope AND key = :key
                                          AND value = CAST(:value AS jsonb))""")
                .param("org", organizationId).param("device", deviceId).param("scope", scope).param("key", key).param("value", valueJson)
                .query(Boolean.class).single();
    }

    /** SERVER는 value, SHARED는 value와 desired_value(기기가 보고하면 reported_value, DEV-07.02 M3) */
    public void upsert(long organizationId, long deviceId, String scope, String key, String valueJson, long updatedBy, Instant now) {
        boolean shared = "SHARED".equals(scope);
        jdbc.sql("""
                        INSERT INTO data2flow_core.device_attributes (organization_id, device_id, scope, key, value, desired_value, updated_by,
                                                                      updated_at)
                        VALUES (:org, :device, :scope, :key, CAST(:value AS jsonb), CASE WHEN :shared THEN CAST(:value AS jsonb) END, :by, :now)
                        ON CONFLICT (device_id, scope, key) DO UPDATE
                           SET value = EXCLUDED.value, desired_value = EXCLUDED.desired_value, updated_by = EXCLUDED.updated_by,
                               updated_at = EXCLUDED.updated_at""")
                .param("org", organizationId).param("device", deviceId).param("scope", scope).param("key", key).param("value", valueJson)
                .param("shared", shared).param("by", updatedBy).param("now", Pg.ts(now)).update();
    }

    public int delete(long organizationId, long deviceId, String scope, String key) {
        return jdbc.sql("DELETE FROM data2flow_core.device_attributes WHERE organization_id = :org AND device_id = :device AND scope = :scope"
                        + " AND key = :key")
                .param("org", organizationId).param("device", deviceId).param("scope", scope).param("key", key).update();
    }

    public void insertHistory(long organizationId, long deviceId, String scope, String key, String oldJson, String newJson, Long changedBy,
                              Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.device_attribute_history (organization_id, device_id, scope, key, old_value, new_value,
                                                                             changed_by, changed_at)
                        VALUES (:org, :device, :scope, :key, CAST(:old AS jsonb), CAST(:new AS jsonb), :by, :now)""")
                .param("org", organizationId).param("device", deviceId).param("scope", scope).param("key", key).param("old", oldJson)
                .param("new", newJson).param("by", changedBy).param("now", Pg.ts(now)).update();
    }

    public List<HistoryRow> findHistory(long organizationId, long deviceId, String key, int limit, long offset) {
        return jdbc.sql("""
                        SELECT h.id, h.scope, h.key, h.old_value::text AS old_value, h.new_value::text AS new_value, h.changed_by,
                               u.name AS changed_by_name, h.changed_at
                          FROM data2flow_core.device_attribute_history h
                          LEFT JOIN data2flow_core.app_users u ON u.id = h.changed_by AND u.organization_id = h.organization_id
                         WHERE h.organization_id = :org AND h.device_id = :device AND (CAST(:key AS text) IS NULL OR h.key = :key)
                         ORDER BY h.changed_at DESC, h.id DESC LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("device", deviceId).param("key", key).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new HistoryRow(rs.getLong("id"), rs.getString("scope"), rs.getString("key"), rs.getString("old_value"),
                        rs.getString("new_value"), Pg.longOrNull(rs, "changed_by"), rs.getString("changed_by_name"),
                        Pg.instant(rs, "changed_at")))
                .list();
    }

    public long countHistory(long organizationId, long deviceId, String key) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.device_attribute_history
                         WHERE organization_id = :org AND device_id = :device AND (CAST(:key AS text) IS NULL OR key = :key)""")
                .param("org", organizationId).param("device", deviceId).param("key", key).query(Long.class).single();
    }

    static AttributeRow map(ResultSet rs, int n) throws SQLException {
        return new AttributeRow(rs.getString("scope"), rs.getString("key"), rs.getString("value"), rs.getString("desired_value"),
                rs.getString("reported_value"), Pg.instant(rs, "updated_at"));
    }

    /** 속성 한 행(값은 JSON 문자열) */
    public record AttributeRow(String scope, String key, String value, String desired, String reported, Instant updatedAt) {
    }

    public record HistoryRow(long id, String scope, String key, String oldValue, String newValue, Long changedBy, String changedByName,
                             Instant changedAt) {
    }
}
