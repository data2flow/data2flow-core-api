package net.java21.data2flow.core.notify.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * 요일·시간대 창(알림 정책 time_window, 반복 무음 recurrence, 당직 교대). 요일은 1=월 … 7=일(ISO), 시각은 HH:mm.
 * {@code to < from}이면 자정을 넘는다(예: 22:00~07:00). 시작은 포함, 끝은 미포함. 요일은 시작한 날 기준이다.
 *
 * @param days 요일(비면 매일)
 * @param from 시작 시각(null이면 0시)
 * @param to   끝 시각(null이면 24시)
 */
public record TimeWindow(Set<Integer> days, LocalTime from, LocalTime to) {

    public TimeWindow {
        days = days == null ? Set.of() : Set.copyOf(days);
        for (Integer d : days) {
            if (d == null || d < 1 || d > 7) {
                throw new IllegalArgumentException("요일은 1~7입니다: " + d);
            }
        }
    }

    public boolean contains(LocalDateTime local) {
        LocalTime t = local.toLocalTime();
        LocalTime start = from == null ? LocalTime.MIN : from;
        boolean wholeDay = from == null && to == null;
        if (wholeDay || (to != null && start.equals(to))) {
            return dayOk(local.getDayOfWeek());
        }
        if (to == null || start.isBefore(to)) {
            boolean inside = !t.isBefore(start) && (to == null || t.isBefore(to));
            return inside && dayOk(local.getDayOfWeek());
        }
        // 자정을 넘는 창: 시작한 날의 [from, 24:00) 또는 다음 날의 [00:00, to)
        if (!t.isBefore(start)) {
            return dayOk(local.getDayOfWeek());
        }
        if (t.isBefore(to)) {
            return dayOk(local.getDayOfWeek().minus(1));
        }
        return false;
    }

    private boolean dayOk(DayOfWeek day) {
        return days.isEmpty() || days.contains(day.getValue());
    }

    /** "HH:mm" 읽기. 형식이 틀리면 IllegalArgumentException */
    public static LocalTime time(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            throw new IllegalArgumentException("시각은 HH:mm입니다: " + raw);
        }
        return LocalTime.parse(raw);
    }
}
