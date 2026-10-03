package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.domain.References.SpaceRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 기기가 참조하는 다른 기능의 테이블을 읽는다(쓰기 없음): {@code device_models}(WP-B1), {@code spaces}(WP-A), {@code data_sources}(WP-C).
 * 같은 스키마의 표를 읽기만 하는 것은 허용된 결정이다(M2 브리프 §7).
 */
@Repository
public class DeviceReferenceRepository {

    private static final String MODEL_COLUMNS = """
            id, code, name, vendor, kind, status, default_interval_sec, default_offline_multiplier,
            attribute_schema::text AS attribute_schema""";
    private static final String SOURCE_COLUMNS = """
            id, organization_id, code, name, type, lifecycle, decoder_key, unknown_device_policy, default_model_id, default_space_id,
            autoreg_limit_per_hour""";

    private final JdbcClient jdbc;

    public DeviceReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ModelRef> findModel(long organizationId, long modelId) {
        return jdbc.sql("SELECT " + MODEL_COLUMNS + " FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId).query(DeviceReferenceRepository::mapModel).optional();
    }

    public Optional<ModelRef> findModelByCode(long organizationId, String code) {
        return jdbc.sql("SELECT " + MODEL_COLUMNS + " FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code).query(DeviceReferenceRepository::mapModel).optional();
    }

    public Map<Long, ModelRef> findModels(long organizationId, Collection<Long> ids) {
        Map<Long, ModelRef> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT " + MODEL_COLUMNS + " FROM data2flow_core.device_models WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(DeviceReferenceRepository::mapModel).list()
                .forEach(m -> result.put(m.id(), m));
        return result;
    }

    /** 모델별 측정 항목 키(모델 추천 API-DEV-29). 사용 중지(DEPRECATED) 모델은 빼고 */
    public Map<Long, List<String>> findActiveModelMetrics(long organizationId) {
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT mm.model_id, mm.metric_key FROM data2flow_core.model_metrics mm
                          JOIN data2flow_core.device_models m ON m.id = mm.model_id
                         WHERE mm.organization_id = :org AND m.organization_id = :org AND m.status = 'ACTIVE'
                         ORDER BY mm.model_id, mm.metric_key""")
                .param("org", organizationId)
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getLong("model_id"), k -> new ArrayList<>()).add(rs.getString("metric_key")));
        return result;
    }

    public Optional<SpaceRef> findSpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT id, parent_id, name, path, depth FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(DeviceReferenceRepository::mapSpace).optional();
    }

    /** 공간 이름(대소문자·앞뒤 공백 무시)이 같은 공간들(ING-03.03 추천) */
    public List<SpaceRef> findSpacesByName(long organizationId, String name) {
        return jdbc.sql("""
                        SELECT id, parent_id, name, path, depth FROM data2flow_core.spaces
                         WHERE organization_id = :org AND status = 'ACTIVE' AND lower(btrim(name)) = lower(btrim(:name))
                         ORDER BY depth DESC, id""")
                .param("org", organizationId).param("name", name).query(DeviceReferenceRepository::mapSpace).list();
    }

    /** 공간 → 루트부터의 이름 경로(API-DEV-11 {@code space.path[]}). 없는 공간은 결과에 없다 */
    public Map<Long, List<String>> findSpacePathNames(long organizationId, Collection<Long> spaceIds) {
        Map<Long, List<String>> result = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return result;
        }
        List<SpaceRef> spaces = jdbc.sql("""
                        SELECT id, parent_id, name, path, depth FROM data2flow_core.spaces
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds)).query(DeviceReferenceRepository::mapSpace).list();
        Set<Long> ancestors = new LinkedHashSet<>();
        for (SpaceRef s : spaces) {
            ancestors.addAll(pathIds(s.path()));
        }
        Map<Long, String> names = new HashMap<>();
        if (!ancestors.isEmpty()) {
            jdbc.sql("SELECT id, name FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                    .param("org", organizationId).param("ids", Pg.bigintArray(ancestors))
                    .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getLong("id"), rs.getString("name")));
        }
        for (SpaceRef s : spaces) {
            List<String> path = new ArrayList<>();
            for (Long id : pathIds(s.path())) {
                if (names.containsKey(id)) {
                    path.add(names.get(id));
                }
            }
            result.put(s.id(), path);
        }
        return result;
    }

    public Map<Long, String> findSpaceNames(long organizationId, Collection<Long> spaceIds) {
        Map<Long, String> names = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getLong("id"), rs.getString("name")));
        return names;
    }

    /** 바로 아래 하위 공간 중 이름이 같은 것(ING-03.03 point 태그) */
    public List<SpaceRef> findChildrenByName(long organizationId, long parentId, String name) {
        return jdbc.sql("""
                        SELECT id, parent_id, name, path, depth FROM data2flow_core.spaces
                         WHERE organization_id = :org AND parent_id = :parent AND status = 'ACTIVE' AND lower(btrim(name)) = lower(btrim(:name))
                         ORDER BY id""")
                .param("org", organizationId).param("parent", parentId).param("name", name).query(DeviceReferenceRepository::mapSpace).list();
    }

    public Optional<SourceRef> findSource(long organizationId, long sourceId) {
        return jdbc.sql("SELECT " + SOURCE_COLUMNS + " FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", sourceId).query(DeviceReferenceRepository::mapSource).optional();
    }

    /**
     * 내부 API용 소스 조회(조직을 모르는 pipeline 호출). 배포 조직으로 좁힌다(ADR-030).
     */
    @OrganizationScopeExempt("내부 API: 소스 ID로 조직을 찾는다. DeploymentOrganization.restriction()으로 배포 조직만")
    public Optional<SourceRef> findSourceAnyOrganization(long sourceId, OptionalLong onlyOrganization) {
        Long org = onlyOrganization.isPresent() ? onlyOrganization.getAsLong() : null;
        return jdbc.sql("SELECT " + SOURCE_COLUMNS + " FROM data2flow_core.data_sources WHERE id = :id"
                        + " AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)")
                .param("id", sourceId).param("org", org).query(DeviceReferenceRepository::mapSource).optional();
    }

    public Map<Long, SourceRef> findSources(long organizationId, Collection<Long> ids) {
        Map<Long, SourceRef> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT " + SOURCE_COLUMNS + " FROM data2flow_core.data_sources WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(DeviceReferenceRepository::mapSource).list()
                .forEach(s -> result.put(s.id(), s));
        return result;
    }

    /** {@code /1/4/9/} → [1, 4, 9] */
    public static List<Long> pathIds(String path) {
        if (path == null) {
            return List.of();
        }
        return Arrays.stream(path.split("/")).filter(p -> !p.isBlank()).map(Long::valueOf).toList();
    }

    static ModelRef mapModel(ResultSet rs, int n) throws SQLException {
        int interval = rs.getInt("default_interval_sec");
        Integer defaultInterval = rs.wasNull() ? null : interval;
        return new ModelRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("vendor"), rs.getString("kind"),
                rs.getString("status"), defaultInterval, rs.getBigDecimal("default_offline_multiplier"), rs.getString("attribute_schema"));
    }

    static SpaceRef mapSpace(ResultSet rs, int n) throws SQLException {
        return new SpaceRef(rs.getLong("id"), Pg.longOrNull(rs, "parent_id"), rs.getString("name"), rs.getString("path"),
                rs.getInt("depth"));
    }

    static SourceRef mapSource(ResultSet rs, int n) throws SQLException {
        return new SourceRef(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("type"), rs.getString("lifecycle"), rs.getString("decoder_key"), rs.getString("unknown_device_policy"),
                Pg.longOrNull(rs, "default_model_id"), Pg.longOrNull(rs, "default_space_id"), rs.getInt("autoreg_limit_per_hour"));
    }
}
