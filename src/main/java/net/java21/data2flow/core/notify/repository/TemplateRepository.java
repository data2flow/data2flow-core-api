package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 알림 템플릿(notification_templates, RUL-05.01). 시스템 기본(organization_id=0, builtin)은 되돌리기 기준이고, 조직이 고치면 조직 행을
 * 따로 만든다(조직 행이 있으면 그것, 없으면 기본). 템플릿 ID는 행 ID이고, 기본 행을 고치면 같은 키·채널·언어의 조직 행을 만든다.
 */
@Repository
public class TemplateRepository {

    static final String COLUMNS = "t.id, t.organization_id, t.template_key, t.channel, t.locale, t.subject, t.body, t.builtin, t.version, t.updated_at";

    private final JdbcClient jdbc;

    public TemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TemplateRow(long id, long organizationId, String templateKey, String channel, String locale, String subject, String body,
                              boolean builtin, int version, Instant updatedAt) {
    }

    /** 조직에서 보이는 템플릿: 조직 행 + 조직 행이 없는 기본 행 */
    public List<TemplateRow> listEffective(long organizationId, String channel, String locale) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_templates t"
                        + " WHERE (t.organization_id = :org OR (t.organization_id = 0 AND NOT EXISTS ("
                        + "   SELECT 1 FROM data2flow_core.notification_templates o WHERE o.organization_id = :org"
                        + "      AND o.template_key = t.template_key AND o.channel = t.channel AND o.locale = t.locale)))"
                        + " AND (CAST(:channel AS text) IS NULL OR t.channel = :channel) AND (CAST(:locale AS text) IS NULL OR t.locale = :locale)"
                        + " ORDER BY t.template_key, t.channel, t.locale")
                .param("org", organizationId).param("channel", channel).param("locale", locale).query(TemplateRepository::map).list();
    }

    /** 조직 행 또는 기본 행(id로) */
    public Optional<TemplateRow> findVisible(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_templates t WHERE t.id = :id AND t.organization_id IN (0, :org)")
                .param("id", id).param("org", organizationId).query(TemplateRepository::map).optional();
    }

    public Optional<TemplateRow> findOrgRow(long organizationId, String key, String channel, String locale) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_templates t WHERE t.organization_id = :org"
                        + " AND t.template_key = :key AND t.channel = :channel AND t.locale = :locale")
                .param("org", organizationId).param("key", key).param("channel", channel).param("locale", locale)
                .query(TemplateRepository::map).optional();
    }

    public Optional<TemplateRow> findBuiltin(String key, String channel, String locale, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_templates t WHERE t.organization_id = 0 AND :org > 0"
                        + " AND t.template_key = :key AND t.channel = :channel AND t.locale = :locale")
                .param("key", key).param("channel", channel).param("locale", locale).param("org", organizationId)
                .query(TemplateRepository::map).optional();
    }

    public long insertOrgRow(long organizationId, String key, String channel, String locale, String subject, String body, long userId,
                             Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.notification_templates (organization_id, template_key, channel, locale, subject, body, builtin,
                               version, updated_by, created_at, updated_at)
                        VALUES (:org, :key, :channel, :locale, :subject, :body, false, 1, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("key", key).param("channel", channel).param("locale", locale).param("subject", subject)
                .param("body", body).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateOrgRow(long organizationId, long id, Integer baseVersion, String subject, String body, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.notification_templates SET subject = :subject, body = :body, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND (CAST(:base AS integer) IS NULL OR version = :base)""")
                .param("subject", subject).param("body", body).param("user", userId).param("now", Pg.ts(now)).param("org", organizationId)
                .param("id", id).param("base", baseVersion).update();
    }

    public int deleteOrgRow(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.notification_templates WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static TemplateRow map(ResultSet rs, int n) throws SQLException {
        return new TemplateRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("template_key"), rs.getString("channel"),
                rs.getString("locale"), rs.getString("subject"), rs.getString("body"), rs.getBoolean("builtin"), rs.getInt("version"),
                Pg.instant(rs, "updated_at"));
    }
}
