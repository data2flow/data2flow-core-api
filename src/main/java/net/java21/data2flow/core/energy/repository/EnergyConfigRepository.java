package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.space.repository.SpaceScopeSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** 요금 단가·배출계수 저장소(ENE-01.02, {@code energy_tariffs}·{@code energy_emission_factors})와 에너지 설정이 쓰는 공간 조회 */
@Repository
public class EnergyConfigRepository {

    /** 시스템 기본 배출계수의 조직 ID */
    public static final long SYSTEM_ORG = 0L;

    private static final String TARIFF = """
            SELECT t.id, t.organization_id, t.scope_space_id, s.name AS scope_space_name, t.valid_from, t.mode, t.flat_rate_krw_per_kwh,
                   t.tou_schedule::text AS tou_schedule, t.base_charge_krw_per_month, t.version, t.updated_at
              FROM data2flow_core.energy_tariffs t
              LEFT JOIN data2flow_core.spaces s ON s.id = t.scope_space_id
            """;

    private static final String FACTOR = """
            SELECT id, organization_id, valid_from, factor_tco2_per_mwh, source_title, source_year FROM data2flow_core.energy_emission_factors
            """;

    private final JdbcClient jdbc;

    public EnergyConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SpaceRow(long id, String name, String path, String type) {
    }

    public record TariffRow(long id, long organizationId, Long scopeSpaceId, String scopeSpaceName, LocalDate validFrom, String mode,
                            BigDecimal flatRate, String touJson, BigDecimal baseCharge, int version, Instant updatedAt) {
    }

    public record TariffValues(Long scopeSpaceId, LocalDate validFrom, String mode, BigDecimal flatRate, String touJson, BigDecimal baseCharge) {
    }

    public record FactorRow(long id, long organizationId, LocalDate validFrom, BigDecimal factor, String sourceTitle, int sourceYear) {
    }

    public record FactorValues(LocalDate validFrom, BigDecimal factor, String sourceTitle, int sourceYear) {
    }

    /** 설정 묶음 버전(API-ENE-70 configVersion). 설정을 한 번도 바꾸지 않았으면 0 */
    public long configVersion(long orgId) {
        return jdbc.sql("SELECT config_version FROM data2flow_core.energy_config_state WHERE organization_id = :org").param("org", orgId)
                .query(Long.class).optional().orElse(0L);
    }

    /** 설정이 바뀔 때마다 버전을 하나 올린다(같은 트랜잭션) */
    public void bumpConfigVersion(long orgId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.energy_config_state (organization_id, config_version, updated_at) VALUES (:org, 1, :now)
                        ON CONFLICT (organization_id) DO UPDATE SET config_version = energy_config_state.config_version + 1, updated_at = :now""")
                .param("org", orgId).param("now", Pg.ts(now)).update();
    }

    /** 켜진 에너지 원천이 있는 조직(일 집계 대상). 배포 조직이 정해져 있으면 그 조직만(ADR-030) */
    @OrganizationScopeExempt("analytics 일 집계가 원천이 있는 모든 조직을 돈다(DeploymentOrganization.restriction()으로 좁힘)")
    public List<Long> organizationsWithSources(OptionalLong restriction) {
        Long only = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        SELECT DISTINCT e.organization_id FROM data2flow_core.energy_sources e
                          JOIN data2flow_core.organizations o ON o.id = e.organization_id AND o.status = 'ACTIVE'
                         WHERE e.enabled AND (CAST(:only AS bigint) IS NULL OR e.organization_id = :only) ORDER BY 1""")
                .param("only", only).query(Long.class).list();
    }

    /** 조직 시간대(없으면 UTC) */
    public String timezone(long orgId) {
        return jdbc.sql("SELECT timezone FROM data2flow_core.organizations WHERE id = :org").param("org", orgId).query(String.class)
                .optional().orElse("UTC");
    }

    /** 조직의 ACTIVE 공간 */
    public Optional<SpaceRow> space(long orgId, long id) {
        return jdbc.sql("SELECT id, name, path, type FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'")
                .param("org", orgId).param("id", id).query((rs, n) -> new SpaceRow(rs.getLong("id"), rs.getString("name"), rs.getString("path"),
                        rs.getString("type"))).optional();
    }

    /** 롤업용 공간 노드: 면적과 켜진 상위 계량기(하위 합계 포함 측정 원천) 여부 */
    public record TreeRow(long id, Long parentId, String name, String type, Double areaM2, boolean coversDescendants) {
    }

    /** ACTIVE 공간 트리. {@code pathPrefix}가 있으면 그 공간과 하위만, 공간 범위가 제한된 사용자면 허용 공간만(ENE-01.03, TC-ENE-019) */
    public List<TreeRow> spaceTree(long orgId, String pathPrefix, SpaceScope scope) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", orgId);
        params.put("prefix", pathPrefix);
        String sql = """
                SELECT s.id, s.parent_id, s.name, s.type, s.area_m2,
                       EXISTS (SELECT 1 FROM data2flow_core.energy_sources e WHERE e.space_id = s.id AND e.kind = 'METERED'
                                  AND e.covers_descendants AND e.enabled) AS covers
                  FROM data2flow_core.spaces s
                 WHERE s.organization_id = :org AND s.status = 'ACTIVE' AND (CAST(:prefix AS text) IS NULL OR s.path LIKE :prefix || '%')"""
                + SpaceScopeSql.and(scope, "s.id", params) + " ORDER BY s.path, s.sort_order, s.id";
        return jdbc.sql(sql).params(params)
                .query((rs, n) -> new TreeRow(rs.getLong("id"), Pg.longOrNull(rs, "parent_id"), rs.getString("name"), rs.getString("type"),
                        rs.getBigDecimal("area_m2") == null ? null : rs.getBigDecimal("area_m2").doubleValue(), rs.getBoolean("covers")))
                .list();
    }

    public List<TariffRow> tariffs(long orgId) {
        return jdbc.sql(TARIFF + " WHERE t.organization_id = :org ORDER BY t.scope_space_id NULLS FIRST, t.valid_from").param("org", orgId)
                .query(EnergyConfigRepository::tariff).list();
    }

    public Optional<TariffRow> tariff(long orgId, long id) {
        return jdbc.sql(TARIFF + " WHERE t.organization_id = :org AND t.id = :id").param("org", orgId).param("id", id)
                .query(EnergyConfigRepository::tariff).optional();
    }

    /** 같은 범위에 같은 시작일 행이 있는가({@code exceptId}는 고치는 행) */
    public boolean tariffStartTaken(long orgId, Long scopeSpaceId, LocalDate validFrom, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.energy_tariffs
                                        WHERE organization_id = :org AND scope_space_id IS NOT DISTINCT FROM CAST(:scope AS bigint)
                                          AND valid_from = :from AND id <> coalesce(CAST(:except AS bigint), -1))""")
                .param("org", orgId).param("scope", scopeSpaceId).param("from", validFrom).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertTariff(long orgId, TariffValues v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.energy_tariffs (organization_id, scope_space_id, valid_from, mode, flat_rate_krw_per_kwh, tou_schedule,
                               base_charge_krw_per_month, updated_by, created_at, updated_at)
                        VALUES (:org, :scope, :from, :mode, :flat, CAST(:tou AS jsonb), :base, :by, :now, :now)
                        RETURNING id""")
                .params(tariffParams(v)).param("org", orgId).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 버전이 맞을 때만 고친다(고친 행 수) */
    public int updateTariff(long orgId, long id, int baseVersion, TariffValues v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_tariffs SET scope_space_id = :scope, valid_from = :from, mode = :mode, flat_rate_krw_per_kwh = :flat,
                               tou_schedule = CAST(:tou AS jsonb), base_charge_krw_per_month = :base, version = version + 1, updated_by = :by,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :version""")
                .params(tariffParams(v)).param("org", orgId).param("id", id).param("version", baseVersion).param("by", userId)
                .param("now", Pg.ts(now)).update();
    }

    public int deleteTariff(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.energy_tariffs WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .update();
    }

    /** 조직 계수와 시스템 기본값(시작일 순, 같은 날이면 조직 먼저) */
    public List<FactorRow> factors(long orgId) {
        return jdbc.sql(FACTOR + " WHERE organization_id IN (:org, 0) ORDER BY valid_from, organization_id DESC").param("org", orgId)
                .query(EnergyConfigRepository::factor).list();
    }

    /** 조직 계수이거나 시스템 기본값인 행 */
    public Optional<FactorRow> factor(long orgId, long id) {
        return jdbc.sql(FACTOR + " WHERE organization_id IN (:org, 0) AND id = :id").param("org", orgId).param("id", id)
                .query(EnergyConfigRepository::factor).optional();
    }

    public boolean factorStartTaken(long orgId, LocalDate validFrom, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.energy_emission_factors
                                        WHERE organization_id = :org AND valid_from = :from AND id <> coalesce(CAST(:except AS bigint), -1))""")
                .param("org", orgId).param("from", validFrom).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertFactor(long orgId, FactorValues v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.energy_emission_factors (organization_id, valid_from, factor_tco2_per_mwh, source_title, source_year,
                               created_by, created_at)
                        VALUES (:org, :from, :factor, :title, :year, :by, :now)
                        RETURNING id""")
                .params(factorParams(v)).param("org", orgId).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateFactor(long orgId, long id, FactorValues v) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_emission_factors SET valid_from = :from, factor_tco2_per_mwh = :factor, source_title = :title,
                               source_year = :year
                         WHERE organization_id = :org AND id = :id""")
                .params(factorParams(v)).param("org", orgId).param("id", id).update();
    }

    public int deleteFactor(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.energy_emission_factors WHERE organization_id = :org AND id = :id").param("org", orgId)
                .param("id", id).update();
    }

    private static Map<String, Object> tariffParams(TariffValues v) {
        Map<String, Object> m = new HashMap<>();
        m.put("scope", v.scopeSpaceId());
        m.put("from", v.validFrom());
        m.put("mode", v.mode());
        m.put("flat", v.flatRate());
        m.put("tou", v.touJson());
        m.put("base", v.baseCharge());
        return m;
    }

    private static Map<String, Object> factorParams(FactorValues v) {
        return Map.of("from", v.validFrom(), "factor", v.factor(), "title", v.sourceTitle(), "year", v.sourceYear());
    }

    static TariffRow tariff(ResultSet rs, int n) throws SQLException {
        return new TariffRow(rs.getLong("id"), rs.getLong("organization_id"), Pg.longOrNull(rs, "scope_space_id"), rs.getString("scope_space_name"),
                rs.getObject("valid_from", LocalDate.class), rs.getString("mode"), rs.getBigDecimal("flat_rate_krw_per_kwh"),
                rs.getString("tou_schedule"), rs.getBigDecimal("base_charge_krw_per_month"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    static FactorRow factor(ResultSet rs, int n) throws SQLException {
        return new FactorRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getObject("valid_from", LocalDate.class),
                rs.getBigDecimal("factor_tco2_per_mwh"), rs.getString("source_title"), rs.getInt("source_year"));
    }
}
