package net.java21.data2flow.core.control.domain;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 예약 제어의 다음 실행 시각(ACT-02.07, API-ACT-15). 시간대는 예약의 timezone(기본 조직 시간대).
 * <ul>
 *   <li>ONCE: {@code at}이 지금 뒤면 그 시각, 지났으면 없음</li>
 *   <li>RECURRING: 5필드 cron(분 시 일 월 요일, 예: {@code 50 8 * * MON-FRI})</li>
 *   <li>SPACE_HOURS: 공간 운영 시간의 시작·끝 + offsetMinutes(예: 18:00 종료 + 10분 = 18:10, TC-ACT-060)</li>
 * </ul>
 * 유효 기간(validFrom~validTo, 날짜 포함) 밖이면 없음(null).
 */
public final class ScheduleTimes {

    /** 운영 시간 한 칸(요일 1=월 … 7=일) */
    public record Hours(int dayOfWeek, LocalTime start, LocalTime end) {
    }

    private ScheduleTimes() {
    }

    /** 5필드 cron을 검사해 Spring 6필드로. 틀리면 IllegalArgumentException */
    public static CronExpression cron(String fiveFields) {
        if (fiveFields == null || fiveFields.isBlank() || fiveFields.strip().split("\\s+").length != 5) {
            throw new IllegalArgumentException("cron은 5필드입니다: " + fiveFields);
        }
        return CronExpression.parse("0 " + fiveFields.strip());
    }

    public static Instant next(String kind, Instant runAt, String cron, Hours[] hours, int offsetMinutes, String edge, LocalDate validFrom,
                               LocalDate validTo, ZoneId zone, Instant after) {
        Instant candidate = switch (kind) {
            case "ONCE" -> runAt != null && runAt.isAfter(after) ? runAt : null;
            case "RECURRING" -> {
                ZonedDateTime from = after.atZone(zone);
                if (validFrom != null && from.toLocalDate().isBefore(validFrom)) {
                    from = validFrom.atStartOfDay(zone).minusSeconds(1);
                }
                ZonedDateTime n = cron(cron).next(from);
                yield n == null ? null : n.toInstant();
            }
            case "SPACE_HOURS" -> spaceHours(List.of(hours), offsetMinutes, edge, zone, after, validFrom);
            default -> null;
        };
        if (candidate == null) {
            return null;
        }
        LocalDate day = candidate.atZone(zone).toLocalDate();
        if (validTo != null && day.isAfter(validTo)) {
            return null;
        }
        if (validFrom != null && day.isBefore(validFrom)) {
            return null;
        }
        return candidate;
    }

    static Instant spaceHours(List<Hours> hours, int offsetMinutes, String edge, ZoneId zone, Instant after, LocalDate validFrom) {
        if (hours.isEmpty()) {
            return null;
        }
        LocalDate start = after.atZone(zone).toLocalDate().minusDays(1);
        if (validFrom != null && validFrom.isAfter(start)) {
            start = validFrom;
        }
        Instant best = null;
        for (int d = 0; d < 9; d++) {
            LocalDate date = start.plusDays(d);
            for (Hours h : hours) {
                if (h.dayOfWeek() != date.getDayOfWeek().getValue()) {
                    continue;
                }
                LocalTime t = "END".equals(edge) ? h.end() : h.start();
                Instant at = date.atTime(t).atZone(zone).toInstant().plusSeconds(offsetMinutes * 60L);
                if (at.isAfter(after) && (best == null || at.isBefore(best))) {
                    best = at;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return best;
    }
}
