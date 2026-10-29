package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.ReportModels;
import net.java21.data2flow.core.compliance.domain.ReportSchedules;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Delivery;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Recipients;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.RunRequested;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.RunView;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.ScheduleRequest;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.ScheduleView;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.Template;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository.RunRow;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository.ScheduleRow;
import net.java21.data2flow.core.compliance.repository.ReportScheduleRepository.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 준수 리포트 정기 발행 일정(CMP-02.04, API-CMP-21·22, UC-CMP-09).
 *
 * <ul>
 *   <li>조회는 CMP_READ, 변경·지금 실행은 CMP_REPORT. 대상 공간이 하나라도 범위 밖이면 일정은 없는 것과 같다(404)</li>
 *   <li>저장할 때 대상(공간·시설 유형·적용 프로필)을 리포트 요청과 같게 검사하고 다음 실행 시각을 계산한다. 꺼진 일정은 다음 실행이 없다</li>
 *   <li>수신자는 사용자·이메일 합쳐 1명 이상(최대 50)이고 사용자는 같은 조직이어야 한다(아니면 400 CMP_SCHEDULE_INVALID)</li>
 *   <li>실행은 {@link ReportScheduleRunner}가 한다. 지금 실행은 현재 시각 회차를 넣고 202</li>
 * </ul>
 */
@Service
public class ReportScheduleService {

    static final int MAX_RECIPIENTS = 50;

    private final ReportScheduleRepository schedules;
    private final ComplianceReportGenerator generator;
    private final ComplianceApplicationRepository applications;
    private final ComplianceStatusRepository status;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ReportScheduleService(ReportScheduleRepository schedules, ComplianceReportGenerator generator, ComplianceApplicationRepository applications,
                                 ComplianceStatusRepository status, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.schedules = schedules;
        this.generator = generator;
        this.applications = applications;
        this.status = status;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-CMP-21 목록(이름 순). 범위 밖 공간이 든 일정은 빠진다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ScheduleView> list(Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        List<ScheduleRow> rows = schedules.list(orgId).stream().filter(this::visible).toList();
        PageParams params = PageParams.of(page, size);
        int start = (int) Math.min(params.offset(), rows.size());
        List<ScheduleRow> slice = rows.subList(start, Math.min(start + params.size(), rows.size()));
        Map<Long, RunRow> last = schedules.lastRuns(orgId, slice.stream().map(ScheduleRow::id).toList());
        return ListApiResponse.of(params, slice.stream().map(s -> view(s, last.get(s.id()))).toList(), rows.size());
    }

    @Transactional(readOnly = true)
    public ScheduleView get(long id) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        ScheduleRow s = schedule(orgId, id);
        return view(s, schedules.lastRuns(orgId, List.of(id)).get(id));
    }

    /** API-CMP-21 만들기 */
    @Transactional
    public ScheduleView create(ScheduleRequest request) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Values values = values(orgId, request);
        long id = schedules.insert(orgId, values, user.userId(), clock.instant());
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_SCHEDULE_CREATED).actor(user).target("COMPLIANCE_REPORT_SCHEDULE", Long.toString(id))
                .detail("name", values.name()).detail("cadence", values.cadence()).detail("enabled", values.enabled()));
        return get(id);
    }

    /** API-CMP-21 고치기(version이 맞아야 한다) */
    @Transactional
    public ScheduleView update(long id, ScheduleRequest request) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScheduleRow current = schedule(orgId, id);
        if (request == null || request.version() == null) {
            throw invalid("version", "NotNull");
        }
        Values values = values(orgId, request);
        if (request.version() != current.version() || schedules.update(orgId, id, request.version(), values, user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_SCHEDULE_UPDATED).actor(user).target("COMPLIANCE_REPORT_SCHEDULE", Long.toString(id))
                .detail("name", values.name()).detail("cadence", values.cadence()).detail("enabled", values.enabled()));
        return get(id);
    }

    /** API-CMP-21 지우기(실행 기록도 지워진다. 만든 리포트는 남는다) */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScheduleRow s = schedule(orgId, id);
        schedules.delete(orgId, id);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_SCHEDULE_DELETED).actor(user).target("COMPLIANCE_REPORT_SCHEDULE", Long.toString(id))
                .detail("name", s.name()));
    }

    /** API-CMP-21 지금 실행 — 202. 꺼진 일정도 실행할 수 있다 */
    @Transactional
    public RunRequested runNow(long id) {
        roleChecker.require(Permission.CMP_REPORT);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        schedule(orgId, id);
        Instant now = clock.instant();
        long runId = schedules.insertRun(orgId, id, now, "MANUAL", now).orElseThrow(() -> new BusinessException(CommonErrorCode.VERSION_CONFLICT));
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_SCHEDULE_RUN_REQUESTED).actor(user)
                .target("COMPLIANCE_REPORT_SCHEDULE", Long.toString(id)).detail("runId", Long.toString(runId)));
        return new RunRequested(Long.toString(runId), Long.toString(id), now);
    }

    /** API-CMP-22 실행 이력(최근부터) */
    @Transactional(readOnly = true)
    public ListApiResponse<RunView> runs(long id, Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        schedule(orgId, id);
        PageParams params = PageParams.of(page, size);
        List<RunView> views = schedules.runs(orgId, id, params.size(), params.offset()).stream().map(this::runView).toList();
        return ListApiResponse.of(params, views, schedules.countRuns(orgId, id));
    }

    private ScheduleRow schedule(long orgId, long id) {
        ScheduleRow s = schedules.find(orgId, id).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_SCHEDULE_NOT_FOUND));
        if (!visible(s)) {
            throw new BusinessException(ComplianceErrorCode.CMP_SCHEDULE_NOT_FOUND);
        }
        return s;
    }

    private boolean visible(ScheduleRow s) {
        var scope = roleChecker.spaceScope();
        return scope.unrestricted() || template(s).spaceIds().stream().allMatch(id -> scope.includes(Long.parseLong(id)));
    }

    /** 요청 검증(TC-CMP-059) */
    Values values(long orgId, ScheduleRequest r) {
        if (r == null) {
            throw invalid("name", "NotBlank");
        }
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name", name.isEmpty() ? "NotBlank" : "Size");
        }
        Template t = template(orgId, r.reportTemplate());
        String cadence = upper(r.cadence());
        if (!ReportSchedules.CADENCES.contains(cadence)) {
            throw invalid("cadence", "Enum");
        }
        if (r.dayOfPeriod() == null || r.dayOfPeriod() < 1 || r.dayOfPeriod() > 28) {
            throw invalid("dayOfPeriod", "Range");
        }
        if (r.publishTime() == null || !ReportSchedules.TIME.matcher(r.publishTime().strip()).matches()) {
            throw invalid("publishTime", "Pattern");
        }
        LocalTime time = LocalTime.parse(r.publishTime().strip());
        String rule = upper(r.periodRule());
        if (!ReportSchedules.PERIOD_RULES.contains(rule)) {
            throw invalid("periodRule", "Enum");
        }
        String format = r.format() == null || r.format().isBlank() ? "PDF" : upper(r.format());
        if (!ReportSchedules.FORMATS.contains(format)) {
            throw invalid("format", "Enum");
        }
        Recipients recipients = recipients(orgId, r.recipients());
        boolean enabled = r.enabled() == null || r.enabled();
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        Instant next = enabled ? ReportSchedules.nextRun(cadence, r.dayOfPeriod(), time, zone, clock.instant()) : null;
        return new Values(name, json.writeValueAsString(t), cadence, r.dayOfPeriod(), time, rule, json.writeValueAsString(recipients), format, enabled,
                next);
    }

    private Template template(long orgId, Template t) {
        if (t == null) {
            throw invalid("reportTemplate", "NotNull");
        }
        String type = upper(t.type());
        if (!ReportModels.KR_SELF_CHECK.equals(type)) {
            throw invalid("reportTemplate.type", type == null ? "NotNull" : "Unsupported");
        }
        if (t.spaceIds() == null || t.spaceIds().isEmpty() || t.spaceIds().size() > ComplianceReportService.MAX_SPACES) {
            throw invalid("reportTemplate.spaceIds", t.spaceIds() == null || t.spaceIds().isEmpty() ? "NotEmpty" : "Size");
        }
        TreeSet<Long> ids = new TreeSet<>();
        for (String raw : t.spaceIds()) {
            long id;
            try {
                id = Long.parseLong(raw == null ? "" : raw.strip());
            } catch (NumberFormatException ex) {
                throw invalid("reportTemplate.spaceIds", "Pattern");
            }
            applications.space(orgId, id).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
            roleChecker.require(Permission.CMP_REPORT, id, ComplianceErrorCode.SPACE_NOT_FOUND);
            ids.add(id);
        }
        String facility = t.facilityType() == null || t.facilityType().isBlank() ? null : upper(t.facilityType());
        generator.target(orgId, ids, facility);
        if (t.options() != null && !t.options().isNull() && !t.options().isObject()) {
            throw invalid("reportTemplate.options", "Type");
        }
        String title = t.title() == null || t.title().isBlank() ? null : t.title().strip();
        if (title != null && title.length() > 200) {
            throw invalid("reportTemplate.title", "Size");
        }
        return new Template(type, title, ids.stream().map(String::valueOf).toList(), facility, t.options() == null || t.options().isNull() ? null : t.options());
    }

    private Recipients recipients(long orgId, Recipients r) {
        List<String> rawUsers = r == null || r.userIds() == null ? List.of() : r.userIds();
        List<String> rawEmails = r == null || r.emails() == null ? List.of() : r.emails();
        Set<Long> users = new LinkedHashSet<>();
        for (String raw : rawUsers) {
            try {
                users.add(Long.parseLong(raw == null ? "" : raw.strip()));
            } catch (NumberFormatException ex) {
                throw invalid("recipients.userIds", "Pattern");
            }
        }
        Set<String> emails = new LinkedHashSet<>();
        for (String raw : rawEmails) {
            String e = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (e.length() > 254 || !ReportSchedules.EMAIL.matcher(e).matches()) {
                throw invalid("recipients.emails", "Email");
            }
            emails.add(e);
        }
        if (users.isEmpty() && emails.isEmpty()) {
            throw invalid("recipients", "NotEmpty");
        }
        if (users.size() + emails.size() > MAX_RECIPIENTS) {
            throw invalid("recipients", "Size");
        }
        if (schedules.existingUsers(orgId, users).size() != users.size()) {
            throw invalid("recipients.userIds", "NotFound");
        }
        return new Recipients(users.stream().map(String::valueOf).toList(), List.copyOf(emails));
    }

    Template template(ScheduleRow s) {
        return json.readValue(s.templateJson(), Template.class);
    }

    ScheduleView view(ScheduleRow s, RunRow last) {
        return new ScheduleView(Long.toString(s.id()), s.name(), template(s), s.cadence(), s.dayOfPeriod(), s.publishTime().toString(), s.periodRule(),
                json.readValue(s.recipientsJson(), Recipients.class), s.format(), s.enabled(), s.nextRunAt(), s.version(), s.updatedAt(),
                last == null ? null : runView(last));
    }

    RunView runView(RunRow r) {
        List<Delivery> deliveries = new ArrayList<>();
        for (var d : json.readTree(r.deliveriesJson()).values()) {
            deliveries.add(new Delivery(d.path("recipient").asString(), d.path("status").asString()));
        }
        return new RunView(Long.toString(r.id()), r.scheduledFor(), r.trigger(), r.status(), r.attempts(),
                r.reportId() == null ? null : Long.toString(r.reportId()), r.revisionNo(), r.error(), deliveries);
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(ComplianceErrorCode.CMP_SCHEDULE_INVALID, List.of(new FieldErrorDetail(field, code, null)));
    }
}
