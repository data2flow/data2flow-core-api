package net.java21.data2flow.core.space.domain;

import java.util.regex.Pattern;

/**
 * 운영 시간 구간(DEV-11.01). 요일 1(월)~7(일), 시각 "HH:mm"(사이트 시간대), 종료는 "24:00"까지 쓸 수 있다. [start, end).
 */
public record ScheduleSlot(int dayOfWeek, String start, String end) {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$|^24:00$");

    public static boolean validTime(String value) {
        return value != null && TIME.matcher(value).matches();
    }

    /** 0~1440 */
    public static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    public int startMinute() {
        return minutes(start);
    }

    public int endMinute() {
        return minutes(end);
    }

    public boolean overlaps(ScheduleSlot other) {
        return dayOfWeek == other.dayOfWeek && startMinute() < other.endMinute() && other.startMinute() < endMinute();
    }

    public boolean contains(int dow, int minuteOfDay) {
        return dayOfWeek == dow && startMinute() <= minuteOfDay && minuteOfDay < endMinute();
    }
}
