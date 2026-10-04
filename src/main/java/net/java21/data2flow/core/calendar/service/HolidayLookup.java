package net.java21.data2flow.core.calendar.service;

import java.time.LocalDate;

/**
 * 휴일 판정(조직 달력 DEV-12.01). 예약 제어의 휴일 제외(ACT-02.07 skipHolidays)처럼 다른 기능이 달력을 직접 몰라도 되게 하는 창구.
 * 휴일 = 운영 모드 영향 HOLIDAY인 일정이 그 날짜를 덮는다(공휴일·휴관일·직접 등록).
 */
public interface HolidayLookup {

    /**
     * @param organizationId 조직
     * @param spaceId        대상 공간(없으면 조직 전체 일정만 본다). 공간이면 상위 공간 범위의 일정도 본다
     * @param date           그 공간 시간대의 날짜
     */
    boolean isHoliday(long organizationId, Long spaceId, LocalDate date);
}
