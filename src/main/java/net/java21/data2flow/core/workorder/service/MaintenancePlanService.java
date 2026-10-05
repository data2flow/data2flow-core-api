package net.java21.data2flow.core.workorder.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import net.java21.data2flow.core.workorder.domain.WorkOrderRules;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CreatePlanRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.PlanResponse;
import net.java21.data2flow.core.workorder.repository.MaintenancePlanRepository;
import net.java21.data2flow.core.workorder.repository.MaintenancePlanRepository.Plan;
import net.java21.data2flow.core.workorder.repository.MaintenancePlanRepository.PlanFields;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository.Target;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 정기 점검 계획(DEV-08.05, API-DEV-95, BR-DEV-28). 매일 03:00(사이트 시간대) 뒤 첫 실행에서 {@code next_due_on - lead_days <= 오늘}인
 * 계획의 대상 그룹 기기마다 작업 지시(출처 SCHEDULE)를 만들고 {@code next_due_on}을 {@code interval_days}만큼 늦춘다. 같은 기기·유형의 열린
 * 작업이 있으면 새로 만들지 않는다(BR-DEV-21).
 */
@Service
public class MaintenancePlanService {

    static final String AUDIT_PLAN = "MAINTENANCE_PLAN_CHANGED";
    /** 사이트 시간대 기준 실행 시각(BR-DEV-28) */
    static final int RUN_HOUR = 3;

    private final RoleChecker roleChecker;
    private final MaintenancePlanRepository plans;
    private final WorkOrderRepository orders;
    private final WorkOrderService workOrders;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public MaintenancePlanService(RoleChecker roleChecker, MaintenancePlanRepository plans, WorkOrderRepository orders,
                                  WorkOrderService workOrders, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.plans = plans;
        this.orders = orders;
        this.workOrders = workOrders;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<PlanResponse> list(Integer page, Integer size) {
        roleChecker.require(Permission.DEV_ADMIN);
        long org = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, plans.list(org, params.size(), params.offset()).stream().map(this::toResponse).toList(),
                plans.count(org));
    }

    @Transactional
    public PlanResponse create(CreatePlanRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        String type = WorkOrderService.upper(req.workType(), WorkOrderRules.TYPES, "workType", errors);
        Long group = WorkOrderService.optionalId(req.targetGroupId(), "targetGroupId", errors);
        Long assignee = WorkOrderService.optionalId(req.defaultAssigneeId(), "defaultAssigneeId", errors);
        int interval = req.intervalDays();
        int lead = req.leadDays() == null ? 7 : req.leadDays();
        range(interval, 7, 1095, "intervalDays", errors);
        range(lead, 0, 60, "leadDays", errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        PlanFields fields = new PlanFields(req.name().strip(), requireGroup(org, group), type, interval, lead, req.nextDueOn(),
                requireAssignee(org, assignee), json.writeValueAsString(WorkOrderService.clean(req.checklistTemplate())),
                req.enabled() == null || req.enabled());
        long id = plans.insert(org, fields, user.userId(), clock.instant());
        audits.record(audits.event(org, AUDIT_PLAN).actor(user).target("MAINTENANCE_PLAN", Long.toString(id)).detail("op", "CREATE")
                .detail("targetGroupId", fields.targetGroupId()).detail("intervalDays", interval));
        return toResponse(plans.find(org, id).orElseThrow());
    }

    /** 부분 수정(온 키만, baseVersion 필수) */
    @Transactional
    public PlanResponse update(long id, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Plan before = plans.find(org, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        int base = (int) VersionCheck.baseVersion(body);
        VersionCheck.require((long) base, (long) before.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = body.has("name") ? text(body.get("name"), "name", 100, errors) : before.name();
        String type = body.has("workType") ? WorkOrderService.upper(body.get("workType").asString(""), WorkOrderRules.TYPES, "workType", errors)
                : before.workType();
        Long group = body.has("targetGroupId") ? WorkOrderService.optionalId(idText(body.get("targetGroupId")), "targetGroupId", errors)
                : Long.valueOf(before.targetGroupId());
        int interval = body.has("intervalDays") ? body.get("intervalDays").asInt(-1) : before.intervalDays();
        int lead = body.has("leadDays") ? body.get("leadDays").asInt(-1) : before.leadDays();
        range(interval, 7, 1095, "intervalDays", errors);
        range(lead, 0, 60, "leadDays", errors);
        LocalDate next = before.nextDueOn();
        if (body.has("nextDueOn")) {
            try {
                next = LocalDate.parse(body.get("nextDueOn").asString(""));
            } catch (DateTimeException ex) {
                errors.add(new FieldErrorDetail("nextDueOn", "Invalid", null));
            }
        }
        Long assignee = body.has("defaultAssigneeId") ? WorkOrderService.optionalId(idText(body.get("defaultAssigneeId")), "defaultAssigneeId", errors)
                : before.defaultAssigneeId();
        String checklist = before.checklistTemplateJson();
        if (body.has("checklistTemplate")) {
            JsonNode c = body.get("checklistTemplate");
            if (!c.isArray() || c.size() > 50) {
                errors.add(new FieldErrorDetail("checklistTemplate", "Invalid", null));
            } else {
                List<String> items = new ArrayList<>();
                c.forEach(x -> items.add(x.asString("")));
                checklist = json.writeValueAsString(WorkOrderService.clean(items));
            }
        }
        boolean enabled = body.has("enabled") ? body.get("enabled").asBoolean(before.enabled()) : before.enabled();
        if (group == null && body.has("targetGroupId")) {
            errors.add(new FieldErrorDetail("targetGroupId", "NotNull", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        PlanFields fields = new PlanFields(name, requireGroup(org, group), type, interval, lead, next, requireAssignee(org, assignee), checklist,
                enabled);
        VersionCheck.requireUpdated(plans.update(org, id, base, fields, user.userId(), clock.instant()));
        audits.record(audits.event(org, AUDIT_PLAN).actor(user).target("MAINTENANCE_PLAN", Long.toString(id)).detail("op", "UPDATE"));
        return toResponse(plans.find(org, id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        plans.find(org, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        plans.delete(org, id);
        audits.record(audits.event(org, AUDIT_PLAN).actor(user).target("MAINTENANCE_PLAN", Long.toString(id)).detail("op", "DELETE"));
    }

    /**
     * BR-DEV-28 한 조직 실행(정기 작업이 부른다). 계획마다 대상 그룹의 사이트 시간대로 오늘·시각을 정하고, 03:00이 지났고 기한이 됐으면
     * 기기마다 작업 지시를 만든 뒤 다음 예정일을 늦춘다. 만든 작업 지시 수를 돌려준다.
     */
    @Transactional
    public int runDue(long organizationId) {
        Instant now = clock.instant();
        int created = 0;
        // 가장 이른 시간대(UTC+14)의 날짜까지 후보로 읽고, 계획마다 자기 시간대로 다시 판정한다
        LocalDate latestToday = now.atZone(ZoneId.of("Pacific/Kiritimati")).toLocalDate();
        for (Plan plan : plans.lockDue(organizationId, latestToday)) {
            ZoneId zone = zone(plans.findPlanTimezone(organizationId, plan.targetGroupId()).orElse("Asia/Seoul"));
            ZonedDateTime local = now.atZone(zone);
            if (local.getHour() < RUN_HOUR || !WorkOrderRules.planDue(plan.nextDueOn(), plan.leadDays(), local.toLocalDate())) {
                continue;
            }
            List<String> checklist = json.readValue(plan.checklistTemplateJson(), new TypeReference<List<String>>() {
            });
            Instant dueAt = plan.nextDueOn().plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
            for (long deviceId : plans.findGroupDevices(organizationId, plan.targetGroupId())) {
                WorkOrderService.Created c = workOrders.create(new WorkOrderService.NewWorkOrder(organizationId, plan.name(), plan.workType(),
                        "NORMAL", List.of(new Target(deviceId, null)), plan.defaultAssigneeId(), null, dueAt, checklist, "SCHEDULE",
                        WorkOrderRules.planOriginRef(plan.id(), plan.nextDueOn(), deviceId), plan.id()), null);
                if (!c.linkedToExisting()) {
                    created++;
                }
            }
            plans.advance(organizationId, plan.id(), plan.nextDueOn().plusDays(plan.intervalDays()), now);
        }
        return created;
    }

    private long requireGroup(long org, Long group) {
        if (group == null || !plans.existsGroup(org, group)) {
            throw new BusinessException(WorkOrderErrorCode.GROUP_NOT_FOUND);
        }
        return group;
    }

    private Long requireAssignee(long org, Long assignee) {
        if (assignee != null && !orders.existsActiveUser(org, assignee)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("defaultAssigneeId", "NotFound", null)));
        }
        return assignee;
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    private static void range(int v, int min, int max, String field, List<FieldErrorDetail> errors) {
        if (v < min || v > max) {
            errors.add(new FieldErrorDetail(field, "Range", min + "~" + max));
        }
    }

    private static String text(JsonNode n, String field, int max, List<FieldErrorDetail> errors) {
        String v = n == null || n.isNull() ? "" : n.asString("").strip();
        if (v.isEmpty() || v.length() > max) {
            errors.add(new FieldErrorDetail(field, "Size", "1~" + max));
        }
        return v;
    }

    private static String idText(JsonNode n) {
        return n == null || n.isNull() ? null : n.isString() ? n.stringValue() : n.toString();
    }

    private PlanResponse toResponse(Plan p) {
        List<String> checklist = json.readValue(p.checklistTemplateJson(), new TypeReference<List<String>>() {
        });
        return new PlanResponse(Long.toString(p.id()), p.name(), Long.toString(p.targetGroupId()), p.workType(), p.intervalDays(),
                p.leadDays(), p.nextDueOn(), WorkOrderService.str(p.defaultAssigneeId()), checklist, p.enabled(), p.version(), p.updatedAt());
    }
}
