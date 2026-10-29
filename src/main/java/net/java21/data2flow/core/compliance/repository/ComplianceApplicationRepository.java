package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 프로필 적용({@code data2flow_core.profile_applications}, CMP-01.03)과 적용 대상 공간·기기 조회(CMP-01.04). 공간 경로는 조상 ID를
 * {@code /}로 이은 값(예: {@code /1/4/9/})이라 하위 공간은 경로 접두사로 찾는다.
 */
@Repository
public class ComplianceApplicationRepository {

    private static final String SELECT = """
            SELECT a.id, a.organization_id, a.profile_id, p.name AS profile_name, p.current_version, a.pinned_version_no, a.target_type,
                   a.target_space_id, s.path AS target_path, s.name AS target_name, a.target_usage, a.include_descendants, a.facility_type,
                   a.applied_by, u.name AS applied_by_name, a.applied_at, a.version
              FROM data2flow_core.profile_applications a
              JOIN data2flow_core.compliance_profiles p ON p.id = a.profile_id
              LEFT JOIN data2flow_core.spaces s ON s.id = a.target_space_id
              LEFT JOIN data2flow_core.app_users u ON u.id = a.applied_by
            """;

    private final JdbcClient jdbc;

    public ComplianceApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ApplicationRow(long id, long organizationId, long profileId, String profileName, int currentVersion, Integer pinnedVersionNo,
                                 String targetType, Long targetSpaceId, String targetPath, String targetName, String targetUsage,
                                 boolean includeDescendants, String facilityType, long appliedBy, String appliedByName, Instant appliedAt,
                                 int version) {

        /** 지금 쓰는 프로필 버전(고정이 없으면 최신) */
        public int effectiveVersionNo() {
            return pinnedVersionNo != null ? pinnedVersionNo : currentVersion;
        }
    }

    public record SpaceRow(long id, String name, String path, String type, String usage) {
    }

    /** ACTIVE 실제 기기와 그 기기 모델이 내는 측정 항목 */
    public record DeviceRow(long id, String name, String spacePath, Set<String> metricKeys) {
    }

    public record TargetRow(String metricKey, Double min, Double max) {
    }

    public long insert(long orgId, long profileId, Integer pinned, String targetType, Long spaceId, String usage, boolean descendants,
                       String facilityType, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.profile_applications (organization_id, profile_id, pinned_version_no, target_type, target_space_id,
                               target_usage, include_descendants, facility_type, applied_by, applied_at, version)
                        VALUES (:org, :profile, :pinned, :type, :space, :usage, :desc, :facility, :user, :now, 0) RETURNING id""")
                .param("org", orgId).param("profile", profileId).param("pinned", pinned).param("type", targetType).param("space", spaceId)
                .param("usage", usage).param("desc", descendants).param("facility", facilityType).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public boolean exists(long orgId, long profileId, String targetType, Long spaceId, String usage) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.profile_applications WHERE organization_id = :org AND profile_id = :profile
                                         AND target_type = :type AND target_space_id IS NOT DISTINCT FROM CAST(:space AS bigint)
                                         AND target_usage IS NOT DISTINCT FROM CAST(:usage AS text))""")
                .param("org", orgId).param("profile", profileId).param("type", targetType).param("space", spaceId).param("usage", usage)
                .query(Boolean.class).single();
    }

    public Optional<ApplicationRow> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.id = :id").param("org", orgId).param("id", id)
                .query(ComplianceApplicationRepository::map).optional();
    }

    public void delete(long orgId, long id) {
        jdbc.sql("DELETE FROM data2flow_core.profile_applications WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .update();
    }

    public List<ApplicationRow> list(long orgId, Long profileId, Long spaceId) {
        return jdbc.sql(SELECT + """
                         WHERE a.organization_id = :org AND (CAST(:profile AS bigint) IS NULL OR a.profile_id = :profile)
                           AND (CAST(:space AS bigint) IS NULL OR a.target_space_id = :space) ORDER BY a.applied_at DESC, a.id DESC""")
                .param("org", orgId).param("profile", profileId).param("space", spaceId).query(ComplianceApplicationRepository::map).list();
    }

    public List<ApplicationRow> byProfile(long orgId, long profileId) {
        return list(orgId, profileId, null);
    }

    /**
     * 이 공간에 걸리는 적용: 공간 자신에 걸린 적용, 하위 포함으로 조상에 걸린 공간·사이트 적용, 공간 용도가 같은 용도 적용
     */
    public List<ApplicationRow> forSpace(long orgId, SpaceRow space) {
        return jdbc.sql(SELECT + """
                         WHERE a.organization_id = :org
                           AND ((a.target_type IN ('SPACE', 'SITE') AND (a.target_space_id = :space
                                   OR ((a.include_descendants OR a.target_type = 'SITE') AND :path LIKE s.path || '%')))
                             OR (a.target_type = 'SPACE_USAGE' AND a.target_usage = CAST(:usage AS text)))
                         ORDER BY a.id""")
                .param("org", orgId).param("space", space.id()).param("path", space.path()).param("usage", space.usage())
                .query(ComplianceApplicationRepository::map).list();
    }

    public Optional<SpaceRow> space(long orgId, long id) {
        return jdbc.sql("SELECT id, name, path, type, usage FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'")
                .param("org", orgId).param("id", id).query(ComplianceApplicationRepository::space).optional();
    }

    /** 내부 API용 ACTIVE 공간과 그 조직. {@code orgId}가 null이면 모든 조직(배포 조직 설정 없음, ADR-030) */
    public Optional<Long> findSpaceOrganization(Long orgId, long id) {
        return jdbc.sql("""
                        SELECT organization_id FROM data2flow_core.spaces
                         WHERE id = :id AND status = 'ACTIVE' AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)""")
                .param("id", id).param("org", orgId).query(Long.class).optional();
    }

    /** 적용이 덮는 공간(하위 포함이면 펼친다, 용도 적용이면 그 용도의 공간들) */
    public List<SpaceRow> coveredSpaces(long orgId, ApplicationRow a) {
        if ("SPACE_USAGE".equals(a.targetType())) {
            return jdbc.sql("""
                            SELECT id, name, path, type, usage FROM data2flow_core.spaces
                             WHERE organization_id = :org AND usage = :usage AND status = 'ACTIVE' ORDER BY path""")
                    .param("org", orgId).param("usage", a.targetUsage()).query(ComplianceApplicationRepository::space).list();
        }
        if (a.targetPath() == null) {
            return List.of();
        }
        boolean descendants = a.includeDescendants() || "SITE".equals(a.targetType());
        return jdbc.sql("""
                        SELECT id, name, path, type, usage FROM data2flow_core.spaces
                         WHERE organization_id = :org AND status = 'ACTIVE' AND (id = :id OR (:desc AND path LIKE :path || '%')) ORDER BY path""")
                .param("org", orgId).param("id", a.targetSpaceId()).param("desc", descendants).param("path", a.targetPath())
                .query(ComplianceApplicationRepository::space).list();
    }

    /** 공간들에 놓인 ACTIVE 실제 기기(가상 기기 제외, BR-CMP-06)와 모델의 측정 항목 */
    public List<DeviceRow> activeRealDevices(long orgId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT d.id, d.name, s.path, coalesce(array_agg(mm.metric_key ORDER BY mm.metric_key)
                               FILTER (WHERE mm.metric_key IS NOT NULL), '{}') AS metric_keys
                          FROM data2flow_core.devices d
                          JOIN data2flow_core.spaces s ON s.id = d.space_id
                          LEFT JOIN data2flow_core.model_metrics mm ON mm.model_id = d.model_id
                         WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND NOT d.is_virtual AND d.space_id = ANY(CAST(:ids AS bigint[]))
                         GROUP BY d.id, d.name, s.path ORDER BY d.id""")
                .param("org", orgId).param("ids", Pg.bigintArray(spaceIds))
                .query((rs, n) -> new DeviceRow(rs.getLong("id"), rs.getString("name"), rs.getString("path"),
                        new LinkedHashSet<>(Pg.stringList(rs, "metric_keys")))).list();
    }

    /** 공간 목표 환경(DEV-01.05). 행이 없는 항목은 가장 가까운 조상의 값을 쓴다 */
    public List<TargetRow> targets(long orgId, SpaceRow space) {
        List<Long> chain = new ArrayList<>();
        for (String part : space.path().split("/")) {
            if (!part.isBlank()) {
                chain.add(Long.parseLong(part));
            }
        }
        return jdbc.sql("""
                        SELECT DISTINCT ON (t.metric_key) t.metric_key, t.min_value, t.max_value
                          FROM data2flow_core.space_targets t JOIN data2flow_core.spaces s ON s.id = t.space_id
                         WHERE t.organization_id = :org AND t.space_id = ANY(CAST(:ids AS bigint[]))
                         ORDER BY t.metric_key, s.depth DESC""")
                .param("org", orgId).param("ids", Pg.bigintArray(chain))
                .query((rs, n) -> new TargetRow(rs.getString("metric_key"), (Double) rs.getObject("min_value", Double.class),
                        (Double) rs.getObject("max_value", Double.class))).list();
    }

    /** 조직 측정 항목 키 → 단위(단위 없으면 빈 문자열) */
    public java.util.Map<String, String> metricUnits(long orgId) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql("SELECT key, coalesce(unit, '') AS unit FROM data2flow_core.metrics WHERE organization_id = :org").param("org", orgId)
                .query((rs, n) -> out.put(rs.getString("key"), rs.getString("unit"))).list();
        return out;
    }

    static SpaceRow space(ResultSet rs, int n) throws SQLException {
        return new SpaceRow(rs.getLong("id"), rs.getString("name"), rs.getString("path"), rs.getString("type"), rs.getString("usage"));
    }

    static ApplicationRow map(ResultSet rs, int n) throws SQLException {
        int pinned = rs.getInt("pinned_version_no");
        Integer pinnedNo = rs.wasNull() ? null : pinned;
        return new ApplicationRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("profile_id"), rs.getString("profile_name"),
                rs.getInt("current_version"), pinnedNo, rs.getString("target_type"), Pg.longOrNull(rs, "target_space_id"),
                rs.getString("target_path"), rs.getString("target_name"), rs.getString("target_usage"), rs.getBoolean("include_descendants"),
                rs.getString("facility_type"), rs.getLong("applied_by"), rs.getString("applied_by_name"), Pg.instant(rs, "applied_at"),
                rs.getInt("version"));
    }
}
