package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 공간 트리({@code data2flow_core.spaces}, DEV-01.01·01.02·10.01). 삭제는 ARCHIVED로 바꾼다. 하위 공간은 {@code path} 접두어로 찾는다.
 */
@Repository
public class SpaceRepository {

    static final String COLUMNS = """
            s.id, s.organization_id, s.parent_id, s.type, s.name, s.code, s.path, s.depth, s.sort_order, s.usage, s.area_m2,
            s.capacity, s.timezone, s.address, s.latitude, s.longitude, s.kma_nx, s.kma_ny, s.mode_override, s.mode_override_until,
            s.schedule_inherit, s.status, s.version, s.created_at, s.updated_at""";

    static final RowMapper<Space> MAPPER = (rs, n) -> new Space(rs.getLong("id"), rs.getLong("organization_id"),
            Pg.longOrNull(rs, "parent_id"), SpaceType.valueOf(rs.getString("type")), rs.getString("name"), rs.getString("code"),
            rs.getString("path"), rs.getInt("depth"), rs.getInt("sort_order"), rs.getString("usage"), rs.getBigDecimal("area_m2"),
            intOrNull(rs.getObject("capacity")), rs.getString("timezone"), rs.getString("address"), rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"), intOrNull(rs.getObject("kma_nx")), intOrNull(rs.getObject("kma_ny")),
            rs.getString("mode_override"), Pg.instant(rs, "mode_override_until"), rs.getBoolean("schedule_inherit"),
            rs.getString("status"), rs.getInt("version"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public SpaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /** ACTIVE 공간 하나 */
    public Optional<Space> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.id = :id AND s.status = 'ACTIVE'")
                .param("org", organizationId).param("id", id).query(MAPPER).optional();
    }

    /** ACTIVE 공간 하나를 잠근다(이동·삭제·수정) */
    public Optional<Space> lockById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.id = :id AND s.status = 'ACTIVE' FOR UPDATE")
                .param("org", organizationId).param("id", id).query(MAPPER).optional();
    }

    /** 조직의 ACTIVE 공간 전체(트리 그리기·상속 계산). 공간은 조직당 수천 개 이하라 한 번에 읽는다 */
    public List<Space> listActive(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.status = 'ACTIVE' ORDER BY s.depth, s.sort_order, s.name, s.id")
                .param("org", organizationId).query(MAPPER).list();
    }

    /**
     * 내부 API(API-DEV-126·128)용: 조직을 모르는 호출자가 공간 ID만 준다. 배포 조직(ADR-030)으로 좁힌다.
     *
     * @param onlyOrganization {@code DeploymentOrganization#restriction()}
     */
    @OrganizationScopeExempt("내부 API는 공간 ID로 조직을 정한다. 배포 조직 조건으로 좁힌다(ADR-030)")
    public Optional<Space> findForInternal(long id, OptionalLong onlyOrganization) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.spaces s
                        WHERE s.id = :id AND s.status = 'ACTIVE' AND (CAST(:org AS bigint) IS NULL OR s.organization_id = :org)""")
                .param("id", id).param("org", org).query(MAPPER).optional();
    }

    /** 주어진 ID의 ACTIVE 공간(조상 사슬 읽기) */
    public List<Space> findByIds(long organizationId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.status = 'ACTIVE' AND s.id = ANY(CAST(:ids AS bigint[])) ORDER BY s.depth")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(MAPPER).list();
    }

    /** 같은 부모 아래 같은 이름(앞뒤 공백·대소문자 무시)이 있는가 */
    public boolean existsSiblingName(long organizationId, Long parentId, String name, Long excludeId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.spaces
                                        WHERE organization_id = :org AND status = 'ACTIVE' AND coalesce(parent_id, 0) = :parent
                                          AND lower(name) = lower(:name) AND id <> :exclude)""")
                .param("org", organizationId).param("parent", parentId == null ? 0L : parentId).param("name", name)
                .param("exclude", excludeId == null ? -1L : excludeId).query(Boolean.class).single();
    }

    /** 조직 안에 같은 코드가 있는가 */
    public boolean existsCode(long organizationId, String code, Long excludeId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.spaces WHERE organization_id = :org AND code = :code AND id <> :exclude)")
                .param("org", organizationId).param("code", code).param("exclude", excludeId == null ? -1L : excludeId)
                .query(Boolean.class).single();
    }

    /** 새 공간. path는 부모 path + id + "/" */
    public long insert(NewSpace s) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.spaces (organization_id, parent_id, type, name, code, path, depth, sort_order, usage,
                                                           area_m2, capacity, timezone, address, latitude, longitude, kma_nx, kma_ny,
                                                           created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :parent, :type, :name, :code, '/', :depth, :sortOrder, :usage, :area, :capacity, :tz, :address,
                                :lat, :lon, :nx, :ny, :user, :user, :now, :now)
                        RETURNING id""")
                .params(attributeParams(s.attributes()))
                .param("org", s.organizationId()).param("parent", s.parentId()).param("type", s.type().name())
                .param("depth", s.depth()).param("user", s.userId()).param("now", Pg.ts(s.now()))
                .query(Long.class).single();
        jdbc.sql("UPDATE data2flow_core.spaces SET path = :path WHERE id = :id AND organization_id = :org")
                .param("path", s.parentPath() + id + "/").param("id", id).param("org", s.organizationId()).update();
        return id;
    }

    /** 속성 수정(API-DEV-03). baseVersion이 다르면 0 */
    public int updateAttributes(long organizationId, long id, int baseVersion, SpaceAttributes a, Long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.spaces
                           SET name = :name, code = :code, sort_order = :sortOrder, usage = :usage, area_m2 = :area, capacity = :capacity,
                               timezone = :tz, address = :address, latitude = :lat, longitude = :lon, kma_nx = :nx, kma_ny = :ny,
                               version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status = 'ACTIVE'""")
                .params(attributeParams(a))
                .param("org", organizationId).param("id", id).param("base", baseVersion).param("user", userId)
                .param("now", Pg.ts(now)).update();
    }

    private static Map<String, Object> attributeParams(SpaceAttributes a) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", a.name());
        p.put("code", a.code());
        p.put("sortOrder", a.sortOrder());
        p.put("usage", a.usage());
        p.put("area", a.areaM2());
        p.put("capacity", a.capacity());
        p.put("tz", a.timezone());
        p.put("address", a.address());
        p.put("lat", a.latitude());
        p.put("lon", a.longitude());
        p.put("nx", a.kmaNx());
        p.put("ny", a.kmaNy());
        return p;
    }

    /** 부모·정렬 순서 바꾸기(이동) */
    public void updateParent(long organizationId, long id, long parentId, int sortOrder, Long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.spaces SET parent_id = :parent, sort_order = :sortOrder, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("parent", parentId).param("sortOrder", sortOrder).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    /** 하위 전체(자기 포함)의 path 접두어와 깊이를 바꾼다. 보관된 행도 같이 옮겨 path를 일관되게 둔다 */
    public int updateSubtreePath(long organizationId, String oldPath, String newPath, int depthDelta) {
        return jdbc.sql("""
                        UPDATE data2flow_core.spaces
                           SET path = :newPath || substr(path, length(:oldPath) + 1), depth = depth + :delta
                         WHERE organization_id = :org AND path LIKE :pattern""")
                .param("newPath", newPath).param("oldPath", oldPath).param("delta", depthDelta)
                .param("org", organizationId).param("pattern", likePrefix(oldPath)).update();
    }

    /** 하위(자기 포함) ACTIVE 공간 중 가장 깊은 깊이 */
    public int maxDepthUnder(long organizationId, String path) {
        return jdbc.sql("SELECT coalesce(max(depth), 0) FROM data2flow_core.spaces WHERE organization_id = :org AND status = 'ACTIVE' AND path LIKE :pattern")
                .param("org", organizationId).param("pattern", likePrefix(path)).query(Integer.class).single();
    }

    /** 보관(삭제). 코드는 비워 다시 쓸 수 있게 한다 */
    public int archive(long organizationId, long id, Long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.spaces SET status = 'ARCHIVED', code = NULL, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 버전만 올린다(목표·시간표·평면도 변경을 설정 버전에 반영). 새 버전 */
    public int touch(long organizationId, long id, Long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.spaces SET version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id RETURNING version""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .query(Integer.class).single();
    }

    /** 시간표 상속 여부 */
    public void updateScheduleInherit(long organizationId, long id, boolean inherit) {
        jdbc.sql("UPDATE data2flow_core.spaces SET schedule_inherit = :inherit WHERE organization_id = :org AND id = :id")
                .param("inherit", inherit).param("org", organizationId).param("id", id).update();
    }

    /** 삭제를 막는 것: ACTIVE 하위 공간 수 */
    public long countChildren(long organizationId, long id) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.spaces WHERE organization_id = :org AND parent_id = :id AND status = 'ACTIVE'")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    /** 삭제를 막는 것: 이 공간에 설치된(삭제되지 않은) 기기 수 */
    public long countDevices(long organizationId, long id) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org AND space_id = :id AND status <> 'DELETED'")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    /** 삭제를 막는 것: 이 공간 평면도의 기기 마커 수 */
    public long countMarkers(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.floorplan_markers m
                          JOIN data2flow_core.floorplans f ON f.id = m.floorplan_id
                         WHERE f.organization_id = :org AND f.space_id = :id""")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    /** 이 공간과 하위(ACTIVE) 공간 ID */
    public List<Long> findIdsUnder(long organizationId, String path) {
        return jdbc.sql("SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND status = 'ACTIVE' AND path LIKE :pattern")
                .param("org", organizationId).param("pattern", likePrefix(path)).query(Long.class).list();
    }

    /** 이 공간과 하위 공간에 설치된(삭제되지 않은) 기기 수 */
    public long countDevicesUnder(long organizationId, String path) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.devices d JOIN data2flow_core.spaces s ON s.id = d.space_id
                         WHERE d.organization_id = :org AND s.organization_id = :org AND d.status <> 'DELETED' AND s.path LIKE :pattern""")
                .param("org", organizationId).param("pattern", likePrefix(path)).query(Long.class).single();
    }

    /** 공간별 직접 설치 기기 수와 오프라인 수(트리 counts, 사이트 요약) */
    public Map<Long, DeviceCount> countDevicesBySpace(long organizationId) {
        Map<Long, DeviceCount> result = new HashMap<>();
        jdbc.sql("""
                        SELECT d.space_id, count(*) AS devices, count(*) FILTER (WHERE st.connectivity = 'OFFLINE') AS offline
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id
                         WHERE d.organization_id = :org AND d.status IN ('ACTIVE', 'INACTIVE') AND d.space_id IS NOT NULL
                         GROUP BY d.space_id""")
                .param("org", organizationId)
                .query((rs, n) -> result.put(rs.getLong("space_id"), new DeviceCount(rs.getLong("devices"), rs.getLong("offline"))))
                .list();
        return result;
    }

    static String likePrefix(String path) {
        return path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 공간 속성(만들기·수정 공통). 위·경도가 있으면 격자도 계산해 넣는다 */
    public record SpaceAttributes(String name, String code, int sortOrder, String usage, BigDecimal areaM2, Integer capacity,
                                  String timezone, String address, BigDecimal latitude, BigDecimal longitude, Integer kmaNx,
                                  Integer kmaNy) {
    }

    /** 새 공간 */
    public record NewSpace(long organizationId, Long parentId, String parentPath, SpaceType type, int depth,
                           SpaceAttributes attributes, Long userId, Instant now) {
    }

    /** 기기 수 */
    public record DeviceCount(long devices, long offline) {
    }
}
