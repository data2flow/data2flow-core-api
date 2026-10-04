package net.java21.data2flow.core.external.provider;

import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DSC-06.03 공휴일 파사드 계약(ADR-040): 특일 정보 어댑터는 예시 응답의 쉬는 날(isHoliday=Y)만, 가짜는 내장 목록 — TC-DSC-145(core 쪽) */
class HolidayProviderContractTest {

    @Test
    @DisplayName("[DSC-06.03] 특일 정보 getRestDeInfo: solYear·_type=json으로 묻고 isHoliday=Y만 날짜 순, item 단일 객체·빈 결과도 읽는다")
    void adapter() {
        StubHttpServer server = new StubHttpServer();
        server.on("GET", "/getRestDeInfo", r -> ProviderContractSupport.reply("holiday/getRestDeInfo-2019.json"));
        DataGoKrHolidayProvider provider = new DataGoKrHolidayProvider(ProviderContractSupport.client(server, "k"));
        List<HolidayProvider.Holiday> holidays = provider.holidays(2019);
        assertThat(server.received().getFirst().query()).contains("solYear=2019").contains("_type=json");
        assertThat(holidays).hasSize(5).first().isEqualTo(new HolidayProvider.Holiday(LocalDate.of(2019, 1, 1), "1월1일"));
        assertThat(holidays).extracting(HolidayProvider.Holiday::name).doesNotContain("제헌절");
        server.on("GET", "/getRestDeInfo", r -> ProviderContractSupport.reply("holiday/getRestDeInfo-single.json"));
        assertThat(provider.holidays(2019)).containsExactly(new HolidayProvider.Holiday(LocalDate.of(2019, 3, 1), "삼일절"));
        server.on("GET", "/getRestDeInfo", r -> ProviderContractSupport.reply("holiday/getRestDeInfo-empty.json"));
        assertThat(provider.holidays(2019)).isEmpty();
        assertThat(provider.descriptor().available()).isTrue();
    }

    @Test
    @DisplayName("[DSC-06.03][ADR-040] 가짜 공휴일: 2026년 개천절·대체공휴일 포함, 목록 밖 해는 고정 날짜 공휴일 8개")
    void fixed() {
        FixedHolidayProvider fake = new FixedHolidayProvider();
        assertThat(fake.holidays(2026)).extracting(HolidayProvider.Holiday::date)
                .contains(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 9, 25));
        assertThat(fake.holidays(2031)).hasSize(8);
        assertThat(fake.descriptor().simulated()).isTrue();
    }
}
