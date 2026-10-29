package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.ActionOutcomeEvaluator;
import net.java21.data2flow.core.compliance.domain.ActionOutcomeEvaluator.Outcome;
import net.java21.data2flow.core.compliance.domain.CorrectiveActions;
import net.java21.data2flow.core.compliance.domain.CorrectiveActions.Summary;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Limit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-CMP-18 개선 조치 결과 자동 확인 — TC-CMP-065, 리포트 문구(AT-CMP-10.1·10.2) */
class ActionOutcomeEvaluatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant ACTED = Instant.parse("2026-10-12T05:30:20Z");
    static final Instant START = Instant.parse("2026-10-12T05:30:00Z");
    static final Limit CO2 = new Limit(null, true, new BigDecimal("1000"), true);

    static Instant minute(int m) {
        return START.plus(Duration.ofMinutes(m));
    }

    @Test
    @DisplayName("[TC-CMP-065] 조치 후 24시간 충족 1411분·이탈 29분이면 충족률 97.99%(문구는 98%), 창 밖 값은 세지 않는다")
    void ratio() {
        NavigableMap<Instant, Double> values = new TreeMap<>();
        values.put(minute(-1), 1500.0);
        for (int m = 0; m < 1440; m++) {
            values.put(minute(m), m < 29 ? 1200.0 : 700.0);
        }
        values.put(minute(1440), 1500.0);
        Outcome o = ActionOutcomeEvaluator.evaluate(values, "INSTANT", CO2, ACTED);
        assertThat(o.metMinutes()).isEqualTo(1411);
        assertThat(o.exceededMinutes()).isEqualTo(29);
        assertThat(o.missingMinutes()).isZero();
        assertThat(o.ratio()).isEqualByComparingTo("97.99");
        assertThat(CorrectiveActions.text(List.of(summary("AUTO", o.ratio(), null, ACTED.plus(Duration.ofDays(1)))), SEOUL))
                .isEqualTo("[환기 부족] 환기 장치 필터 교체 (운영자, 2026-10-12 14:30) — 조치 후 충족률 98%");
    }

    @Test
    @DisplayName("[TC-CMP-065] 같은 24시간 결측률이 12%면 확인 불가(ratio null)")
    void unverifiable() {
        NavigableMap<Instant, Double> values = new TreeMap<>();
        for (int m = 173; m < 1440; m++) {
            values.put(minute(m), 700.0);
        }
        Outcome o = ActionOutcomeEvaluator.evaluate(values, "INSTANT", CO2, ACTED);
        assertThat(o.missingMinutes()).isEqualTo(173);
        assertThat(o.verifiable()).isFalse();
        assertThat(CorrectiveActions.resultStatus("AUTO", null, ACTED.plus(Duration.ofDays(1)))).isEqualTo("UNVERIFIABLE");
    }

    @Test
    @DisplayName("결과 상태와 문구: 수동은 메모, 자동 대기, 기타 원인은 메모를 붙인다")
    void statusAndText() {
        assertThat(CorrectiveActions.resultStatus("MANUAL", null, null)).isEqualTo("RECORDED");
        assertThat(CorrectiveActions.resultStatus("AUTO", null, null)).isEqualTo("PENDING");
        assertThat(CorrectiveActions.resultStatus("AUTO", new BigDecimal("98"), ACTED)).isEqualTo("CONFIRMED");
        Summary manual = new Summary("OTHER", "공조 일정 변경", "운영 시간 조정", null, ACTED, "MANUAL", null, "다음 날 정상", null);
        String text = CorrectiveActions.text(List.of(summary("AUTO", null, null, null), manual), SEOUL);
        assertThat(text).isEqualTo("""
                [환기 부족] 환기 장치 필터 교체 (운영자, 2026-10-12 14:30) — 결과 확인 대기
                [기타: 공조 일정 변경] 운영 시간 조정 (-, 2026-10-12 14:30) — 결과: 다음 날 정상""");
        assertThat(CorrectiveActions.text(List.of(), SEOUL)).isNull();
    }

    private static Summary summary(String mode, BigDecimal ratio, String note, Instant checked) {
        return new Summary("VENTILATION", null, "환기 장치 필터 교체", "운영자", ACTED, mode, ratio, note, checked);
    }
}
