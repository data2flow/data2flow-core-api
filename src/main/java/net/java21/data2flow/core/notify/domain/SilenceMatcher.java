package net.java21.data2flow.core.notify.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 무음 판정(RUL-02.07, BR-RUL-14). 무음 중에도 알람은 기록하고 알림만 SKIPPED(SILENCED)로 남긴다.
 * <ul>
 *   <li>ONE_TIME: {@code startsAt ≤ t < endsAt}(끝 배타, TC-RUL-063)</li>
 *   <li>RECURRING: 조직 시간대로 {@code {days[], from, to}}(요일·시간대) 또는 {@code {dateFrom, dateTo}}(날짜 구간, 끝 날짜 포함)</li>
 *   <li>대상: RULE(규칙 ID), DEVICE(기기 ID), SPACE(그 공간과 하위, 경로 접두어), ALARM(알람 ID)</li>
 * </ul>
 */
public final class SilenceMatcher {

    private SilenceMatcher() {
    }

    /** 무음 한 건의 시간 조건 */
    public record Period(String kind, Instant startsAt, Instant endsAt, TimeWindow window, LocalDate dateFrom, LocalDate dateTo) {
    }

    /** 알람 쪽 값 */
    public record Target(Long alarmId, Long ruleId, Long deviceId, String spacePath) {
    }

    public static boolean active(Period p, Instant at, ZoneId zone) {
        if ("ONE_TIME".equals(p.kind())) {
            return p.startsAt() != null && p.endsAt() != null && !at.isBefore(p.startsAt()) && at.isBefore(p.endsAt());
        }
        ZonedDateTime local = at.atZone(zone);
        if (p.dateFrom() != null || p.dateTo() != null) {
            LocalDate d = local.toLocalDate();
            return (p.dateFrom() == null || !d.isBefore(p.dateFrom())) && (p.dateTo() == null || !d.isAfter(p.dateTo()));
        }
        return p.window() != null && p.window().contains(local.toLocalDateTime());
    }

    /**
     * @param targetType      RULE·DEVICE·SPACE·ALARM
     * @param targetId        대상 ID
     * @param targetSpacePath SPACE일 때 그 공간 경로
     */
    public static boolean covers(String targetType, long targetId, String targetSpacePath, Target t) {
        return switch (targetType) {
            case "RULE" -> t.ruleId() != null && t.ruleId() == targetId;
            case "DEVICE" -> t.deviceId() != null && t.deviceId() == targetId;
            case "ALARM" -> t.alarmId() != null && t.alarmId() == targetId;
            case "SPACE" -> targetSpacePath != null && t.spacePath() != null && t.spacePath().startsWith(targetSpacePath);
            default -> false;
        };
    }
}
