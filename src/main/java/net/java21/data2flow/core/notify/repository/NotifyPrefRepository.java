package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** 사용자 알림 수신 설정(user_notify_prefs, OPS-06.05·RUL-05.04)과 메신저 연결(user_messenger_links·messenger_link_codes, RUL-05.02) */
@Repository
public class NotifyPrefRepository {

    private final JdbcClient jdbc;

    public NotifyPrefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PrefRow(long userId, List<String> channels, String minSeverity, LocalTime dndFrom, LocalTime dndTo,
                          boolean dndAllowCritical, String locale, int version, Instant updatedAt) {
    }

    public record LinkRow(long id, long organizationId, long userId, String channel, String externalUserId, Instant linkedAt) {
    }

    public record CodeRow(long id, long organizationId, long userId, String channel, Instant expiresAt, Instant usedAt) {
    }

    public Optional<PrefRow> findPref(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT user_id, channels, min_severity, dnd_from, dnd_to, dnd_allow_critical, locale, version, updated_at
                          FROM data2flow_core.user_notify_prefs WHERE organization_id = :org AND user_id = :user""")
                .param("org", organizationId).param("user", userId)
                .query((rs, n) -> new PrefRow(rs.getLong("user_id"), Pg.stringList(rs, "channels"), rs.getString("min_severity"),
                        rs.getObject("dnd_from", LocalTime.class), rs.getObject("dnd_to", LocalTime.class),
                        rs.getBoolean("dnd_allow_critical"), rs.getString("locale"), rs.getInt("version"), Pg.instant(rs, "updated_at")))
                .optional();
    }

    /** 저장(없으면 만들고, 있으면 baseVersion이 같을 때만). 바뀐 행 수 */
    public int upsertPref(long organizationId, long userId, Integer baseVersion, List<String> channels, String minSeverity, LocalTime dndFrom,
                          LocalTime dndTo, boolean dndAllowCritical, String locale, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.user_notify_prefs (user_id, organization_id, channels, min_severity, dnd_from, dnd_to,
                               dnd_allow_critical, locale, version, updated_at)
                        VALUES (:user, :org, CAST(:channels AS text[]), :min, :from, :to, :critical, :locale, 1, :now)
                        ON CONFLICT (user_id) DO UPDATE SET channels = EXCLUDED.channels, min_severity = EXCLUDED.min_severity,
                               dnd_from = EXCLUDED.dnd_from, dnd_to = EXCLUDED.dnd_to, dnd_allow_critical = EXCLUDED.dnd_allow_critical,
                               locale = EXCLUDED.locale, version = data2flow_core.user_notify_prefs.version + 1, updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.user_notify_prefs.organization_id = :org
                           AND (CAST(:base AS integer) IS NULL OR data2flow_core.user_notify_prefs.version = :base)""")
                .param("user", userId).param("org", organizationId).param("channels", Pg.textArray(channels)).param("min", minSeverity)
                .param("from", dndFrom).param("to", dndTo).param("critical", dndAllowCritical).param("locale", locale)
                .param("now", Pg.ts(now)).param("base", baseVersion).update();
    }

    public List<LinkRow> listLinks(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, channel, external_user_id, linked_at FROM data2flow_core.user_messenger_links
                         WHERE organization_id = :org AND user_id = :user ORDER BY channel""")
                .param("org", organizationId).param("user", userId).query(NotifyPrefRepository::link).list();
    }

    /** 외부 계정으로 연결 찾기(콜백). 채널·외부 ID는 전역 UNIQUE지만 조직 조건을 함께 건다 */
    public Optional<LinkRow> findLinkByExternal(long organizationId, String channel, String externalUserId) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, channel, external_user_id, linked_at FROM data2flow_core.user_messenger_links
                         WHERE organization_id = :org AND channel = :channel AND external_user_id = :ext""")
                .param("org", organizationId).param("channel", channel).param("ext", externalUserId).query(NotifyPrefRepository::link)
                .optional();
    }

    @net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt("메신저 콜백은 외부 계정만 알므로 채널·외부 ID(전역 UNIQUE)로 찾고 배포 조직인지 호출자가 본다")
    public Optional<LinkRow> findLinkByExternalAnyOrganization(String channel, String externalUserId) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, channel, external_user_id, linked_at FROM data2flow_core.user_messenger_links
                         WHERE channel = :channel AND external_user_id = :ext""")
                .param("channel", channel).param("ext", externalUserId).query(NotifyPrefRepository::link).optional();
    }

    @net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt("연결 코드(해시, 전역 UNIQUE)로 찾고 배포 조직인지 호출자가 본다")
    public Optional<CodeRow> lockCodeAnyOrganization(String codeHash) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, channel, expires_at, used_at FROM data2flow_core.messenger_link_codes
                         WHERE code_hash = :hash FOR UPDATE""")
                .param("hash", codeHash)
                .query((rs, n) -> new CodeRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("channel"),
                        Pg.instant(rs, "expires_at"), Pg.instant(rs, "used_at"))).optional();
    }

    /** 활성 사용자(ID 또는 역할로) */
    public List<UserProfile> listProfiles(long organizationId, java.util.Collection<Long> userIds, java.util.Collection<String> roles) {
        return jdbc.sql("""
                        SELECT u.id, u.name, u.status, u.locale, u.timezone, r.role FROM data2flow_core.app_users u
                          LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id AND r.organization_id = u.organization_id
                         WHERE u.organization_id = :org AND (u.id = ANY(CAST(:ids AS bigint[])) OR r.role = ANY(CAST(:roles AS text[])))
                         ORDER BY u.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(userIds)).param("roles", Pg.textArray(roles))
                .query((rs, n) -> new UserProfile(rs.getLong("id"), rs.getString("name"), rs.getString("status"), rs.getString("locale"),
                        rs.getString("timezone"), rs.getString("role"))).list();
    }

    public int deleteLink(long organizationId, long userId, String channel) {
        return jdbc.sql("DELETE FROM data2flow_core.user_messenger_links WHERE organization_id = :org AND user_id = :user AND channel = :channel")
                .param("org", organizationId).param("user", userId).param("channel", channel).update();
    }

    /** 연결(같은 외부 계정이 다른 사용자에 연결돼 있었으면 옮긴다: 외부 계정 1 ↔ 사용자 1) */
    public void upsertLink(long organizationId, long userId, String channel, String externalUserId, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.user_messenger_links WHERE channel = :channel AND external_user_id = :ext AND organization_id = :org")
                .param("channel", channel).param("ext", externalUserId).param("org", organizationId).update();
        jdbc.sql("""
                        INSERT INTO data2flow_core.user_messenger_links (organization_id, user_id, channel, external_user_id, linked_at)
                        VALUES (:org, :user, :channel, :ext, :now)
                        ON CONFLICT (user_id, channel) DO UPDATE SET external_user_id = EXCLUDED.external_user_id, linked_at = EXCLUDED.linked_at""")
                .param("org", organizationId).param("user", userId).param("channel", channel).param("ext", externalUserId)
                .param("now", Pg.ts(now)).update();
    }

    public void insertCode(long organizationId, long userId, String channel, String codeHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.messenger_link_codes (organization_id, user_id, channel, code_hash, expires_at, created_at)
                        VALUES (:org, :user, :channel, :hash, :expires, :now)""")
                .param("org", organizationId).param("user", userId).param("channel", channel).param("hash", codeHash)
                .param("expires", Pg.ts(expiresAt)).param("now", Pg.ts(now)).update();
    }

    /** 코드 잠금 조회(일회용) */
    public Optional<CodeRow> lockCode(long organizationId, String codeHash) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, channel, expires_at, used_at FROM data2flow_core.messenger_link_codes
                         WHERE organization_id = :org AND code_hash = :hash FOR UPDATE""")
                .param("org", organizationId).param("hash", codeHash)
                .query((rs, n) -> new CodeRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("channel"),
                        Pg.instant(rs, "expires_at"), Pg.instant(rs, "used_at"))).optional();
    }

    public void updateCodeUsed(long organizationId, long id, Instant now) {
        jdbc.sql("UPDATE data2flow_core.messenger_link_codes SET used_at = :now WHERE organization_id = :org AND id = :id")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 사용자 기본 정보(알림 발송용) */
    public record UserProfile(long userId, String name, String status, String locale, String timezone, String role) {
    }

    public java.util.Optional<UserProfile> findProfile(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT u.id, u.name, u.status, u.locale, u.timezone, r.role FROM data2flow_core.app_users u
                          LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id AND r.organization_id = u.organization_id
                         WHERE u.organization_id = :org AND u.id = :id""")
                .param("org", organizationId).param("id", userId)
                .query((rs, n) -> new UserProfile(rs.getLong("id"), rs.getString("name"), rs.getString("status"), rs.getString("locale"),
                        rs.getString("timezone"), rs.getString("role"))).optional();
    }

    /** 역할의 활성 사용자 ID */
    public List<Long> listActiveUsersByRole(long organizationId, String role) {
        return jdbc.sql("""
                        SELECT u.id FROM data2flow_core.app_users u JOIN data2flow_core.user_roles r ON r.user_id = u.id
                               AND r.organization_id = u.organization_id
                         WHERE u.organization_id = :org AND u.status = 'ACTIVE' AND r.role = :role ORDER BY u.id""")
                .param("org", organizationId).param("role", role).query(Long.class).list();
    }

    static LinkRow link(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LinkRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("channel"),
                rs.getString("external_user_id"), Pg.instant(rs, "linked_at"));
    }
}
