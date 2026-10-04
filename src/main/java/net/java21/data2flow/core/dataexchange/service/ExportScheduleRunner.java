package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.BiExportFinished;
import net.java21.data2flow.core.dataexchange.domain.ExportFormat;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.domain.RelativePeriod;
import net.java21.data2flow.core.dataexchange.repository.ExchangeFileRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository.ExportJobRow;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository.OrgInfo;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository.RunRow;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository.ScheduleRow;
import net.java21.data2flow.core.mail.service.MailLinks;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 정기 내보내기 실행기(TSD-04.03·07.02, BR-TSD-28, AT-TSD-12.1·17.1~17.3). 1분마다:
 * <ol>
 *   <li>때가 된 일정마다 그 실행 시각의 기간(전날·지난주·지난달, 조직 시간대)으로 실행을 하나 만들고, 재계산된(stale) 지난 기간은 새 판으로
 *       다시 만든다(AT-TSD-17.2, 이전 판 유지). 다음 실행 시각을 cron으로 옮긴다</li>
 *   <li>대기·재시도 실행을 처리한다: 일정 만든 사람의 권한으로 파일을 만들고(내보내기 작업, 7일 보관) EMAIL이면 수신자에게 내려받기 링크,
 *       STORAGE면 {@code {조직}_{범위}_{시작}_{끝}_v{판}.{확장자}}와 {@code data-dictionary_v{n}.json}을 대상에 쓴다</li>
 *   <li>실패하면 1분·2분·4분 뒤 다시(3회), 그래도 실패면 FAILED와 EVT-TSD-07 {@code bi.export.failed}(관리자 알림). 성공은
 *       {@code bi.export.completed}</li>
 * </ol>
 */
@Component
public class ExportScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportScheduleRunner.class);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ExportScheduleRepository schedules;
    private final ExportScheduleService scheduleService;
    private final ExportJobRepository jobs;
    private final ExchangeFileRepository files;
    private final ExportJobRunner jobRunner;
    private final ExportPlanner planner;
    private final DataDictionaryService dictionary;
    private final DeliveryTargets targets;
    private final MailService mail;
    private final MailLinks links;
    private final CoreEventPublisher events;
    private final RoleChecker roleChecker;
    private final ExchangeProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public ExportScheduleRunner(ExportScheduleRepository schedules, ExportScheduleService scheduleService, ExportJobRepository jobs,
                                ExchangeFileRepository files, ExportJobRunner jobRunner, ExportPlanner planner, DataDictionaryService dictionary,
                                DeliveryTargets targets, MailService mail, MailLinks links, CoreEventPublisher events, RoleChecker roleChecker,
                                ExchangeProperties properties, PlatformTransactionManager txManager, JsonMapper json, Clock clock) {
        this.schedules = schedules;
        this.scheduleService = scheduleService;
        this.jobs = jobs;
        this.files = files;
        this.jobRunner = jobRunner;
        this.planner = planner;
        this.dictionary = dictionary;
        this.targets = targets;
        this.mail = mail;
        this.links = links;
        this.events = events;
        this.roleChecker = roleChecker;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    /** 한 번 돌기: 때가 된 일정 → 실행 만들기, 대기 실행 처리. 처리한 실행 수 */
    public int runOnce() {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            for (ScheduleRow schedule : schedules.lockDue(now, 50)) {
                plan(schedule, now);
            }
        });
        int done = 0;
        while (done < 50) {
            List<RunRow> runs = tx.execute(s -> schedules.lockRunnable(clock.instant(), 1));
            if (runs == null || runs.isEmpty()) {
                break;
            }
            execute(runs.getFirst());
            done++;
        }
        return done;
    }

    private void plan(ScheduleRow schedule, Instant now) {
        OrgInfo org = schedules.findOrganization(schedule.organizationId());
        ZoneId zone = ZoneId.of(org.timezone());
        RelativePeriod period = RelativePeriod.valueOf(schedule.relativePeriod());
        RelativePeriod.Range range = period.range(schedule.nextRunAt(), zone);
        int version = schedules.findNextFileVersion(schedule.organizationId(), schedule.id(), range.from());
        schedules.insertRun(schedule.organizationId(), schedule.id(), range.from(), range.to(), version, now);
        for (RunRow stale : schedules.findStale(schedule.organizationId(), schedule.id())) {
            if (stale.periodFrom().equals(range.from())) {
                schedules.updateRunStale(schedule.organizationId(), stale.id(), false, now);
                continue;
            }
            int v = schedules.findNextFileVersion(schedule.organizationId(), schedule.id(), stale.periodFrom());
            schedules.insertRun(schedule.organizationId(), schedule.id(), stale.periodFrom(), stale.periodTo(), v, now);
            schedules.updateRunStale(schedule.organizationId(), stale.id(), false, now);
        }
        Instant next = ExportScheduleService.nextRun(schedule.cron(), zone, now);
        schedules.updateNextRun(schedule.organizationId(), schedule.id(), next, now);
    }

    /** 실행 하나(대기 → 성공·재시도·실패) */
    void execute(RunRow run) {
        long org = run.organizationId();
        ScheduleRow schedule = schedules.findById(org, run.scheduleId()).orElse(null);
        if (schedule == null) {
            return;
        }
        int attempts = run.attempts() + 1;
        Long jobId = run.jobId();
        String fileName = null;
        try {
            ExportFormat format = ExportFormat.valueOf(schedule.format());
            OrgInfo orgInfo = schedules.findOrganization(org);
            if (jobId == null || !"SUCCEEDED".equals(jobs.findStatus(org, jobId))) {
                jobId = generate(schedule, run, format);
            }
            long id = jobId;
            ExportJobRow job = jobs.findById(org, id).orElseThrow();
            ZoneId zone = ZoneId.of(orgInfo.timezone());
            fileName = fileName(orgInfo.code(), schedule, run, zone, format);
            if ("STORAGE".equals(schedule.delivery())) {
                deliverToStorage(schedule, job, fileName, Locale.forLanguageTag(orgInfo.locale()));
            } else {
                deliverByMail(schedule, job, run, orgInfo, zone);
            }
            Instant now = clock.instant();
            String name = fileName;
            Long finalJob = jobId;
            tx.executeWithoutResult(s -> {
                schedules.updateRun(org, run.id(), "SUCCEEDED", attempts, null, finalJob, name, null, now);
                schedules.updateResult(org, schedule.id(), "SUCCEEDED", null, run.fileVersion(), now);
                events.event(EventType.BI_EXPORT_COMPLETED, org, new BiExportFinished(schedule.id(), run.fileVersion(), name, null, attempts));
            });
        } catch (Exception ex) {
            String error = "EXPORT_TARGET_UNWRITABLE: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("정기 내보내기 {} 실행 {} 실패({}회): {}", schedule.id(), run.id(), attempts, error);
            Instant now = clock.instant();
            boolean last = attempts >= properties.scheduleRetries();
            Instant retryAt = last ? null : now.plus(properties.retryBase().multipliedBy(1L << (attempts - 1)));
            Long finalJob = jobId;
            String name = fileName;
            tx.executeWithoutResult(s -> {
                schedules.updateRun(org, run.id(), last ? "FAILED" : "RETRYING", attempts, retryAt, finalJob, name, error, now);
                schedules.updateResult(org, schedule.id(), last ? "FAILED" : "RETRYING", error, null, now);
                if (last) {
                    events.event(EventType.BI_EXPORT_FAILED, org, new BiExportFinished(schedule.id(), run.fileVersion(), null,
                            error.length() > 300 ? error.substring(0, 300) : error, attempts));
                }
            });
        }
    }

    /** 일정 만든 사람의 권한으로 내보내기 작업을 만들고 바로 실행. 작업 ID */
    private long generate(ScheduleRow schedule, RunRow run, ExportFormat format) {
        long org = schedule.organizationId();
        CurrentUser owner = new CurrentUser(schedule.createdBy(), org);
        long id = CurrentUserHolder.callAs(owner, () -> tx.execute(s -> {
            if (!roleChecker.has(Permission.TS_EXPORT)) {
                throw new IllegalStateException("일정 소유자에게 내보내기 권한이 없습니다");
            }
            QueryRequest query = json.readValue(schedule.queryJson(), QueryRequest.class);
            ExportPlan plan = planner.plan(query, "LONG", true, null, new Instant[]{run.periodFrom(), run.periodTo()});
            Instant now = clock.instant();
            long jobId = jobs.insert(org, schedule.createdBy(), schedule.id(), schedule.queryJson(), json.writeValueAsString(plan),
                    format.name(), -1, dictionary.currentVersion(org), now);
            jobs.updateClaim(org, jobId, now);
            return jobId;
        }));
        ExportJobRow job = jobs.findById(org, id).orElseThrow();
        String status = jobRunner.generate(job);
        if (!"SUCCEEDED".equals(status)) {
            throw new IllegalStateException("파일을 만들지 못했습니다: " + jobs.findById(org, id).map(ExportJobRow::error).orElse(status));
        }
        return id;
    }

    private void deliverToStorage(ScheduleRow schedule, ExportJobRow job, String fileName, Locale locale) throws IOException {
        JsonNode target = json.readTree(schedule.targetJson());
        JsonNode credential = scheduleService.credential(schedule);
        Path data = Files.createTempFile("d2f-sched-" + job.id() + "-", ".bin");
        Path dict = Files.createTempFile("d2f-dict-" + job.id() + "-", ".json");
        try {
            try (InputStream in = files.open(job.organizationId(), ExportJobRunner.OWNER, job.id())) {
                Files.copy(in, data, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String dictionaryJson = tx.execute(s -> dictionary.currentJson(job.organizationId(), locale));
            Files.writeString(dict, dictionaryJson == null ? "{}" : dictionaryJson, StandardCharsets.UTF_8);
            try (DeliveryTarget t = targets.open(schedule.targetType(), target, credential)) {
                t.put(fileName, data, ExportFormat.valueOf(job.format()).contentType());
                t.put("data-dictionary_v" + job.dictionaryVersion() + ".json", dict, "application/json");
            }
        } finally {
            Files.deleteIfExists(data);
            Files.deleteIfExists(dict);
        }
    }

    private void deliverByMail(ScheduleRow schedule, ExportJobRow job, RunRow run, OrgInfo org, ZoneId zone) {
        Locale locale = MailLinks.locale(org.locale());
        String link = links.link("/data/exports/" + job.id(), locale);
        for (String to : schedule.recipients()) {
            boolean sent = mail.send(schedule.organizationId(), to, "mail.export-schedule", locale, schedule.name(),
                    MailLinks.format(run.periodFrom(), zone.getId()), MailLinks.format(run.periodTo(), zone.getId()), link);
            if (!sent) {
                throw new IllegalStateException("메일 서버(OPS-07.02)가 설정되지 않았습니다");
            }
        }
    }

    /** {조직}_{범위}_{시작}_{끝}_v{판}.{확장자}(BR-TSD-28). 범위는 일정 이름의 영숫자(없으면 schedule{ID}) */
    static String fileName(String orgCode, ScheduleRow schedule, RunRow run, ZoneId zone, ExportFormat format) {
        String scope = schedule.name().replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (scope.isEmpty()) {
            scope = "schedule" + schedule.id();
        }
        return safe(orgCode) + "_" + scope + "_" + DAY.format(run.periodFrom().atZone(zone)) + "_" + DAY.format(run.periodTo().atZone(zone))
                + "_v" + run.fileVersion() + "." + format.extension();
    }

    private static String safe(String s) {
        return s == null ? "org" : s.replaceAll("[^A-Za-z0-9_-]+", "-");
    }

    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.exchange", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ExportScheduleRunner runner;

        Schedule(ExportScheduleRunner runner) {
            this.runner = runner;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
        void run() {
            runner.runOnce();
        }
    }
}
