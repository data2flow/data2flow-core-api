package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시맨틱 계층 Location → Equipment → Point(DEV-13.01, {@code equipment}·{@code points}). 기기 하나의 장비·점을 통째로 바꾼다.
 */
@Repository
public class SemanticRepository {

    private final JdbcClient jdbc;

    public SemanticRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기기의 장비와 점(장비 ID 순) */
    public List<EquipmentRow> findByDevice(long organizationId, long deviceId) {
        Map<Long, EquipmentRow> equipment = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.id, e.equip_class, e.name, e.space_id, p.id AS point_id, p.metric_key, p.point_type, p.quantity, p.tags, p.source
                          FROM data2flow_core.equipment e
                          LEFT JOIN data2flow_core.points p ON p.equipment_id = e.id
                         WHERE e.organization_id = :org AND e.device_id = :device
                         ORDER BY e.id, p.id""")
                .param("org", organizationId).param("device", deviceId)
                .query((rs, n) -> {
                    long id = rs.getLong("id");
                    EquipmentRow row = equipment.computeIfAbsent(id, k -> {
                        try {
                            return new EquipmentRow(id, rs.getString("equip_class"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                                    new ArrayList<>());
                        } catch (java.sql.SQLException ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
                    Long pointId = Pg.longOrNull(rs, "point_id");
                    if (pointId != null) {
                        row.points().add(new PointRow(pointId, rs.getString("metric_key"), rs.getString("point_type"),
                                rs.getString("quantity"), Pg.stringList(rs, "tags"), rs.getString("source")));
                    }
                    return id;
                })
                .list();
        return new ArrayList<>(equipment.values());
    }

    /** 기기의 장비·점을 모두 지운다 */
    public void deleteByDevice(long organizationId, long deviceId) {
        jdbc.sql("DELETE FROM data2flow_core.equipment WHERE organization_id = :org AND device_id = :device")
                .param("org", organizationId).param("device", deviceId).update();
    }

    /** 장비 하나와 점들을 넣는다. 장비 ID */
    public long insertEquipment(long organizationId, long deviceId, EquipmentRow e, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.equipment (organization_id, device_id, space_id, equip_class, name, created_at, updated_at)
                        VALUES (:org, :device, :space, :cls, :name, :now, :now) RETURNING id""")
                .param("org", organizationId).param("device", deviceId).param("space", e.spaceId()).param("cls", e.equipClass())
                .param("name", e.name()).param("now", Pg.ts(now)).query(Long.class).single();
        for (PointRow p : e.points()) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.points (organization_id, equipment_id, metric_key, point_type, quantity, tags, source,
                                                               created_at, updated_at)
                            VALUES (:org, :eq, :key, :type, :quantity, CAST(:tags AS text[]), :source, :now, :now)""")
                    .param("org", organizationId).param("eq", id).param("key", p.metricKey()).param("type", p.pointType())
                    .param("quantity", p.quantity()).param("tags", Pg.textArray(p.tags())).param("source", p.source())
                    .param("now", Pg.ts(now)).update();
        }
        return id;
    }

    /** 기기 모델의 시맨틱 템플릿(JSON 문자열, 없으면 빈 값) */
    public Optional<String> findModelTemplate(long organizationId, long modelId) {
        return jdbc.sql("SELECT CAST(semantic_template AS text) FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).query(String.class).list().stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** 장비 */
    public record EquipmentRow(Long id, String equipClass, String name, Long spaceId, List<PointRow> points) {
    }

    /** 점 */
    public record PointRow(Long id, String metricKey, String pointType, String quantity, List<String> tags, String source) {
    }
}
