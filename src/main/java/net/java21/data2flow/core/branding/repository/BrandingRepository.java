package net.java21.data2flow.core.branding.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** 조직 브랜딩({@code branding_settings}, 조직당 1행)과 자산 원본({@code branding_assets}) (DSH-13.01) */
@Repository
public class BrandingRepository {

    private static final String SELECT = """
            SELECT organization_id, logo_light_object_key, logo_dark_object_key, favicon_object_key, primary_color, login_background_object_key,
                   login_message, mail_sender_name, mail_signature, public_theme, app_name, app_short_name, contrast_warning_acked, version, updated_at
              FROM data2flow_core.branding_settings
            """;

    private final JdbcClient jdbc;

    public BrandingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BrandingRow> findSettings(long organizationId) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org").param("org", organizationId).query(BrandingRepository::map).optional();
    }

    public int insertSettings(long organizationId, BrandingRow b, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.branding_settings (organization_id, logo_light_object_key, logo_dark_object_key, favicon_object_key,
                               primary_color, login_background_object_key, login_message, mail_sender_name, mail_signature, public_theme, app_name,
                               app_short_name, contrast_warning_acked, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :logoLight, :logoDark, :favicon, :color, :loginBg, :loginMessage, :sender, :signature, :theme, :appName,
                                :appShort, :acked, 1, :user, :user, :now, :now)
                        ON CONFLICT (organization_id) DO NOTHING""")
                .params(params(b)).param("org", organizationId).param("user", userId).param("now", Pg.ts(now)).update();
    }

    public int updateSettings(long organizationId, int baseVersion, BrandingRow b, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.branding_settings
                           SET logo_light_object_key = :logoLight, logo_dark_object_key = :logoDark, favicon_object_key = :favicon,
                               primary_color = :color, login_background_object_key = :loginBg, login_message = :loginMessage,
                               mail_sender_name = :sender, mail_signature = :signature, public_theme = :theme, app_name = :appName,
                               app_short_name = :appShort, contrast_warning_acked = :acked, version = version + 1, updated_by = :user,
                               updated_at = :now
                         WHERE organization_id = :org AND version = :base""")
                .params(params(b)).param("org", organizationId).param("user", userId).param("now", Pg.ts(now)).param("base", baseVersion)
                .update();
    }

    private static java.util.Map<String, Object> params(BrandingRow b) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("logoLight", b.logoLightKey());
        p.put("logoDark", b.logoDarkKey());
        p.put("favicon", b.faviconKey());
        p.put("color", b.primaryColor());
        p.put("loginBg", b.loginBackgroundKey());
        p.put("loginMessage", b.loginMessage());
        p.put("sender", b.mailSenderName());
        p.put("signature", b.mailSignature());
        p.put("theme", b.publicTheme());
        p.put("appName", b.appName());
        p.put("appShort", b.appShortName());
        p.put("acked", b.contrastWarningAcked());
        return p;
    }

    public long insertAsset(long organizationId, String kind, String contentType, byte[] data, String sha256, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.branding_assets (organization_id, kind, content_type, size_bytes, sha256, data, created_by, created_at)
                        VALUES (:org, :kind, :type, :size, :sha, :data, :user, :now) RETURNING id""")
                .param("org", organizationId).param("kind", kind).param("type", contentType).param("size", data.length).param("sha", sha256)
                .param("data", data).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<AssetRow> findAsset(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT id, organization_id, kind, content_type, size_bytes, sha256 FROM data2flow_core.branding_assets
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id)
                .query((rs, n) -> new AssetRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"), rs.getString("content_type"),
                        rs.getInt("size_bytes"), rs.getString("sha256"))).optional();
    }

    public Optional<byte[]> findAssetData(long organizationId, long id) {
        return jdbc.sql("SELECT data FROM data2flow_core.branding_assets WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query((rs, n) -> rs.getBytes("data")).optional();
    }

    static BrandingRow map(ResultSet rs, int n) throws SQLException {
        return new BrandingRow(rs.getString("logo_light_object_key"), rs.getString("logo_dark_object_key"), rs.getString("favicon_object_key"),
                rs.getString("primary_color"), rs.getString("login_background_object_key"), rs.getString("login_message"),
                rs.getString("mail_sender_name"), rs.getString("mail_signature"), rs.getString("public_theme"), rs.getString("app_name"),
                rs.getString("app_short_name"), rs.getBoolean("contrast_warning_acked"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    /** 자산 키는 {@code db:branding_assets/<id>} */
    public record BrandingRow(String logoLightKey, String logoDarkKey, String faviconKey, String primaryColor, String loginBackgroundKey,
                              String loginMessage, String mailSenderName, String mailSignature, String publicTheme, String appName,
                              String appShortName, boolean contrastWarningAcked, int version, Instant updatedAt) {
        public static final BrandingRow DEFAULT = new BrandingRow(null, null, null, null, null, null, null, null, "AUTO", null, null, false,
                0, null);
    }

    public record AssetRow(long id, long organizationId, String kind, String contentType, int sizeBytes, String sha256) {
    }
}
