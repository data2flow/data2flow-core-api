package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.ingest.domain.IngestAlertRules;
import net.java21.data2flow.core.ingest.domain.IngestAlertRules.Alert;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import net.java21.data2flow.core.ingest.dto.IngestDtos.AlertResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.IngestSummaryResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.MetricPoint;
import net.java21.data2flow.core.ingest.dto.IngestDtos.MetricsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsIngestMetricsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsIngestSeries;
import net.java21.data2flow.core.ingest.dto.IngestDtos.SourceSummary;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository.BucketCount;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository.BucketLatency;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository.SourceRow;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository.SourceStatusCount;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 수집 모니터(ING-01.02, ING-07.04, OPS-01.02, OPS-01.05): API-ING-01 요약, API-ING-02 지표 시계열, API-OPS-02 운영 수집 지표.
 * 원천은 pipeline이 남긴 원본 처리 결과({@code raw_messages.status}·{@code processed_at})다. Prometheus 지표(OPS-02.01)가 생기면
 * 같은 값을 그쪽에서도 볼 수 있다. 조직 단위 운영 지표라 공간 범위는 적용하지 않는다(개별 원본 목록은 API-ING-05에서 범위를 적용).
 */
@Service
public class IngestMonitorService {

    /** 처리 결과 기본 키(웹 RESULT_CODES와 같다). 그 밖의 상태(STORE_ERROR 등)는 있을 때만 더한다 */
    static final List<String> RESULT_CODES = List.of("OK", "DECODE_ERROR", "SCRIPT_ERROR", "DUPLICATE", "UNKNOWN_DEVICE_REJECTED", "INVALID");
    static final Duration RECENT = Duration.ofMinutes(5);
    static final Duration METRICS_MAX_SPAN = Duration.ofHours(24);

    private final IngestMonitorRepository repository;
    private final IngestSettingsService settings;
    private final RoleChecker roleChecker;
    private final MessageSource messages;
    private final Clock clock;

    public IngestMonitorService(IngestMonitorRepository repository, IngestSettingsService settings, RoleChecker roleChecker,
                                MessageSource messages, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.messages = messages;
        this.clock = clock;
    }

    /** API-ING-01 수집 요약(window 1h 기본·6h·24h) */
    @Transactional(readOnly = true)
    public IngestSummaryResponse summary(String windowParam) {
        roleChecker.require(Permission.INGEST_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String window = windowParam == null || windowParam.isBlank() ? "1h" : windowParam.strip();
        Duration span = switch (window) {
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "24h" -> Duration.ofHours(24);
            default -> throw invalid("window");
        };
        Instant now = clock.instant();
        Instant until = now.plusSeconds(1);
        Instant since = now.minus(span);
        Instant recentSince = now.minus(RECENT);

        List<SourceRow> sources = repository.findSources(orgId);
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        Map<Long, Instant> lastAt = new HashMap<>();
        for (SourceStatusCount c : repository.countBySourceAndStatus(orgId, since, until)) {
            counts.computeIfAbsent(c.sourceId(), k -> new HashMap<>()).merge(c.status(), c.count(), Long::sum);
            lastAt.merge(c.sourceId(), c.lastAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<Long, Long> recent = new HashMap<>();
        long recentTotal = 0;
        long recentScriptErrors = 0;
        for (SourceStatusCount c : repository.countBySourceAndStatus(orgId, recentSince, until)) {
            recent.merge(c.sourceId(), c.count(), Long::sum);
            recentTotal += c.count();
            if ("SCRIPT_ERROR".equals(c.status())) {
                recentScriptErrors += c.count();
            }
        }
        double perMinute = round1(recentTotal / (double) RECENT.toMinutes());

        List<SourceSummary> summaries = new ArrayList<>();
        int activeSources = 0;
        boolean anyDisconnected = false;
        long windowTotal = 0;
        for (SourceRow s : sources) {
            Map<String, Long> byStatus = resultCounts(counts.getOrDefault(s.id(), Map.of()));
            windowTotal += counts.getOrDefault(s.id(), Map.of()).values().stream().mapToLong(Long::longValue).sum();
            Instant last = lastAt.get(s.id()) != null ? lastAt.get(s.id()) : s.lastStatMinute();
            summaries.add(new SourceSummary(Long.toString(s.id()), s.name(), s.type(), s.connection(),
                    round1(recent.getOrDefault(s.id(), 0L) / (double) RECENT.toMinutes()), byStatus, last));
            if ("ACTIVE".equals(s.lifecycle())) {
                activeSources++;
                anyDisconnected |= s.connection() != null && !"CONNECTED".equals(s.connection());
            }
        }

        var latency = repository.findLatency(orgId, recentSince, until);
        Long lagSec = repository.findOldestPending(orgId, now.minus(Duration.ofDays(1)))
                .map(oldest -> Math.max(0, Duration.between(oldest, now).toSeconds())).orElse(0L);
        ZoneId zone = zone(repository.findOrganizationTimezone(orgId));
        long failuresToday = repository.countDlqSince(orgId, now.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant());

        IngestSettingsService.AlertSettings alertSettings = settings.alertSettings(orgId);
        int zeroMinutes = (int) Math.max(1, Math.round(alertSettings.zeroMinutes()));
        long receivedInZeroWindow = zeroMinutes == RECENT.toMinutes() ? recentTotal
                : repository.countBySourceAndStatus(orgId, now.minus(Duration.ofMinutes(zeroMinutes)), until).stream()
                        .mapToLong(SourceStatusCount::count).sum();
        double baselinePerMinute = span.toMinutes() > RECENT.toMinutes()
                ? Math.max(0, windowTotal - recentTotal) / (double) (span.toMinutes() - RECENT.toMinutes()) : 0;
        double surge = baselinePerMinute <= 0 ? 0 : (recentTotal / (double) RECENT.toMinutes()) / baselinePerMinute;
        List<Alert> alerts = IngestAlertRules.evaluate(new IngestAlertRules.Inputs(activeSources, receivedInZeroWindow,
                alertSettings.zeroEnabled(), zeroMinutes, lagSec, alertSettings.lagWarnSec(), alertSettings.lagCriticalSec(),
                recentTotal == 0 ? 0 : recentScriptErrors / (double) recentTotal, surge,
                repository.countDlqSince(orgId, now.minus(Duration.ofMinutes(10))), alertSettings.dlqEnabled(), alertSettings.dlqPer10Min(),
                anyDisconnected));
        List<AlertResponse> alertResponses = alerts.stream()
                .map(a -> new AlertResponse(a.level(), a.code(), message(a), a.causeHints())).toList();
        return new IngestSummaryResponse(window, perMinute, latency.map(l -> l.p50()).orElse(null), latency.map(l -> l.p95()).orElse(null),
                lagSec, null, failuresToday, alertResponses, summaries);
    }

    private String message(Alert alert) {
        return messages.getMessage("ingest.alert." + alert.code(), null, alert.code(), LocaleContextHolder.getLocale());
    }

    /** API-ING-02 수집 지표 시계열(최대 24시간, step 1m·5m). 빈 구간은 0으로 채운다 */
    @Transactional(readOnly = true)
    public MetricsResponse metrics(Instant fromParam, Instant toParam, Long sourceId, String stepParam) {
        roleChecker.require(Permission.INGEST_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Instant now = clock.instant();
        Instant to = toParam == null ? now : toParam;
        Instant from = fromParam == null ? to.minus(Duration.ofHours(1)) : fromParam;
        if (!from.isBefore(to)) {
            throw invalid("from");
        }
        if (Duration.between(from, to).compareTo(METRICS_MAX_SPAN) > 0) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        String step = stepParam == null || stepParam.isBlank() ? "1m" : stepParam.strip();
        Duration stepDuration = switch (step) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            default -> throw invalid("step");
        };
        if (sourceId != null && !repository.existsSource(orgId, sourceId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant origin = floor(from, stepDuration);
        String interval = stepDuration.toMinutes() + " minutes";
        Map<Instant, Map<String, Long>> byBucket = new TreeMap<>();
        for (BucketCount c : repository.countByBucket(orgId, sourceId, origin, to, interval)) {
            byBucket.computeIfAbsent(c.bucket(), k -> new LinkedHashMap<>()).merge(c.status(), c.count(), Long::sum);
        }
        Map<Instant, BucketLatency> latency = new HashMap<>();
        for (BucketLatency l : repository.findLatencyByBucket(orgId, sourceId, origin, to, interval)) {
            latency.put(l.bucket(), l);
        }
        List<MetricPoint> points = new ArrayList<>();
        for (Instant t = origin; t.isBefore(to); t = t.plus(stepDuration)) {
            Map<String, Long> statuses = resultCounts(byBucket.getOrDefault(t, Map.of()));
            BucketLatency l = latency.get(t);
            points.add(new MetricPoint(t, statuses.values().stream().mapToLong(Long::longValue).sum(), statuses,
                    l == null ? null : l.p50(), l == null ? null : l.p95()));
        }
        return new MetricsResponse(step, points);
    }

    /**
     * API-OPS-02 운영 수집 지표(OPS-01.02, ADMIN): 24h는 5분 구간, 7d는 1시간 구간. 분당 수신·지연 p50/p95·처리 대기(그 구간에 받아 아직
     * RECEIVED인 건수) 추이와 기간 전체의 처리 결과별 건수.
     */
    @Transactional(readOnly = true)
    public OpsIngestMetricsResponse opsMetrics(String rangeParam) {
        roleChecker.require(Permission.OPS_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        String range = rangeParam == null || rangeParam.isBlank() ? "24h" : rangeParam.strip();
        Duration span;
        Duration step;
        switch (range) {
            case "24h" -> {
                span = Duration.ofHours(24);
                step = Duration.ofMinutes(5);
            }
            case "7d" -> {
                span = Duration.ofDays(7);
                step = Duration.ofHours(1);
            }
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("range", "INVALID", null)));
        }
        Instant now = clock.instant();
        Instant to = floor(now, step).plus(step);
        Instant from = to.minus(span);
        String interval = step.toMinutes() + " minutes";
        Map<Instant, Long> received = new TreeMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (BucketCount c : repository.countByBucket(orgId, null, from, to, interval)) {
            received.merge(c.bucket(), c.count(), Long::sum);
            totals.merge(c.status(), c.count(), Long::sum);
        }
        Map<Instant, BucketLatency> latency = new HashMap<>();
        for (BucketLatency l : repository.findLatencyByBucket(orgId, null, from, to, interval)) {
            latency.put(l.bucket(), l);
        }
        List<List<Object>> perMin = new ArrayList<>();
        List<List<Object>> p50 = new ArrayList<>();
        List<List<Object>> p95 = new ArrayList<>();
        List<List<Object>> backlog = new ArrayList<>();
        for (Instant t = from; t.isBefore(to); t = t.plus(step)) {
            BucketLatency l = latency.get(t);
            perMin.add(Arrays.asList(t.toString(), round1(received.getOrDefault(t, 0L) / (double) step.toMinutes())));
            p50.add(Arrays.asList(t.toString(), l == null ? null : l.p50()));
            p95.add(Arrays.asList(t.toString(), l == null ? null : l.p95()));
            backlog.add(Arrays.asList(t.toString(), l == null ? 0L : l.pending()));
        }
        Map<String, Long> resultCounts = resultCounts(totals);
        return new OpsIngestMetricsResponse(range, new OpsIngestSeries(perMin, p50, p95, backlog), resultCounts);
    }

    /** 기본 6개 결과 키(0 포함) + 그 밖에 나온 결과(RECEIVED 제외) */
    static Map<String, Long> resultCounts(Map<String, Long> raw) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String code : RESULT_CODES) {
            result.put(code, raw.getOrDefault(code, 0L));
        }
        raw.forEach((k, v) -> {
            if (!result.containsKey(k) && !"RECEIVED".equals(k)) {
                result.put(k, v);
            }
        });
        return result;
    }

    private static Instant floor(Instant t, Duration step) {
        long s = step.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), s) * s);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException | NullPointerException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
