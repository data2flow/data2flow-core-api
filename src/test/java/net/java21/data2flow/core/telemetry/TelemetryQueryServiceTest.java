package net.java21.data2flow.core.telemetry;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.telemetry.domain.AggFunction;
import net.java21.data2flow.core.telemetry.domain.BucketGrid;
import net.java21.data2flow.core.telemetry.domain.FillMode;
import net.java21.data2flow.core.telemetry.domain.FillMode.Gap;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import net.java21.data2flow.core.telemetry.domain.ResolutionPlanner;
import net.java21.data2flow.core.telemetry.domain.ResolutionPlanner.Plan;
import net.java21.data2flow.core.telemetry.domain.SeriesPoint;
import net.java21.data2flow.core.telemetry.domain.TimeCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시계열 조회 규칙(BR-TSD-08·09·10, TSD-01.05) 단위 시험 — TC-TSD-051·077·085·140·146 */
class TelemetryQueryServiceTest {

    static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    private static Plan auto(Duration span, long rawCount) {
        return ResolutionPlanner.plan(null, NOW.minus(span), NOW, NOW, true, () -> rawCount);
    }

    @Test
    @DisplayName("[TSD-03.01][BR-TSD-08] auto: 1,000점 이하 가장 작은 저장 단위 — 원본 점 수, 1m(16.6시간), 1h(41일), 1d")
    void autoPicksSmallestUnder1000() {
        assertThat(auto(Duration.ofMinutes(30), 30)).isEqualTo(new Plan(Resolution.RAW, "AUTO"));
        assertThat(auto(Duration.ofHours(12), 1001).resolution()).isEqualTo(Resolution.M1);
        assertThat(auto(Duration.ofHours(24), 1440).resolution()).isEqualTo(Resolution.H1);
        assertThat(auto(Duration.ofDays(40), 0).resolution()).isEqualTo(Resolution.H1);
        assertThat(auto(Duration.ofDays(400), 0)).isEqualTo(new Plan(Resolution.D1, "AUTO"));
        // 원본 없음(공간 집계): 1분부터
        assertThat(ResolutionPlanner.plan(null, NOW.minus(Duration.ofMinutes(30)), NOW, NOW, false, () -> 0).resolution()).isEqualTo(Resolution.M1);
        // 1분 집계 보관(90일) 밖이면 1h로 올리고 CAPPED
        assertThat(ResolutionPlanner.plan(null, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(199)).minus(Duration.ofHours(8)), NOW,
                false, () -> 0)).isEqualTo(new Plan(Resolution.H1, "CAPPED"));
    }

    @Test
    @DisplayName("[TSD-06.01][BR-TSD-09][AT-TSD-01.5] 직접 지정: raw 31일 초과·보관 밖, 집계 10,000점 초과는 400, 공간의 raw는 1m CAPPED")
    void requestedLimits() {
        assertThat(ResolutionPlanner.plan(Resolution.RAW, NOW.minus(Duration.ofDays(31)), NOW, NOW, true, () -> 0).reason()).isEqualTo("REQUESTED");
        assertThatThrownBy(() -> ResolutionPlanner.plan(Resolution.RAW, NOW.minus(Duration.ofDays(32)), NOW, NOW, true, () -> 0))
                .isInstanceOf(BusinessException.class).hasMessage("TSD_RANGE_TOO_LARGE");
        assertThatThrownBy(() -> ResolutionPlanner.plan(Resolution.RAW, NOW.minus(Duration.ofDays(730)), NOW.minus(Duration.ofDays(729)), NOW, true, () -> 0))
                .hasMessage("TSD_RESOLUTION_UNAVAILABLE");
        assertThatThrownBy(() -> ResolutionPlanner.plan(Resolution.M1, NOW.minus(Duration.ofDays(8)), NOW, NOW, true, () -> 0))
                .hasMessage("TSD_RANGE_TOO_LARGE");
        assertThat(ResolutionPlanner.plan(Resolution.D1, NOW.minus(Duration.ofDays(3000)), NOW, NOW, true, () -> 0).resolution()).isEqualTo(Resolution.D1);
        assertThat(ResolutionPlanner.plan(Resolution.RAW, NOW.minus(Duration.ofHours(1)), NOW, NOW, false, () -> 0))
                .isEqualTo(new Plan(Resolution.M1, "CAPPED"));
        assertThat(Resolution.parse("auto")).isNull();
        assertThat(Resolution.parse(" 1H ")).isEqualTo(Resolution.H1);
        assertThatThrownBy(() -> Resolution.parse("5m")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[TSD-06.02] 집계 함수: avg·min·max·sum·last·count·twa, 열거형 항목은 last·count만")
    void aggFunctions() {
        assertThat(AggFunction.parse("TWA")).isEqualTo(AggFunction.TWA);
        assertThat(AggFunction.parse(null)).isNull();
        assertThat(AggFunction.AVG.allowedFor("ENUM")).isFalse();
        assertThat(AggFunction.LAST.allowedFor("ENUM")).isTrue();
        assertThat(AggFunction.SUM.allowedFor("NUMBER")).isTrue();
        assertThatThrownBy(() -> AggFunction.parse("median")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[TSD-06.02][BR-TSD-10][AT-TSD-01.4] 채우기: none 그대로, previous는 보유 시간까지, linear 보간, 공백은 채우지 않음")
    void fillModes() {
        Instant t = Instant.parse("2026-10-02T10:00:00Z");
        List<SeriesPoint> points = List.of(new SeriesPoint(t, 10.0, 1), new SeriesPoint(t.plusSeconds(180), 16.0, 1),
                new SeriesPoint(t.plusSeconds(600), 20.0, 1));
        List<Instant> grid = BucketGrid.of(Resolution.M1, t, t.plusSeconds(660), ZoneId.of("UTC"));
        List<Gap> gaps = List.of(new Gap(t.plusSeconds(300), t.plusSeconds(540)));
        assertThat(FillMode.NONE.fill(points, grid, gaps, Duration.ofMinutes(3))).isEqualTo(points);
        List<SeriesPoint> previous = FillMode.PREVIOUS.fill(points, grid, gaps, Duration.ofMinutes(2));
        assertThat(previous).extracting(SeriesPoint::value).containsExactly(10.0, 10.0, 10.0, 16.0, 16.0, 20.0);
        List<SeriesPoint> linear = FillMode.LINEAR.fill(points, grid, gaps, Duration.ZERO);
        assertThat(linear).extracting(SeriesPoint::value).containsExactly(10.0, 12.0, 14.0, 16.0, 20.0);
        assertThat(linear.get(1).third()).isZero();
        assertThat(FillMode.parse(null)).isEqualTo(FillMode.NONE);
        assertThat(FillMode.LINEAR.fill(List.of(), grid, gaps, Duration.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("[TSD-01.05][BR-TSD-05] 1일 격자는 사이트 시간대 자정(Asia/Seoul → 15:00Z), 1m·1h는 UTC 정각, 커서는 시각을 그대로 되돌린다")
    void gridAndCursor() {
        List<Instant> days = BucketGrid.of(Resolution.D1, Instant.parse("2026-10-01T03:00:00Z"), Instant.parse("2026-10-03T00:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        assertThat(days).containsExactly(Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-10-01T15:00:00Z"),
                Instant.parse("2026-10-02T15:00:00Z"));
        assertThat(BucketGrid.of(Resolution.H1, Instant.parse("2026-10-01T03:30:00Z"), Instant.parse("2026-10-01T05:00:00Z"), ZoneId.of("UTC")))
                .containsExactly(Instant.parse("2026-10-01T03:00:00Z"), Instant.parse("2026-10-01T04:00:00Z"));
        assertThat(BucketGrid.of(Resolution.RAW, NOW, NOW.plusSeconds(60), ZoneId.of("UTC"))).isEmpty();
        assertThat(BucketGrid.lowerBound(Resolution.D1, NOW)).isEqualTo(NOW.minus(Duration.ofDays(1)));
        Instant at = Instant.parse("2026-10-02T10:00:00.123456Z");
        assertThat(TimeCursor.decode(TimeCursor.encode(at))).isEqualTo(at);
        assertThatThrownBy(() -> TimeCursor.decode("bm9kb3Q")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SeriesPoint(NOW, null, 0).toArray()).containsExactly("2026-10-03T00:00:00Z", null, 0);
    }
}
