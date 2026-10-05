package net.java21.data2flow.core.analytics.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 분석 일정 형식·다음 실행 시각(ANA-04.01, BR-ANA-09 아님 — 일정만) */
class AnalysisScheduleTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static AnalysisSchedule parse(String json) {
        return AnalysisSchedule.parse(json == null ? null : JSON.readTree(json));
    }

    @Test
    @DisplayName("[ANA-04.01] 프리셋 DAILY·WEEKLY와 5필드 cron을 cron으로 정규화하고 조직 시간대로 다음 시각을 계산")
    void presets() {
        Instant sat = Instant.parse("2026-10-03T00:00:00Z");
        assertThat(parse("{\"preset\":\"DAILY\",\"at\":\"06:00\"}").next(sat, SEOUL)).isEqualTo(Instant.parse("2026-10-03T21:00:00Z"));
        assertThat(parse("{\"preset\":\"WEEKLY\",\"at\":\"06:00\",\"weekday\":\"MON\"}").cron()).isEqualTo("0 6 * * MON");
        assertThat(parse("{\"preset\":\"weekly\",\"at\":\"06:00\",\"weekday\":\"1\"}").next(sat, SEOUL))
                .isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
        assertThat(parse("{\"cron\":\"0  6 * * 1\"}").cron()).isEqualTo("0 6 * * 1");
        assertThat(parse(null)).isNull();
        assertThat(parse("{}")).isNull();
        assertThat(parse("null")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"cron\":\"0 61 * * *\"}", "{\"cron\":\"* * *\"}", "{\"preset\":\"HOURLY\",\"at\":\"06:00\"}",
            "{\"preset\":\"DAILY\",\"at\":\"25:00\"}", "{\"preset\":\"WEEKLY\",\"at\":\"06:00\"}", "{\"preset\":\"WEEKLY\",\"at\":\"06:00\",\"weekday\":\"XYZ\"}",
            "{\"cron\":\"0 6 * * *\",\"preset\":\"DAILY\"}", "\"daily\"", "{\"at\":\"06:00\"}"})
    @DisplayName("[ANA-04.01][AT-ANA-10.3] 잘못된 일정 → 400 ANALYSIS_SCHEDULE_INVALID — TC-ANA-097")
    void invalid(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(AnalyticsErrorCode.ANALYSIS_SCHEDULE_INVALID);
    }
}
