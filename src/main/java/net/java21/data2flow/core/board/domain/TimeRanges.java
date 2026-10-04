package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 대시보드 시간 범위(DSH-04.06): 최근 N({@code {"relative":"24h"}}, 단위 m·h·d·w·y) 또는 기간 지정({@code {"from","to"}}, ISO-8601).
 * 범위는 1분 이상 400일 이하. 틀리면 WIDGET_QUERY_INVALID(필드 timeRange).
 */
public final class TimeRanges {

    public static final Duration MAX_SPAN = Duration.ofDays(400);
    private static final Pattern RELATIVE = Pattern.compile("(\\d{1,4})([mhdwy])");

    private TimeRanges() {
    }

    public record Range(Instant from, Instant to) {
        public Duration span() {
            return Duration.between(from, to);
        }
    }

    /** 저장된 범위 형식이 맞는지만 본다(대시보드 저장) */
    public static void validate(JsonNode timeRange, Instant now) {
        resolve(timeRange, now);
    }

    public static Range resolve(JsonNode timeRange, Instant now) {
        if (timeRange == null || timeRange.isNull() || !timeRange.isObject()) {
            throw invalid("timeRange");
        }
        if (timeRange.hasNonNull("relative")) {
            Matcher m = RELATIVE.matcher(timeRange.get("relative").asString(""));
            if (!m.matches()) {
                throw invalid("timeRange.relative");
            }
            long n = Long.parseLong(m.group(1));
            Duration d = switch (m.group(2)) {
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                case "w" -> Duration.ofDays(7 * n);
                default -> Duration.ofDays(365 * n);
            };
            return check(now.minus(d), now);
        }
        try {
            Instant from = Instant.parse(timeRange.path("from").asString(""));
            Instant to = timeRange.hasNonNull("to") ? Instant.parse(timeRange.get("to").asString("")) : now;
            return check(from, to);
        } catch (DateTimeParseException ex) {
            throw invalid("timeRange.from");
        }
    }

    private static Range check(Instant from, Instant to) {
        Duration span = Duration.between(from, to);
        if (span.compareTo(Duration.ofMinutes(1)) < 0 || span.compareTo(MAX_SPAN) > 0) {
            throw invalid("timeRange");
        }
        return new Range(from, to);
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
