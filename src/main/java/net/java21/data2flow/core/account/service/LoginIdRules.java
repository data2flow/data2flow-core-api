package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.common.CoreErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 로그인 아이디 규칙(IAM-02.01, BR-IAM-02): 영문 소문자·숫자·{@code . _ -} 4~30자, 저장은 소문자, 예약어 금지.
 */
public final class LoginIdRules {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9._-]{4,30}$");
    private static final Set<String> RESERVED = Set.of("admin", "system", "root", "support");

    private LoginIdRules() {
    }

    /** 소문자로 바꾼 아이디. 형식이 틀리거나 예약어이면 400 LOGIN_ID_INVALID */
    public static String normalize(String raw) {
        String reason = rejectReason(raw);
        if (reason != null) {
            throw new BusinessException(CoreErrorCode.LOGIN_ID_INVALID, List.of(new FieldErrorDetail("loginId", reason, null)));
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    /** 거부 사유(FORMAT, RESERVED) 또는 null */
    public static String rejectReason(String raw) {
        if (raw == null) {
            return "FORMAT";
        }
        String id = raw.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(id).matches() || id.startsWith("deleted-")) {
            return "FORMAT";
        }
        if (RESERVED.contains(id)) {
            return "RESERVED";
        }
        return null;
    }
}
