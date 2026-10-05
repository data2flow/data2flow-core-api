package net.java21.data2flow.core.analytics.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.control.domain.ScheduleTimes;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 분석 일정(ANA-04.01, API-ANA-06 {@code schedule}): {@code {preset: DAILY, at: "06:00"}}, {@code {preset: WEEKLY, at: "06:00", weekday: "MON"}}
 * 또는 {@code {cron: "0 6 * * 1"}}(5필드: 분 시 일 월 요일). 시각은 조직 시간대. 틀리면 400 ANALYSIS_SCHEDULE_INVALID(TC-ANA-097).
 *
 * @param cron 정규화한 5필드 cron(프리셋도 cron으로 바꿔 둔다)
 */
public record AnalysisSchedule(String cron) {

    /** 비었거나 null이면 일정 없음(null) */
    public static AnalysisSchedule parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isObject() && node.isEmpty()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid();
        }
        String cron = text(node, "cron");
        String preset = text(node, "preset");
        if (cron != null && preset != null) {
            throw invalid();
        }
        if (cron != null) {
            try {
                ScheduleTimes.cron(cron);
            } catch (IllegalArgumentException ex) {
                throw invalid();
            }
            return new AnalysisSchedule(cron.strip().replaceAll("\\s+", " "));
        }
        if (preset == null) {
            throw invalid();
        }
        LocalTime at;
        try {
            at = LocalTime.parse(text(node, "at") == null ? "" : text(node, "at"));
        } catch (DateTimeParseException ex) {
            throw invalid();
        }
        String time = at.getMinute() + " " + at.getHour();
        return switch (preset.toUpperCase(Locale.ROOT)) {
            case "DAILY" -> new AnalysisSchedule(time + " * * *");
            case "WEEKLY" -> {
                String weekday = text(node, "weekday");
                if (weekday == null) {
                    throw invalid();
                }
                DayOfWeek day;
                try {
                    day = weekday.matches("[1-7]") ? DayOfWeek.of(Integer.parseInt(weekday))
                            : DayOfWeek.valueOf(expand(weekday.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw invalid();
                }
                yield new AnalysisSchedule(time + " * * " + day.name().substring(0, 3));
            }
            default -> throw invalid();
        };
    }

    /** after 뒤의 첫 실행 시각 */
    public Instant next(Instant after, ZoneId zone) {
        CronExpression expression = ScheduleTimes.cron(cron);
        ZonedDateTime n = expression.next(after.atZone(zone));
        return n == null ? null : n.toInstant();
    }

    private static String expand(String weekday) {
        return switch (weekday) {
            case "MON" -> "MONDAY";
            case "TUE" -> "TUESDAY";
            case "WED" -> "WEDNESDAY";
            case "THU" -> "THURSDAY";
            case "FRI" -> "FRIDAY";
            case "SAT" -> "SATURDAY";
            case "SUN" -> "SUNDAY";
            default -> weekday;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString().strip();
    }

    private static BusinessException invalid() {
        return new BusinessException(AnalyticsErrorCode.ANALYSIS_SCHEDULE_INVALID);
    }
}
