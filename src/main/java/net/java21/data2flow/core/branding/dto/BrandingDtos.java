package net.java21.data2flow.core.branding.dto;

/** 브랜딩 API(design/api/DSH-api.md API-DSH-25) */
public final class BrandingDtos {

    private BrandingDtos() {
    }

    /** GET·PUT /core/branding 응답. URL은 공개 경로(로그인 화면·공개 화면에서도 쓴다) */
    public record BrandingResponse(String logoLightUrl, String logoDarkUrl, String faviconUrl, String primaryColor, String loginBackgroundUrl,
                                   String loginMessage, String mailSenderName, String mailSignature, String publicTheme, String appName,
                                   String appShortName, Double contrastRatio, int version) {
    }

    /** 로그인 화면·공개 화면용(메일 정보 없음) */
    public record PublicBranding(String logoLightUrl, String logoDarkUrl, String faviconUrl, String primaryColor, String loginBackgroundUrl,
                                 String loginMessage, String publicTheme, String appName, String appShortName) {
    }

    /** POST /core/branding/assets 201 */
    public record AssetResponse(String assetId, String kind, String url, String contentType, int sizeBytes) {
    }

    /** 자산 내려받기 */
    public record AssetFile(String contentType, String sha256, byte[] data) {
    }

    /** 메일 발신 이름·서명(MailService) */
    public record MailBranding(String senderName, String signature) {
    }
}
