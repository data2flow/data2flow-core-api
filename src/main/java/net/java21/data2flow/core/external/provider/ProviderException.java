package net.java21.data2flow.core.external.provider;

/**
 * 외부 API 호출 실패(어댑터는 한 번 호출만 하고, 재시도·한도는 호출하는 쪽 공통 계층이 맡는다, external-integrations.md §2).
 */
public class ProviderException extends RuntimeException {

    /** 실패 종류. 소스 연결 상태의 오류 종류(AUTH·QUOTA·TIMEOUT·PROTOCOL·OTHER)로 옮긴다 */
    public enum Kind { AUTH, QUOTA, TIMEOUT, PROTOCOL, OTHER }

    private final Kind kind;

    public ProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
