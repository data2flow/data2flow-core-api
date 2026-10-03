package net.java21.data2flow.core.dashboard.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 내 화면 설정({@code data2flow_core.user_dashboard_prefs}, API-DSH-12, DSH-07.02·07.04·07.05)과 그 기본값의 원천
 * (조직 설정 시간대·단위, 계정 언어), 즐겨찾기·최근 항목 이름 찾기(공간·기기, 공간 범위 안만).
 */
@Repository
public class PreferencesRepository {

    private static final String COLUMNS = """
            theme, locale, time_zone, home, default_dashboard_id, favorites::text AS favorites, recent::text AS recent,
            tours_dismissed, temperature_unit, version""";

    private final JdbcClient jdbc;

    public PreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PrefsRow> findPrefs(long organizationId, long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.user_dashboard_prefs WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId)
                .query((rs, n) -> new PrefsRow(rs.getString("theme"), rs.getString("locale"), rs.getString("time_zone"),
                        rs.getString("home"), Pg.longOrNull(rs, "default_dashboard_id"), rs.getString("favorites"), rs.getString("recent"),
                        Pg.stringList(rs, "tours_dismissed"), rs.getString("temperature_unit"), rs.getInt("version")))
                .optional();
    }

    /** 행 잠금(최근 항목 갱신). 없으면 빈 값 */
    public Optional<PrefsRow> lockPrefs(long organizationId, long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.user_dashboard_prefs WHERE organization_id = :org AND user_id = :user FOR UPDATE")
                .param("org", organizationId).param("user", userId)
                .query((rs, n) -> new PrefsRow(rs.getString("theme"), rs.getString("locale"), rs.getString("time_zone"),
                        rs.getString("home"), Pg.longOrNull(rs, "default_dashboard_id"), rs.getString("favorites"), rs.getString("recent"),
                        Pg.stringList(rs, "tours_dismissed"), rs.getString("temperature_unit"), rs.getInt("version")))
                .optional();
    }

    /** 처음 저장(version 1). 이미 있으면 0 */
    public int insertPrefs(long organizationId, long userId, PrefsRow p, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.user_dashboard_prefs (organization_id, user_id, default_dashboard_id, home, theme, locale,
                               time_zone, favorites, recent, tours_dismissed, temperature_unit, version, updated_at)
                        VALUES (:org, :user, :dashboard, :home, :theme, :locale, :tz, CAST(:favorites AS jsonb), CAST(:recent AS jsonb),
                                CAST(:tours AS text[]), :unit, :version, :now)
                        ON CONFLICT (user_id) DO NOTHING""")
                .param("org", organizationId).param("user", userId).param("dashboard", p.defaultDashboardId()).param("home", p.home())
                .param("theme", p.theme()).param("locale", p.locale()).param("tz", p.timeZone()).param("favorites", p.favoritesJson())
                .param("recent", p.recentJson()).param("tours", Pg.textArray(p.toursDismissed())).param("unit", p.temperatureUnit())
                .param("version", p.version()).param("now", Pg.ts(now))
                .update();
    }

    /** 설정 저장(낙관적 잠금: baseVersion이 맞을 때만, version + 1). 바뀐 행 수 */
    public int updatePrefs(long organizationId, long userId, int baseVersion, PrefsRow p, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.user_dashboard_prefs
                           SET default_dashboard_id = :dashboard, home = :home, theme = :theme, locale = :locale, time_zone = :tz,
                               favorites = CAST(:favorites AS jsonb), tours_dismissed = CAST(:tours AS text[]), temperature_unit = :unit,
                               version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND user_id = :user AND version = :base""")
                .param("org", organizationId).param("user", userId).param("base", baseVersion)
                .param("dashboard", p.defaultDashboardId()).param("home", p.home()).param("theme", p.theme()).param("locale", p.locale())
                .param("tz", p.timeZone()).param("favorites", p.favoritesJson()).param("tours", Pg.textArray(p.toursDismissed()))
                .param("unit", p.temperatureUnit()).param("now", Pg.ts(now))
                .update();
    }

    /** 최근 본 항목만 바꾼다(설정 버전은 그대로: 화면 이동이 설정 편집과 충돌하지 않게) */
    public int updateRecent(long organizationId, long userId, String recentJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.user_dashboard_prefs SET recent = CAST(:recent AS jsonb), updated_at = :now
                         WHERE organization_id = :org AND user_id = :user""")
                .param("org", organizationId).param("user", userId).param("recent", recentJson).param("now", Pg.ts(now))
                .update();
    }

    /** 조직 기본값(시간대·언어·단위 체계). 조직 설정 행이 없으면 조직 행의 값 */
    public OrgDefaults findOrgDefaults(long organizationId) {
        return jdbc.sql("""
                        SELECT COALESCE(s.timezone, o.timezone, 'Asia/Seoul') AS timezone, COALESCE(s.locale, o.locale, 'ko') AS locale,
                               COALESCE(s.unit_system, 'METRIC') AS unit_system
                          FROM data2flow_core.organizations o
                          LEFT JOIN data2flow_core.org_settings s ON s.organization_id = o.id
                         WHERE o.id = :org""")
                .param("org", organizationId)
                .query((rs, n) -> new OrgDefaults(rs.getString("timezone"), rs.getString("locale"), rs.getString("unit_system")))
                .optional().orElse(new OrgDefaults("Asia/Seoul", "ko", "METRIC"));
    }

    /** 계정 언어(app_users.locale, IAM-03.02) */
    public Optional<String> findUserLocale(long organizationId, long userId) {
        return jdbc.sql("SELECT locale FROM data2flow_core.app_users WHERE organization_id = :org AND id = :user")
                .param("org", organizationId).param("user", userId).query(String.class).optional();
    }

    /** 보이는 ACTIVE 공간 이름(id → name) */
    public Map<Long, String> findSpaceNames(long organizationId, Collection<Long> ids, SpaceScope scope) {
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("""
                        SELECT id, name FROM data2flow_core.spaces
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status = 'ACTIVE'
                           AND (:all OR id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(List.copyOf(ids)))
                .param("all", scope.unrestricted()).param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((ResultSet rs) -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                });
        return names;
    }

    /** 보이는 기기 이름(삭제 제외, 공간 미배치 기기는 조직 단위로 본다) */
    public Map<Long, String> findDeviceNames(long organizationId, Collection<Long> ids, SpaceScope scope) {
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("""
                        SELECT id, name FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'
                           AND (:all OR space_id IS NULL OR space_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(List.copyOf(ids)))
                .param("all", scope.unrestricted()).param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((ResultSet rs) -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                });
        return names;
    }

    /** 저장 행. favorites·recent는 jsonb 문자열 */
    public record PrefsRow(String theme, String locale, String timeZone, String home, Long defaultDashboardId, String favoritesJson,
                           String recentJson, List<String> toursDismissed, String temperatureUnit, int version) {

        public static final PrefsRow DEFAULT = new PrefsRow("SYSTEM", null, null, "HOME", null, "[]", "[]", List.of(), null, 0);
    }

    public record OrgDefaults(String timeZone, String locale, String unitSystem) {
    }
}
