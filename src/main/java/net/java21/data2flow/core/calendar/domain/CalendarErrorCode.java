package net.java21.data2flow.core.calendar.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/** 조직 달력·운영 모드 오류 코드(spec/detail/DEV/domain-model.md §5). 문구는 {@code i18n/calendar*.properties} */
public enum CalendarErrorCode implements ErrorCode {
    /** 기간 역전·366일 초과·자동 생성 일정의 잠긴 필드 수정 등 */
    CALENDAR_EVENT_INVALID(400),
    SPACE_NOT_FOUND(404);

    private final int httpStatus;

    CalendarErrorCode(int httpStatus) {
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
