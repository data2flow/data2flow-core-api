package net.java21.data2flow.core.catalog.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.catalog.domain.CatalogModels.DeviceModel;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelFields;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelMetric;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelPackage;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** 기기 모델 저장소({@code device_models}, {@code model_metrics}). 기기 수는 {@code devices}를 읽기만 한다 */
@Repository
public class DeviceModelRepository {

    private static final String COLUMNS = """
            id, organization_id, code, vendor, name, protocol, kind, default_interval_sec, default_offline_multiplier, description,
            image_object_key, builtin, transform_script_id, decode_script_id, driver_key, default_dashboard_id,
            default_rule_template_ids, attribute_schema::text AS attribute_schema, capabilities::text AS capabilities, status,
            version, updated_at""";

    private final JdbcClient jdbc;

    public DeviceModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건(API-DEV-46). code가 있으면 DEPRECATED도 찾는다 */
    public record ModelFilter(long organizationId, String q, String code, String protocol, String kind,
                              boolean includeDeprecated, Boolean builtin) {
    }

    public List<DeviceModel> list(ModelFilter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_models WHERE " + where()
                        + " ORDER BY builtin DESC, code LIMIT :limit OFFSET :offset")
                .params(filterParams(f)).param("limit", limit).param("offset", offset)
                .query(DeviceModelRepository::map).list();
    }

    public long count(ModelFilter f) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE " + where())
                .params(filterParams(f)).query(Long.class).single();
    }

    private static String where() {
        return """
                organization_id = :org
                AND (CAST(:code AS text) IS NULL OR code = :code)
                AND (CAST(:protocol AS text) IS NULL OR protocol = :protocol)
                AND (CAST(:kind AS text) IS NULL OR kind = :kind)
                AND (CAST(:builtin AS boolean) IS NULL OR builtin = :builtin)
                AND (:includeDeprecated OR CAST(:code AS text) IS NOT NULL OR status = 'ACTIVE')
                AND (CAST(:q AS text) IS NULL OR code ILIKE :like OR name ILIKE :like OR coalesce(vendor, '') ILIKE :like)""";
    }

    private static Map<String, Object> filterParams(ModelFilter f) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", f.organizationId());
        p.put("code", f.code());
        p.put("protocol", f.protocol());
        p.put("kind", f.kind());
        p.put("builtin", f.builtin());
        p.put("includeDeprecated", f.includeDeprecated());
        p.put("q", f.q());
        p.put("like", f.q() == null ? null : "%" + f.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return p;
    }

    public Optional<DeviceModel> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_models WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(DeviceModelRepository::map).optional();
    }

    public Optional<DeviceModel> findByCode(long organizationId, String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_models WHERE code = :code AND organization_id = :org")
                .param("code", code).param("org", organizationId).query(DeviceModelRepository::map).optional();
    }

    public boolean existsCode(long organizationId, String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code)")
                .param("org", organizationId).param("code", code).query(Boolean.class).single();
    }

    /** 새 모델(사용자 정의 또는 복제). 반환: ID */
    public long insert(long organizationId, String code, ModelFields f, ModelPackage pkg, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_models (organization_id, code, vendor, name, protocol, kind, default_interval_sec,
                            default_offline_multiplier, description, builtin, transform_script_id, decode_script_id, driver_key,
                            default_dashboard_id, default_rule_template_ids, attribute_schema, capabilities, created_by, updated_by,
                            created_at, updated_at)
                        VALUES (:org, :code, :vendor, :name, :protocol, :kind, :interval, :multiplier, :desc, false, :transform, :decode,
                            :driver, :dashboard, CAST(:rules AS bigint[]), CAST(:schema AS jsonb), CAST(:caps AS jsonb), :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("code", code).param("vendor", f.vendor()).param("name", f.name())
                .param("protocol", f.protocol()).param("kind", f.kind()).param("interval", f.defaultIntervalSec())
                .param("multiplier", BigDecimal.valueOf(f.defaultOfflineMultiplier())).param("desc", f.description())
                .param("transform", pkg.transformScriptId()).param("decode", pkg.decodeScriptId()).param("driver", pkg.driverKey())
                .param("dashboard", pkg.defaultDashboardId()).param("rules", Pg.bigintArray(pkg.defaultRuleTemplateIds()))
                .param("schema", pkg.attributeSchemaJson()).param("caps", f.capabilitiesJson()).param("by", createdBy)
                .param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 기본 제공 모델(시드). 이미 있으면(같은 코드) 아무것도 하지 않고 빈 값 */
    public Optional<Long> insertBuiltin(long organizationId, String code, String vendor, String name, String protocol, String kind,
                                        int defaultIntervalSec, String description, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_models (organization_id, code, vendor, name, protocol, kind, default_interval_sec,
                            description, builtin, created_at, updated_at)
                        VALUES (:org, :code, :vendor, :name, :protocol, :kind, :interval, :desc, true, :now, :now)
                        ON CONFLICT (organization_id, code) DO NOTHING RETURNING id""")
                .param("org", organizationId).param("code", code).param("vendor", vendor).param("name", name)
                .param("protocol", protocol).param("kind", kind).param("interval", defaultIntervalSec).param("desc", description)
                .param("now", Pg.ts(now))
                .query(Long.class).optional();
    }

    public int update(long organizationId, long id, int baseVersion, ModelFields f, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_models
                           SET vendor = :vendor, name = :name, protocol = :protocol, kind = :kind, default_interval_sec = :interval,
                               default_offline_multiplier = :multiplier, description = :desc, capabilities = CAST(:caps AS jsonb),
                               updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org AND version = :base""")
                .param("vendor", f.vendor()).param("name", f.name()).param("protocol", f.protocol()).param("kind", f.kind())
                .param("interval", f.defaultIntervalSec()).param("multiplier", BigDecimal.valueOf(f.defaultOfflineMultiplier()))
                .param("desc", f.description()).param("caps", f.capabilitiesJson()).param("by", updatedBy).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).param("base", baseVersion)
                .update();
    }

    /** 패키지 저장. baseVersion이 null이면 버전 비교 없이 */
    public int updatePackage(long organizationId, long id, Integer baseVersion, ModelPackage pkg, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_models
                           SET transform_script_id = :transform, decode_script_id = :decode, driver_key = :driver,
                               default_dashboard_id = :dashboard, default_rule_template_ids = CAST(:rules AS bigint[]),
                               attribute_schema = CAST(:schema AS jsonb), updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org AND (CAST(:base AS integer) IS NULL OR version = :base)""")
                .param("transform", pkg.transformScriptId()).param("decode", pkg.decodeScriptId()).param("driver", pkg.driverKey())
                .param("dashboard", pkg.defaultDashboardId()).param("rules", Pg.bigintArray(pkg.defaultRuleTemplateIds()))
                .param("schema", pkg.attributeSchemaJson()).param("by", updatedBy).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).param("base", baseVersion)
                .update();
    }

    public int updateStatus(long organizationId, long id, String status, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_models SET status = :status, updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org""")
                .param("status", status).param("by", updatedBy).param("now", Pg.ts(now)).param("id", id).param("org", organizationId)
                .update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.device_models WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    // ---- 측정 항목 연결(model_metrics)

    public List<ModelMetric> listMetrics(long organizationId, long modelId) {
        return jdbc.sql("""
                        SELECT metric_key, required FROM data2flow_core.model_metrics
                         WHERE organization_id = :org AND model_id = :model ORDER BY created_at, metric_key""")
                .param("org", organizationId).param("model", modelId)
                .query((rs, n) -> new ModelMetric(rs.getString("metric_key"), rs.getBoolean("required"))).list();
    }

    /** 모델 ID → 측정 항목 수 */
    public Map<Long, Long> countMetricsByModel(long organizationId, List<Long> modelIds) {
        Map<Long, Long> result = new HashMap<>();
        if (modelIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT model_id, count(*) AS n FROM data2flow_core.model_metrics
                         WHERE organization_id = :org AND model_id = ANY(CAST(:ids AS bigint[])) GROUP BY model_id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(modelIds))
                .query((rs, n) -> result.put(rs.getLong("model_id"), rs.getLong("n"))).list();
        return result;
    }

    /** 모델의 측정 항목을 통째로 바꾼다(순서 유지를 위해 created_at을 조금씩 늘린다) */
    public void replaceMetrics(long organizationId, long modelId, List<ModelMetric> metrics, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.model_metrics WHERE organization_id = :org AND model_id = :model")
                .param("org", organizationId).param("model", modelId).update();
        for (int i = 0; i < metrics.size(); i++) {
            insertMetricIfAbsent(organizationId, modelId, metrics.get(i), now.plusMillis(i));
        }
    }

    public void insertMetricIfAbsent(long organizationId, long modelId, ModelMetric metric, Instant at) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.model_metrics (organization_id, model_id, metric_key, required, created_at)
                        VALUES (:org, :model, :key, :required, :at) ON CONFLICT (model_id, metric_key) DO NOTHING""")
                .param("org", organizationId).param("model", modelId).param("key", metric.key()).param("required", metric.required())
                .param("at", Pg.ts(at)).update();
    }

    // ---- 사용처(devices·data_sources는 다른 기능 소유, 읽기만)

    /** 이 모델을 쓰는 기기 수(삭제된 기기 제외, 공간 범위 적용 — BR-DEV-25) */
    public Map<Long, Long> countDevicesByModel(long organizationId, List<Long> modelIds, SpaceScope scope) {
        Map<Long, Long> result = new HashMap<>();
        if (modelIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT model_id, count(*) AS n FROM data2flow_core.devices
                         WHERE organization_id = :org AND model_id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'
                           AND (:all OR space_id = ANY(CAST(:allowed AS bigint[])))
                         GROUP BY model_id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(modelIds)).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> result.put(rs.getLong("model_id"), rs.getLong("n"))).list();
        return result;
    }

    /**
     * 삭제를 막는 참조 수(BR-DEV-15): 기기(소프트 삭제된 기기도 FK로 모델을 잡고 있다)와 소스의 기본 모델.
     */
    public long countReferences(long organizationId, long modelId) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org AND model_id = :model)
                             + (SELECT count(*) FROM data2flow_core.data_sources WHERE organization_id = :org AND default_model_id = :model)""")
                .param("org", organizationId).param("model", modelId).query(Long.class).single();
    }

    /** 스크립트 종류(DECODE·TRANSFORM). 없으면 빈 값 */
    public Optional<String> findScriptKind(long organizationId, long scriptId) {
        return jdbc.sql("SELECT kind FROM data2flow_core.scripts WHERE id = :id AND organization_id = :org")
                .param("id", scriptId).param("org", organizationId).query(String.class).optional();
    }

    /** ACTIVE 조직인가(내부 API의 조직 확인) */
    public boolean existsActiveOrganization(long organizationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.organizations WHERE id = :org AND status = 'ACTIVE')")
                .param("org", organizationId).query(Boolean.class).single();
    }

    /** 시드 대상 조직: ACTIVE 조직, 배포 조직이 정해져 있으면 그 조직만(ADR-030) */
    @OrganizationScopeExempt("기본 카탈로그 시드는 이 배포가 맡는 모든 ACTIVE 조직을 돈다(DeploymentOrganization.restriction()으로 좁힘)")
    public List<Long> listActiveOrganizationIds(OptionalLong restriction) {
        Long only = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.organizations
                         WHERE status = 'ACTIVE' AND (CAST(:only AS bigint) IS NULL OR id = :only) ORDER BY id""")
                .param("only", only).query(Long.class).list();
    }

    private static DeviceModel map(ResultSet rs, int n) throws SQLException {
        return new DeviceModel(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("code"), rs.getString("vendor"),
                rs.getString("name"), rs.getString("protocol"), rs.getString("kind"), rs.getInt("default_interval_sec"),
                rs.getBigDecimal("default_offline_multiplier").doubleValue(), rs.getString("description"),
                rs.getString("image_object_key"), rs.getBoolean("builtin"), Pg.longOrNull(rs, "transform_script_id"),
                Pg.longOrNull(rs, "decode_script_id"), rs.getString("driver_key"), Pg.longOrNull(rs, "default_dashboard_id"),
                Pg.longList(rs, "default_rule_template_ids"), rs.getString("attribute_schema"), rs.getString("capabilities"),
                rs.getString("status"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }
}
