package net.java21.data2flow.core.ingest.dto;

import net.java21.data2flow.core.ingest.dto.IngestDtos.QualityItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** M5 수집 관리 조회: 재처리 작업 목록(API-ING-14), 품질 추이·요약(ING-06.02), 공백·완전성(API-TSD-09) */
public final class IngestInsightDtos {

    private IngestInsightDtos() {
    }

    /** API-ING-14 항목. progressPercent는 처리 건수 ÷ 대상 건수(0~100, 소수 1자리) */
    public record ReprocessJobItem(String jobId, String sourceId, String sourceName, List<String> deviceIds, Instant from, Instant to,
                                   String status, long total, long processed, long failed, long skipped, double progressPercent,
                                   boolean onlyFailed, String requestedBy, String requestedByName, String memo, String error, Instant createdAt,
                                   Instant startedAt, Instant finishedAt) {
    }

    /** 품질 추이 한 날 */
    public record TrendPoint(LocalDate day, int score, int completeness, int timeliness, int validity, int stability, int devices) {
    }

    public record QualityTrend(String groupBy, String targetId, LocalDate from, LocalDate to, List<TrendPoint> points) {
    }

    /** 문제 유형 분포(공백·범위 초과·의심(값 멈춤·급변)·지연 도착) */
    public record IssueDistribution(long gaps, long outOfRange, long suspect, long late, long expected, long received) {
    }

    public record QualitySummary(LocalDate day, long devices, int averageScore, List<QualityItem> bottom10, IssueDistribution distribution) {
    }

    /** API-TSD-09 공백 한 건 */
    public record GapEntry(String deviceId, Instant from, Instant to, long expectedCount) {
    }

    /** API-TSD-09 기기별 완전성. completenessPercent = 수신 추정 ÷ 예상 × 100(소수 1자리) */
    public record DeviceCompleteness(String deviceId, String deviceName, int intervalSec, long expected, long missing, double completenessPercent) {
    }

    public record GapsResponse(Instant from, Instant to, List<GapEntry> gaps, List<DeviceCompleteness> devices) {
    }
}
