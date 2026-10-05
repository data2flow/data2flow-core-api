package net.java21.data2flow.core.apitoken.domain;

import net.java21.data2flow.core.common.Tokens;

/**
 * 장기 토큰 원문과 해시(design/auth.md §4, BR-IAM-18): 불투명 {@code data2flow_} + base64url(32바이트). DB에는 SHA-256 hex만
 * ({@code api_tokens.token_hash}) 두고, 목록 표시용 앞 12자({@code data2flow_} + 2자)를 {@code token_prefix}에 둔다.
 * auth introspection은 같은 방식(원문 UTF-8의 SHA-256 소문자 hex)으로 해시를 만들어 API-IAM-46에 묻는다.
 */
public final class ApiTokenSecrets {

    public static final String PREFIX = "data2flow_";
    static final int DISPLAY_PREFIX_LENGTH = 12;

    private ApiTokenSecrets() {
    }

    /** 새 원문 */
    public static String newToken() {
        return PREFIX + Tokens.newToken();
    }

    /** 원문의 SHA-256 hex(64자) */
    public static String hash(String token) {
        return Tokens.sha256Hex(token);
    }

    /** 목록 표시용 앞부분 */
    public static String displayPrefix(String token) {
        return token.substring(0, DISPLAY_PREFIX_LENGTH);
    }
}
