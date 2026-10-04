package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/** 자동화 플로우(FLW) 오류 코드(00-error-codes.md FLW). 문구는 {@code i18n/flow*.properties} */
public enum FlowErrorCode implements ErrorCode {
    FLOW_NOT_FOUND(404),
    FLOW_NAME_DUPLICATED(409),
    FLOW_VALIDATION_FAILED(400),
    FLOW_VERSION_CONFLICT(409),
    FLOW_STATE_CONFLICT(409),
    /** 성공 응답의 resultCode(202, isSuccessful=true): 승인 요청을 보냈다 */
    FLOW_APPROVAL_REQUIRED(202),
    FLOW_LIMIT_EXCEEDED(409),
    FLOW_NODE_LIMIT_EXCEEDED(400),
    FLOW_DEFINITION_TOO_LARGE(413),
    FLOW_TEMPLATE_NOT_FOUND(404),
    FLOW_OWNER_INVALID(400),
    /** API-FLW-14: 엔진 지표 API(FLW-05.05, M4)가 아직 없거나 응답하지 않는다 */
    FLOW_METRICS_UNAVAILABLE(503),
    /** API-FLW-50 쓰는 플로우가 있는 Sink 연결 삭제 */
    SINK_CONNECTION_IN_USE(409),
    /** API-FLW-51 연결 테스트 실패(원인 AUTH·DNS·TLS·TIMEOUT·REFUSED·OTHER) */
    SINK_CONNECTION_TEST_FAILED(502);

    private final int httpStatus;

    FlowErrorCode(int httpStatus) {
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
