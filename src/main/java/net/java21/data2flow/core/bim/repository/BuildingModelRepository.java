package net.java21.data2flow.core.bim.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** IFC 건물 모델({@code building_models}·{@code building_model_files}·{@code building_model_space_maps}, DSH-12.04)과 층 목록 */
@Repository
public class BuildingModelRepository {

    private static final String SELECT = """
            SELECT id, organization_id, space_id, name, size_bytes, ifc_schema, status, error, element_count, version, created_at, updated_at
              FROM data2flow_core.building_models
            """;

    private final JdbcClient jdbc;

    public BuildingModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 공간(ACTIVE) 종류·경로 */
    public Optional<SpaceRow> findSpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT id, type, name, path FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'")
                .param("org", organizationId).param("id", spaceId)
                .query((rs, n) -> new SpaceRow(rs.getLong("id"), rs.getString("type"), rs.getString("name"), rs.getString("path"))).optional();
    }

    /** 건물의 층(직속 FLOOR 하위, 정렬 순서)과 평면도 */
    public List<FloorRow> findFloors(long organizationId, long buildingId) {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.code, s.sort_order, f.id AS floorplan_id, f.width_px, f.height_px, f.version AS floorplan_version
                          FROM data2flow_core.spaces s
                          LEFT JOIN data2flow_core.floorplans f ON f.space_id = s.id AND f.organization_id = s.organization_id
                         WHERE s.organization_id = :org AND s.parent_id = :building AND s.type = 'FLOOR' AND s.status = 'ACTIVE'
                         ORDER BY s.sort_order, s.name, s.id""")
                .param("org", organizationId).param("building", buildingId)
                .query((rs, n) -> new FloorRow(rs.getLong("id"), rs.getString("name"), rs.getString("code"), rs.getInt("sort_order"),
                        Pg.longOrNull(rs, "floorplan_id"), (Integer) rs.getObject("width_px"), (Integer) rs.getObject("height_px"),
                        (Integer) rs.getObject("floorplan_version"))).list();
    }

    public long insert(long organizationId, long spaceId, String name, long sizeBytes, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.building_models (organization_id, space_id, name, object_key, size_bytes, status, version,
                               created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :space, :name, 'db:building_model_files/pending', :size, 'PROCESSING', 1, :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("space", spaceId).param("name", name).param("size", sizeBytes).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public void insertFile(long organizationId, long modelId, String sha256, byte[] data) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.building_model_files (building_model_id, organization_id, sha256, data)
                        VALUES (:id, :org, :sha, :data)""")
                .param("id", modelId).param("org", organizationId).param("sha", sha256).param("data", data).update();
        jdbc.sql("UPDATE data2flow_core.building_models SET object_key = 'db:building_model_files/' || id WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).update();
    }

    /** 변환 결과(READY·FAILED) */
    public void finish(long organizationId, long modelId, String status, String schema, Integer elementCount, String error, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.building_models SET status = :status, ifc_schema = :schema, element_count = :count, error = :error,
                               version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("schema", schema).param("count", elementCount).param("error", error).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", modelId).update();
    }

    public Optional<ModelRow> find(long organizationId, long buildingId, long modelId) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND space_id = :space AND id = :id")
                .param("org", organizationId).param("space", buildingId).param("id", modelId).query(BuildingModelRepository::map).optional();
    }

    public List<ModelRow> list(long organizationId, long buildingId) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND space_id = :space ORDER BY created_at DESC, id DESC")
                .param("org", organizationId).param("space", buildingId).query(BuildingModelRepository::map).list();
    }

    public Optional<byte[]> findFile(long organizationId, long modelId) {
        return jdbc.sql("SELECT data FROM data2flow_core.building_model_files WHERE organization_id = :org AND building_model_id = :id")
                .param("org", organizationId).param("id", modelId).query((rs, n) -> rs.getBytes("data")).optional();
    }

    public List<MapRow> findMappings(long organizationId, long modelId) {
        return jdbc.sql("""
                        SELECT ifc_global_id, space_id FROM data2flow_core.building_model_space_maps
                         WHERE organization_id = :org AND building_model_id = :id ORDER BY ifc_global_id""")
                .param("org", organizationId).param("id", modelId)
                .query((rs, n) -> new MapRow(rs.getString("ifc_global_id"), rs.getLong("space_id"))).list();
    }

    /** 연결을 통째로 바꾼다 */
    public void replaceMappings(long organizationId, long modelId, List<MapRow> mappings, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.building_model_space_maps WHERE organization_id = :org AND building_model_id = :id")
                .param("org", organizationId).param("id", modelId).update();
        for (MapRow m : mappings) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.building_model_space_maps (organization_id, building_model_id, ifc_global_id, space_id, created_at)
                            VALUES (:org, :id, :gid, :space, :now)""")
                    .param("org", organizationId).param("id", modelId).param("gid", m.ifcGlobalId()).param("space", m.spaceId())
                    .param("now", Pg.ts(now)).update();
        }
        jdbc.sql("UPDATE data2flow_core.building_models SET version = version + 1, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", modelId).update();
    }

    public int delete(long organizationId, long modelId) {
        return jdbc.sql("DELETE FROM data2flow_core.building_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).update();
    }

    static ModelRow map(ResultSet rs, int n) throws SQLException {
        return new ModelRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("space_id"), rs.getString("name"),
                rs.getLong("size_bytes"), rs.getString("ifc_schema"), rs.getString("status"), rs.getString("error"),
                (Integer) rs.getObject("element_count"), rs.getInt("version"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    public record SpaceRow(long id, String type, String name, String path) {
    }

    public record FloorRow(long spaceId, String name, String code, int sortOrder, Long floorplanId, Integer width, Integer height,
                           Integer floorplanVersion) {
    }

    public record ModelRow(long id, long organizationId, long spaceId, String name, long sizeBytes, String ifcSchema, String status, String error,
                           Integer elementCount, int version, Instant createdAt, Instant updatedAt) {
    }

    public record MapRow(String ifcGlobalId, long spaceId) {
    }
}
