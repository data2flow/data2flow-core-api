package net.java21.data2flow.core.branding.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.board.domain.BoardErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 브랜딩 자산 검사(DSH-13.01, BR-DSH-20, TC-DSH-112). PNG·SVG(파비콘은 ICO도, 로그인 배경은 JPEG도) 1MB 이하.
 * SVG는 스크립트({@code <script>}), 이벤트 속성({@code on*=}), 외부 참조(http·https·//·data:text 등 {@code href}·{@code src}·{@code url(...)}),
 * {@code <foreignObject>}·{@code <iframe>}·{@code <embed>}·{@code <object>}, {@code javascript:} URL, DOCTYPE·ENTITY(XXE)를 거부한다.
 * 같은 문서 안 참조({@code href="#id"})와 {@code data:image/png;base64,…}는 허용한다. 위반은 400 BRANDING_ASSET_INVALID.
 */
public final class BrandingAssetValidator {

    public static final int MAX_BYTES = 1024 * 1024;
    public static final Set<String> KINDS = Set.of("LOGO_LIGHT", "LOGO_DARK", "FAVICON", "LOGIN_BACKGROUND");

    private static final Pattern SCRIPT = Pattern.compile("<\\s*script\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_ATTR = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_TAG = Pattern.compile("<\\s*(foreignObject|iframe|embed|object|audio|video|handler|listener)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL = Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF = Pattern.compile("(?:href|src)\\s*=\\s*[\"']\\s*([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?\\s*([^)'\"]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPORT = Pattern.compile("@import", Pattern.CASE_INSENSITIVE);

    private BrandingAssetValidator() {
    }

    /** 검사하고 저장할 content type을 돌려준다 */
    public static String validate(String kind, byte[] data) {
        if (!KINDS.contains(kind)) {
            throw invalid("kind", "INVALID");
        }
        if (data == null || data.length == 0) {
            throw invalid("file", "REQUIRED");
        }
        if (data.length > MAX_BYTES) {
            throw invalid("file", "TOO_LARGE");
        }
        if (isPng(data)) {
            return "image/png";
        }
        if ("LOGIN_BACKGROUND".equals(kind) && isJpeg(data)) {
            return "image/jpeg";
        }
        if ("FAVICON".equals(kind) && isIco(data)) {
            return "image/x-icon";
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.toLowerCase(Locale.ROOT).contains("<svg")) {
            checkSvg(text);
            return "image/svg+xml";
        }
        throw invalid("file", "UNSUPPORTED_FORMAT");
    }

    static void checkSvg(String svg) {
        if (SCRIPT.matcher(svg).find() || EVENT_ATTR.matcher(svg).find() || DANGEROUS_TAG.matcher(svg).find() || JS_URL.matcher(svg).find()
                || DOCTYPE.matcher(svg).find() || IMPORT.matcher(svg).find()) {
            throw invalid("file", "UNSAFE_SVG");
        }
        var refs = REF.matcher(svg);
        while (refs.find()) {
            if (!safeRef(refs.group(1))) {
                throw invalid("file", "EXTERNAL_REFERENCE");
            }
        }
        var urls = CSS_URL.matcher(svg);
        while (urls.find()) {
            if (!safeRef(urls.group(1))) {
                throw invalid("file", "EXTERNAL_REFERENCE");
            }
        }
    }

    private static boolean safeRef(String raw) {
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return v.isEmpty() || v.startsWith("#") || v.startsWith("data:image/png") || v.startsWith("data:image/jpeg")
                || v.startsWith("data:image/gif");
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    private static boolean isJpeg(byte[] d) {
        return d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
    }

    private static boolean isIco(byte[] d) {
        return d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 1 && d[3] == 0;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(BoardErrorCode.BRANDING_ASSET_INVALID, List.of(new FieldErrorDetail(field, code, null)));
    }
}
