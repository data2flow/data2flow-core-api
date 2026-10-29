package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.command.ActionIdempotencyKeys;
import net.java21.data2flow.contracts.command.CommandSource;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.ActionRequest;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.ComplianceReportPublished;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.notification.NotificationEvents;
import net.java21.data2flow.contracts.notification.NotificationRecipient;
import net.java21.data2flow.contracts.notification.NotificationRequest;
import net.java21.data2flow.core.compliance.domain.ReportSchedules;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.CreateReportRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.ReportCreated;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Delivery;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Recipients;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Template;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.RevisionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository.RunRow;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository.ScheduleRow;
import net.java21.data2flow.core.compliance.service.ComplianceReportService.Actor;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정기 발행 실행(CMP-02.04, UC-CMP-09). {@link ComplianceJobs#schedules()}가 조직마다 부른다.
 *
 * <ol>
 *   <li>실행 시각이 된 일정에 SCHEDULED 회차를 넣고 다음 실행 시각으로 넘긴다(같은 회차는 한 번만)</li>
 *   <li>PENDING 회차: 일정을 만든 사용자 대신 리포트 생성을 요청하고 RUNNING으로(대상 기간은 회차 시각 기준)</li>
 *   <li>RUNNING 회차: 판이 DRAFT가 되면 발행하고(EVT-CMP-03에 수신자를 싣는다) 사용자 수신자에게 웹 알림을 보낸다.
 *       이메일 수신자는 아직 이메일 채널이 없어 UNSUPPORTED로 남긴다</li>
 *   <li>생성 실패·요청 거절은 3번까지 다시 시도하고, 그래도 안 되면 FAILED로 끝내고 일정을 만든 사용자에게 알린다</li>
 * </ol>
 * 단계마다 트랜잭션을 따로 써서 한 회차의 실패가 다른 회차를 막지 않는다.
 */
@Component
public class ReportScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleRunner.class);
    static final int MAX_ATTEMPTS = 3;

    private final ReportScheduleRepository schedules;
    private final ComplianceReportService reportService;
    private final ComplianceReportRepository reports;
    private final ComplianceStatusRepository status;
    private final OutboxWriter outbox;
    private final MessageCodec codec;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String webBaseUrl;
    private final Clock clock;

    public ReportScheduleRunner(ReportScheduleRepository schedules, ComplianceReportService reportService, ComplianceReportRepository reports,
                                ComplianceStatusRepository status, OutboxWriter outbox, MessageCodec codec, JsonMapper json,
                                PlatformTransactionManager txManager, CoreProperties properties, Clock clock) {
        this.schedules = schedules;
        this.reportService = reportService;
        this.reports = reports;
        this.status = status;
        this.outbox = outbox;
        this.codec = codec;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.webBaseUrl = properties.webBaseUrl();
        this.clock = clock;
    }

    /** 조직 하나를 돌린다 */
    public void run(long orgId, Instant now) {
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        tx.executeWithoutResult(s -> {
            for (ScheduleRow due : schedules.due(orgId, now)) {
                schedules.insertRun(orgId, due.id(), due.nextRunAt(), "SCHEDULED", now);
                Instant after = due.nextRunAt().isAfter(now) ? due.nextRunAt() : now;
                schedules.advance(orgId, due.id(), ReportSchedules.nextRun(due.cadence(), due.dayOfPeriod(), due.publishTime(), zone, after));
            }
        });
        for (RunRow run : schedules.activeRuns(orgId)) {
            try {
                step(orgId, run, zone, now);
            } catch (RuntimeException ex) {
                log.warn("정기 발행 회차 실패: 조직 {} 회차 {}", orgId, run.id(), ex);
            }
        }
    }

    private void step(long orgId, RunRow run, ZoneId zone, Instant now) {
        ScheduleRow s = schedules.find(orgId, run.scheduleId()).orElse(null);
        if (s == null) {
            return;
        }
        Actor actor = Actor.schedule(orgId, s.createdBy());
        if ("PENDING".equals(run.status())) {
            int attempts = tx.execute(t -> schedules.attempt(orgId, run.id(), now));
            Template template = json.readValue(s.templateJson(), Template.class);
            LocalDate[] period = ReportSchedules.period(s.periodRule(), run.scheduledFor(), zone);
            try {
                ReportCreated created = tx.execute(t -> reportService.create(actor, new CreateReportRequest(template.type(), template.title(),
                        template.spaceIds(), template.facilityType(), period[0].toString(), period[1].toString(), template.options())));
                tx.executeWithoutResult(t -> schedules.markRunning(orgId, run.id(), Long.parseLong(created.reportId()),
                        Long.parseLong(created.revisionId()), created.revisionNo(), now));
            } catch (RuntimeException ex) {
                failOrRetry(orgId, s, run.id(), attempts, reason(orgId, run, ex), now);
            }
            return;
        }
        RevisionRow revision = reports.revisions(orgId, List.of(run.reportId())).stream().filter(r -> r.id() == run.revisionId()).findFirst()
                .orElse(null);
        if (revision == null) {
            failOrRetry(orgId, s, run.id(), run.attempts(), "REVISION_NOT_FOUND", now);
            return;
        }
        switch (revision.status()) {
            case "DRAFT" -> publish(orgId, s, run, actor, revision, now);
            case "PUBLISHED" -> tx.executeWithoutResult(t -> schedules.finish(orgId, run.id(), "SUCCEEDED", "[]", null, now));
            case "FAILED" -> failOrRetry(orgId, s, run.id(), run.attempts(), revision.failureReason(), now);
            default -> {
                // GENERATING: 다음 차례에 다시 본다
            }
        }
    }

    private void publish(long orgId, ScheduleRow s, RunRow run, Actor actor, RevisionRow revision, Instant now) {
        Recipients recipients = json.readValue(s.recipientsJson(), Recipients.class);
        List<Long> userIds = recipients.userIds().stream().map(Long::parseLong).toList();
        try {
            tx.executeWithoutResult(t -> {
                reportService.publish(actor, run.reportId(), revision.revisionNo(),
                        new ComplianceReportPublished.Recipients(userIds, recipients.emails()));
                List<Delivery> deliveries = new ArrayList<>();
                String link = webBaseUrl + "/compliance/reports/" + run.reportId() + "?rev=" + revision.revisionNo();
                if (!userIds.isEmpty()) {
                    notify(orgId, "report-schedule-run", run.id(), userIds, s.name() + " 리포트 발행",
                            s.name() + " 정기 리포트(" + revision.revisionNo() + "판)를 발행했습니다.", link);
                }
                userIds.forEach(id -> deliveries.add(new Delivery("user:" + id, "REQUESTED")));
                recipients.emails().forEach(e -> deliveries.add(new Delivery(e, "UNSUPPORTED")));
                schedules.finish(orgId, run.id(), "SUCCEEDED", json.writeValueAsString(deliveries), null, now);
            });
        } catch (RuntimeException ex) {
            failOrRetry(orgId, s, run.id(), run.attempts(), reason(orgId, run, ex), now);
        }
    }

    /** 업무 오류는 오류 코드, 그 밖의 오류는 로그를 남기고 INTERNAL_ERROR */
    private static String reason(long orgId, RunRow run, RuntimeException ex) {
        if (ex instanceof BusinessException b) {
            return b.getErrorCode().code();
        }
        log.warn("정기 발행 회차 오류: 조직 {} 회차 {}", orgId, run.id(), ex);
        return "INTERNAL_ERROR";
    }

    /** 시도가 남았으면 PENDING으로, 아니면 FAILED로 끝내고 일정을 만든 사용자에게 알린다 */
    private void failOrRetry(long orgId, ScheduleRow s, long runId, int attempts, String error, Instant now) {
        String reason = error == null || error.isBlank() ? "UNKNOWN" : error;
        tx.executeWithoutResult(t -> {
            if (attempts < MAX_ATTEMPTS) {
                schedules.retry(orgId, runId, reason, now);
                return;
            }
            schedules.finish(orgId, runId, "FAILED", "[]", reason, now);
            notify(orgId, "report-schedule-failed", runId, List.of(s.createdBy()), s.name() + " 정기 발행 실패",
                    s.name() + " 정기 리포트를 만들지 못했습니다(" + reason + ").", webBaseUrl + "/compliance/reports/schedules");
        });
    }

    private void notify(long orgId, String scope, long runId, List<Long> userIds, String title, String message, String link) {
        List<NotificationRecipient> to = userIds.stream()
                .map(id -> new NotificationRecipient(NotificationRecipient.Type.USER, Long.toString(id), "WEB", null)).toList();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("title", title);
        variables.put("message", message);
        NotificationRequest body = new NotificationRequest(null, NotificationEvents.FLOW_NOTIFY, null, null, null, to,
                Map.of(NotificationRequest.DEFAULT_TEMPLATE, NotificationEvents.defaultTemplateKey(NotificationEvents.FLOW_NOTIFY)), variables, 0,
                null, null, link, null);
        String key = ActionIdempotencyKeys.of(scope, Long.toString(orgId), Long.toString(runId));
        ActionRequest req = ActionRequest.notify(orgId, key, CommandSource.system(), null, body, clock);
        outbox.message(orgId, "NOTIFY", MessagingNames.EXCHANGE_ACTIONS, req.routingKey(), req.messageId().toString(), codec.writeAsString(req));
    }
}
