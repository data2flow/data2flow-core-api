package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.dataexchange.domain.ExchangeErrorCode;
import net.java21.data2flow.core.dataexchange.domain.ExportFormat;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportCreatedResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportJobResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportRequest;
import net.java21.data2flow.core.dataexchange.repository.ExchangeFileRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportDataRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository.ExportJobRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 내보내기 API(TSD-04.01, API-TSD-20~22, BR-TSD-14, AT-TSD-04.1~04.5). TS_EXPORT(ANALYST 이상). 목록·상세는 본인 작업(ADMIN은 조직 전체).
 * 예상 행 수가 100만 이하면 바로 만들어 보고 30초 안에 끝나면 동기(SYNC + 다운로드 링크), 아니면 비동기(ASYNC + jobId)로 응답한다.
 * 사용자당 진행 중 작업이 3개면 429 {@code EXPORT_LIMIT_EXCEEDED}. 결과 파일은 7일, 링크는 1시간.
 */
@Service
public class ExportService {

    static final String OWNER = ExportJobRunner.OWNER;

    private final RoleChecker roleChecker;
    private final ExportPlanner planner;
    private final ExportDataRepository data;
    private final ExportJobRepository jobs;
    private final ExchangeFileRepository files;
    private final ExportJobRunner runner;
    private final DataDictionaryService dictionary;
    private final ExportLinkSigner signer;
    private final ExchangeProperties properties;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public ExportService(RoleChecker roleChecker, ExportPlanner planner, ExportDataRepository data, ExportJobRepository jobs,
                         ExchangeFileRepository files, ExportJobRunner runner, DataDictionaryService dictionary, ExportLinkSigner signer,
                         ExchangeProperties properties, Audits audits, PlatformTransactionManager txManager, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.planner = planner;
        this.data = data;
        this.jobs = jobs;
        this.files = files;
        this.runner = runner;
        this.dictionary = dictionary;
        this.signer = signer;
        this.properties = properties;
        this.audits = audits;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    /** API-TSD-20 */
    public ExportCreatedResponse create(ExportRequest req) {
        roleChecker.require(Permission.TS_EXPORT);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        if (req == null || req.query() == null) {
            throw invalid("query");
        }
        ExportFormat format = req.format() == null ? ExportFormat.CSV : ExportFormat.parse(req.format());
        if (format == null) {
            throw invalid("format");
        }
        String layout = req.columns() == null || req.columns().isBlank() ? "LONG" : req.columns().strip().toUpperCase(Locale.ROOT);
        if (!"LONG".equals(layout) && !"WIDE".equals(layout) || format == ExportFormat.PARQUET && "WIDE".equals(layout)) {
            throw invalid("columns");
        }
        ExportPlan plan = planner.plan(req.query(), layout, req.includeQuality() == null || req.includeQuality(), req.tz(), null);
        if (jobs.countActive(org, user.userId()) >= properties.maxActivePerUser()) {
            throw new BusinessException(ExchangeErrorCode.EXPORT_LIMIT_EXCEEDED);
        }
        long estimated = tx.execute(s -> data.countRows(org, plan, properties.importMaxRows()));
        Instant now = clock.instant();
        ExportJobRow job = tx.execute(s -> {
            int dict = dictionary.currentVersion(org);
            long id = jobs.insert(org, user.userId(), null, json.writeValueAsString(req.query()), json.writeValueAsString(plan),
                    format.name(), estimated, dict, now);
            audits.record(audits.event(org, "TELEMETRY_EXPORT_REQUESTED").actor(user).target("EXPORT", Long.toString(id))
                    .detail("format", format.name()).detail("series", plan.series().size()).detail("estimatedRows", estimated));
            return jobs.findById(org, id).orElseThrow();
        });
        if (estimated <= properties.syncMaxRows() && Boolean.TRUE.equals(tx.execute(s -> jobs.updateClaim(org, job.id(), now)))) {
            Future<String> result = runner.submit(job);
            try {
                String status = result.get(properties.syncTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if ("SUCCEEDED".equals(status)) {
                    return new ExportCreatedResponse("SYNC", Long.toString(job.id()),
                            signer.url(org, job.id(), clock.instant().plus(properties.linkTtl())), estimated);
                }
            } catch (TimeoutException ex) {
                // 30초 안에 끝나지 않으면 비동기로 이어 간다(BR-TSD-14). 끝나면 EVT-TSD-01
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                // 실패는 작업 상태(FAILED)로 남는다
            }
        }
        return new ExportCreatedResponse("ASYNC", Long.toString(job.id()), null, estimated);
    }

    /** API-TSD-21 목록(본인, ADMIN은 조직 전체) */
    public ListApiResponse<ExportJobResponse> list(Integer page, Integer size) {
        roleChecker.require(Permission.TS_EXPORT);
        CurrentUser user = roleChecker.currentUser();
        Long owner = roleChecker.has(Permission.IAM_MANAGE) ? null : user.userId();
        PageParams params = PageParams.of(page, size);
        List<ExportJobResponse> items = jobs.list(user.organizationId(), owner, params.size(), params.offset()).stream()
                .map(this::toResponse).toList();
        return ListApiResponse.of(params, items, jobs.count(user.organizationId(), owner));
    }

    /** API-TSD-21 상세(완료면 새 1시간 링크) */
    public ExportJobResponse get(long id) {
        roleChecker.require(Permission.TS_EXPORT);
        return toResponse(load(id));
    }

    /** API-TSD-22 취소(QUEUED·RUNNING만, 끝난 작업은 409) */
    public ExportJobResponse cancel(long id) {
        roleChecker.require(Permission.TS_EXPORT);
        ExportJobRow job = load(id);
        boolean done = Boolean.TRUE.equals(tx.execute(s -> jobs.updateCancelled(job.organizationId(), id, clock.instant())));
        if (!done) {
            throw new BusinessException(ExchangeErrorCode.EXPORT_STATE_CONFLICT);
        }
        audits.record(audits.event(job.organizationId(), "TELEMETRY_EXPORT_CANCELLED").actor(roleChecker.currentUser())
                .target("EXPORT", Long.toString(id)));
        return toResponse(jobs.findById(job.organizationId(), id).orElseThrow());
    }

    /** 내려받을 파일 */
    public record Download(String fileName, String contentType, long bytes, InputStream content) {
    }

    /** 서명 링크로 내려받기. 링크 1시간·파일 7일이 지나면 410 {@code EXPORT_EXPIRED} */
    public Download download(long id, Long expires, String signature) {
        roleChecker.require(Permission.TS_EXPORT);
        ExportJobRow job = load(id);
        if (expires == null || !signer.verify(job.organizationId(), id, expires, signature)) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        Instant now = clock.instant();
        if (Instant.ofEpochSecond(expires).isBefore(now) || "EXPIRED".equals(job.status())
                || job.expiresAt() != null && !job.expiresAt().isAfter(now)) {
            throw new BusinessException(ExchangeErrorCode.EXPORT_EXPIRED);
        }
        if (!"SUCCEEDED".equals(job.status())) {
            throw new BusinessException(ExchangeErrorCode.EXPORT_NOT_FOUND);
        }
        ExportFormat format = ExportFormat.valueOf(job.format());
        audits.record(audits.event(job.organizationId(), "TELEMETRY_EXPORT_DOWNLOADED").actor(roleChecker.currentUser())
                .target("EXPORT", Long.toString(id)));
        return new Download("data2flow_export_" + id + "." + format.extension(), format.contentType(), job.bytes() == null ? -1 : job.bytes(),
                files.open(job.organizationId(), OWNER, id));
    }

    private ExportJobRow load(long id) {
        CurrentUser user = roleChecker.currentUser();
        ExportJobRow job = jobs.findById(user.organizationId(), id).orElseThrow(() -> new BusinessException(ExchangeErrorCode.EXPORT_NOT_FOUND));
        if (job.requestedBy() != user.userId() && !roleChecker.has(Permission.IAM_MANAGE)) {
            throw new BusinessException(ExchangeErrorCode.EXPORT_NOT_FOUND);
        }
        return job;
    }

    ExportJobResponse toResponse(ExportJobRow j) {
        Instant now = clock.instant();
        String url = "SUCCEEDED".equals(j.status()) && (j.expiresAt() == null || j.expiresAt().isAfter(now))
                ? signer.url(j.organizationId(), j.id(), now.plus(properties.linkTtl())) : null;
        String status = "SUCCEEDED".equals(j.status()) && j.expiresAt() != null && !j.expiresAt().isAfter(now) ? "EXPIRED" : j.status();
        return new ExportJobResponse(Long.toString(j.id()), status, j.format(), json.readTree(j.queryJson()), j.rows(), j.bytes(),
                j.dictionaryVersion(), j.expiresAt(), url, j.error(), Long.toString(j.requestedBy()),
                j.scheduleId() == null ? null : Long.toString(j.scheduleId()), j.estimatedRows(), j.createdAt(), j.finishedAt());
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
