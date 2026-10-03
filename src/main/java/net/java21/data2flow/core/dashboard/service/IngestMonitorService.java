package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.IngestMonitorResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.SourceSnapshot;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.StageSnapshot;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.Throughput;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.MinuteCount;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.SourceRow;
import net.java21.data2flow.core.dashboard.repository.IngestStatsRepository.StatSums;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 흐름 스냅샷(API-DSH-05, DSH-03.01·03.02)과 실시간 {@code ingest-stats}(API-DSH-20 topic {@code ingest}, 5초).
 *
 * <p>단계 6개(SOURCE → DECODE → SCRIPT → VALIDATE → STORE → PUBLISH)의 처리·실패는 최근 5분(끝난 분까지) 평균 분당 값이다.
 * 원천은 소스별 분 통계({@code source_stat_1m}: received·dup·decode_errors·script_errors·invalid·rejected_unknown·accepted)와
 * 실패 보관함({@code dlq_items}: STORE·PUBLISH)이다. 문서의 "Micrometer → Valkey 집계"(TC-DSH-020)는 아직 원천이 없어
 * {@code latencyP95Ms}는 null이다.
 * <ul>
 *   <li>SOURCE: 처리 = received, 실패 없음</li>
 *   <li>DECODE: 처리 = received − dup, 실패 = decode_errors → 실패 목록 {@code stage=DECODE&code=DECODE_ERROR}</li>
 *   <li>SCRIPT: 처리 = DECODE 처리 − decode_errors, 실패 = script_errors → {@code stage=SCRIPT&code=SCRIPT_ERROR}(AT-DSH-03.1)</li>
 *   <li>VALIDATE: 처리 = SCRIPT 처리 − script_errors, 실패 = invalid + rejected_unknown(실패 보관함 단계가 없어 링크 없음)</li>
 *   <li>STORE: 처리 = accepted, 실패 = dlq STORE → {@code stage=STORE}</li>
 *   <li>PUBLISH: 처리 = accepted − STORE 실패, 실패 = dlq PUBLISH → {@code stage=PUBLISH}</li>
 * </ul>
 * 실패 보관함은 소스 정보가 없어 공간 범위가 제한된 사용자에게는 STORE·PUBLISH 실패를 0으로 보인다(BR-DSH-01: 범위 밖 수치를 보이지 않음).
 */
@Service
public class IngestMonitorService {

    public static final List<String> STAGES = List.of("SOURCE", "DECODE", "SCRIPT", "VALIDATE", "STORE", "PUBLISH");
    static final int STAGE_WINDOW_MINUTES = 5;
    static final String FAILURES_PATH = "/ingest/failures";

    private final RoleChecker roleChecker;
    private final IngestStatsRepository repository;
    private final Clock clock;

    public IngestMonitorService(RoleChecker roleChecker, IngestStatsRepository repository, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.clock = clock;
    }

    /** API-DSH-05 GET /core/monitoring/ingest?window=1h|24h — OPERATOR 이상(INGEST_READ) */
    @Transactional(readOnly = true)
    public IngestMonitorResponse snapshot(String window) {
        AccessGrant grant = roleChecker.require(Permission.INGEST_READ);
        String w = window == null || window.isBlank() ? "1h" : window;
        if (!"1h".equals(w) && !"24h".equals(w)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("window", "INVALID", "1h|24h")));
        }
        return compute(roleChecker.currentUser().organizationId(), grant.spaceScope(), w);
    }

    /** 실시간 {@code ingest-stats}: 단계와 소스만(처리량 추이는 스냅샷 API로) */
    @Transactional(readOnly = true)
    public IngestMonitorResponse live(long organizationId, SpaceScope scope) {
        return compute(organizationId, scope, null);
    }

    IngestMonitorResponse compute(long organizationId, SpaceScope scope, String window) {
        Instant now = clock.instant();
        Instant minuteEnd = now.truncatedTo(ChronoUnit.MINUTES);
        Instant stageFrom = minuteEnd.minus(STAGE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        List<StageSnapshot> stages = stages(repository.sumStats(organizationId, scope, stageFrom, minuteEnd),
                scope.unrestricted() ? repository.countDlqByStage(organizationId, now.minus(Duration.ofMinutes(STAGE_WINDOW_MINUTES)), now)
                        : Map.of());

        List<SourceRow> sourceRows = repository.findSources(organizationId, scope);
        List<Long> ids = sourceRows.stream().map(SourceRow::id).toList();
        Instant countsFrom = stageFrom;
        if ("24h".equals(window)) {
            countsFrom = fiveMinuteFloor(now).minus(Duration.ofHours(24));
        } else if ("1h".equals(window)) {
            countsFrom = minuteEnd.minus(Duration.ofHours(1));
        }
        List<MinuteCount> counts = repository.findMinuteCounts(organizationId, ids, countsFrom, minuteEnd);
        Map<Long, Long> recent = new HashMap<>();
        for (MinuteCount c : counts) {
            if (!c.minute().isBefore(stageFrom)) {
                recent.merge(c.sourceId(), c.received(), Long::sum);
            }
        }
        Map<Long, Instant> last = repository.findLastActiveMinutes(organizationId, ids, now.minus(Duration.ofHours(24)));
        List<SourceSnapshot> sources = sourceRows.stream()
                .map(s -> new SourceSnapshot(Long.toString(s.id()), s.name(), state(s),
                        perMinute(recent.getOrDefault(s.id(), 0L), STAGE_WINDOW_MINUTES), last.get(s.id())))
                .toList();
        List<Throughput> throughput = window == null ? null : throughput(window, now, ids, counts);
        return new IngestMonitorResponse(window, stages, sources, throughput);
    }

    static List<StageSnapshot> stages(StatSums s, Map<String, Long> dlq) {
        long source = s.received();
        long decodeIn = Math.max(0, source - s.dup());
        long scriptIn = Math.max(0, decodeIn - s.decodeErrors());
        long validateIn = Math.max(0, scriptIn - s.scriptErrors());
        long storeFail = dlq.getOrDefault("STORE", 0L);
        long publishFail = dlq.getOrDefault("PUBLISH", 0L);
        int w = STAGE_WINDOW_MINUTES;
        return List.of(
                new StageSnapshot("SOURCE", perMinute(source, w), 0, null, null),
                new StageSnapshot("DECODE", perMinute(decodeIn, w), perMinute(s.decodeErrors(), w), null, link("DECODE", "DECODE_ERROR")),
                new StageSnapshot("SCRIPT", perMinute(scriptIn, w), perMinute(s.scriptErrors(), w), null, link("SCRIPT", "SCRIPT_ERROR")),
                new StageSnapshot("VALIDATE", perMinute(validateIn, w), perMinute(s.invalid() + s.rejectedUnknown(), w), null, null),
                new StageSnapshot("STORE", perMinute(s.accepted(), w), perMinute(storeFail, w), null, link("STORE", null)),
                new StageSnapshot("PUBLISH", perMinute(Math.max(0, s.accepted() - storeFail), w), perMinute(publishFail, w), null,
                        link("PUBLISH", null)));
    }

    static String link(String stage, String code) {
        return FAILURES_PATH + "?stage=" + stage + (code == null ? "" : "&code=" + code);
    }

    static double perMinute(long count, int minutes) {
        return Math.round(count * 10.0 / minutes) / 10.0;
    }

    /** 대표 상태: 운영 중지·초안이면 DISABLED, 런타임 보고가 있으면 가장 좋은 인스턴스 상태, 없으면 DISCONNECTED */
    static String state(SourceRow s) {
        if (!"ACTIVE".equals(s.lifecycle())) {
            return "DISABLED";
        }
        return s.runtimeState() == null ? "DISCONNECTED" : s.runtimeState();
    }

    /** 1h: 1분 점 60개, 24h: 5분 점 288개(분당 평균). 수신이 없던 구간은 0 */
    static List<Throughput> throughput(String window, Instant now, List<Long> ids, List<MinuteCount> counts) {
        boolean day = "24h".equals(window);
        int step = day ? 5 : 1;
        int points = day ? 288 : 60;
        Instant end = day ? fiveMinuteFloor(now) : now.truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(Duration.ofMinutes((long) step * points));
        Map<Long, long[]> buckets = new HashMap<>();
        for (Long id : ids) {
            buckets.put(id, new long[points]);
        }
        for (MinuteCount c : counts) {
            long index = Duration.between(start, c.minute()).toMinutes() / step;
            long[] b = buckets.get(c.sourceId());
            if (b != null && index >= 0 && index < points) {
                b[(int) index] += c.received();
            }
        }
        List<Throughput> result = new ArrayList<>();
        for (Long id : ids) {
            long[] b = buckets.get(id);
            List<List<Object>> series = new ArrayList<>(points);
            for (int i = 0; i < points; i++) {
                series.add(List.of(start.plus(Duration.ofMinutes((long) i * step)).toString(), perMinute(b[i], step)));
            }
            result.add(new Throughput(Long.toString(id), series));
        }
        return result;
    }

    static Instant fiveMinuteFloor(Instant t) {
        Instant minute = t.truncatedTo(ChronoUnit.MINUTES);
        long epochMinute = minute.getEpochSecond() / 60;
        return minute.minus(Duration.ofMinutes(epochMinute % 5));
    }
}
