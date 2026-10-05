package net.java21.data2flow.core.workorder.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 작업 지시·현장 설치·기기 검색·표준 내보내기 오류 코드(spec/detail/DEV/domain-model.md §5). 문구는 {@code i18n/fieldops*.properties}의
 * {@code error.<코드>}. 공간·기기·그룹 코드는 다른 기능 묶음과 같은 이름이다.
 */
public enum WorkOrderErrorCode implements ErrorCode {
    WORKORDER_NOT_FOUND(404),
    WORKORDER_STATE_CONFLICT(409),
    GROUP_NOT_FOUND(404),
    /** 다른 사람이 먼저(더 나중 시각으로) 설치했다(BR-DEV-37). 인자: 설치자 이름, 설치 시각 */
    COMMISSION_CONFLICT(409),
    /** 검색식 구문·필드 오류(BR-DEV-35). 인자: 열(1부터) */
    DEVICE_QUERY_INVALID(400),
    DEVICE_QUERY_TIMEOUT(400),
    /** 결과 파일 전체가 스키마 검증 실패(BR-DEV-36) */
    EXPORT_INVALID_REQUEST(400);

    private final int httpStatus;

    WorkOrderErrorCode(int httpStatus) {
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
