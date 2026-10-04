package net.java21.data2flow.core.branding.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.branding.domain.BrandingAssetValidator;
import net.java21.data2flow.core.branding.domain.Contrast;
import net.java21.data2flow.core.branding.dto.BrandingDtos.AssetFile;
import net.java21.data2flow.core.branding.dto.BrandingDtos.AssetResponse;
import net.java21.data2flow.core.branding.dto.BrandingDtos.BrandingResponse;
import net.java21.data2flow.core.branding.dto.BrandingDtos.MailBranding;
import net.java21.data2flow.core.branding.dto.BrandingDtos.PublicBranding;
import net.java21.data2flow.core.branding.repository.BrandingRepository;
import net.java21.data2flow.core.branding.repository.BrandingRepository.AssetRow;
import net.java21.data2flow.core.branding.repository.BrandingRepository.BrandingRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 조직 브랜딩(DSH-13.01, BR-DSH-20, API-DSH-25). 웹 헤더·로그인 화면·메일·공개 화면·PDF 리포트가 같은 값을 쓴다.
 * <ul>
 *   <li>조회 DASHBOARD_READ(VIEWER+), 변경·자산 올리기 BRANDING_MANAGE(ADMIN). 동시 수정은 baseVersion(409 VERSION_CONFLICT)</li>
 *   <li>주 색상이 흰 배경 대비 4.5:1 미만이면 {@code contrastWarningAcked=true}로 확인해야 저장된다(400 INVALID_REQUEST
 *       {@code errors[{field: primaryColor, code: CONTRAST_LOW, message: "1.07:1"}]})</li>
 *   <li>자산 URL은 공개 경로 {@code /api/v1/core/public/branding/assets/{id}}. 이 배포의 조직이 지금 쓰는 자산만 내준다(남의 조직·쓰지 않는 자산 404)</li>
 * </ul>
 */
@Service
public class BrandingService {

    static final Set<String> THEMES = Set.of("LIGHT", "DARK", "AUTO");
    static final String KEY_PREFIX = "db:branding_assets/";
    static final String PUBLIC_ASSET_PATH = "/api/v1/core/public/branding/assets/";

    private final RoleChecker roleChecker;
    private final BrandingRepository repository;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final Clock clock;

    public BrandingService(RoleChecker roleChecker, BrandingRepository repository, DeploymentOrganization deployment, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.deployment = deployment;
        this.audits = audits;
        this.clock = clock;
    }

    /** GET /core/branding */
    @Transactional(readOnly = true)
    public BrandingResponse get() {
        roleChecker.require(Permission.DASHBOARD_READ);
        return response(repository.findSettings(roleChecker.currentUser().organizationId()).orElse(BrandingRow.DEFAULT));
    }

    /** PUT /core/branding — 온 키만 바꾼다 */
    @Transactional
    public BrandingResponse update(JsonNode body) {
        roleChecker.require(Permission.BRANDING_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        int base = (int) VersionCheck.baseVersion(body);
        Optional<BrandingRow> stored = repository.findSettings(org);
        BrandingRow current = stored.orElse(BrandingRow.DEFAULT);
        VersionCheck.require(base, current.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        String logoLight = asset(body, "logoLightAssetId", current.logoLightKey(), Set.of("LOGO_LIGHT", "LOGO_DARK"), org, errors);
        String logoDark = asset(body, "logoDarkAssetId", current.logoDarkKey(), Set.of("LOGO_LIGHT", "LOGO_DARK"), org, errors);
        String favicon = asset(body, "faviconAssetId", current.faviconKey(), Set.of("FAVICON"), org, errors);
        String loginBg = asset(body, "loginBackgroundAssetId", current.loginBackgroundKey(), Set.of("LOGIN_BACKGROUND"), org, errors);
        String color = text(body, "primaryColor", current.primaryColor(), 7, errors);
        if (color != null && !color.matches("#[0-9A-Fa-f]{6}")) {
            errors.add(new FieldErrorDetail("primaryColor", "PATTERN", "#RRGGBB"));
            color = current.primaryColor();
        } else if (color != null) {
            color = color.toUpperCase(Locale.ROOT);
        }
        String loginMessage = text(body, "loginMessage", current.loginMessage(), 300, errors);
        String sender = text(body, "mailSenderName", current.mailSenderName(), 100, errors);
        String signature = text(body, "mailSignature", current.mailSignature(), 1000, errors);
        String appName = text(body, "appName", current.appName(), 30, errors);
        String appShort = text(body, "appShortName", current.appShortName(), 12, errors);
        String theme = text(body, "publicTheme", current.publicTheme(), 6, errors);
        theme = theme == null ? "AUTO" : theme.toUpperCase(Locale.ROOT);
        if (!THEMES.contains(theme)) {
            errors.add(new FieldErrorDetail("publicTheme", "INVALID", String.join("|", THEMES)));
        }
        boolean acked = body.path("contrastWarningAcked").asBoolean(false);
        if (errors.isEmpty() && color != null && !color.equals(current.primaryColor())) {
            double ratio = Contrast.againstWhite(color);
            if (ratio < Contrast.AA && !acked) {
                errors.add(new FieldErrorDetail("primaryColor", "CONTRAST_LOW", String.format(Locale.ROOT, "%.2f:1", ratio)));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        BrandingRow next = new BrandingRow(logoLight, logoDark, favicon, color, loginBg, loginMessage, sender, signature, theme, appName, appShort,
                acked, current.version(), null);
        Instant now = clock.instant();
        if (stored.isEmpty()) {
            VersionCheck.requireUpdated(repository.insertSettings(org, next, user.userId(), now));
        } else {
            VersionCheck.requireUpdated(repository.updateSettings(org, base, next, user.userId(), now));
        }
        audits.record(audits.event(org, "BRANDING_UPDATED").actor(user).target("BRANDING", Long.toString(org))
                .detail("primaryColor", color).detail("publicTheme", theme));
        return response(repository.findSettings(org).orElseThrow());
    }

    /** POST /core/branding/assets — 201 */
    @Transactional
    public AssetResponse upload(String kindParam, byte[] data) {
        roleChecker.require(Permission.BRANDING_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        String kind = kindParam == null ? "" : kindParam.strip().toUpperCase(Locale.ROOT);
        String contentType = BrandingAssetValidator.validate(kind, data);
        long id = repository.insertAsset(user.organizationId(), kind, contentType, data, sha256(data), user.userId(), clock.instant());
        audits.record(audits.event(user.organizationId(), "BRANDING_ASSET_UPLOADED").actor(user).target("BRANDING_ASSET", Long.toString(id))
                .detail("kind", kind).detail("sizeBytes", data.length));
        return new AssetResponse(Long.toString(id), kind, PUBLIC_ASSET_PATH + id, contentType, data.length);
    }

    /** GET /core/branding/assets/{asset-id}(로그인 사용자, 같은 조직만) */
    @Transactional(readOnly = true)
    public AssetFile asset(long assetId) {
        roleChecker.require(Permission.DASHBOARD_READ);
        long org = roleChecker.currentUser().organizationId();
        return file(org, assetId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** GET /core/public/branding/assets/{asset-id}: 이 배포 조직이 지금 설정에 쓰는 자산만 */
    @Transactional(readOnly = true)
    public AssetFile publicAsset(long assetId) {
        long org = deployment.current().map(o -> o.id()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        BrandingRow b = repository.findSettings(org).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        String key = KEY_PREFIX + assetId;
        if (!List.of(nz(b.logoLightKey()), nz(b.logoDarkKey()), nz(b.faviconKey()), nz(b.loginBackgroundKey())).contains(key)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return file(org, assetId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** GET /core/public/branding(로그인 화면): 이 배포 조직의 브랜딩. 조직을 정할 수 없으면 기본값 */
    @Transactional(readOnly = true)
    public PublicBranding publicBranding() {
        BrandingRow b = deployment.current().flatMap(o -> repository.findSettings(o.id())).orElse(BrandingRow.DEFAULT);
        return new PublicBranding(url(b.logoLightKey()), url(b.logoDarkKey()), url(b.faviconKey()), b.primaryColor(), url(b.loginBackgroundKey()),
                b.loginMessage(), b.publicTheme(), b.appName(), b.appShortName());
    }

    /** 공유 링크 화면(API-DSH-15)의 branding 요약 */
    @Transactional(readOnly = true)
    public Map<String, Object> publicSummary(long organizationId) {
        BrandingRow b = repository.findSettings(organizationId).orElse(BrandingRow.DEFAULT);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logoUrl", url(b.logoLightKey()));
        m.put("primaryColor", b.primaryColor());
        m.put("publicTheme", b.publicTheme());
        return m;
    }

    /** 메일 발신 이름·서명(권한 검사 없음, MailService용). 설정이 없으면 빈 값 */
    @Transactional(readOnly = true)
    public Optional<MailBranding> mailBranding(long organizationId) {
        return repository.findSettings(organizationId)
                .filter(b -> b.mailSenderName() != null || b.mailSignature() != null)
                .map(b -> new MailBranding(b.mailSenderName(), b.mailSignature()));
    }

    private Optional<AssetFile> file(long org, long assetId) {
        Optional<AssetRow> row = repository.findAsset(org, assetId);
        return row.flatMap(a -> repository.findAssetData(org, assetId).map(d -> new AssetFile(a.contentType(), a.sha256(), d)));
    }

    private String asset(JsonNode body, String field, String current, Set<String> kinds, long org, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode n = body.get(field);
        if (n.isNull() || n.asString("").isBlank()) {
            return null;
        }
        String raw = n.asString("");
        if (!raw.matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, "INVALID", raw));
            return current;
        }
        Optional<AssetRow> a = repository.findAsset(org, Long.parseLong(raw));
        if (a.isEmpty() || !kinds.contains(a.get().kind())) {
            errors.add(new FieldErrorDetail(field, "NOT_FOUND", raw));
            return current;
        }
        return KEY_PREFIX + raw;
    }

    private static String text(JsonNode body, String field, String current, int max, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode n = body.get(field);
        if (n.isNull()) {
            return null;
        }
        String v = n.asString("").strip();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > max) {
            errors.add(new FieldErrorDetail(field, "SIZE", "0~" + max));
            return current;
        }
        return v;
    }

    private BrandingResponse response(BrandingRow b) {
        return new BrandingResponse(url(b.logoLightKey()), url(b.logoDarkKey()), url(b.faviconKey()), b.primaryColor(),
                url(b.loginBackgroundKey()), b.loginMessage(), b.mailSenderName(), b.mailSignature(), b.publicTheme(), b.appName(),
                b.appShortName(), b.primaryColor() == null ? null : Contrast.round(Contrast.againstWhite(b.primaryColor())), b.version());
    }

    static String url(String key) {
        return key == null || !key.startsWith(KEY_PREFIX) ? null : PUBLIC_ASSET_PATH + key.substring(KEY_PREFIX.length());
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", ex);
        }
    }
}
