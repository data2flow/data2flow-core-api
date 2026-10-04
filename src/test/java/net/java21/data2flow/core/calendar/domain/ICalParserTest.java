package net.java21.data2flow.core.calendar.domain;

import net.java21.data2flow.core.calendar.domain.ICalParser.ICalEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSC-06.04 iCal 읽기(RFC 5545) — TC-DSC-151(core 쪽): 날짜 일정 DTEND 배타, 시각 일정, 접힌 줄, 취소, 카테고리 */
class ICalParserTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static String sample() throws IOException {
        return new ClassPathResource("contracts/ical/academic-2026.ics").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("[DSC-06.04][BR-DSC-18] 예시 학사일정: 취소 일정 빼고 4건, 날짜 일정 끝은 DTEND-1일, 카테고리 여러 개, 이스케이프 풀기, UTC·DURATION을 서울 시각으로")
    void parsesSample() throws IOException {
        List<ICalEvent> events = ICalParser.parse(sample(), SEOUL);
        assertThat(events).extracting(ICalEvent::uid).containsExactly("exam-2026-2-mid@example.ac.kr", "closed-2026-10-30@example.ac.kr",
                "festival-2026@example.ac.kr", "utc-meeting@example.ac.kr");
        ICalEvent exam = events.getFirst();
        assertThat(exam.startsOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(exam.endsOn()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(exam.startTime()).isNull();
        assertThat(exam.categories()).containsExactly("시험");
        ICalEvent closed = events.get(1);
        assertThat(closed.endsOn()).isEqualTo(closed.startsOn());
        assertThat(closed.categories()).containsExactly("휴일", "행사");
        ICalEvent festival = events.get(2);
        assertThat(festival.title()).isEqualTo("대동제, 야간 공연");
        assertThat(festival.startTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(festival.endTime()).isEqualTo(LocalTime.of(22, 0));
        ICalEvent meeting = events.get(3);
        assertThat(meeting.startsOn()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(meeting.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(meeting.endTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("[DSC-06.04] 형식 오류: VCALENDAR 없음·DTSTART 형식 오류는 거부, UID 없는 일정은 건너뜀, 알 수 없는 TZID는 조직 시간대")
    void invalid() {
        assertThatThrownBy(() -> ICalParser.parse("hello", SEOUL)).isInstanceOf(ICalParser.ICalException.class);
        assertThatThrownBy(() -> ICalParser.parse(null, SEOUL)).isInstanceOf(ICalParser.ICalException.class);
        String bad = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:a\nDTSTART:2026-10-01\nEND:VEVENT\nEND:VCALENDAR";
        assertThatThrownBy(() -> ICalParser.parse(bad, SEOUL)).isInstanceOf(ICalParser.ICalException.class);
        String noUid = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART;VALUE=DATE:20261001\nEND:VEVENT\nBEGIN:VEVENT\nUID:b\nDTSTART;TZID=\"Korea Standard Time\":20261001T090000\n"
                + "DTEND:20261001T030000Z\nSUMMARY:x\nEND:VEVENT\nBEGIN:VEVENT\nUID:c\nDTSTART;VALUE=DATE:20261005\nDURATION:P2D\nEND:VEVENT\n"
                + "BEGIN:VEVENT\nUID:d\nDTSTART:20261006T090000\nEND:VEVENT\nEND:VCALENDAR";
        List<ICalEvent> events = ICalParser.parse(noUid, SEOUL);
        assertThat(events).extracting(ICalEvent::uid).containsExactly("b", "c", "d");
        assertThat(events.getFirst().startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(events.getFirst().endTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(events.get(1).endsOn()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(events.get(2).endTime()).isEqualTo(LocalTime.of(9, 0));
        String badDuration = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:e\nDTSTART;VALUE=DATE:20261005\nDURATION:XYZ\nEND:VEVENT\nEND:VCALENDAR";
        assertThatThrownBy(() -> ICalParser.parse(badDuration, SEOUL)).isInstanceOf(ICalParser.ICalException.class);
        assertThat(ICalParser.unfold("A:1\r\n 2\r\nB:3")).containsExactly("A:12", "B:3");
    }
}
