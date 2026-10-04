package net.java21.data2flow.core.external.provider;

import java.time.LocalDate;
import java.util.List;

/**
 * 공휴일 파사드(DSC-06.03). 국가 공휴일·대체공휴일을 연 단위로 준다.
 * 구현: {@link FixedHolidayProvider}(가짜, 내장 목록), {@link DataGoKrHolidayProvider}(한국천문연구원 특일 정보 어댑터, 서비스 키가 있을 때만).
 */
public interface HolidayProvider {

    ProviderDescriptor descriptor();

    /** 그 해의 쉬는 날(공휴일·대체공휴일·임시공휴일), 날짜 순 */
    List<Holiday> holidays(int year);

    record Holiday(LocalDate date, String name) {
    }
}
