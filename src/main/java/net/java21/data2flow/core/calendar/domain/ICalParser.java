package net.java21.data2flow.core.calendar.domain;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * iCalendar(RFC 5545) 일정 읽기(DSC-06.04). 학사일정·휴관일처럼 단건 일정을 가져오는 데 필요한 만큼만 읽는다(외부 라이브러리 없이).
 * <ul>
 *   <li>줄 접기 풀기(CRLF + 공백/탭), {@code VEVENT}의 UID·SUMMARY·DTSTART·DTEND·DURATION·CATEGORIES·STATUS</li>
 *   <li>날짜 일정({@code VALUE=DATE})의 DTEND는 다음 날(배타)이라 하루 뺀다. DTEND가 없으면 하루짜리</li>
 *   <li>시각 일정은 {@code Z}(UTC)·{@code TZID}·떠 있는 시각을 조직 시간대 현지 날짜·시각으로 바꾼다</li>
 *   <li>{@code STATUS:CANCELLED}는 건너뛴다. 반복 규칙(RRULE)은 첫 회차만 쓴다(학사일정은 단건 일정이 대부분)</li>
 * </ul>
 * BEGIN:VCALENDAR가 없거나 UID·DTSTART가 빠진 일정만 있으면 {@link ICalException}.
 */
public final class ICalParser {

    /** 한 파일·URL에서 받는 최대 일정 수 */
    public static final int MAX_EVENTS = 5000;

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private ICalParser() {
    }

    /** 읽은 일정. 날짜·시각은 zone 기준 현지 값, 하루 종일이면 시각이 null */
    public record ICalEvent(String uid, String title, LocalDate startsOn, LocalDate endsOn, LocalTime startTime, LocalTime endTime,
                            List<String> categories) {
    }

    public static class ICalException extends RuntimeException {
        public ICalException(String message) {
            super(message);
        }
    }

    public static List<ICalEvent> parse(String text, ZoneId zone) {
        if (text == null || !text.toUpperCase(Locale.ROOT).contains("BEGIN:VCALENDAR")) {
            throw new ICalException("BEGIN:VCALENDAR가 없습니다");
        }
        List<String> lines = unfold(text);
        List<ICalEvent> events = new ArrayList<>();
        Map<String, Prop> current = null;
        List<String> categories = null;
        int depth = 0;
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN:VEVENT")) {
                current = new LinkedHashMap<>();
                categories = new ArrayList<>();
                depth = 0;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (upper.startsWith("BEGIN:")) {
                depth++;
                continue;
            }
            if (upper.startsWith("END:") && depth > 0) {
                depth--;
                continue;
            }
            if (upper.equals("END:VEVENT")) {
                ICalEvent e = toEvent(current, categories, zone);
                if (e != null) {
                    if (events.size() >= MAX_EVENTS) {
                        throw new ICalException("일정이 " + MAX_EVENTS + "개를 넘습니다");
                    }
                    events.add(e);
                }
                current = null;
                continue;
            }
            if (depth > 0) {
                continue; // VALARM 등 하위 구성 요소
            }
            Prop p = Prop.of(line);
            if (p == null) {
                continue;
            }
            if (p.name().equals("CATEGORIES")) {
                for (String c : splitEscaped(p.value())) {
                    if (!c.isBlank()) {
                        categories.add(unescape(c).strip());
                    }
                }
            } else {
                current.putIfAbsent(p.name(), p);
            }
        }
        return events;
    }

    private static ICalEvent toEvent(Map<String, Prop> props, List<String> categories, ZoneId zone) {
        Prop status = props.get("STATUS");
        if (status != null && "CANCELLED".equalsIgnoreCase(status.value().strip())) {
            return null;
        }
        Prop uid = props.get("UID");
        Prop start = props.get("DTSTART");
        if (uid == null || uid.value().isBlank() || start == null) {
            return null;
        }
        String title = props.containsKey("SUMMARY") ? unescape(props.get("SUMMARY").value()).strip() : "";
        if (title.length() > 150) {
            title = title.substring(0, 150);
        }
        Moment from = moment(start, zone);
        Prop end = props.get("DTEND");
        Moment to;
        if (end != null) {
            to = moment(end, zone);
        } else if (props.containsKey("DURATION")) {
            to = from.plus(props.get("DURATION").value().strip());
        } else {
            to = from.allDay() ? new Moment(from.date().plusDays(1), null) : from;
        }
        Set<String> cats = new LinkedHashSet<>(categories);
        if (from.allDay()) {
            LocalDate last = to.date().minusDays(1);
            if (last.isBefore(from.date())) {
                last = from.date();
            }
            return new ICalEvent(uid.value().strip(), title, from.date(), last, null, null, List.copyOf(cats));
        }
        LocalDate endDate = to.date().isBefore(from.date()) ? from.date() : to.date();
        LocalTime endTime = to.time() == null ? LocalTime.MAX.withNano(0) : to.time();
        return new ICalEvent(uid.value().strip(), title, from.date(), endDate, from.time(), endTime, List.copyOf(cats));
    }

    /** 날짜(하루 종일이면 time=null) */
    record Moment(LocalDate date, LocalTime time) {
        boolean allDay() {
            return time == null;
        }

        Moment plus(String duration) {
            try {
                boolean negative = duration.startsWith("-");
                String d = duration.replaceFirst("^[+-]", "");
                Period period = Period.ZERO;
                Duration time = Duration.ZERO;
                int t = d.indexOf('T');
                String datePart = t < 0 ? d : d.substring(0, t);
                if (!datePart.equals("P")) {
                    period = Period.parse(datePart);
                }
                if (t >= 0) {
                    time = Duration.parse("PT" + d.substring(t + 1));
                }
                if (negative) {
                    return this;
                }
                if (allDay()) {
                    return new Moment(date.plus(period).plusDays(time.toDays()), null);
                }
                LocalDateTime dt = date.atTime(this.time).plus(period).plus(time);
                return new Moment(dt.toLocalDate(), dt.toLocalTime());
            } catch (DateTimeParseException ex) {
                throw new ICalException("DURATION 형식 오류: " + duration);
            }
        }
    }

    static Moment moment(Prop p, ZoneId zone) {
        String v = p.value().strip();
        try {
            if ("DATE".equalsIgnoreCase(p.params().get("VALUE")) || v.length() == 8) {
                return new Moment(LocalDate.parse(v.substring(0, 8), DATE), null);
            }
            if (v.endsWith("Z") || v.endsWith("z")) {
                Instant at = LocalDateTime.parse(v.substring(0, v.length() - 1), DATE_TIME).toInstant(ZoneOffset.UTC);
                LocalDateTime local = LocalDateTime.ofInstant(at, zone);
                return new Moment(local.toLocalDate(), local.toLocalTime());
            }
            LocalDateTime local = LocalDateTime.parse(v.length() > 15 ? v.substring(0, 15) : v, DATE_TIME);
            String tzid = p.params().get("TZID");
            if (tzid != null) {
                try {
                    LocalDateTime converted = local.atZone(ZoneId.of(tzid.replace("\"", "").strip())).withZoneSameInstant(zone).toLocalDateTime();
                    return new Moment(converted.toLocalDate(), converted.toLocalTime());
                } catch (DateTimeException ignored) {
                    // 알 수 없는 TZID(예: Windows 이름)는 조직 시간대의 현지 시각으로 본다
                }
            }
            return new Moment(local.toLocalDate(), local.toLocalTime());
        } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            throw new ICalException(p.name() + " 형식 오류: " + v);
        }
    }

    record Prop(String name, Map<String, String> params, String value) {
        static Prop of(String line) {
            int colon = -1;
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ':' && !quoted) {
                    colon = i;
                    break;
                }
            }
            if (colon <= 0) {
                return null;
            }
            String[] head = line.substring(0, colon).split(";");
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < head.length; i++) {
                int eq = head[i].indexOf('=');
                if (eq > 0) {
                    params.put(head[i].substring(0, eq).toUpperCase(Locale.ROOT), head[i].substring(eq + 1));
                }
            }
            return new Prop(head[0].toUpperCase(Locale.ROOT), params, line.substring(colon + 1));
        }
    }

    static List<String> unfold(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : normalized.split("\n", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && cur != null) {
                cur.append(raw, 1, raw.length());
                continue;
            }
            if (cur != null) {
                out.add(cur.toString());
            }
            cur = new StringBuilder(raw);
        }
        if (cur != null && !cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    static List<String> splitEscaped(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                cur.append(c).append(value.charAt(++i));
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    static String unescape(String value) {
        return value.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
