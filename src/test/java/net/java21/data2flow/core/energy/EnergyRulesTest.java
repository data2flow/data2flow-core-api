package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.energy.domain.EnergyRules;
import net.java21.data2flow.core.energy.domain.TouSchedule;
import net.java21.data2flow.core.energy.domain.TouSchedule.Band;
import net.java21.data2flow.core.energy.domain.TouSchedule.Season;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ENE-01.01·01.02 설정 규칙 — TC-ENE-012②③(TOU 24시간·단가), TC-ENE-005(고지서 차이), BR-ENE-02(상위 계량기 겹침) */
class EnergyRulesTest {

    private static final List<Integer> ALL = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

    private static Band band(String dayType, String from, String to, String rate) {
        return new Band(dayType, from, to, new BigDecimal(rate), null);
    }

    private static List<Band> fullDay(String dayType) {
        return List.of(band(dayType, "00:00", "09:00", "100"), band(dayType, "09:00", "18:00", "200"), band(dayType, "18:00", "24:00", "100"));
    }

    private static TouSchedule schedule(List<Band> weekday) {
        List<Band> bands = new ArrayList<>(weekday);
        bands.addAll(fullDay("WEEKEND_HOLIDAY"));
        return new TouSchedule(List.of(new Season("ALL", ALL, bands)));
    }

    @Test
    @DisplayName("[TC-ENE-012] TOU: 요일 유형마다 24시간을 빈틈·겹침 없이 덮어야 한다")
    void touCoverage() {
        assertThat(schedule(fullDay("WEEKDAY")).problem()).isEmpty();
        // 00~09, 10~24는 09~10이 빈다
        assertThat(schedule(List.of(band("WEEKDAY", "00:00", "09:00", "100"), band("WEEKDAY", "10:00", "24:00", "200"))).problem())
                .contains("touSchedule.seasons[0].bands");
        // 겹침
        assertThat(schedule(List.of(band("WEEKDAY", "00:00", "12:00", "100"), band("WEEKDAY", "11:00", "24:00", "200"))).problem()).isPresent();
        // 주말 구간이 없다
        assertThat(new TouSchedule(List.of(new Season("ALL", ALL, fullDay("WEEKDAY")))).problem()).isPresent();
        // 단가 0, 소수 셋째 자리
        assertThat(schedule(List.of(band("WEEKDAY", "00:00", "24:00", "0"))).problem()).contains("touSchedule.seasons[0].bands.rate");
        assertThat(schedule(List.of(band("WEEKDAY", "00:00", "24:00", "98.123"))).problem()).isPresent();
        // 잘못된 시각
        assertThat(schedule(List.of(band("WEEKDAY", "00:00", "24:30", "100"))).problem()).isPresent();
    }

    @Test
    @DisplayName("[TC-ENE-012] TOU: 12개월이 모두 한 계절에만 속한다")
    void touMonths() {
        List<Band> bands = new ArrayList<>(fullDay("WEEKDAY"));
        bands.addAll(fullDay("WEEKEND_HOLIDAY"));
        Season summer = new Season("SUMMER", List.of(6, 7, 8), bands);
        Season rest = new Season("REST", List.of(1, 2, 3, 4, 5, 9, 10, 11, 12), bands);
        assertThat(new TouSchedule(List.of(summer, rest)).problem()).isEmpty();
        assertThat(new TouSchedule(List.of(summer, new Season("REST", List.of(1, 2, 3, 4, 5, 9, 10, 11), bands))).problem())
                .contains("touSchedule.seasons.months");
        assertThat(new TouSchedule(List.of(summer, new Season("REST", List.of(1, 2, 3, 4, 5, 8, 9, 10, 11, 12), bands))).problem())
                .contains("touSchedule.seasons[1].months");
        assertThat(new TouSchedule(List.of()).problem()).contains("touSchedule.seasons");
    }

    @Test
    @DisplayName("[TC-ENE-005] 고지서 3,200kWh와 측정 2,950kWh의 차이는 7.8%, 측정이 없으면 null")
    void billDiff() {
        assertThat(EnergyRules.diffRatio(new BigDecimal("3200"), new BigDecimal("2950"))).isEqualByComparingTo("0.0781");
        assertThat(EnergyRules.diffRatio(new BigDecimal("3200"), null)).isNull();
        assertThat(EnergyRules.diffRatio(BigDecimal.ZERO, BigDecimal.ONE)).isNull();
    }

    @Test
    @DisplayName("기간 끝은 다음 시작 전날, 마지막은 끝이 없다")
    void validTo() {
        List<LocalDate> starts = List.of(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-10-01"));
        assertThat(EnergyRules.validTo(starts, LocalDate.parse("2026-01-01"))).isEqualTo("2026-09-30");
        assertThat(EnergyRules.validTo(starts, LocalDate.parse("2026-10-01"))).isNull();
    }

    @Test
    @DisplayName("[BR-ENE-02] 하위 합계 포함 계량기끼리 조상·자손이면 겹친다, 형제는 아니다")
    void nested() {
        assertThat(EnergyRules.nested("/1/2/", "/1/")).isTrue();
        assertThat(EnergyRules.nested("/1/", "/1/2/3/")).isTrue();
        assertThat(EnergyRules.nested("/1/2/", "/1/2/")).isTrue();
        assertThat(EnergyRules.nested("/1/2/", "/1/3/")).isFalse();
        assertThat(EnergyRules.nested("/1/2/", "/1/22/")).isFalse();
    }
}
