package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.compliance.domain.ActionOutcomeEvaluator;
import net.java21.data2flow.core.compliance.domain.ActionOutcomeEvaluator.Outcome;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.CorrectiveActions;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Limit;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ActionRequest;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ActionView;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ExcursionDetail;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ExcursionRef;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.VersionInfo;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.ExcursionRow;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository.ActionRow;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository.Filter;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository.Values;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 개선 조치 기록(CMP-02.05, API-CMP-23·24, UC-CMP-10).
 *
 * <ul>
 *   <li>기록·수정·삭제는 CMP_ACTION(OPERATOR+)이고 이탈 구간의 공간이 범위 안이어야 한다. 범위 밖이나 다른 조직이면 404</li>
 *   <li>조치 시각은 이탈 시작 이후, 지금 이전이다. 원인 OTHER는 메모(1~200자), MANUAL 결과는 메모(1~500자)가 필요하다
 *       (어기면 400 CMP_ACTION_INVALID)</li>
 *   <li>연결한 알람·작업 지시는 같은 조직에 있어야 한다(ALARM_NOT_FOUND·WORKORDER_NOT_FOUND)</li>
 *   <li>AUTO 결과는 조치 후 24시간이 지나면 {@link #evaluateDue}가 채운다(BR-CMP-18). 조치 시각이나 결과 방식을 바꾸면 다시 계산한다</li>
 * </ul>
 */
@Service
public class CorrectiveActionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectiveActionService.class);
    static final int MAX_LINKS = 20;
    static final int MAX_LIST = 2000;
    /** 이동 평균(HOUR_8)을 위해 조치 시각보다 앞서 읽는 시간 */
    private static final Duration LOOKBACK = Duration.ofHours(8);
    private static final int DUE_BATCH = 200;

    private final CorrectiveActionRepository actions;
    private final ComplianceStatusRepository status;
    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceReportRepository reports;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public CorrectiveActionService(CorrectiveActionRepository actions, ComplianceStatusRepository status, ComplianceApplicationRepository applications,
                                   ComplianceProfileRepository profiles, ComplianceReportRepository reports, RoleChecker roleChecker, Audits audits,
                                   Clock clock) {
        this.actions = actions;
        this.status = status;
        this.applications = applications;
        this.profiles = profiles;
        this.reports = reports;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-CMP-23 기록 */
    @Transactional
    public ActionView create(long excursionId, ActionRequest request) {
        roleChecker.require(Permission.CMP_ACTION);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ExcursionRow excursion = actions.excursion(orgId, excursionId)
                .orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_EXCURSION_NOT_FOUND));
        roleChecker.require(Permission.CMP_ACTION, excursion.spaceId(), ComplianceErrorCode.CMP_EXCURSION_NOT_FOUND);
        Values values = values(orgId, user, excursion, request);
        long id = actions.insert(orgId, excursionId, values, user.userId(), clock.instant());
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_ACTION_RECORDED).actor(user).target("CORRECTIVE_ACTION", Long.toString(id))
                .detail("excursionId", Long.toString(excursionId)).detail("spaceId", Long.toString(excursion.spaceId()))
                .detail("cause", values.cause()).detail("resultMode", values.resultMode()));
        return view(actions.find(orgId, id).orElseThrow());
    }

    /** API-CMP-23 수정 */
    @Transactional
    public ActionView update(long actionId, ActionRequest request) {
        roleChecker.require(Permission.CMP_ACTION);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ActionRow current = visible(orgId, actionId, Permission.CMP_ACTION);
        Values values = values(orgId, user, current.excursion(), request);
        boolean reset = !values.actedAt().equals(current.actedAt()) || !values.resultMode().equals(current.resultMode());
        actions.update(orgId, actionId, values, reset, clock.instant());
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_ACTION_UPDATED).actor(user).target("CORRECTIVE_ACTION", Long.toString(actionId))
                .detail("excursionId", Long.toString(current.excursion().id())).detail("cause", values.cause())
                .detail("resultMode", values.resultMode()));
        return view(actions.find(orgId, actionId).orElseThrow());
    }

    /** API-CMP-23 삭제 */
    @Transactional
    public void delete(long actionId) {
        roleChecker.require(Permission.CMP_ACTION);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ActionRow current = visible(orgId, actionId, Permission.CMP_ACTION);
        actions.delete(orgId, actionId);
        audits.record(audits.event(orgId, ComplianceAuditCodes.CMP_ACTION_DELETED).actor(user).target("CORRECTIVE_ACTION", Long.toString(actionId))
                .detail("excursionId", Long.toString(current.excursion().id())));
    }

    /** 이탈 구간 하나와 조치(조치 시각 순). 범위 밖이나 다른 조직이면 404 */
    @Transactional(readOnly = true)
    public ExcursionDetail excursion(long excursionId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        ExcursionRow x = actions.excursion(orgId, excursionId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_EXCURSION_NOT_FOUND));
        roleChecker.requireSpace(x.spaceId(), ComplianceErrorCode.CMP_EXCURSION_NOT_FOUND);
        String spaceName = applications.space(orgId, x.spaceId()).map(SpaceRow::name).orElse(null);
        return new ExcursionDetail(Long.toString(x.id()), Long.toString(x.spaceId()), spaceName, x.metricKey(), x.startedAt(), x.endedAt(),
                x.peakValue(), x.alarmId() == null ? null : Long.toString(x.alarmId()),
                actions.byExcursions(orgId, List.of(excursionId)).stream().map(this::view).toList());
    }

    /** 조치 하나 */
    @Transactional(readOnly = true)
    public ActionView get(long actionId) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return view(visible(orgId, actionId, Permission.CMP_READ));
    }

    /**
     * API-CMP-24 목록(조치 시각 최근부터). 기간은 조직 시간대 날짜 [from, to]. spaceId는 그 공간과 하위 공간, excursionId는 이탈 구간 하나로
     * 좁힌다(문서에 없던 조건). 범위 밖 공간의 조치는 빠진다
     */
    @Transactional(readOnly = true)
    public ListApiResponse<ActionView> list(String spaceId, String excursionId, String from, String to, String cause, Integer page, Integer size) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        Long excursion = id(excursionId, "excursionId");
        if (excursion != null) {
            Optional<ExcursionRow> x = actions.excursion(orgId, excursion);
            if (x.isEmpty() || !roleChecker.spaceScope().includes(x.get().spaceId())) {
                return ListApiResponse.of(params, List.of(), 0);
            }
            List<ActionView> views = actions.byExcursions(orgId, List.of(excursion)).stream().map(this::view).toList();
            return ListApiResponse.of(params, page(views, params), views.size());
        }
        String c = cause == null || cause.isBlank() ? null : cause.strip().toUpperCase(Locale.ROOT);
        if (c != null && !CorrectiveActions.CAUSES.contains(c)) {
            throw invalid("cause", "Enum");
        }
        LocalDate[] period = ComplianceStatusService.period(from, to);
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        Collection<Long> spaces = spaces(orgId, id(spaceId, "spaceId"), roleChecker.spaceScope());
        if (spaces != null && spaces.isEmpty()) {
            return ListApiResponse.of(params, List.of(), 0);
        }
        Filter filter = new Filter(spaces, period[0].atStartOfDay(zone).toInstant(), period[1].plusDays(1).atStartOfDay(zone).toInstant(), c);
        List<ActionView> views = actions.list(orgId, filter, params.size(), params.offset()).stream().map(this::view).toList();
        return ListApiResponse.of(params, views, actions.count(orgId, filter));
    }

    /** 조치 후 24시간이 지난 AUTO 조치의 결과를 채운다. 채운 수 */
    public int evaluateDue(long orgId, Instant now) {
        int n = 0;
        for (ActionRow a : actions.due(orgId, now.minus(ActionOutcomeEvaluator.WINDOW), DUE_BATCH)) {
            BigDecimal ratio = outcome(orgId, a).map(Outcome::ratio).orElse(null);
            actions.saveOutcome(orgId, a.id(), ratio, now);
            n++;
        }
        return n;
    }

    /** 조치 후 24시간 결과. 이탈 구간의 기준을 찾지 못하면(적용 해제 등) 빈 값 = 확인 불가 */
    Optional<Outcome> outcome(long orgId, ActionRow a) {
        ExcursionRow x = a.excursion();
        Optional<MetricRule> rule = rule(orgId, x);
        if (rule.isEmpty()) {
            log.info("개선 조치 {}: 이탈 구간 {}의 기준을 찾지 못해 확인 불가로 남긴다", a.id(), x.id());
            return Optional.empty();
        }
        MetricRule r = rule.get();
        Instant start = a.actedAt();
        NavigableMap<Instant, Double> series = status.minuteSeries(orgId, x.spaceId(), List.of(x.metricKey()), start.minus(LOOKBACK),
                start.plus(ActionOutcomeEvaluator.WINDOW).plus(Duration.ofMinutes(1))).getOrDefault(x.metricKey(), new TreeMap<>());
        Limit limit = new Limit(r.lower(), r.inclusive(), r.upper(), r.inclusive());
        return Optional.of(ActionOutcomeEvaluator.evaluate(series, r.evalWindow(), limit, start));
    }

    /** 이탈 구간을 만든 기준(버전·항목, 그 공간에 걸린 적용의 시설 유형) */
    private Optional<MetricRule> rule(long orgId, ExcursionRow x) {
        Optional<VersionInfo> version = reports.versions(orgId, List.of(x.profileVersionId())).stream().findFirst();
        Optional<SpaceRow> space = applications.space(orgId, x.spaceId());
        if (version.isEmpty() || space.isEmpty()) {
            return Optional.empty();
        }
        String facility = applications.forSpace(orgId, space.get()).stream().filter(a -> a.profileId() == version.get().profileId())
                .map(ApplicationRow::facilityType).filter(Objects::nonNull).findFirst().orElse(null);
        return ComplianceReportGenerator.rules(profiles.rules(x.profileVersionId()), facility).stream()
                .filter(r -> r.metricKey().equals(x.metricKey()) && "LIMIT".equals(r.requirement()) && (r.lower() != null || r.upper() != null))
                .findFirst();
    }

    private ActionRow visible(long orgId, long actionId, Permission permission) {
        ActionRow a = actions.find(orgId, actionId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.CMP_ACTION_NOT_FOUND));
        roleChecker.require(permission, a.excursion().spaceId(), ComplianceErrorCode.CMP_ACTION_NOT_FOUND);
        return a;
    }

    /** 요청 검증(UI-CMP-10 필드 규칙) */
    Values values(long orgId, CurrentUser user, ExcursionRow excursion, ActionRequest request) {
        if (request == null) {
            throw invalid("cause", "NotNull");
        }
        String cause = request.cause() == null || request.cause().isBlank() ? null : request.cause().strip().toUpperCase(Locale.ROOT);
        if (cause == null) {
            throw invalid("cause", "NotNull");
        }
        if (!CorrectiveActions.CAUSES.contains(cause)) {
            throw invalid("cause", "Enum");
        }
        String causeNote = blankToNull(request.causeNote());
        if ("OTHER".equals(cause) && causeNote == null) {
            throw invalid("causeNote", "NotBlank");
        }
        if (causeNote != null && causeNote.length() > 200) {
            throw invalid("causeNote", "Size");
        }
        String text = blankToNull(request.actionText());
        if (text == null) {
            throw invalid("actionText", "NotBlank");
        }
        if (text.length() > 1000) {
            throw invalid("actionText", "Size");
        }
        Long actor = id(request.actorUserId(), "actorUserId");
        long actorId = actor == null ? user.userId() : actor;
        if (actions.userName(orgId, actorId).isEmpty()) {
            throw invalid("actorUserId", "NotFound");
        }
        Instant actedAt = request.actedAt();
        if (actedAt == null) {
            throw invalid("actedAt", "NotNull");
        }
        if (actedAt.isBefore(excursion.startedAt())) {
            throw invalid("actedAt", "BeforeExcursion");
        }
        if (actedAt.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw invalid("actedAt", "Future");
        }
        String mode = request.resultMode() == null || request.resultMode().isBlank() ? "AUTO"
                : request.resultMode().strip().toUpperCase(Locale.ROOT);
        if (!CorrectiveActions.RESULT_MODES.contains(mode)) {
            throw invalid("resultMode", "Enum");
        }
        String resultNote = blankToNull(request.resultNote());
        if ("MANUAL".equals(mode) && resultNote == null) {
            throw invalid("resultNote", "NotBlank");
        }
        if (resultNote != null && resultNote.length() > 500) {
            throw invalid("resultNote", "Size");
        }
        List<Long> alarms = ids(request.linkedAlarmIds(), "linkedAlarmIds");
        if (actions.existingAlarms(orgId, alarms).size() != alarms.size()) {
            throw new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND);
        }
        List<Long> orders = ids(request.linkedWorkOrderIds(), "linkedWorkOrderIds");
        if (actions.existingWorkOrders(orgId, orders).size() != orders.size()) {
            throw new BusinessException(WorkOrderErrorCode.WORKORDER_NOT_FOUND);
        }
        return new Values(cause, causeNote, text, actorId, actedAt, alarms, orders, mode, resultNote);
    }

    ActionView view(ActionRow a) {
        ExcursionRow x = a.excursion();
        return new ActionView(Long.toString(a.id()), Long.toString(x.id()),
                new ExcursionRef(Long.toString(x.spaceId()), x.metricKey(), x.startedAt(), x.endedAt(), x.peakValue()), a.cause(), a.causeNote(),
                a.actionText(), new UserRef(Long.toString(a.actorUserId()), a.actorName()), a.actedAt(),
                a.linkedAlarmIds().stream().map(String::valueOf).toList(), a.linkedWorkOrderIds().stream().map(String::valueOf).toList(),
                a.resultMode(), a.resultRatio24h(), a.resultNote(),
                CorrectiveActions.resultStatus(a.resultMode(), a.resultRatio24h(), a.resultCheckedAt()), a.updatedAt());
    }

    /** 목록 공간 조건: null이면 거르지 않는다 */
    private Collection<Long> spaces(long orgId, Long spaceId, SpaceScope scope) {
        Set<Long> ids = null;
        if (spaceId != null) {
            Optional<SpaceRow> space = applications.space(orgId, spaceId);
            if (space.isEmpty()) {
                return List.of();
            }
            ids = new LinkedHashSet<>(status.subtreeIds(orgId, space.get().path()));
        }
        if (scope.unrestricted()) {
            return ids;
        }
        Set<Long> allowed = new LinkedHashSet<>(scope.allowedSpaceIds());
        if (ids != null) {
            allowed.retainAll(ids);
        }
        return allowed;
    }

    private static <T> List<T> page(List<T> all, PageParams params) {
        int start = (int) Math.min(params.offset(), all.size());
        return all.subList(start, Math.min(start + params.size(), all.size()));
    }

    private static List<Long> ids(List<String> raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_LINKS) {
            throw invalid(field, "Size");
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String r : raw) {
            Long id = id(r, field);
            if (id == null) {
                throw invalid(field, "NotNull");
            }
            out.add(id);
        }
        return List.copyOf(out);
    }

    private static Long id(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw invalid(field, "Pattern");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(ComplianceErrorCode.CMP_ACTION_INVALID, List.of(new FieldErrorDetail(field, code, null)));
    }
}
