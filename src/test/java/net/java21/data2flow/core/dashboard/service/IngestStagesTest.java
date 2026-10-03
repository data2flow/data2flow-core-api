package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.core.dashboard.dto.DashboardDtos.StageSnapshot;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.Throughput;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.MinuteCount;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.SourceRow;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.StatSums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DSH-03.01·03.02 단계 계산과 처리량 점(단위) */
class IngestStagesTest {

    @Test
    @DisplayName("[DSH-03.01][AT-DSH-03.1] 5분 합계 → 단계별 분당 처리·실패, 실패 링크(SCRIPT_ERROR 필터) — TC-DSH-020")
    void stagesFromStats() {
        // 5분: 수신 600, 중복 10, 디코딩 오류 15, 스크립트 오류 60(분당 12), 검증 실패 5 + 미등록 거부 5, 저장 500
        List<StageSnapshot> stages = IngestMonitorService.stages(new StatSums(600, 500, 15, 60, 5, 5, 10),
                Map.of("STORE", 5L, "PUBLISH", 10L));
        assertThat(stages).extracting(StageSnapshot::key).containsExactly("SOURCE", "DECODE", "SCRIPT", "VALIDATE", "STORE", "PUBLISH");
        assertThat(stages).extracting(StageSnapshot::inPerMin).containsExactly(120.0, 118.0, 115.0, 103.0, 100.0, 99.0);
        assertThat(stages).extracting(StageSnapshot::failPerMin).containsExactly(0.0, 3.0, 12.0, 2.0, 1.0, 2.0);
        assertThat(stages.get(2).failureLink()).isEqualTo("/ingest/failures?stage=SCRIPT&code=SCRIPT_ERROR");
        assertThat(stages.get(1).failureLink()).isEqualTo("/ingest/failures?stage=DECODE&code=DECODE_ERROR");
        assertThat(stages.get(0).failureLink()).isNull();
        assertThat(stages.get(3).failureLink()).isNull();
        assertThat(stages.get(5).failureLink()).isEqualTo("/ingest/failures?stage=PUBLISH");
        assertThat(stages).allSatisfy(s -> assertThat(s.latencyP95Ms()).isNull());
        assertThat(IngestMonitorService.stages(new StatSums(0, 0, 0, 0, 0, 0, 0), Map.of()))
                .allSatisfy(s -> assertThat(s.inPerMin()).isZero());
    }

    @Test
    @DisplayName("[DSH-03.02] 대표 상태: 운영 중지·초안 DISABLED, 보고 없음 DISCONNECTED, 그 밖은 런타임 상태")
    void sourceState() {
        assertThat(IngestMonitorService.state(new SourceRow(1, "a", "PAUSED", "CONNECTED"))).isEqualTo("DISABLED");
        assertThat(IngestMonitorService.state(new SourceRow(1, "a", "ACTIVE", null))).isEqualTo("DISCONNECTED");
        assertThat(IngestMonitorService.state(new SourceRow(1, "a", "ACTIVE", "ERROR"))).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("[DSH-03.02] 처리량 점: 1h는 1분 점 60개, 24h는 5분 점 288개(분당 평균), 빈 구간 0 — TC-DSH-024")
    void throughputPoints() {
        Instant now = Instant.parse("2026-10-03T12:07:30Z");
        List<MinuteCount> counts = List.of(new MinuteCount(1, Instant.parse("2026-10-03T12:06:00Z"), 42),
                new MinuteCount(1, Instant.parse("2026-10-03T12:00:00Z"), 10),
                new MinuteCount(1, Instant.parse("2026-10-03T12:01:00Z"), 20),
                new MinuteCount(9, Instant.parse("2026-10-03T12:01:00Z"), 99));
        List<Throughput> hour = IngestMonitorService.throughput("1h", now, List.of(1L, 2L), counts);
        assertThat(hour).hasSize(2);
        assertThat(hour.getFirst().points()).hasSize(60);
        assertThat(hour.getFirst().points().getFirst()).containsExactly("2026-10-03T11:07:00Z", 0.0);
        assertThat(hour.getFirst().points().getLast()).containsExactly("2026-10-03T12:06:00Z", 42.0);
        assertThat(hour.get(1).points()).allSatisfy(p -> assertThat(p.get(1)).isEqualTo(0.0));
        List<Throughput> day = IngestMonitorService.throughput("24h", now, List.of(1L), counts);
        assertThat(day.getFirst().points()).hasSize(288);
        assertThat(day.getFirst().points().getLast()).containsExactly("2026-10-03T12:00:00Z", 6.0);
        assertThat(IngestMonitorService.fiveMinuteFloor(now)).isEqualTo(Instant.parse("2026-10-03T12:05:00Z"));
        assertThat(IngestMonitorService.perMinute(7, 5)).isEqualTo(1.4);
    }
}
