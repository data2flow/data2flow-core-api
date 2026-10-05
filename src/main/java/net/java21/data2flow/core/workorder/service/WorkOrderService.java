package net.java21.data2flow.core.workorder.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.WorkOrderChanged;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.filestore.service.StoredFiles.Stored;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.workorder.domain.WorkOrder;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import net.java21.data2flow.core.workorder.domain.WorkOrderRules;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.AttachmentResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.ChecklistItemResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CommentResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CreateWorkOrderRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.InternalCreateWorkOrderRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.InternalCreateWorkOrderResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.Stats;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.TargetDto;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.TargetResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.TransitionRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderDetailResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderListResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderResponse;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository.Attachment;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository.ChecklistItem;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository.Filter;
import net.java21.data2flow.core.workorder.repository.WorkOrderRepository.Target;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 작업 지시(경량 CMMS, DEV-08.02·08.06, UC-DEV-16). 상태 OPEN → ASSIGNED → IN_PROGRESS → DONE / CANCELLED, 바뀔 때마다 EVT-DEV-09
 * {@code workorder.changed}(담당자 알림은 action, 배터리 예측 초기화는 analytics). 자동 생성(알람·분석·플로우·정기 점검)은 같은 기기·같은
 * 유형의 열린 작업 지시가 있으면 새로 만들지 않고 출처를 덧붙인다(BR-DEV-21, AT-DEV-16.2). 조회는 대상 공간 기준 권한 범위(BR-DEV-25).
 */
@Service
public class WorkOrderService {

    static final String AUDIT_CREATED = "WORKORDER_CREATED";
    static final String AUDIT_TRANSITIONED = "WORKORDER_TRANSITIONED";
    static final String AUDIT_ATTACHMENT = "WORKORDER_ATTACHMENT_CHANGED";
    private static final Long NO_SPACE = -1L;

    private final RoleChecker roleChecker;
    private final WorkOrderRepository orders;
    private final DeviceRepository devices;
    private final DeviceReferenceRepository refs;
    private final StoredFiles files;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public WorkOrderService(RoleChecker roleChecker, WorkOrderRepository orders, DeviceRepository devices, DeviceReferenceRepository refs,
                            StoredFiles files, CoreEventPublisher publisher, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.orders = orders;
        this.devices = devices;
        this.refs = refs;
        this.files = files;
        this.publisher = publisher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 만들 내용(검증 뒤). 출처가 MANUAL이 아니면 BR-DEV-21 중복 방지를 적용한다 */
    public record NewWorkOrder(long organizationId, String title, String type, String priority, List<Target> targets, Long assigneeId,
                               Long requesterId, java.time.Instant dueAt, List<String> checklist, String origin, String originRef,
                               Long planId) {
    }

    public record Created(WorkOrder order, boolean linkedToExisting) {
    }

    /** API-DEV-90 — WORKORDER_WRITE, 201(기존에 연결되면 linkedToExisting=true) */
    @Transactional
    public WorkOrderResponse create(CreateWorkOrderRequest req) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        String type = upper(req.type(), WorkOrderRules.TYPES, "type", errors);
        String priority = req.priority() == null || req.priority().isBlank() ? "NORMAL" : upper(req.priority(), WorkOrderRules.PRIORITIES, "priority", errors);
        String origin = req.origin() == null || req.origin().isBlank() ? "MANUAL" : upper(req.origin(), WorkOrderRules.ORIGINS, "origin", errors);
        Long assignee = optionalId(req.assigneeId(), "assigneeId", errors);
        String title = req.title().strip();
        if (title.isEmpty()) {
            errors.add(new FieldErrorDetail("title", "NotBlank", null));
        }
        if (req.dueAt() != null && !req.dueAt().isAfter(clock.instant())) {
            errors.add(new FieldErrorDetail("dueAt", "Future", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        List<Target> targets = resolveTargets(org, req.targets(), true);
        requireAssignee(org, assignee);
        Created created = create(new NewWorkOrder(org, title, type, priority, targets, assignee, user.userId(), req.dueAt(),
                clean(req.checklist()), origin, blankToNull(req.originRef()), null), user.userId());
        if (!created.linkedToExisting()) {
            audits.record(audits.event(org, AUDIT_CREATED).actor(user).target("WORK_ORDER", Long.toString(created.order().id()))
                    .detail("type", type).detail("origin", origin).detail("targets", targets.size()));
        }
        return toResponse(created.order(), created.linkedToExisting());
    }

    /** API-DEV-129 내부 생성(flow-engine·analytics·core 알람). 권한 검사 없음(내부망, ADR-021). 조직의 기기·공간만 */
    @Transactional
    public InternalCreateWorkOrderResponse createInternal(InternalCreateWorkOrderRequest req) {
        long org = req.organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        String type = upper(req.type(), WorkOrderRules.TYPES, "type", errors);
        String priority = req.priority() == null || req.priority().isBlank() ? "NORMAL" : upper(req.priority(), WorkOrderRules.PRIORITIES, "priority", errors);
        String origin = upper(req.origin(), WorkOrderRules.ORIGINS, "origin", errors);
        Long assignee = optionalId(req.assigneeId(), "assigneeId", errors);
        if ("MANUAL".equals(origin)) {
            errors.add(new FieldErrorDetail("origin", "Invalid", "ALARM|ANALYSIS|FLOW|SCHEDULE"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        List<Target> targets = resolveTargets(org, req.targets(), false);
        if (assignee != null && !orders.existsActiveUser(org, assignee)) {
            assignee = null;
        }
        Created created = create(new NewWorkOrder(org, req.title().strip(), type, priority, targets, assignee, null, req.dueAt(),
                clean(req.checklist()), origin, req.originRef().strip(), null), null);
        return new InternalCreateWorkOrderResponse(Long.toString(created.order().id()), created.order().status(), created.linkedToExisting());
    }

    /**
     * 만들거나 기존 열린 작업 지시에 연결한다(BR-DEV-21). 정기 점검(BR-DEV-28)도 이 경로를 쓴다.
     * 같은 출처 참조의 열린 작업 → 그대로 돌려줌, 같은 기기·유형의 열린 작업(자동 생성만) → 출처를 덧붙임.
     */
    public Created create(NewWorkOrder n, Long actorUserId) {
        long org = n.organizationId();
        Instant now = clock.instant();
        boolean automatic = !"MANUAL".equals(n.origin());
        if (n.originRef() != null) {
            Optional<WorkOrder> same = orders.findOpenByOrigin(org, n.origin(), n.originRef());
            if (same.isPresent()) {
                return new Created(same.get(), true);
            }
        }
        if (automatic) {
            List<Long> deviceIds = n.targets().stream().map(Target::deviceId).filter(java.util.Objects::nonNull).toList();
            Optional<WorkOrder> existing = orders.findOpenByDeviceAndType(org, deviceIds, n.type());
            if (existing.isPresent()) {
                Map<String, Object> linked = new LinkedHashMap<>();
                linked.put("origin", n.origin());
                linked.put("originRef", n.originRef());
                linked.put("at", now.toString());
                orders.appendLinkedOrigin(org, existing.get().id(), json.writeValueAsString(linked), now);
                return new Created(orders.findById(org, existing.get().id()).orElseThrow(), true);
            }
        }
        String status = n.assigneeId() == null ? "OPEN" : "ASSIGNED";
        long id;
        try {
            id = orders.insert(org, n.title(), n.type(), status, n.priority(), n.assigneeId(), n.requesterId(), n.dueAt(), n.origin(),
                    n.originRef(), n.planId(), actorUserId, now);
        } catch (DuplicateKeyException ex) {
            // 같은 출처 참조가 동시에 들어왔다(부분 UNIQUE): 먼저 만든 쪽에 연결
            return new Created(orders.findOpenByOrigin(org, n.origin(), n.originRef()).orElseThrow(() -> ex), true);
        }
        orders.insertTargets(org, id, n.targets());
        orders.insertChecklist(org, id, n.checklist());
        WorkOrder order = orders.findById(org, id).orElseThrow();
        publish(order, null, null);
        return new Created(order, false);
    }

    /** API-DEV-91 — DEV_READ. assigneeId=me면 내 열린 작업만(상태를 따로 주지 않았을 때) */
    @Transactional(readOnly = true)
    public WorkOrderListResponse list(List<String> status, String assigneeId, Instant dueBefore, Boolean overdue, String spaceId, String type,
                                      Instant from, Instant to, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        CurrentUser user = roleChecker.currentUser();
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        if (status != null) {
            for (String s : status) {
                if (s != null && !s.isBlank()) {
                    statuses.add(upper(s, WorkOrderRules.STATUSES, "status", errors));
                }
            }
        }
        Long assignee = null;
        if (assigneeId != null && !assigneeId.isBlank()) {
            if ("me".equalsIgnoreCase(assigneeId.strip())) {
                assignee = user.userId();
                if (statuses.isEmpty()) {
                    statuses.addAll(WorkOrderRules.OPEN_STATUSES);
                }
            } else {
                assignee = optionalId(assigneeId, "assigneeId", errors);
            }
        }
        Long space = optionalId(spaceId, "spaceId", errors);
        String t = type == null || type.isBlank() ? null : upper(type, WorkOrderRules.TYPES, "type", errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        SpaceScope scope = roleChecker.spaceScope();
        Filter filter = new Filter(user.organizationId(), statuses, assignee, dueBefore, Boolean.TRUE.equals(overdue), space, t, from, to,
                scope.unrestricted() ? null : scope.allowedSpaceIds(), clock.instant());
        PageParams params = PageParams.of(page, size);
        List<WorkOrder> rows = orders.list(filter, params.size(), params.offset());
        long total = orders.count(filter);
        Double avg = orders.averageLeadTimeHours(filter);
        List<WorkOrderResponse> items = toResponses(user.organizationId(), rows);
        int totalPages = (int) Math.ceil(total / (double) params.size());
        return new WorkOrderListResponse(ApiHeader.success(), params.page(), params.size(), totalPages, items, total,
                new Stats(avg == null ? null : Math.round(avg * 10) / 10.0));
    }

    /** 상세 — DEV_READ */
    @Transactional(readOnly = true)
    public WorkOrderDetailResponse get(long id) {
        roleChecker.require(Permission.DEV_READ);
        WorkOrder w = load(id, Permission.DEV_READ, false);
        long org = w.organizationId();
        List<AttachmentResponse> attachments = orders.findAttachments(org, id).stream().map(WorkOrderService::toAttachment).toList();
        List<CommentResponse> comments = orders.findComments(org, id).stream()
                .map(c -> new CommentResponse(Long.toString(c.id()), Long.toString(c.authorId()), c.authorName(), c.body(), c.createdAt()))
                .toList();
        List<TargetResponse> targets = targets(orders.findTargets(org, List.of(id)).getOrDefault(id, List.of()));
        List<ChecklistItemResponse> checklist = checklist(orders.findChecklists(org, List.of(id)).getOrDefault(id, List.of()));
        return new WorkOrderDetailResponse(Long.toString(w.id()), w.title(), w.type(), w.status(), w.priority(), targets, str(w.assigneeId()),
                str(w.requesterId()), w.dueAt(), w.origin(), w.originRef(), readJson(w.linkedOriginsJson()), checklist,
                readJson(w.resultJson()), w.completedAt(), attachments, comments, w.version(), w.createdAt(), w.updatedAt());
    }

    /**
     * API-DEV-92 전이 — WORKORDER_WRITE. ASSIGN(담당자 필수)·START(담당자가 없으면 나)·COMPLETE(결과)·CANCEL. 허용되지 않은 전이는 409
     * WORKORDER_STATE_CONFLICT. note가 있으면 댓글로 남긴다. 완료 결과의 후속 처리(배터리 예측 초기화·교체 연결)는 DEV-08.04(M7).
     */
    @Transactional
    public WorkOrderResponse transition(long id, TransitionRequest req) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        WorkOrder before = load(id, Permission.WORKORDER_WRITE, true);
        long org = before.organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        String action = upper(req.action(), WorkOrderRules.ACTIONS, "action", errors);
        Long assignee = optionalId(req.assigneeId(), "assigneeId", errors);
        if ("ASSIGN".equals(action) && assignee == null) {
            errors.add(new FieldErrorDetail("assigneeId", "NotNull", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        String next = WorkOrderRules.next(before.status(), action)
                .orElseThrow(() -> new BusinessException(WorkOrderErrorCode.WORKORDER_STATE_CONFLICT));
        Long newAssignee = before.assigneeId();
        if ("ASSIGN".equals(action)) {
            requireAssignee(org, assignee);
            newAssignee = assignee;
        } else if ("START".equals(action) && newAssignee == null) {
            newAssignee = user.userId();
        }
        Instant now = clock.instant();
        String result = before.resultJson();
        Instant completedAt = before.completedAt();
        if ("COMPLETE".equals(action)) {
            result = req.result() == null || req.result().isNull() ? null : json.writeValueAsString(req.result());
            completedAt = now;
        }
        if (orders.updateState(org, id, before.version(), next, newAssignee, result, completedAt, user.userId(), now) == 0) {
            throw new BusinessException(WorkOrderErrorCode.WORKORDER_STATE_CONFLICT);
        }
        if (req.note() != null && !req.note().isBlank()) {
            orders.insertComment(org, id, user.userId(), req.note().strip(), now);
        }
        WorkOrder after = orders.findById(org, id).orElseThrow();
        publish(after, before.status(), result);
        audits.record(audits.event(org, AUDIT_TRANSITIONED).actor(user).target("WORK_ORDER", Long.toString(id))
                .detail("action", action).detail("from", before.status()).detail("to", next));
        return toResponse(after, false);
    }

    /** API-DEV-93 첨부 올리기 — WORKORDER_WRITE, 이미지·PDF ≤20MB(내용으로 판정) */
    @Transactional
    public AttachmentResponse attach(long id, String fileName, byte[] data) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        WorkOrder w = load(id, Permission.WORKORDER_WRITE, true);
        Stored stored = files.storeMedia(w.organizationId(), "WORK_ORDER", fileName, data, user.userId(), false);
        String kind = stored.contentType().startsWith("image/") ? "PHOTO" : "FILE";
        Instant now = clock.instant();
        long attachmentId = orders.insertAttachment(w.organizationId(), id, stored.key(), kind, user.userId(), now);
        orders.touch(w.organizationId(), id, user.userId(), now);
        audits.record(audits.event(w.organizationId(), AUDIT_ATTACHMENT).actor(user).target("WORK_ORDER", Long.toString(id))
                .detail("attachmentId", attachmentId).detail("op", "ADD").detail("kind", kind));
        return toAttachment(orders.findAttachment(w.organizationId(), id, attachmentId).orElseThrow());
    }

    /** API-DEV-93 첨부 삭제 — WORKORDER_WRITE, 204 */
    @Transactional
    public void detach(long id, long attachmentId) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        WorkOrder w = load(id, Permission.WORKORDER_WRITE, false);
        Attachment a = orders.findAttachment(w.organizationId(), id, attachmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        orders.deleteAttachment(w.organizationId(), id, attachmentId);
        files.delete(w.organizationId(), List.of(a.objectKey()));
        audits.record(audits.event(w.organizationId(), AUDIT_ATTACHMENT).actor(user).target("WORK_ORDER", Long.toString(id))
                .detail("attachmentId", attachmentId).detail("op", "DELETE"));
    }

    /** 첨부 내려받기 — DEV_READ(작업 지시를 볼 수 있는 사람) */
    @Transactional(readOnly = true)
    public Blob attachmentContent(long id, long attachmentId) {
        roleChecker.require(Permission.DEV_READ);
        WorkOrder w = load(id, Permission.DEV_READ, false);
        Attachment a = orders.findAttachment(w.organizationId(), id, attachmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return files.load(w.organizationId(), a.objectKey()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** API-DEV-94 댓글 — WORKORDER_WRITE, 201 */
    @Transactional
    public CommentResponse comment(long id, String text) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        WorkOrder w = load(id, Permission.WORKORDER_WRITE, false);
        Instant now = clock.instant();
        long commentId = orders.insertComment(w.organizationId(), id, user.userId(), text.strip(), now);
        orders.touch(w.organizationId(), id, user.userId(), now);
        return orders.findComments(w.organizationId(), id).stream().filter(c -> c.id() == commentId).findFirst()
                .map(c -> new CommentResponse(Long.toString(c.id()), Long.toString(c.authorId()), c.authorName(), c.body(), c.createdAt()))
                .orElseThrow();
    }

    /** API-DEV-94 체크리스트 항목 완료 표시 — WORKORDER_WRITE. 닫힌 작업 지시는 409 */
    @Transactional
    public ChecklistItemResponse check(long id, long itemId, boolean done) {
        roleChecker.require(Permission.WORKORDER_WRITE);
        CurrentUser user = roleChecker.currentUser();
        WorkOrder w = load(id, Permission.WORKORDER_WRITE, true);
        if (!WorkOrderRules.isOpen(w.status())) {
            throw new BusinessException(WorkOrderErrorCode.WORKORDER_STATE_CONFLICT);
        }
        orders.findChecklistItem(w.organizationId(), id, itemId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        orders.updateChecklistItem(w.organizationId(), id, itemId, done, done ? user.userId() : null, done ? now : null);
        orders.touch(w.organizationId(), id, user.userId(), now);
        ChecklistItem item = orders.findChecklistItem(w.organizationId(), id, itemId).orElseThrow();
        return new ChecklistItemResponse(Long.toString(item.id()), item.text(), item.done(), str(item.doneBy()), item.doneAt());
    }

    // ---- 내부

    /** 보이는 작업 지시(대상 공간 중 하나라도 범위 안). 아니면 404 WORKORDER_NOT_FOUND, 권한이 없으면 403 */
    private WorkOrder load(long id, Permission permission, boolean lock) {
        long org = roleChecker.currentUser().organizationId();
        WorkOrder w = (lock ? orders.lockById(org, id) : orders.findById(org, id))
                .orElseThrow(() -> new BusinessException(WorkOrderErrorCode.WORKORDER_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        Long key = null;
        if (!scope.unrestricted()) {
            key = orders.findTargets(org, List.of(id)).getOrDefault(id, List.of()).stream().map(Target::spaceId)
                    .filter(s -> s != null && scope.allowedSpaceIds().contains(s)).findFirst().orElse(NO_SPACE);
        }
        roleChecker.require(permission, key, WorkOrderErrorCode.WORKORDER_NOT_FOUND);
        return w;
    }

    /** 대상 검증: 기기·공간 하나씩, 조직의 것이고(사용자 요청이면) 권한 범위 안. 같은 대상은 한 번만 */
    private List<Target> resolveTargets(long org, List<TargetDto> raw, boolean checkScope) {
        Set<Target> out = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            TargetDto t = raw.get(i);
            boolean hasDevice = t != null && t.deviceId() != null && !t.deviceId().isBlank();
            boolean hasSpace = t != null && t.spaceId() != null && !t.spaceId().isBlank();
            if (hasDevice == hasSpace) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                        List.of(new FieldErrorDetail("targets[" + i + "]", "ExactlyOne", "deviceId|spaceId")));
            }
            if (hasDevice) {
                long deviceId = id(t.deviceId(), "targets[" + i + "].deviceId");
                Device d = devices.findById(org, deviceId).filter(x -> !x.deleted())
                        .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
                if (checkScope) {
                    roleChecker.require(Permission.WORKORDER_WRITE, d.spaceId() == null && !roleChecker.spaceScope().unrestricted() ? NO_SPACE
                            : d.spaceId(), DeviceErrorCode.DEVICE_NOT_FOUND);
                }
                out.add(new Target(deviceId, null));
            } else {
                long spaceId = id(t.spaceId(), "targets[" + i + "].spaceId");
                refs.findSpace(org, spaceId).orElseThrow(() -> new BusinessException(DeviceErrorCode.SPACE_NOT_FOUND));
                if (checkScope) {
                    roleChecker.require(Permission.WORKORDER_WRITE, spaceId, DeviceErrorCode.SPACE_NOT_FOUND);
                }
                out.add(new Target(null, spaceId));
            }
        }
        return List.copyOf(out);
    }

    private void requireAssignee(long org, Long assignee) {
        if (assignee != null && !orders.existsActiveUser(org, assignee)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("assigneeId", "NotFound", null)));
        }
    }

    private void publish(WorkOrder w, String from, String result) {
        List<Long> deviceIds = orders.findTargets(w.organizationId(), List.of(w.id())).getOrDefault(w.id(), List.of()).stream()
                .map(Target::deviceId).filter(java.util.Objects::nonNull).toList();
        publisher.event(EventType.WORKORDER_CHANGED, w.organizationId(),
                new WorkOrderChanged(w.id(), w.type(), from, w.status(), deviceIds, result, w.assigneeId()));
    }

    List<WorkOrderResponse> toResponses(long org, List<WorkOrder> rows) {
        List<Long> ids = rows.stream().map(WorkOrder::id).toList();
        Map<Long, List<Target>> targets = orders.findTargets(org, ids);
        Map<Long, List<ChecklistItem>> checklists = orders.findChecklists(org, ids);
        return rows.stream().map(w -> response(w, targets.getOrDefault(w.id(), List.of()), checklists.getOrDefault(w.id(), List.of()), false))
                .toList();
    }

    private WorkOrderResponse toResponse(WorkOrder w, boolean linked) {
        return response(w, orders.findTargets(w.organizationId(), List.of(w.id())).getOrDefault(w.id(), List.of()),
                orders.findChecklists(w.organizationId(), List.of(w.id())).getOrDefault(w.id(), List.of()), linked);
    }

    private WorkOrderResponse response(WorkOrder w, List<Target> targets, List<ChecklistItem> checklist, boolean linked) {
        return new WorkOrderResponse(Long.toString(w.id()), w.title(), w.type(), w.status(), w.priority(), targets(targets),
                str(w.assigneeId()), str(w.requesterId()), w.dueAt(), w.origin(), w.originRef(), checklist(checklist), readJson(w.resultJson()),
                w.completedAt(), linked, w.version(), w.createdAt(), w.updatedAt());
    }

    private static List<TargetResponse> targets(List<Target> targets) {
        return targets.stream().map(t -> new TargetResponse(str(t.deviceId()), str(t.spaceId()))).toList();
    }

    private static List<ChecklistItemResponse> checklist(List<ChecklistItem> items) {
        return items.stream().map(c -> new ChecklistItemResponse(Long.toString(c.id()), c.text(), c.done(), str(c.doneBy()), c.doneAt()))
                .toList();
    }

    static AttachmentResponse toAttachment(Attachment a) {
        return new AttachmentResponse(Long.toString(a.id()), Long.toString(a.workOrderId()), a.kind(), a.fileName(), a.sizeBytes(),
                "/api/v1/core/work-orders/" + a.workOrderId() + "/attachments/" + a.id() + "/content", Long.toString(a.uploadedBy()),
                a.createdAt());
    }

    private JsonNode readJson(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    static String upper(String raw, List<String> allowed, String field, List<FieldErrorDetail> errors) {
        String v = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            errors.add(new FieldErrorDetail(field, "Invalid", String.join("|", allowed)));
            return null;
        }
        return v;
    }

    static Long optionalId(String raw, String field, List<FieldErrorDetail> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.strip().matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, "INVALID", null));
            return null;
        }
        return Long.valueOf(raw.strip());
    }

    static long id(String raw, String field) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Long v = optionalId(raw, field, errors);
        if (v == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
        }
        return v;
    }

    static List<String> clean(List<String> items) {
        return items == null ? List.of() : items.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
