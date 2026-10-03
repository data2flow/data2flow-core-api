package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import net.java21.data2flow.core.ingest.dto.IngestDtos.CancelJobResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.DecoderInfo;
import net.java21.data2flow.core.ingest.dto.IngestDtos.DiscardRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.DiscardResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.FailureGroup;
import net.java21.data2flow.core.ingest.dto.IngestDtos.FailureGroupsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.FailureItem;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessJobRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessJobResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessPreviewResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessResult;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessSummary;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ScriptInfo;
import net.java21.data2flow.core.ingest.repository.FailureRepository;
import net.java21.data2flow.core.ingest.repository.FailureRepository.DlqTarget;
import net.java21.data2flow.core.ingest.repository.FailureRepository.FailureFilter;
import net.java21.data2flow.core.ingest.repository.FailureRepository.JobRow;
import net.java21.data2flow.core.ingest.repository.FailureRepository.RawTarget;
import net.java21.data2flow.core.ingest.repository.IngestSettingsRepository;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.CancelJobRequest;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.CreateJobRequest;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.CreateJobResponse;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.Item;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.ItemResult;
import net.java21.data2flow.core.ingest.service.PipelineIngestClient.ReprocessItemsRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 실패 메시지 보관함(ING-07.03, UC-ING-07)과 기간 재처리 작업(UC-ING-08): API-ING-07~12.
 * <ul>
 *   <li>조회 INGEST_READ(OPERATOR 이상), 재처리·폐기·작업 INGEST_REPROCESS(INTEGRATOR·ADMIN)</li>
 *   <li>쓰기는 pipeline 내부 API로 한다(dlq_items·reprocess_jobs는 pipeline 소유). 같은 항목을 두 사람이 동시에 처리하지 않도록 core가 먼저
 *       {@code dlq_item_claims}에 5분 동안 잡아 두고(TC-ING-086·089) 호출이 끝나면 놓는다. 이 메서드들은 트랜잭션 밖에서 돌아 잡기가 바로 보인다</li>
 *   <li>요청한 항목이 모두 다른 사람이 처리 중이면 409 ING_DLQ_ITEM_LOCKED, 일부면 그 항목만 결과에 LOCKED</li>
 *   <li>감사: DLQ_REPROCESSED, DLQ_DISCARDED, REPROCESS_STARTED, REPROCESS_CANCELLED</li>
 * </ul>
 */
@Service
public class FailureService {

    static final int MAX_BATCH = 5000;
    static final Duration CLAIM_TTL = Duration.ofMinutes(5);
    static final Duration RAW_RETENTION = Duration.ofDays(30);
    static final Duration DLQ_RETENTION = Duration.ofDays(14);
    static final Duration MAX_SPAN = Duration.ofDays(31);
    /** 재처리 처리량 한도(BR-ING-13, 초당 500건) — 예상 시간 계산용 */
    static final int REPROCESS_PER_SECOND = 500;
    static final Set<String> STAGES = Set.of("DECODE", "SCRIPT", "STORE", "PUBLISH");
    static final Set<String> STATUSES = Set.of("OPEN", "REPROCESSING", "RESOLVED", "DISCARDED");
    static final String AUDIT_REPROCESSED = "DLQ_REPROCESSED";
    static final String AUDIT_DISCARDED = "DLQ_DISCARDED";
    static final String AUDIT_JOB_STARTED = "REPROCESS_STARTED";
    static final String AUDIT_JOB_CANCELLED = "REPROCESS_CANCELLED";

    private final FailureRepository repository;
    private final IngestSettingsRepository claims;
    private final PipelineIngestClient pipeline;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public FailureService(FailureRepository repository, IngestSettingsRepository claims, PipelineIngestClient pipeline,
                          RoleChecker roleChecker, Audits audits, Clock clock) {
        this.repository = repository;
        this.claims = claims;
        this.pipeline = pipeline;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-ING-08 쿼리 */
    public record FailureQuery(String stage, String errorCode, String status, Instant from, Instant to, String groupBy, Integer page,
                               Integer size) {
    }

    // ---------------------------------------------------------------- API-ING-08

    /** API-ING-08 실패 메시지 목록: groupBy=errorCode면 묶음 + 단계별 건수, none(기본)이면 오프셋 목록 */
    public Object list(FailureQuery q) {
        roleChecker.require(Permission.INGEST_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String status = q.status() == null || q.status().isBlank() ? "OPEN" : q.status().strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw IngestMonitorService.invalid("status");
        }
        String stage = q.stage() == null || q.stage().isBlank() ? "" : q.stage().strip().toUpperCase(Locale.ROOT);
        if (!stage.isEmpty() && !STAGES.contains(stage)) {
            throw IngestMonitorService.invalid("stage");
        }
        Instant now = clock.instant();
        Instant to = q.to() == null ? now.plusSeconds(1) : q.to();
        Instant from = q.from() == null ? to.minus(DLQ_RETENTION) : q.from();
        if (!from.isBefore(to)) {
            throw IngestMonitorService.invalid("from");
        }
        if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        String groupBy = q.groupBy() == null || q.groupBy().isBlank() ? "none" : q.groupBy().strip();
        String code = q.errorCode() == null ? "" : q.errorCode().strip();
        FailureFilter filter = new FailureFilter(orgId, status, from, to, stage, code, roleChecker.spaceScope());
        if ("errorCode".equals(groupBy)) {
            Map<String, Long> byStage = new LinkedHashMap<>();
            for (String s : List.of("DECODE", "SCRIPT", "STORE", "PUBLISH")) {
                byStage.put(s, 0L);
            }
            byStage.putAll(repository.countByStage(new FailureFilter(orgId, status, from, to, "", "", filter.scope())));
            List<FailureGroup> groups = repository.findGroups(filter).stream()
                    .map(g -> new FailureGroup(g.errorCode(), g.count(), g.firstAt(), g.lastAt(), g.sampleMessage())).toList();
            return ApiResponse.success(new FailureGroupsResponse(groups, byStage));
        }
        if (!"none".equals(groupBy)) {
            throw IngestMonitorService.invalid("groupBy");
        }
        PageParams page = PageParams.of(q.page(), q.size());
        List<FailureItem> items = repository.findPage(filter, page.offset(), page.size()).stream()
                .map(r -> new FailureItem(Long.toString(r.id()), Long.toString(r.rawMessageId()), r.stage(), r.errorCode(), r.errorMessage(),
                        r.attempts(), r.status(), id(r.sourceId()), r.sourceName(), id(r.deviceId()), r.deviceName(), r.createdAt(),
                        id(r.lockedBy())))
                .toList();
        return ListApiResponse.of(page, items, repository.count(filter));
    }

    // ---------------------------------------------------------------- API-ING-07

    /** API-ING-07 선택 재처리(DLQ 항목 또는 원본, 1~5,000건) */
    public ReprocessResponse reprocess(ReprocessRequest req) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        List<Long> dlqIds = distinct(req == null ? null : req.dlqItemIds());
        List<Long> rawIds = distinct(req == null ? null : req.rawMessageIds());
        if (dlqIds.isEmpty() == rawIds.isEmpty()) {
            throw IngestMonitorService.invalid("dlqItemIds");
        }
        if (dlqIds.size() + rawIds.size() > MAX_BATCH) {
            throw new BusinessException(IngestErrorCode.ING_DLQ_BATCH_TOO_LARGE);
        }
        Instant now = clock.instant();
        SpaceScope scope = roleChecker.spaceScope();
        Map<Long, ReprocessResult> results = new LinkedHashMap<>();
        Map<Long, String> originalErrors = new HashMap<>();
        List<Long> candidates = new ArrayList<>();
        String kind;
        if (!dlqIds.isEmpty()) {
            kind = "DLQ";
            Map<Long, DlqTarget> targets = new HashMap<>();
            repository.findDlqTargets(orgId, dlqIds).forEach(t -> targets.put(t.id(), t));
            for (long id : dlqIds) {
                DlqTarget t = targets.get(id);
                results.put(id, null);
                if (t == null || !visible(scope, t.deviceId(), t.spaceId())) {
                    results.put(id, new ReprocessResult(Long.toString(id), "OTHER_ERROR", "RESOURCE_NOT_FOUND"));
                } else if ("RESOLVED".equals(t.status())) {
                    results.put(id, new ReprocessResult(Long.toString(id), "RESOLVED", null));
                } else if ("DISCARDED".equals(t.status())) {
                    results.put(id, new ReprocessResult(Long.toString(id), "OTHER_ERROR", "DISCARDED"));
                } else if (lockedByOther(t, user.userId(), now)) {
                    results.put(id, locked(id));
                } else {
                    candidates.add(id);
                    originalErrors.put(id, t.errorCode());
                }
            }
        } else {
            kind = "RAW";
            Map<Long, RawTarget> targets = new HashMap<>();
            repository.findRawTargets(orgId, rawIds, now.minus(RAW_RETENTION)).forEach(t -> targets.put(t.id(), t));
            for (long id : rawIds) {
                RawTarget t = targets.get(id);
                if (t == null || !visible(scope, t.deviceId(), t.spaceId())) {
                    results.put(id, new ReprocessResult(Long.toString(id), "OTHER_ERROR", "RESOURCE_NOT_FOUND"));
                } else {
                    results.put(id, null);
                    candidates.add(id);
                    originalErrors.put(id, t.errorCode());
                }
            }
        }
        List<Long> claimed = "DLQ".equals(kind) ? claims.claim(orgId, candidates, user.userId(), now, now.plus(CLAIM_TTL)) : candidates;
        Set<Long> claimedSet = new HashSet<>(claimed);
        for (Long id : candidates) {
            if (!claimedSet.contains(id)) {
                results.put(id, locked(id));
            }
        }
        if (!results.isEmpty() && results.values().stream().allMatch(r -> r != null && "LOCKED".equals(r.outcome()))) {
            throw new BusinessException(IngestErrorCode.ING_DLQ_ITEM_LOCKED);
        }
        if (!claimed.isEmpty()) {
            try {
                var response = pipeline.reprocessItems(new ReprocessItemsRequest(orgId, user.userId(),
                        claimed.stream().map(id -> new Item(kind, id)).toList()));
                Map<Long, ItemResult> byId = new HashMap<>();
                if (response.results() != null) {
                    response.results().forEach(r -> byId.put(r.id(), r));
                }
                for (Long id : claimed) {
                    results.put(id, outcome(id, byId.get(id), originalErrors.get(id)));
                }
            } finally {
                if ("DLQ".equals(kind)) {
                    claims.deleteClaims(orgId, claimed, user.userId());
                }
            }
        }
        List<ReprocessResult> list = results.values().stream().filter(Objects::nonNull).toList();
        ReprocessSummary summary = new ReprocessSummary(count(list, "RESOLVED"), count(list, "SAME_ERROR"), count(list, "OTHER_ERROR"),
                count(list, "LOCKED"));
        audits.record(audits.event(orgId, AUDIT_REPROCESSED).actor(user).target("DLQ", kind)
                .detail("requested", dlqIds.size() + rawIds.size()).detail("kind", kind)
                .detail("resolved", summary.resolved()).detail("sameError", summary.sameError())
                .detail("otherError", summary.otherError()).detail("locked", summary.locked()));
        return new ReprocessResponse(list, summary);
    }

    /** pipeline 결과(OK·FAILED·SKIPPED) → 화면 결과(RESOLVED·SAME_ERROR·OTHER_ERROR·LOCKED) */
    static ReprocessResult outcome(long id, ItemResult result, String originalError) {
        String key = Long.toString(id);
        if (result == null) {
            return new ReprocessResult(key, "OTHER_ERROR", null);
        }
        return switch (result.outcome() == null ? "" : result.outcome()) {
            case "OK" -> new ReprocessResult(key, "RESOLVED", null);
            case "FAILED" -> new ReprocessResult(key, Objects.equals(result.errorCode(), originalError) ? "SAME_ERROR" : "OTHER_ERROR",
                    result.errorCode());
            case "SKIPPED" -> locked(id);
            default -> new ReprocessResult(key, "OTHER_ERROR", result.errorCode());
        };
    }

    // ---------------------------------------------------------------- API-ING-11

    /** API-ING-11 일괄 폐기(사유 2~200자). 원본은 보관 기간까지 남는다(AT-ING-07.2). 감사 1건 */
    public DiscardResponse discard(DiscardRequest req) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        List<Long> ids = distinct(req == null ? null : req.dlqItemIds());
        if (ids.isEmpty()) {
            throw IngestMonitorService.invalid("dlqItemIds");
        }
        if (ids.size() > MAX_BATCH) {
            throw new BusinessException(IngestErrorCode.ING_DLQ_BATCH_TOO_LARGE);
        }
        String reason = req.reason() == null ? "" : req.reason().strip();
        if (reason.length() < 2 || reason.length() > 200) {
            throw IngestMonitorService.invalid("reason");
        }
        Instant now = clock.instant();
        SpaceScope scope = roleChecker.spaceScope();
        List<Long> candidates = new ArrayList<>();
        boolean anyLocked = false;
        for (DlqTarget t : repository.findDlqTargets(orgId, ids)) {
            if (!visible(scope, t.deviceId(), t.spaceId()) || !"OPEN".equals(t.status())) {
                continue;
            }
            if (lockedByOther(t, user.userId(), now)) {
                anyLocked = true;
            } else {
                candidates.add(t.id());
            }
        }
        List<Long> claimed = claims.claim(orgId, candidates, user.userId(), now, now.plus(CLAIM_TTL));
        if (claimed.isEmpty() && (anyLocked || !candidates.isEmpty())) {
            throw new BusinessException(IngestErrorCode.ING_DLQ_ITEM_LOCKED);
        }
        int discarded = 0;
        if (!claimed.isEmpty()) {
            try {
                Integer n = pipeline.discard(new PipelineIngestClient.DiscardRequest(orgId, user.userId(), claimed, reason)).discarded();
                discarded = n == null ? 0 : n;
            } finally {
                claims.deleteClaims(orgId, claimed, user.userId());
            }
        }
        audits.record(audits.event(orgId, AUDIT_DISCARDED).actor(user).target("DLQ", "DISCARD")
                .detail("requested", ids.size()).detail("discarded", discarded).detail("reason", reason));
        return new DiscardResponse(discarded);
    }

    // ---------------------------------------------------------------- API-ING-09·10·12

    /** API-ING-09 재처리 미리 보기 */
    public ReprocessPreviewResponse preview(ReprocessJobRequest req) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        long orgId = roleChecker.currentUser().organizationId();
        JobTarget target = jobTarget(orgId, req);
        Map<String, Long> byStatus = repository.countRawForReprocess(orgId, target.sourceId(), target.deviceIds(), req.from(), req.to());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        List<ScriptInfo> scripts = repository.findSourceScripts(orgId, target.sourceId()).stream()
                .map(s -> new ScriptInfo(s.scope(), Long.toString(s.scriptId()), s.name(), s.version())).toList();
        return new ReprocessPreviewResponse(total, byStatus, (total + REPROCESS_PER_SECOND - 1) / REPROCESS_PER_SECOND,
                new DecoderInfo(target.decoder(), null), scripts);
    }

    /** API-ING-10 재처리 작업 만들기(pipeline API-ING-23). 같은 소스에 실행 중 작업이 있으면 409 */
    public ReprocessJobResponse createJob(ReprocessJobRequest req) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JobTarget target = jobTarget(orgId, req);
        if (req.memo() != null && req.memo().length() > 200) {
            throw IngestMonitorService.invalid("memo");
        }
        if (repository.existsRunningJob(orgId, target.sourceId())) {
            throw new BusinessException(IngestErrorCode.ING_REPROCESS_ALREADY_RUNNING);
        }
        CreateJobResponse created = pipeline.createJob(new CreateJobRequest(orgId, user.userId(), target.sourceId(),
                target.deviceIds().isEmpty() ? null : target.deviceIds(), req.from(), req.to(), false, req.memo()));
        if (created.jobId() == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        long total = created.estimatedCount() != null ? created.estimatedCount()
                : repository.countRawForReprocess(orgId, target.sourceId(), target.deviceIds(), req.from(), req.to()).values().stream()
                        .mapToLong(Long::longValue).sum();
        audits.record(audits.event(orgId, AUDIT_JOB_STARTED).actor(user).target("REPROCESS_JOB", created.jobId().toString())
                .detail("sourceId", target.sourceId()).detail("from", req.from().toString()).detail("to", req.to().toString())
                .detail("deviceIds", target.deviceIds()).detail("total", total));
        // pipeline은 QUEUED, 문서 API-ING-10과 reprocess_jobs.status는 PENDING — 외부에는 PENDING으로 맞춘다
        String status = created.status() == null || "QUEUED".equals(created.status()) ? "PENDING" : created.status();
        return new ReprocessJobResponse(created.jobId().toString(), status, total);
    }

    /** API-ING-12 재처리 작업 취소. 이미 끝난 작업은 409 ING_REPROCESS_NOT_CANCELLABLE */
    public CancelJobResponse cancelJob(long jobId) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JobRow job = repository.findJob(orgId, jobId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        if (!scope.unrestricted()) {
            Map<Long, Long> spaces = job.deviceIds().isEmpty() ? Map.of() : repository.findDeviceSpaces(orgId, job.deviceIds());
            if (job.deviceIds().isEmpty() || spaces.size() != job.deviceIds().size()
                    || !spaces.values().stream().allMatch(s -> s != null && scope.includes(s))) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        if (!"PENDING".equals(job.status()) && !"RUNNING".equals(job.status())) {
            throw new BusinessException(IngestErrorCode.ING_REPROCESS_NOT_CANCELLABLE);
        }
        pipeline.cancelJob(jobId, new CancelJobRequest(orgId, user.userId()));
        audits.record(audits.event(orgId, AUDIT_JOB_CANCELLED).actor(user).target("REPROCESS_JOB", Long.toString(jobId))
                .detail("processed", job.processed()));
        return new CancelJobResponse(Long.toString(jobId), "CANCELLED", job.processed());
    }

    private record JobTarget(long sourceId, String decoder, List<Long> deviceIds) {
    }

    /**
     * 재처리 대상 확인: 소스(조직 안), 기간(31일, 원본 보관 30일 안), 기기(조직 안·공간 범위 안). 공간 범위가 제한된 사용자는 기기를 꼭 골라야 한다
     * (소스 전체에는 범위 밖 기기가 섞일 수 있음).
     */
    private JobTarget jobTarget(long orgId, ReprocessJobRequest req) {
        if (req == null || req.sourceId() == null) {
            throw IngestMonitorService.invalid("sourceId");
        }
        if (req.from() == null || req.to() == null || !req.from().isBefore(req.to())) {
            throw IngestMonitorService.invalid("from");
        }
        if (Duration.between(req.from(), req.to()).compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        if (req.from().isBefore(clock.instant().minus(RAW_RETENTION))) {
            throw new BusinessException(IngestErrorCode.ING_REPROCESS_OUT_OF_RETENTION);
        }
        String decoder = repository.findSourceDecoder(orgId, req.sourceId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        List<Long> deviceIds = distinct(req.deviceIds());
        SpaceScope scope = roleChecker.spaceScope();
        if (deviceIds.isEmpty() && !scope.unrestricted()) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        if (!deviceIds.isEmpty()) {
            Map<Long, Long> spaces = repository.findDeviceSpaces(orgId, deviceIds);
            for (Long id : deviceIds) {
                if (!spaces.containsKey(id) || !(scope.unrestricted() || scope.includes(spaces.get(id)))) {
                    throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
        return new JobTarget(req.sourceId(), decoder, deviceIds);
    }

    private static boolean visible(SpaceScope scope, Long deviceId, Long spaceId) {
        return scope.unrestricted() || (deviceId != null && spaceId != null && scope.includes(spaceId));
    }

    private static boolean lockedByOther(DlqTarget t, long userId, Instant now) {
        if ("REPROCESSING".equals(t.status())) {
            return true;
        }
        return t.lockedUntil() != null && t.lockedUntil().isAfter(now) && !Objects.equals(t.lockedBy(), userId);
    }

    private static ReprocessResult locked(long id) {
        return new ReprocessResult(Long.toString(id), "LOCKED", IngestErrorCode.ING_DLQ_ITEM_LOCKED.code());
    }

    private static int count(List<ReprocessResult> results, String outcome) {
        return (int) results.stream().filter(r -> outcome.equals(r.outcome())).count();
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }
}
