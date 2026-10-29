package net.java21.data2flow.core.compliance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 정기 발행 일정 규칙(CMP-02.04, UI-CMP-09): 다음 실행 시각과 대상 기간.
 *
 * <ul>
 *   <li>MONTHLY는 매월 N일, QUARTERLY는 분기 첫 달(1·4·7·10월) N일의 발행 시각(조직 시간대)에 돈다. N은 1~28이라 모든 달에 있다</li>
 *   <li>대상 기간: PREVIOUS_MONTH는 실행일의 직전 달 1일~말일, PREVIOUS_QUARTER는 실행일이 든 분기의 직전 분기(조직 시간대)</li>
 * </ul>
 */
public final class ReportSchedules {

    public static final List<String> CADENCES = List.of("MONTHLY", "QUARTERLY");
    public static final List<String> PERIOD_RULES = List.of("PREVIOUS_MONTH", "PREVIOUS_QUARTER");
    public static final List<String> FORMATS = List.of("PDF", "PDF_CSV");
    public static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    public static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private ReportSchedules() {
    }

    /** {@code after}보다 뒤인 첫 실행 시각 */
    public static Instant nextRun(String cadence, int dayOfPeriod, LocalTime time, ZoneId zone, Instant after) {
        YearMonth month = YearMonth.from(after.atZone(zone));
        for (int i = 0; i < 16; i++, month = month.plusMonths(1)) {
            if ("QUARTERLY".equals(cadence) && (month.getMonthValue() - 1) % 3 != 0) {
                continue;
            }
            Instant at = month.atDay(dayOfPeriod).atTime(time).atZone(zone).toInstant();
            if (at.isAfter(after)) {
                return at;
            }
        }
        throw new IllegalStateException("다음 실행 시각을 찾지 못했습니다: " + cadence);
    }

    /** 실행 시각의 대상 기간 [시작일, 끝일] */
    public static LocalDate[] period(String periodRule, Instant runAt, ZoneId zone) {
        LocalDate day = LocalDate.ofInstant(runAt, zone);
        if ("PREVIOUS_QUARTER".equals(periodRule)) {
            int firstMonth = (day.getMonthValue() - 1) / 3 * 3 + 1;
            LocalDate quarterStart = LocalDate.of(day.getYear(), firstMonth, 1);
            LocalDate from = quarterStart.minusMonths(3);
            return new LocalDate[]{from, quarterStart.minusDays(1)};
        }
        YearMonth previous = YearMonth.from(day).minusMonths(1);
        return new LocalDate[]{previous.atDay(1), previous.atEndOfMonth()};
    }
}
