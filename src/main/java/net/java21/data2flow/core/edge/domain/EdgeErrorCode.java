package net.java21.data2flow.core.edge.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/** 엣지 게이트웨이 오류 코드(design/api/DSC-api.md §8, DSC test-plan). 문구는 {@code i18n/edge*.properties} */
public enum EdgeErrorCode implements ErrorCode {
    EDGE_NOT_FOUND(404),
    EDGE_STATE_CONFLICT(409),
    EDGE_TOKEN_INVALID(401);

    private final int httpStatus;

    EdgeErrorCode(int httpStatus) {
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
