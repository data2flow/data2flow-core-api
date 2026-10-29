package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Coverage;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기준 프로필·버전·항목 기준·등급·측정 적정성 규칙({@code data2flow_core.compliance_*}·{@code profile_*}, CMP-01.01·01.02).
 * 시스템 프로필은 {@code organization_id = 0}이고 모든 조직이 읽는다. 버전 내용(규칙·등급·적정성)은 버전 행 아래에 통째로 저장·교체한다.
 */
@Repository
public class ComplianceProfileRepository {

    public static final long SYSTEM_ORG = 0L;

    private static final String PROFILE_COLUMNS = """
            p.id, p.organization_id, p.kind, p.code, p.name, p.current_version, p.archived_at, p.version, p.updated_at,
            v.source_title, v.effective_date,
            (SELECT count(*) FROM data2flow_core.profile_applications a WHERE a.profile_id = p.id AND a.organization_id = :org) AS application_count""";

    private static final String VERSION_COLUMNS = """
            v.id, v.profile_id, v.version_no, v.source_title, v.source_url, v.effective_date, v.description, v.streak_days, v.streak_daily_ratio,
            v.facility_type_required, v.change_note, v.locked, v.created_by, v.created_at, u.name AS created_by_name,
            (SELECT count(*) FROM data2flow_core.profile_applications a JOIN data2flow_core.compliance_profiles pp ON pp.id = a.profile_id
              WHERE a.profile_id = v.profile_id AND a.organization_id = :org
                AND coalesce(a.pinned_version_no, pp.current_version) = v.version_no) AS application_count""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ComplianceProfileRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ProfileRow(long id, long organizationId, String kind, String code, String name, int currentVersion, Instant archivedAt,
                             int version, Instant updatedAt, String sourceTitle, LocalDate effectiveDate, long applicationCount) {

        public boolean system() {
            return "SYSTEM".equals(kind);
        }
    }

    public record VersionRow(long id, long profileId, int versionNo, String sourceTitle, String sourceUrl, LocalDate effectiveDate,
                             String description, Integer streakDays, BigDecimal streakDailyRatio, boolean facilityTypeRequired, String changeNote,
                             boolean locked, long createdBy, Instant createdAt, String createdByName, long applicationCount) {
    }

    /** 이 조직이 볼 수 있는 프로필(자기 조직 + 시스템) */
    public Optional<ProfileRow> findVisible(long orgId, long id) {
        return jdbc.sql("SELECT " + PROFILE_COLUMNS + """
                         FROM data2flow_core.compliance_profiles p
                         JOIN data2flow_core.compliance_profile_versions v ON v.profile_id = p.id AND v.version_no = p.current_version
                        WHERE p.id = :id AND p.organization_id IN (:org, 0)""")
                .param("org", orgId).param("id", id).query(this::profile).optional();
    }

    /** 프로필 행을 잠근다(버전 추가·보관·적용이 겹치지 않게). 시스템 프로필도 잠근다 */
    public void lock(long orgId, long id) {
        jdbc.sql("SELECT id FROM data2flow_core.compliance_profiles WHERE id = :id AND organization_id IN (:org, 0) FOR UPDATE")
                .param("id", id).param("org", orgId).query(Long.class).optional();
    }

    public List<ProfileRow> list(long orgId, String kind, String keyword, Boolean applied, int limit, long offset) {
        return jdbc.sql("SELECT " + PROFILE_COLUMNS + """
                         FROM data2flow_core.compliance_profiles p
                         JOIN data2flow_core.compliance_profile_versions v ON v.profile_id = p.id AND v.version_no = p.current_version
                        WHERE\s""" + filter() + " ORDER BY p.kind DESC, p.name, p.id LIMIT :limit OFFSET :offset")
                .param("org", orgId).param("kind", kind).param("kw", keyword).param("applied", applied).param("limit", limit)
                .param("offset", offset).query(this::profile).list();
    }

    public long count(long orgId, String kind, String keyword, Boolean applied) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.compliance_profiles p WHERE " + filter())
                .param("org", orgId).param("kind", kind).param("kw", keyword).param("applied", applied).query(Long.class).single();
    }

    private static String filter() {
        return """
                p.organization_id IN (:org, 0)
                  AND (CAST(:kind AS text) IS NULL OR p.kind = :kind)
                  AND (CAST(:kw AS text) IS NULL OR p.name ILIKE '%' || :kw || '%' OR p.code ILIKE '%' || :kw || '%')
                  AND (CAST(:applied AS boolean) IS NULL OR :applied = EXISTS (SELECT 1 FROM data2flow_core.profile_applications a
                        WHERE a.profile_id = p.id AND a.organization_id = :org))""";
    }

    public boolean nameExists(long orgId, String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.compliance_profiles WHERE organization_id = :org AND name = :name)")
                .param("org", orgId).param("name", name).query(Boolean.class).single();
    }

    public long insertProfile(long orgId, String name, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, name, current_version, version, created_by, updated_by,
                               created_at, updated_at)
                        VALUES (:org, 'ORGANIZATION', :name, 1, 0, :user, :user, :now, :now) RETURNING id""")
                .param("org", orgId).param("name", name).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 현재 버전 번호를 바꾸고 낙관적 잠금 버전을 올린다. 바뀐 행 수 */
    public int advance(long id, int baseVersion, int currentVersion, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_profiles SET current_version = :cur, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE id = :id AND version = :base""")
                .param("cur", currentVersion).param("user", userId).param("now", Pg.ts(now)).param("id", id).param("base", baseVersion).update();
    }

    public int touch(long id, int baseVersion, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_profiles SET version = version + 1, updated_by = :user, updated_at = :now
                         WHERE id = :id AND version = :base""")
                .param("user", userId).param("now", Pg.ts(now)).param("id", id).param("base", baseVersion).update();
    }

    public void setArchived(long id, Instant archivedAt, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_profiles SET archived_at = :at, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE id = :id""")
                .param("at", Pg.ts(archivedAt)).param("user", userId).param("now", Pg.ts(now)).param("id", id).update();
    }

    public List<VersionRow> versions(long orgId, long profileId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                         FROM data2flow_core.compliance_profile_versions v
                         LEFT JOIN data2flow_core.app_users u ON u.id = v.created_by
                        WHERE v.profile_id = :profile ORDER BY v.version_no DESC""")
                .param("org", orgId).param("profile", profileId).query(ComplianceProfileRepository::version).list();
    }

    public Optional<VersionRow> version(long orgId, long profileId, int versionNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                         FROM data2flow_core.compliance_profile_versions v
                         LEFT JOIN data2flow_core.app_users u ON u.id = v.created_by
                        WHERE v.profile_id = :profile AND v.version_no = :no""")
                .param("org", orgId).param("profile", profileId).param("no", versionNo).query(ComplianceProfileRepository::version).optional();
    }

    /** 버전 행과 내용(규칙·등급·적정성)을 넣는다. 버전 ID */
    public long insertVersion(long orgId, long profileId, int versionNo, VersionSpec spec, long userId, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, source_url,
                               effective_date, description, streak_days, streak_daily_ratio, facility_type_required, change_note, locked, created_by,
                               created_at, updated_at)
                        VALUES (:org, :profile, :no, :title, :url, :effective, :desc, :days, :ratio, :facility, :note, false, :user, :now, :now)
                        RETURNING id""")
                .param("org", orgId).param("profile", profileId).param("no", versionNo).param("title", spec.sourceTitle())
                .param("url", spec.sourceUrl()).param("effective", Date.valueOf(spec.effectiveDate())).param("desc", spec.description())
                .param("days", spec.streakDays()).param("ratio", spec.streakDailyRatio()).param("facility", spec.facilityTypeRequired())
                .param("note", spec.changeNote()).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
        insertContent(orgId, id, spec);
        return id;
    }

    /** 잠기지 않은 버전의 머리와 내용을 바꾼다 */
    public void replaceVersion(long orgId, long versionId, VersionSpec spec, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_profile_versions SET source_title = :title, source_url = :url, effective_date = :effective,
                               description = :desc, streak_days = :days, streak_daily_ratio = :ratio, facility_type_required = :facility,
                               change_note = coalesce(:note, change_note), updated_at = :now
                         WHERE id = :id AND NOT locked""")
                .param("title", spec.sourceTitle()).param("url", spec.sourceUrl()).param("effective", Date.valueOf(spec.effectiveDate()))
                .param("desc", spec.description()).param("days", spec.streakDays()).param("ratio", spec.streakDailyRatio())
                .param("facility", spec.facilityTypeRequired()).param("note", spec.changeNote()).param("now", Pg.ts(now)).param("id", versionId)
                .update();
        jdbc.sql("DELETE FROM data2flow_core.profile_metric_rules WHERE profile_version_id = :id").param("id", versionId).update();
        jdbc.sql("DELETE FROM data2flow_core.profile_coverage_rules WHERE profile_version_id = :id").param("id", versionId).update();
        insertContent(orgId, versionId, spec);
    }

    private void insertContent(long orgId, long versionId, VersionSpec spec) {
        int order = 0;
        for (MetricRule r : spec.metrics()) {
            long ruleId = jdbc.sql("""
                            INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, facility_type, eval_window,
                                   lower_limit, upper_limit, limit_inclusive, unit, requirement, note, sort_order)
                            VALUES (:org, :version, :key, :facility, :window, :lower, :upper, :inclusive, :unit, :req, :note, :order) RETURNING id""")
                    .param("org", orgId).param("version", versionId).param("key", r.metricKey()).param("facility", r.facilityType())
                    .param("window", r.evalWindow()).param("lower", r.lower()).param("upper", r.upper()).param("inclusive", r.inclusive())
                    .param("unit", r.unit()).param("req", r.requirement()).param("note", r.note()).param("order", ++order)
                    .query(Long.class).single();
            int bandOrder = 0;
            for (Band b : r.bands()) {
                jdbc.sql("""
                                INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base,
                                       sort_order)
                                VALUES (:org, :rule, :name, :lower, :upper, :base, :order)""")
                        .param("org", orgId).param("rule", ruleId).param("name", b.name()).param("lower", b.lower()).param("upper", b.upper())
                        .param("base", b.base()).param("order", ++bandOrder).update();
            }
        }
        for (Coverage c : spec.coverage()) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.profile_coverage_rules (organization_id, profile_version_id, kind, params)
                            VALUES (:org, :version, :kind, CAST(:params AS jsonb))""")
                    .param("org", orgId).param("version", versionId).param("kind", c.kind()).param("params", json.writeValueAsString(c.params()))
                    .update();
        }
    }

    /** 버전을 잠근다(적용·리포트에 쓰이면, BR-CMP-02) */
    @OrganizationScopeExempt("시스템 프로필 버전(organization_id 0)도 어느 조직이 적용하면 잠근다. 프로필은 호출 전에 조직 범위로 찾았다")
    public void lockVersion(long profileId, int versionNo) {
        jdbc.sql("UPDATE data2flow_core.compliance_profile_versions SET locked = true WHERE profile_id = :profile AND version_no = :no AND NOT locked")
                .param("profile", profileId).param("no", versionNo).update();
    }

    /** 버전 내용(규칙·등급·적정성) */
    public VersionSpec spec(VersionRow v) {
        return new VersionSpec(v.sourceTitle(), v.sourceUrl(), v.effectiveDate(), v.description(), v.streakDays(), v.streakDailyRatio(),
                v.facilityTypeRequired(), v.changeNote(), rules(v.id()), coverage(v.id()));
    }

    public List<MetricRule> rules(long versionId) {
        record Raw(long id, String key, String facility, String window, BigDecimal lower, BigDecimal upper, boolean inclusive, String unit,
                   String requirement, String note) {
        }
        List<Raw> raws = jdbc.sql("""
                        SELECT id, metric_key, facility_type, eval_window, lower_limit, upper_limit, limit_inclusive, unit, requirement, note
                          FROM data2flow_core.profile_metric_rules WHERE profile_version_id = :id ORDER BY facility_type NULLS FIRST, sort_order, id""")
                .param("id", versionId)
                .query((rs, n) -> new Raw(rs.getLong("id"), rs.getString("metric_key"), rs.getString("facility_type"), rs.getString("eval_window"),
                        rs.getBigDecimal("lower_limit"), rs.getBigDecimal("upper_limit"), rs.getBoolean("limit_inclusive"), rs.getString("unit"),
                        rs.getString("requirement"), rs.getString("note"))).list();
        Map<Long, List<Band>> bands = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT b.metric_rule_id, b.name, b.lower_value, b.upper_value, b.is_base FROM data2flow_core.profile_grade_bands b
                          JOIN data2flow_core.profile_metric_rules r ON r.id = b.metric_rule_id
                         WHERE r.profile_version_id = :id ORDER BY b.metric_rule_id, b.sort_order""")
                .param("id", versionId).query((rs, n) -> {
                    bands.computeIfAbsent(rs.getLong("metric_rule_id"), k -> new ArrayList<>()).add(new Band(rs.getString("name"),
                            rs.getBigDecimal("lower_value"), rs.getBigDecimal("upper_value"), rs.getBoolean("is_base")));
                    return 0;
                }).list();
        return raws.stream().map(r -> new MetricRule(r.key(), r.facility(), r.window(), r.lower(), r.upper(), r.inclusive(), r.unit(),
                r.requirement(), r.note(), bands.getOrDefault(r.id(), List.of()))).toList();
    }

    public List<Coverage> coverage(long versionId) {
        return jdbc.sql("SELECT kind, params::text AS params FROM data2flow_core.profile_coverage_rules WHERE profile_version_id = :id ORDER BY kind")
                .param("id", versionId).query((rs, n) -> new Coverage(rs.getString("kind"), json.readTree(rs.getString("params")))).list();
    }

    ProfileRow profile(ResultSet rs, int n) throws SQLException {
        Date effective = rs.getDate("effective_date");
        return new ProfileRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"), rs.getString("code"), rs.getString("name"),
                rs.getInt("current_version"), Pg.instant(rs, "archived_at"), rs.getInt("version"), Pg.instant(rs, "updated_at"),
                rs.getString("source_title"), effective == null ? null : effective.toLocalDate(), rs.getLong("application_count"));
    }

    static VersionRow version(ResultSet rs, int n) throws SQLException {
        int days = rs.getInt("streak_days");
        Integer streakDays = rs.wasNull() ? null : days;
        return new VersionRow(rs.getLong("id"), rs.getLong("profile_id"), rs.getInt("version_no"), rs.getString("source_title"),
                rs.getString("source_url"), rs.getDate("effective_date").toLocalDate(), rs.getString("description"), streakDays,
                rs.getBigDecimal("streak_daily_ratio"), rs.getBoolean("facility_type_required"), rs.getString("change_note"),
                rs.getBoolean("locked"), rs.getLong("created_by"), Pg.instant(rs, "created_at"), rs.getString("created_by_name"),
                rs.getLong("application_count"));
    }
}
