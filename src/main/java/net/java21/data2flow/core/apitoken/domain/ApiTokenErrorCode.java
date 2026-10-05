package net.java21.data2flow.core.apitoken.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 장기 토큰(API 키·MCP)·서비스 계정 오류 코드(spec/detail/IAM/domain-model.md 오류 표, IAM-05·IAM-04.07). 문구는
 * {@code i18n/apitoken*.properties}의 {@code error.<코드>}. API_TOKEN_STATE_CONFLICT는 승인 대기가 아닌 토큰 승인·거절,
 * 폐기된 토큰 교체처럼 상태가 맞지 않는 요청이다(문서 오류 표에 없어 더한 코드).
 */
public enum ApiTokenErrorCode implements ErrorCode {
    API_TOKEN_SCOPE_EXCEEDED(400),
    API_TOKEN_EXPIRY_INVALID(400),
    API_TOKEN_LIMIT_EXCEEDED(409),
    API_TOKEN_NOT_FOUND(404),
    API_TOKEN_STATE_CONFLICT(409),
    SERVICE_ACCOUNT_NOT_FOUND(404);

    private final int httpStatus;

    ApiTokenErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
