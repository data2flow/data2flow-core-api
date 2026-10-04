package net.java21.data2flow.core.board.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 사용자 정의 대시보드({@code dashboards})와 공유 링크({@code share_links}) (DSH-04·06.03). 보관(ARCHIVED)한 대시보드는 없는 것으로 본다.
 * 보이는 대시보드 = 같은 조직 + ACTIVE + (ORG 공개 또는 소유자 본인)(BR-DSH-08).
 */
@Repository
public class DashboardBoardRepository {

    private static final String SELECT = """
            SELECT d.id, d.organization_id, d.name, d.description, d.visibility, d.owner_user_id, ou.name AS owner_name,
                   d.layout::text AS layout, d.variables::text AS variables, d.time_range::text AS time_range, d.resolution, d.refresh,
                   d.template_source, d.status, d.version, d.updated_by, uu.name AS updated_by_name, d.created_at, d.updated_at,
                   jsonb_array_length(coalesce(d.layout->'widgets', '[]'::jsonb)) AS widget_count
              FROM data2flow_core.dashboards d
              LEFT JOIN data2flow_core.app_users ou ON ou.id = d.owner_user_id
              LEFT JOIN data2flow_core.app_users uu ON uu.id = d.updated_by
            """;

    private final JdbcClient jdbc;

    public DashboardBoardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DashboardRow> findActive(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE d.organization_id = :org AND d.id = :id AND d.status = 'ACTIVE'")
                .param("org", organizationId).param("id", id).query(DashboardBoardRepository::map).optional();
    }

    /**
     * 목록. tab: mine(본인 소유), shared(남이 ORG로 공개한 것), favorite(ids 안에서 보이는 것). keyword는 이름 부분 일치.
     */
    public List<DashboardRow> list(long organizationId, long userId, String tab, String keyword, Collection<Long> favoriteIds,
                                   int limit, long offset) {
        return jdbc.sql(SELECT + " " + where() + " ORDER BY d.updated_at DESC, d.id DESC LIMIT :limit OFFSET :offset")
                .params(params(organizationId, userId, tab, keyword, favoriteIds)).param("limit", limit).param("offset", offset)
                .query(DashboardBoardRepository::map).list();
    }

    public long count(long organizationId, long userId, String tab, String keyword, Collection<Long> favoriteIds) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.dashboards d " + where())
                .params(params(organizationId, userId, tab, keyword, favoriteIds)).query(Long.class).single();
    }

    private static String where() {
        return """
                 WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND (d.visibility = 'ORG' OR d.owner_user_id = :user)
                   AND (:tab <> 'mine' OR d.owner_user_id = :user)
                   AND (:tab <> 'shared' OR (d.visibility = 'ORG' AND d.owner_user_id <> :user))
                   AND (:tab <> 'favorite' OR d.id = ANY(CAST(:favorites AS bigint[])))
                   AND (CAST(:keyword AS text) IS NULL OR lower(d.name) LIKE '%' || lower(CAST(:keyword AS text)) || '%')""";
    }

    private static Map<String, Object> params(long organizationId, long userId, String tab, String keyword, Collection<Long> favoriteIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", organizationId);
        p.put("user", userId);
        p.put("tab", tab);
        p.put("keyword", keyword);
        p.put("favorites", Pg.bigintArray(favoriteIds == null ? List.of() : favoriteIds));
        return p;
    }

    /** 보이는 대시보드 이름(즐겨찾기·최근 항목·기본 대시보드 표시) */
    public Map<Long, String> findVisibleNames(long organizationId, long userId, Collection<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("""
                        SELECT id, name FROM data2flow_core.dashboards
                         WHERE organization_id = :org AND status = 'ACTIVE' AND (visibility = 'ORG' OR owner_user_id = :user)
                           AND id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("user", userId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("name"))).list()
                .forEach(e -> names.put(e.getKey(), e.getValue()));
        return names;
    }

    public long insert(long organizationId, NewDashboard d, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.dashboards (organization_id, name, description, visibility, owner_user_id, layout, variables,
                               time_range, resolution, refresh, template_source, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :description, :visibility, :owner, CAST(:layout AS jsonb), CAST(:variables AS jsonb),
                                CAST(:timeRange AS jsonb), :resolution, :refresh, :template, 1, :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", d.name()).param("description", d.description())
                .param("visibility", d.visibility()).param("owner", userId).param("layout", d.layout()).param("variables", d.variables())
                .param("timeRange", d.timeRange()).param("resolution", d.resolution()).param("refresh", d.refresh())
                .param("template", d.templateSource()).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 낙관적 잠금 저장. 바뀐 행 수(0이면 판 번호 충돌) */
    public int update(long organizationId, long id, int baseVersion, NewDashboard d, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.dashboards
                           SET name = :name, description = :description, visibility = :visibility, layout = CAST(:layout AS jsonb),
                               variables = CAST(:variables AS jsonb), time_range = CAST(:timeRange AS jsonb), resolution = :resolution,
                               refresh = :refresh, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status = 'ACTIVE'""")
                .param("name", d.name()).param("description", d.description()).param("visibility", d.visibility())
                .param("layout", d.layout()).param("variables", d.variables()).param("timeRange", d.timeRange())
                .param("resolution", d.resolution()).param("refresh", d.refresh()).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    public int archive(long organizationId, long id, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.dashboards SET status = 'ARCHIVED', version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 보관한 대시보드를 기본 대시보드로 쓰던 사용자 설정을 비운다(FK SET NULL은 실제 삭제에만 동작) */
    public int clearDefaultDashboard(long organizationId, long dashboardId) {
        return jdbc.sql("""
                        UPDATE data2flow_core.user_dashboard_prefs SET default_dashboard_id = NULL
                         WHERE organization_id = :org AND default_dashboard_id = :id""")
                .param("org", organizationId).param("id", dashboardId).update();
    }

    /** 기본 대시보드 지정(설정 행이 없으면 만든다) */
    public void upsertDefaultDashboard(long organizationId, long userId, Long dashboardId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.user_dashboard_prefs (organization_id, user_id, default_dashboard_id, version, updated_at)
                        VALUES (:org, :user, :dashboard, 1, :now)
                        ON CONFLICT (user_id) DO UPDATE SET default_dashboard_id = EXCLUDED.default_dashboard_id,
                               version = data2flow_core.user_dashboard_prefs.version + 1, updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.user_dashboard_prefs.organization_id = EXCLUDED.organization_id""")
                .param("org", organizationId).param("user", userId).param("dashboard", dashboardId).param("now", Pg.ts(now)).update();
    }

    /** 사용자 즐겨찾기 JSON(없으면 []) */
    public String findFavoritesJson(long organizationId, long userId) {
        return jdbc.sql("SELECT favorites::text FROM data2flow_core.user_dashboard_prefs WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId).query(String.class).optional().orElse("[]");
    }

    // ---------------------------------------------------------------- 공유 링크

    public long insertShareLink(long organizationId, long dashboardId, String tokenHash, Instant expiresAt, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.share_links (organization_id, dashboard_id, token_hash, expires_at, created_by, created_at)
                        VALUES (:org, :dashboard, :hash, :expires, :user, :now) RETURNING id""")
                .param("org", organizationId).param("dashboard", dashboardId).param("hash", tokenHash).param("expires", Pg.ts(expiresAt))
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public List<ShareLinkRow> listShareLinks(long organizationId, long dashboardId) {
        return jdbc.sql("""
                        SELECT id, organization_id, dashboard_id, expires_at, revoked_at, last_used_at, created_by, created_at
                          FROM data2flow_core.share_links WHERE organization_id = :org AND dashboard_id = :dashboard
                         ORDER BY created_at DESC, id DESC""")
                .param("org", organizationId).param("dashboard", dashboardId).query(DashboardBoardRepository::shareLink).list();
    }

    public Optional<ShareLinkRow> findShareLink(long organizationId, long dashboardId, long id) {
        return jdbc.sql("""
                        SELECT id, organization_id, dashboard_id, expires_at, revoked_at, last_used_at, created_by, created_at
                          FROM data2flow_core.share_links WHERE organization_id = :org AND dashboard_id = :dashboard AND id = :id""")
                .param("org", organizationId).param("dashboard", dashboardId).param("id", id)
                .query(DashboardBoardRepository::shareLink).optional();
    }

    public int revokeShareLink(long organizationId, long id, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.share_links SET revoked_at = :now WHERE organization_id = :org AND id = :id AND revoked_at IS NULL")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 공개 경로(API-DSH-15): 토큰 해시로 찾는다. 조직은 토큰이 정한다(로그인 없음) */
    @OrganizationScopeExempt("공유 링크 토큰(32바이트 난수의 해시)이 조직과 대시보드를 정한다(공개 경로, BR-DSH-12)")
    public Optional<ShareLinkRow> findShareLinkByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, organization_id, dashboard_id, expires_at, revoked_at, last_used_at, created_by, created_at
                          FROM data2flow_core.share_links WHERE token_hash = :hash""")
                .param("hash", tokenHash).query(DashboardBoardRepository::shareLink).optional();
    }

    /** 마지막 사용 시각(1분에 한 번만 쓴다) */
    public void touchShareLink(long organizationId, long id, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.share_links SET last_used_at = :now
                         WHERE organization_id = :org AND id = :id AND (last_used_at IS NULL OR last_used_at < CAST(:now AS timestamptz) - interval '1 minute')""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    static DashboardRow map(ResultSet rs, int n) throws SQLException {
        return new DashboardRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("visibility"), rs.getLong("owner_user_id"), rs.getString("owner_name"), rs.getString("layout"),
                rs.getString("variables"), rs.getString("time_range"), rs.getString("resolution"), rs.getString("refresh"),
                rs.getString("template_source"), rs.getInt("version"), Pg.longOrNull(rs, "updated_by"), rs.getString("updated_by_name"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"), rs.getInt("widget_count"));
    }

    static ShareLinkRow shareLink(ResultSet rs, int n) throws SQLException {
        return new ShareLinkRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("dashboard_id"), Pg.instant(rs, "expires_at"),
                Pg.instant(rs, "revoked_at"), Pg.instant(rs, "last_used_at"), rs.getLong("created_by"), Pg.instant(rs, "created_at"));
    }

    public record DashboardRow(long id, long organizationId, String name, String description, String visibility, long ownerUserId,
                               String ownerName, String layout, String variables, String timeRange, String resolution, String refresh,
                               String templateSource, int version, Long updatedBy, String updatedByName, Instant createdAt,
                               Instant updatedAt, int widgetCount) {
    }

    public record NewDashboard(String name, String description, String visibility, String layout, String variables, String timeRange,
                               String resolution, String refresh, String templateSource) {
    }

    public record ShareLinkRow(long id, long organizationId, long dashboardId, Instant expiresAt, Instant revokedAt, Instant lastUsedAt,
                               long createdBy, Instant createdAt) {
    }
}
