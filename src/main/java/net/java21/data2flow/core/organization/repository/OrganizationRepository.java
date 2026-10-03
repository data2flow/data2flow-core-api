package net.java21.data2flow.core.organization.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.organization.domain.OrganizationModels.OrgSettings;
import net.java21.data2flow.core.organization.domain.OrganizationModels.Organization;
import net.java21.data2flow.core.organization.domain.OrganizationModels.SecurityPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 조직·조직 설정·보안 정책 저장소. 조직 테이블 자체는 조직 조건이 곧 기본 키다 */
@Repository
public class OrganizationRepository {

    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Organization> findById(long organizationId) {
        return jdbc.sql("SELECT id, code, name, timezone, locale, status FROM data2flow_core.organizations WHERE id = :id")
                .param("id", organizationId).query(OrganizationRepository::mapOrg).optional();
    }

    @OrganizationScopeExempt("최초 설치(부트스트랩)에서 조직 코드로 찾는다")
    public Optional<Organization> findByCode(String code) {
        return jdbc.sql("SELECT id, code, name, timezone, locale, status FROM data2flow_core.organizations WHERE code = :code")
                .param("code", code).query(OrganizationRepository::mapOrg).optional();
    }

    /** 배포 설정 조직(ADR-030, {@code DATA2FLOW_ORGANIZATION_CODE}). ACTIVE일 때만 */
    @OrganizationScopeExempt("배포 설정의 조직 코드로 이 배포의 조직을 찾는다(ADR-030)")
    public Optional<Organization> findActiveByCode(String code) {
        return jdbc.sql("SELECT id, code, name, timezone, locale, status FROM data2flow_core.organizations WHERE code = :code AND status = 'ACTIVE'")
                .param("code", code).query(OrganizationRepository::mapOrg).optional();
    }

    /** v1 단일 조직(ADR-004): 로그인 아이디만 오는 공개 경로(가입 신청 등)의 조직. 조직이 정확히 하나일 때만 돌려준다 */
    @OrganizationScopeExempt("v1 단일 조직: 공개 경로는 요청에 조직이 없다")
    public Optional<Organization> findSingleActive() {
        List<Organization> orgs = jdbc.sql("""
                        SELECT id, code, name, timezone, locale, status FROM data2flow_core.organizations
                         WHERE status = 'ACTIVE' ORDER BY id LIMIT 2""")
                .query(OrganizationRepository::mapOrg).list();
        return orgs.size() == 1 ? Optional.of(orgs.get(0)) : Optional.empty();
    }

    public long insert(String code, String name, String timezone, String locale) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.organizations (code, name, timezone, locale)
                        VALUES (:code, :name, :timezone, :locale) RETURNING id""")
                .param("code", code).param("name", name).param("timezone", timezone).param("locale", locale)
                .query(Long.class).single();
    }

    // ---- 조직 설정(OPS-07.01)

    public void insertSettingsIfAbsent(long organizationId, String displayName, String timezone, String locale) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.org_settings (organization_id, display_name, timezone, locale)
                        VALUES (:org, :name, :tz, :locale) ON CONFLICT (organization_id) DO NOTHING""")
                .param("org", organizationId).param("name", displayName).param("tz", timezone).param("locale", locale)
                .update();
    }

    public Optional<OrgSettings> findSettings(long organizationId) {
        return jdbc.sql("""
                        SELECT organization_id, display_name, logo_object_key, timezone, locale, unit_system, date_format, version, updated_at
                          FROM data2flow_core.org_settings WHERE organization_id = :org""")
                .param("org", organizationId)
                .query((rs, n) -> new OrgSettings(rs.getLong("organization_id"), rs.getString("display_name"),
                        rs.getString("logo_object_key"), rs.getString("timezone"), rs.getString("locale"),
                        rs.getString("unit_system"), rs.getString("date_format"), rs.getInt("version"), Pg.instant(rs, "updated_at")))
                .optional();
    }

    /** 낙관적 잠금: version이 baseVersion일 때만 바꾸고 1 올린다(BR-OPS-21) */
    public int updateSettings(long organizationId, int baseVersion, String displayName, String timezone, String locale,
                              String unitSystem, String dateFormat, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.org_settings
                           SET display_name = :name, timezone = :tz, locale = :locale, unit_system = :unit, date_format = :fmt,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND version = :base""")
                .param("name", displayName).param("tz", timezone).param("locale", locale).param("unit", unitSystem)
                .param("fmt", dateFormat).param("by", updatedBy).param("now", Pg.ts(now))
                .param("org", organizationId).param("base", baseVersion)
                .update();
    }

    // ---- 보안 정책(API-IAM-72)

    public void insertPolicyIfAbsent(long organizationId) {
        jdbc.sql("INSERT INTO data2flow_core.org_security_policies (organization_id) VALUES (:org) ON CONFLICT (organization_id) DO NOTHING")
                .param("org", organizationId).update();
    }

    public Optional<SecurityPolicy> findPolicy(long organizationId) {
        return jdbc.sql("""
                        SELECT organization_id, session_idle_minutes, session_absolute_hours, access_ttl_minutes, refresh_ttl_hours,
                               login_max_failures, lockout_minutes, mfa_required_roles, signup_request_enabled, signup_allowed_domains,
                               audit_retention_days, version
                          FROM data2flow_core.org_security_policies WHERE organization_id = :org""")
                .param("org", organizationId).query(OrganizationRepository::mapPolicy).optional();
    }

    public int updatePolicy(SecurityPolicy p, int baseVersion, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.org_security_policies
                           SET session_idle_minutes = :idle, session_absolute_hours = :abs, access_ttl_minutes = :access,
                               refresh_ttl_hours = :refresh, login_max_failures = :maxFail, lockout_minutes = :lockout,
                               mfa_required_roles = CAST(:mfaRoles AS varchar(20)[]), signup_request_enabled = :signup,
                               signup_allowed_domains = CAST(:domains AS varchar(253)[]), audit_retention_days = :retention,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND version = :base""")
                .param("idle", p.sessionIdleMinutes()).param("abs", p.sessionAbsoluteHours()).param("access", p.accessTtlMinutes())
                .param("refresh", p.refreshTtlHours()).param("maxFail", p.loginMaxFailures()).param("lockout", p.lockoutMinutes())
                .param("mfaRoles", Pg.textArray(p.mfaRequiredRoles())).param("signup", p.signupRequestEnabled())
                .param("domains", Pg.textArray(p.signupAllowedDomains())).param("retention", p.auditRetentionDays())
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", p.organizationId()).param("base", baseVersion)
                .update();
    }

    private static Organization mapOrg(ResultSet rs, int n) throws SQLException {
        return new Organization(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("timezone"),
                rs.getString("locale"), rs.getString("status"));
    }

    private static SecurityPolicy mapPolicy(ResultSet rs, int n) throws SQLException {
        return new SecurityPolicy(rs.getLong("organization_id"), rs.getInt("session_idle_minutes"),
                rs.getInt("session_absolute_hours"), rs.getInt("access_ttl_minutes"), rs.getInt("refresh_ttl_hours"),
                rs.getInt("login_max_failures"), rs.getInt("lockout_minutes"), Pg.stringList(rs, "mfa_required_roles"),
                rs.getBoolean("signup_request_enabled"), Pg.stringList(rs, "signup_allowed_domains"),
                rs.getInt("audit_retention_days"), rs.getInt("version"));
    }
}
