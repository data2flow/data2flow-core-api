package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 평면도({@code floorplans}, {@code floorplan_markers}, 원본 {@code floorplan_images}) — DEV-01.03.
 * 오브젝트 저장소가 생기기 전까지 원본은 DB에 두고 {@code object_key}는 {@code db:floorplan_images/<id>}로 적는다.
 */
@Repository
public class FloorplanRepository {

    public static final String DB_KEY_PREFIX = "db:floorplan_images/";

    private final JdbcClient jdbc;

    public FloorplanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Floorplan> findBySpace(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT f.id, f.space_id, f.width_px, f.height_px, f.scale_m_per_px, f.version, i.content_type, f.updated_at
                          FROM data2flow_core.floorplans f
                          LEFT JOIN data2flow_core.floorplan_images i ON i.floorplan_id = f.id
                         WHERE f.organization_id = :org AND f.space_id = :space""")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> new Floorplan(rs.getLong("id"), rs.getLong("space_id"), rs.getInt("width_px"), rs.getInt("height_px"),
                        rs.getBigDecimal("scale_m_per_px"), rs.getInt("version"), rs.getString("content_type"), Pg.instant(rs, "updated_at")))
                .optional();
    }

    /** 평면도를 만들거나 바꾼다(마커는 비율 좌표라 그대로 둔다). 평면도 ID */
    public long upsert(long organizationId, long spaceId, int width, int height, BigDecimal scale, Long userId, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.floorplans (organization_id, space_id, object_key, width_px, height_px, scale_m_per_px,
                                                               updated_by, created_at, updated_at)
                        VALUES (:org, :space, 'pending', :w, :h, :scale, :user, :now, :now)
                        ON CONFLICT (space_id) DO UPDATE
                           SET width_px = EXCLUDED.width_px, height_px = EXCLUDED.height_px, scale_m_per_px = EXCLUDED.scale_m_per_px,
                               version = data2flow_core.floorplans.version + 1, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                        RETURNING id""")
                .param("org", organizationId).param("space", spaceId).param("w", width).param("h", height).param("scale", scale)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
        jdbc.sql("UPDATE data2flow_core.floorplans SET object_key = :key WHERE id = :id AND organization_id = :org")
                .param("key", DB_KEY_PREFIX + id).param("id", id).param("org", organizationId).update();
        return id;
    }

    /** 원본 이미지 저장(같은 평면도면 덮어쓴다) */
    public void storeImage(long organizationId, long floorplanId, String contentType, byte[] data, String sha256, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.floorplan_images (floorplan_id, organization_id, content_type, size_bytes, sha256, data, created_at)
                        VALUES (:id, :org, :type, :size, :sha, :data, :now)
                        ON CONFLICT (floorplan_id) DO UPDATE
                           SET content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256,
                               data = EXCLUDED.data, created_at = EXCLUDED.created_at""")
                .param("id", floorplanId).param("org", organizationId).param("type", contentType).param("size", data.length)
                .param("sha", sha256).param("data", data).param("now", Pg.ts(now)).update();
    }

    /** 원본 이미지 */
    public Optional<Image> findImage(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT i.content_type, i.sha256, i.data FROM data2flow_core.floorplan_images i
                          JOIN data2flow_core.floorplans f ON f.id = i.floorplan_id
                         WHERE f.organization_id = :org AND f.space_id = :space""")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> new Image(rs.getString("content_type"), rs.getString("sha256"), rs.getBytes("data")))
                .optional();
    }

    /** 평면도 삭제(마커·원본은 FK로 함께 지워진다) */
    public int deleteBySpace(long organizationId, long spaceId) {
        return jdbc.sql("DELETE FROM data2flow_core.floorplans WHERE organization_id = :org AND space_id = :space")
                .param("org", organizationId).param("space", spaceId).update();
    }

    public List<Marker> findMarkers(long organizationId, long floorplanId) {
        return jdbc.sql("""
                        SELECT m.device_id, d.name, m.x, m.y, m.rotation FROM data2flow_core.floorplan_markers m
                          JOIN data2flow_core.devices d ON d.id = m.device_id
                         WHERE m.organization_id = :org AND m.floorplan_id = :fp ORDER BY d.name, m.device_id""")
                .param("org", organizationId).param("fp", floorplanId)
                .query((rs, n) -> new Marker(rs.getLong("device_id"), rs.getString("name"), rs.getBigDecimal("x"), rs.getBigDecimal("y"),
                        rs.getInt("rotation")))
                .list();
    }

    /** 마커 전체 교체 */
    public void replaceMarkers(long organizationId, long floorplanId, List<Marker> markers, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.floorplan_markers WHERE organization_id = :org AND floorplan_id = :fp")
                .param("org", organizationId).param("fp", floorplanId).update();
        for (Marker m : markers) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.floorplan_markers (organization_id, floorplan_id, device_id, x, y, rotation, updated_at)
                            VALUES (:org, :fp, :device, :x, :y, :rotation, :now)""")
                    .param("org", organizationId).param("fp", floorplanId).param("device", m.deviceId()).param("x", m.x())
                    .param("y", m.y()).param("rotation", m.rotation()).param("now", Pg.ts(now)).update();
        }
    }

    /** 평면도 */
    public record Floorplan(long id, long spaceId, int widthPx, int heightPx, BigDecimal scaleMPerPx, int version,
                            String contentType, Instant updatedAt) {
    }

    /** 원본 */
    public record Image(String contentType, String sha256, byte[] data) {
    }

    /** 마커(비율 좌표 0~1) */
    public record Marker(long deviceId, String deviceName, BigDecimal x, BigDecimal y, int rotation) {
    }
}
