package net.java21.data2flow.core.account.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.common.CoreErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 비밀번호 정책(IAM-02.02, BR-IAM-03): 10~128자, 로그인 아이디·이메일 로컬 부분 포함 금지, 유출 목록에 없음, 최근 3개와 다름.
 * 지키지 않으면 사유를 담아 400 {@code PASSWORD_POLICY_VIOLATION}(errors[].code = 사유 코드)으로 거부한다.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    /** 사유 코드 */
    public enum Violation { TOO_SHORT, TOO_LONG, CONTAINS_LOGIN_ID, CONTAINS_EMAIL, BREACHED, REUSED }

    private final BreachedPasswordChecker breached;
    private final PasswordEncoder encoder;
    private final MessageSource messages;

    public PasswordPolicy(BreachedPasswordChecker breached, PasswordEncoder encoder, MessageSource messages) {
        this.breached = breached;
        this.encoder = encoder;
        this.messages = messages;
    }

    /**
     * @param field         오류에 적을 필드 이름(password, newPassword …)
     * @param recentHashes  최근 비밀번호 해시(재사용 검사, 없으면 빈 목록)
     */
    public void check(String field, String password, String loginId, String email, List<String> recentHashes) {
        List<Violation> violations = violations(password, loginId, email, recentHashes);
        if (violations.isEmpty()) {
            return;
        }
        Locale locale = LocaleContextHolder.getLocale();
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (Violation v : violations) {
            errors.add(new FieldErrorDetail(field, v.name(), reason(v, locale)));
        }
        String reasons = errors.stream().map(FieldErrorDetail::message).collect(Collectors.joining(", "));
        throw new BusinessException(CoreErrorCode.PASSWORD_POLICY_VIOLATION, errors, reasons);
    }

    List<Violation> violations(String password, String loginId, String email, List<String> recentHashes) {
        List<Violation> result = new ArrayList<>();
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            result.add(Violation.TOO_SHORT);
        }
        if (pw.length() > MAX_LENGTH) {
            result.add(Violation.TOO_LONG);
            return result;
        }
        String lower = pw.toLowerCase(Locale.ROOT);
        if (loginId != null && loginId.length() >= 3 && lower.contains(loginId.toLowerCase(Locale.ROOT))) {
            result.add(Violation.CONTAINS_LOGIN_ID);
        }
        if (email != null && email.indexOf('@') >= 3) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (lower.contains(local)) {
                result.add(Violation.CONTAINS_EMAIL);
            }
        }
        if (!pw.isEmpty() && breached.isBreached(pw)) {
            result.add(Violation.BREACHED);
        }
        if (recentHashes != null && result.isEmpty()) {
            for (String hash : recentHashes) {
                if (hash != null && encoder.matches(pw, hash)) {
                    result.add(Violation.REUSED);
                    break;
                }
            }
        }
        return result;
    }

    private String reason(Violation v, Locale locale) {
        return messages.getMessage("password.policy." + v.name(), new Object[]{MIN_LENGTH, MAX_LENGTH}, v.name(), locale);
    }
}
