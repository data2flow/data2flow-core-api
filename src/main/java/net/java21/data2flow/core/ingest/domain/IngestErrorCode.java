package net.java21.data2flow.core.ingest.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 수집 관리 API 오류 코드(spec/detail/ING/domain-model.md "오류 코드" 중 HTTP 응답이 있는 것). 문구는 {@code i18n/telemetry*.properties}
 * (WP-E 묶음)의 {@code error.<코드>}.
 */
public enum IngestErrorCode implements ErrorCode {
    ING_QUERY_RANGE_TOO_LARGE(400),
    ING_RAW_MESSAGE_NOT_FOUND(404),
    ING_PAYLOAD_FORBIDDEN(403),
    ING_DLQ_ITEM_LOCKED(409),
    ING_DLQ_BATCH_TOO_LARGE(400),
    ING_REPROCESS_ALREADY_RUNNING(409),
    ING_REPROCESS_OUT_OF_RETENTION(400),
    ING_REPROCESS_NOT_CANCELLABLE(409);

    private final int httpStatus;

    IngestErrorCode(int httpStatus) {
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
