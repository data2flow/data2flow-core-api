package net.java21.data2flow.core.ingest;

import net.java21.data2flow.core.ingest.domain.IngestAlertRules;
import net.java21.data2flow.core.ingest.domain.IngestAlertRules.Alert;
import net.java21.data2flow.core.ingest.domain.IngestAlertRules.Inputs;
import net.java21.data2flow.core.ingest.domain.RawCursor;
import net.java21.data2flow.core.ingest.service.FailureService;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.ItemResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 수집 경고 판정(BR-OPS-02, BR-ING-16) 단위 시험 — TC-OPS-016, TC-ING-092(core 표시 부분) */
class IngestAlertRulesTest {

    private static Inputs inputs(int activeSources, long recent, boolean zeroEnabled, Long lag, double scriptErrorRate, double surge, long dlq) {
        return new Inputs(activeSources, recent, zeroEnabled, 5, lag, 60, 300, scriptErrorRate, surge, dlq, true, 100, true);
    }

    @Test
    @DisplayName("[OPS-01.05][AT-OPS-02.1][AT-OPS-02.2][BR-OPS-02] 활성 소스 5분 0건 CRITICAL, 활성 소스 없음·기준 끔이면 없음")
    void ingestStopped() {
        assertThat(IngestAlertRules.evaluate(inputs(1, 0, true, 0L, 0, 0, 0)))
                .containsExactly(new Alert("CRITICAL", "INGEST_STOPPED", List.of("SOURCE_DISCONNECTED")));
        assertThat(IngestAlertRules.evaluate(inputs(0, 0, true, 0L, 0, 0, 0))).isEmpty();
        assertThat(IngestAlertRules.evaluate(inputs(1, 0, false, 0L, 0, 0, 0))).isEmpty();
        assertThat(IngestAlertRules.evaluate(inputs(1, 3, true, 0L, 0, 0, 0))).isEmpty();
    }

    @Test
    @DisplayName("[ING-07.04][BR-ING-16] 지연 59초 없음, 60초 WARNING, 300초 CRITICAL, 원인 후보(스크립트 오류율 10% 초과·입력 2배), DLQ 증가 WARNING")
    void lagAndDlq() {
        assertThat(IngestAlertRules.evaluate(inputs(1, 9, true, 59L, 0, 0, 0))).isEmpty();
        assertThat(IngestAlertRules.evaluate(inputs(1, 9, true, 60L, 0.2, 2.5, 0)))
                .containsExactly(new Alert("WARNING", "INGEST_LAG_HIGH", List.of("SCRIPT_ERROR_RATE", "INPUT_SURGE")));
        assertThat(IngestAlertRules.evaluate(inputs(1, 9, true, 301L, 0.05, 1, 0)).getFirst().level()).isEqualTo("CRITICAL");
        assertThat(IngestAlertRules.evaluate(inputs(1, 9, true, null, 0, 0, 100)))
                .containsExactly(new Alert("WARNING", "DLQ_GROWING", List.of()));
    }

    @Test
    @DisplayName("[ING-07.03] pipeline 결과 → 화면 결과: OK→RESOLVED, 같은 오류→SAME_ERROR, 다른 오류→OTHER_ERROR, SKIPPED→LOCKED, 원본 커서 왕복")
    void outcomesAndCursor() {
        assertThat(FailureService.outcome(1, new ItemResult(1, "OK", null), "X").outcome()).isEqualTo("RESOLVED");
        assertThat(FailureService.outcome(1, new ItemResult(1, "FAILED", "X"), "X").outcome()).isEqualTo("SAME_ERROR");
        assertThat(FailureService.outcome(1, new ItemResult(1, "FAILED", "Y"), "X").outcome()).isEqualTo("OTHER_ERROR");
        assertThat(FailureService.outcome(1, new ItemResult(1, "SKIPPED", null), "X").outcome()).isEqualTo("LOCKED");
        assertThat(FailureService.outcome(1, new ItemResult(1, "WEIRD", null), "X").outcome()).isEqualTo("OTHER_ERROR");
        assertThat(FailureService.outcome(1, null, "X").outcome()).isEqualTo("OTHER_ERROR");
        RawCursor cursor = new RawCursor(Instant.parse("2026-10-02T10:00:00.5Z"), 42);
        assertThat(RawCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThatThrownBy(() -> RawCursor.decode("eHl6")).isInstanceOf(IllegalArgumentException.class);
    }
}
