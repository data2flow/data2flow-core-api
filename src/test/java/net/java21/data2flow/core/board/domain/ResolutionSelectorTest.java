package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSH-04.06 집계 단위 자동 선택(BR-DSH-04) — TC-DSH-044 */
class ResolutionSelectorTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("[DSH-04.06][AT-DSH-04.2][TC-DSH-044] AUTO: 30일 → 1h(720점), 1일 → 1m(1,440점), 3일 → 5m, 1년 → 1d, 점 수 ≤ 2,000")
    @CsvSource({"30일,PT720H,1h,720", "1일,PT24H,1m,1440", "3일,PT72H,5m,864", "1년,PT8760H,1d,365", "6시간(원본 적음),PT6H,raw,-1"})
    void auto(String name, String span, String expected, long points) {
        Duration d = Duration.parse(span);
        long raw = "raw".equals(expected) ? 360 : -1;
        WidgetResolution r = WidgetResolution.choose("AUTO", d, raw);
        assertThat(r.key()).isEqualTo(expected);
        if (points > 0) {
            assertThat(WidgetResolution.points(d, r)).isEqualTo(points).isLessThanOrEqualTo(WidgetResolution.MAX_POINTS);
        }
    }

    @Test
    @DisplayName("[DSH-04.06][TC-DSH-044] 원본 고정 + 30일 → WIDGET_QUERY_INVALID와 권장 단위(1h), 1h 고정 1년 → 권장 1d, 모르는 값 400")
    void fixed() {
        assertThatThrownBy(() -> WidgetResolution.choose("RAW", Duration.ofDays(30), -1)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getErrorCode().code()).isEqualTo("WIDGET_QUERY_INVALID");
            assertThat(ex.getErrors().getFirst().message()).isEqualTo("1h");
        });
        assertThatThrownBy(() -> WidgetResolution.choose("1h", Duration.ofDays(365), -1)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrors().getFirst().message()).isEqualTo("1d"));
        assertThat(WidgetResolution.choose("1d", Duration.ofDays(365), -1)).isEqualTo(WidgetResolution.D1);
        assertThat(WidgetResolution.choose("raw", Duration.ofHours(1), 50)).isEqualTo(WidgetResolution.RAW);
        assertThat(WidgetResolution.choose(null, Duration.ofHours(2), 5000)).isEqualTo(WidgetResolution.M1);
        assertThatThrownBy(() -> WidgetResolution.parse("2h")).isInstanceOf(BusinessException.class);
        assertThat(WidgetResolution.M5.rebucket()).isTrue();
        assertThat(WidgetResolution.H1.table()).isEqualTo("telemetry_1h");
    }

    @Test
    @DisplayName("[DSH-04.06] 시간 범위: 최근 N(m·h·d·w·y)·기간 지정, 1분 미만·400일 초과·형식 오류는 WIDGET_QUERY_INVALID")
    void timeRanges() {
        JsonMapper json = JsonMapper.builder().build();
        Instant now = Instant.parse("2026-10-04T00:00:00Z");
        assertThat(TimeRanges.resolve(json.readTree("{\"relative\":\"24h\"}"), now).from()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
        assertThat(TimeRanges.resolve(json.readTree("{\"relative\":\"2w\"}"), now).span()).isEqualTo(Duration.ofDays(14));
        assertThat(TimeRanges.resolve(json.readTree("{\"relative\":\"30m\"}"), now).span()).isEqualTo(Duration.ofMinutes(30));
        assertThat(TimeRanges.resolve(json.readTree("{\"relative\":\"1y\"}"), now).span()).isEqualTo(Duration.ofDays(365));
        assertThat(TimeRanges.resolve(json.readTree("{\"from\":\"2026-10-01T00:00:00Z\"}"), now).to()).isEqualTo(now);
        assertThat(TimeRanges.resolve(json.readTree("{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-02T00:00:00Z\"}"), now).span())
                .isEqualTo(Duration.ofDays(1));
        for (String bad : new String[]{"{\"relative\":\"2y\"}", "{\"relative\":\"10s\"}", "{\"from\":\"x\"}", "[]",
                "{\"from\":\"2026-10-02T00:00:00Z\",\"to\":\"2026-10-01T00:00:00Z\"}"}) {
            assertThatThrownBy(() -> TimeRanges.resolve(json.readTree(bad), now)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> TimeRanges.resolve(null, now)).isInstanceOf(BusinessException.class);
    }
}
