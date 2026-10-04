package net.java21.data2flow.core.dataexchange.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * 정기 내보내기 기간(export_schedules.relative_period, UC-TSD-12). 실행 시각을 조직 시간대로 보고 그 앞의 온전한 기간을 고른다.
 * 예: 매일 07:00 KST + {@link #PREVIOUS_DAY} → 전날 00:00~24:00 KST(AT-TSD-12.1).
 */
public enum RelativePeriod {
    /** 전날 */
    PREVIOUS_DAY,
    /** 지난주(월~일) */
    PREVIOUS_WEEK,
    /** 지난달 */
    PREVIOUS_MONTH;

    /** 기간 [from, to) */
    public record Range(Instant from, Instant to) {
    }

    public Range range(Instant runAt, ZoneId zone) {
        LocalDate today = runAt.atZone(zone).toLocalDate();
        LocalDate start;
        LocalDate end;
        switch (this) {
            case PREVIOUS_DAY -> {
                start = today.minusDays(1);
                end = today;
            }
            case PREVIOUS_WEEK -> {
                end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                start = end.minusWeeks(1);
            }
            default -> {
                end = today.withDayOfMonth(1);
                start = end.minusMonths(1);
            }
        }
        return new Range(ZonedDateTime.of(start.atStartOfDay(), zone).toInstant(), ZonedDateTime.of(end.atStartOfDay(), zone).toInstant());
    }

    /** 대소문자 무시, 한국어 표기(전날·지난주·지난달)도 받는다. 모르는 값이면 null */
    public static RelativePeriod parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        return switch (v) {
            case "전날" -> PREVIOUS_DAY;
            case "지난주" -> PREVIOUS_WEEK;
            case "지난달" -> PREVIOUS_MONTH;
            default -> {
                try {
                    yield valueOf(v.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }
}
