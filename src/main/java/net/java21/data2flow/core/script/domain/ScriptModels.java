package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 스크립트 도메인 값과 규칙(spec/detail/SCR/domain-model.md, BR-SCR-03·04·14).
 */
public final class ScriptModels {

    /** 코드 크기 한도(SCR-04.03, BR-SCR-14): UTF-8 바이트 기준 64KB */
    public static final int MAX_CODE_BYTES = 64 * 1024;
    /** 조직당 스크립트 한도(SCR-04.03) */
    public static final int MAX_SCRIPTS_PER_ORGANIZATION = 300;
    /** 스크립트당 보관 버전 수(SCR-04.03, BR-SCR-14) */
    public static final int KEEP_VERSIONS = 50;
    /** 테스트 실행 입력 한도(UI-SCR-02 직접 입력, TC-SCR-045): 256KB */
    public static final int MAX_TEST_INPUT_BYTES = 256 * 1024;

    private ScriptModels() {
    }

    public enum ScriptKind {
        DECODE, TRANSFORM
    }

    public enum ScriptStatus {
        ENABLED, DISABLED, AUTO_DISABLED
    }

    public enum VersionStatus {
        DRAFT, ACTIVE, ARCHIVED
    }

    /** 연결 대상. DECODE는 SOURCE만, TRANSFORM은 MODEL·DEVICE(실행 순서 모델 → 기기, BR-SCR-03) */
    public enum TargetType {
        SOURCE, MODEL, DEVICE
    }

    /** 실패 정책(SCR-02.03, BR-SCR-04). 기본 FAIL_OPEN */
    public enum FailurePolicy {
        FAIL_OPEN, FAIL_CLOSED
    }

    public static ScriptKind kind(String raw) {
        return parse(ScriptKind.class, raw, "kind");
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "NotBlank");
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid(field, "Enum");
        }
    }

    /** 400 INVALID_REQUEST(errors[0].field) */
    public static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    /** UTF-8 바이트 수가 64KB를 넘으면 400 SCRIPT_CODE_TOO_LARGE(TC-SCR-072: 65,536 허용, 65,537 거부) */
    public static String requireCodeSize(String code) {
        if (code == null) {
            throw invalid("code", "NotNull");
        }
        if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_CODE_TOO_LARGE);
        }
        return code;
    }

    /** code_sha256(실행 엔진 캐시 키) */
    public static String sha256(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 연결 규칙(SCR-01.01·01.02): DECODE → SOURCE, TRANSFORM → MODEL·DEVICE */
    public static boolean bindingAllowed(ScriptKind kind, TargetType target) {
        return kind == ScriptKind.DECODE ? target == TargetType.SOURCE : target != TargetType.SOURCE;
    }
}
