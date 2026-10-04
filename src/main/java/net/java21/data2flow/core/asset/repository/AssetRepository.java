package net.java21.data2flow.core.asset.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** 기기 자산 정보({@code asset_info}, 1:1, DEV-08.01) */
@Repository
public class AssetRepository {

    private static final String COLUMNS = """
            organization_id, device_id, serial_no, purchased_on, installed_on, warranty_until, supplier, installer, photo_keys, version, updated_at""";

    private final JdbcClient jdbc;

    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssetInfo> find(long organizationId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.asset_info WHERE organization_id = :org AND device_id = :d")
                .param("org", organizationId).param("d", deviceId).query(AssetRepository::map).optional();
    }

    /** 있으면 바꾸고 없으면 만든다(사진은 그대로). 보증 만료일이 바뀌면 알림 기록을 지운다 */
    public void upsert(long organizationId, long deviceId, Fields f, long updatedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.asset_info (organization_id, device_id, serial_no, purchased_on, installed_on, warranty_until,
                               supplier, installer, updated_by, created_at, updated_at)
                        VALUES (:org, :d, :serial, :purchased, :installed, :warranty, :supplier, :installer, :by, :now, :now)
                        ON CONFLICT (device_id) DO UPDATE
                           SET serial_no = EXCLUDED.serial_no, purchased_on = EXCLUDED.purchased_on, installed_on = EXCLUDED.installed_on,
                               warranty_until = EXCLUDED.warranty_until, supplier = EXCLUDED.supplier, installer = EXCLUDED.installer,
                               warranty_notified_for = CASE WHEN data2flow_core.asset_info.warranty_until IS DISTINCT FROM EXCLUDED.warranty_until
                                                            THEN NULL ELSE data2flow_core.asset_info.warranty_notified_for END,
                               version = data2flow_core.asset_info.version + 1, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.asset_info.organization_id = EXCLUDED.organization_id""")
                .param("org", organizationId).param("d", deviceId).param("serial", f.serialNo()).param("purchased", f.purchasedOn())
                .param("installed", f.installedOn()).param("warranty", f.warrantyUntil()).param("supplier", f.supplier())
                .param("installer", f.installer()).param("by", updatedBy).param("now", Pg.ts(now)).update();
    }

    /** 사진 키 추가(행이 없으면 만든다) */
    public void addPhoto(long organizationId, long deviceId, String key, long updatedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.asset_info (organization_id, device_id, photo_keys, updated_by, created_at, updated_at)
                        VALUES (:org, :d, ARRAY[CAST(:key AS text)], :by, :now, :now)
                        ON CONFLICT (device_id) DO UPDATE
                           SET photo_keys = array_append(data2flow_core.asset_info.photo_keys, CAST(:key AS text)),
                               version = data2flow_core.asset_info.version + 1, updated_by = :by, updated_at = :now
                         WHERE data2flow_core.asset_info.organization_id = :org""")
                .param("org", organizationId).param("d", deviceId).param("key", key).param("by", updatedBy).param("now", Pg.ts(now)).update();
    }

    public int removePhoto(long organizationId, long deviceId, String key, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.asset_info SET photo_keys = array_remove(photo_keys, CAST(:key AS text)),
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND device_id = :d AND CAST(:key AS text) = ANY(photo_keys)""")
                .param("org", organizationId).param("d", deviceId).param("key", key).param("by", updatedBy).param("now", Pg.ts(now)).update();
    }

    /** 보증 만료가 [from, to] 안이고 그 만료일로 아직 알리지 않은 자산(AT-DEV-16.5) */
    @OrganizationScopeExempt("매일 보증 만료 점검이 이 배포의 조직(제한이 있으면 그 조직)을 모두 본다")
    public List<Expiring> listExpiring(OptionalLong onlyOrganization, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT a.organization_id, a.device_id, d.name, a.warranty_until
                          FROM data2flow_core.asset_info a
                          JOIN data2flow_core.devices d ON d.id = a.device_id AND d.organization_id = a.organization_id
                         WHERE (CAST(:org AS bigint) IS NULL OR a.organization_id = :org) AND d.status <> 'DELETED'
                           AND a.warranty_until BETWEEN :from AND :to
                           AND a.warranty_notified_for IS DISTINCT FROM a.warranty_until
                         ORDER BY a.organization_id, a.device_id""")
                .param("org", onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null).param("from", from).param("to", to)
                .query((rs, n) -> new Expiring(rs.getLong("organization_id"), rs.getLong("device_id"), rs.getString("name"),
                        rs.getObject("warranty_until", LocalDate.class)))
                .list();
    }

    public int markNotified(long organizationId, long deviceId, LocalDate warrantyUntil) {
        return jdbc.sql("UPDATE data2flow_core.asset_info SET warranty_notified_for = :w WHERE organization_id = :org AND device_id = :d")
                .param("w", warrantyUntil).param("org", organizationId).param("d", deviceId).update();
    }

    static AssetInfo map(ResultSet rs, int n) throws SQLException {
        return new AssetInfo(rs.getLong("organization_id"), rs.getLong("device_id"), rs.getString("serial_no"),
                rs.getObject("purchased_on", LocalDate.class), rs.getObject("installed_on", LocalDate.class),
                rs.getObject("warranty_until", LocalDate.class), rs.getString("supplier"), rs.getString("installer"),
                Pg.stringList(rs, "photo_keys"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    public record AssetInfo(long organizationId, long deviceId, String serialNo, LocalDate purchasedOn, LocalDate installedOn,
                            LocalDate warrantyUntil, String supplier, String installer, List<String> photoKeys, int version, Instant updatedAt) {
    }

    public record Fields(String serialNo, LocalDate purchasedOn, LocalDate installedOn, LocalDate warrantyUntil, String supplier,
                         String installer) {
    }

    public record Expiring(long organizationId, long deviceId, String deviceName, LocalDate warrantyUntil) {
    }
}
