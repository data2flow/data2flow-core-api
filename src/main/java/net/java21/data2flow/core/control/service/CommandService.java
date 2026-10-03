package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.contracts.web.CursorParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp.Result;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository.CommandRow;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository.EventRow;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository.Search;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.service.DeviceAccess;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 수동 제어와 명령 조회(ACT-02.01·04.03, API-ACT-01~04·06). core는 권한(DEVICE_CONTROL·DEV_READ)과 공간 범위를 보고(남의 기기 404,
 * 역할 부족 403) action 제어 창구 내부 API로 넘긴다. 검증·인터락·보호·드라이버 판정과 그 오류(거부된 명령의 commandId 포함)는 action이
 * 정하고 core는 그대로 전한다. 기기별 이력은 action 내부 API로, 공간·조직 전체 이력은 action 스키마를 직접 읽는다(conventions §6 예외,
 * action에 전체 이력 API가 없음). 출처 표시 이름은 core가 붙인다(ADR-043).
 */
@Service
public class CommandService {

    static final String AUDIT_REQUESTED = "DEVICE_COMMAND_REQUESTED";
    static final String AUDIT_CANCELLED = "DEVICE_COMMAND_CANCELLED";
    static final String AUDIT_OVERRIDE_RELEASED = "MANUAL_OVERRIDE_RELEASED";
    static final Set<String> WAIT = Set.of("none", "ack", "applied");
    static final Set<String> STATUSES = Set.of("REQUESTED", "REJECTED", "BLOCKED", "SKIPPED", "DELAYED", "QUEUED", "QUEUED_FOR_DOWNLINK",
            "SENT", "ACKED", "APPLIED", "TIMEOUT", "FAILED", "SUPERSEDED", "CANCELLED");
    static final Set<String> SOURCE_TYPES = Set.of("USER", "FLOW", "RULE", "AI", "SCHEDULE", "SCENE", "BULK", "SYSTEM");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final DeviceAccess access;
    private final ActionClient action;
    private final CommandHistoryRepository history;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;

    public CommandService(DeviceAccess access, ActionClient action, CommandHistoryRepository history, RoleChecker roleChecker,
                          Audits audits, JsonMapper json) {
        this.access = access;
        this.action = action;
        this.history = history;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
    }

    /**
     * API-ACT-01 명령 요청 → {@code POST /internal/action/commands}(priority=MANUAL, source=USER). 응답 상태(202 기본, wait면 200)를
     * 그대로 돌려준다. 감사 {@code DEVICE_COMMAND_REQUESTED}(거부도 남긴다, BR-IAM-17·BR-ACT-15).
     */
    public Result send(long deviceId, JsonNode body, String idempotencyKey) {
        Device device = access.device(deviceId, Permission.DEVICE_CONTROL);
        CurrentUser user = roleChecker.currentUser();
        String capability = requiredText(body, "capability");
        String command = requiredText(body, "command");
        JsonNode args = body.get("args");
        if (args == null || !args.isObject()) {
            throw ControlModels.invalid("args", "NotNull", null);
        }
        String wait = body.hasNonNull("wait") ? body.get("wait").asString("").toLowerCase(Locale.ROOT) : "none";
        if (!WAIT.contains(wait)) {
            throw ControlModels.invalid("wait", "Pattern", "none|ack|applied");
        }
        Integer validity = null;
        if (body.hasNonNull("validitySeconds")) {
            JsonNode v = body.get("validitySeconds");
            if (!v.isIntegralNumber() || v.asInt() < 60 || v.asInt() > 3600) {
                throw ControlModels.invalid("validitySeconds", "Range", "60~3600");
            }
            validity = v.asInt();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("organizationId", user.organizationId());
        request.put("deviceId", Long.toString(device.id()));
        request.put("capability", capability);
        request.put("command", command);
        request.put("args", args);
        if (validity != null) {
            request.put("validitySeconds", validity);
        }
        request.put("wait", wait);
        request.put("priority", "MANUAL");
        request.put("source", Map.of("type", "USER", "userId", Long.toString(user.userId())));
        request.put("idempotencyKey", idempotencyKey);
        try {
            Result result = action.command(request, idempotencyKey);
            audit(user, device, capability, command, result.response() == null ? null : result.response().path("id").asString(null),
                    result.response() == null ? null : result.response().path("status").asString(null), null);
            return result;
        } catch (RelayedErrorException ex) {
            JsonNode response = ex.body().get("response");
            audit(user, device, capability, command, response == null ? null : response.path("commandId").asString(null), "REJECTED",
                    ex.resultCode());
            throw ex;
        }
    }

    private void audit(CurrentUser user, Device device, String capability, String command, String commandId, String status,
                       String resultCode) {
        var event = audits.event(user.organizationId(), AUDIT_REQUESTED).actor(user).target("DEVICE", Long.toString(device.id()))
                .detail("capability", capability).detail("command", command);
        if (commandId != null) {
            event.detail("commandId", commandId);
        }
        if (status != null) {
            event.detail("status", status);
        }
        if (resultCode != null) {
            event.detail("resultCode", resultCode);
        }
        audits.record(event);
    }

    /** API-ACT-02 명령 하나 — DEV_READ + 기기 공간 범위. 없으면 404 COMMAND_NOT_FOUND */
    public JsonNode get(String commandId) {
        roleChecker.require(Permission.DEV_READ);
        UUID id = commandId(commandId);
        JsonNode command = fetch(id);
        Device device = requireVisible(command, Permission.DEV_READ);
        JsonNode copy = command.deepCopy();
        enrich(java.util.List.of(copy), Map.of(device.id(), device.name()));
        return copy;
    }

    /** API-ACT-02 취소(QUEUED·DELAYED·QUEUED_FOR_DOWNLINK만, 아니면 409 COMMAND_NOT_CANCELLABLE은 action이 낸다) — DEVICE_CONTROL */
    public JsonNode cancel(String commandId) {
        roleChecker.require(Permission.DEV_READ);
        UUID id = commandId(commandId);
        JsonNode command = fetch(id);
        requireVisible(command, Permission.DEVICE_CONTROL);
        JsonNode result = action.cancel(id.toString());
        CurrentUser user = roleChecker.currentUser();
        audits.record(audits.event(user.organizationId(), AUDIT_CANCELLED).actor(user).target("COMMAND", id.toString()));
        return result;
    }

    /** API-ACT-03 컨트롤 생성용 정보 → action — DEV_READ */
    public JsonNode control(long deviceId) {
        access.device(deviceId, Permission.DEV_READ);
        return action.control(deviceId);
    }

    /** API-ACT-04 섀도 → action — DEV_READ */
    public JsonNode shadow(long deviceId) {
        access.device(deviceId, Permission.DEV_READ);
        return action.shadow(deviceId);
    }

    /** API-ACT-06 수동 우선 해제 → action — DEVICE_CONTROL, 204 */
    public void releaseManualOverride(long deviceId, String capability) {
        Device device = access.device(deviceId, Permission.DEVICE_CONTROL);
        action.releaseManualOverride(deviceId, capability);
        CurrentUser user = roleChecker.currentUser();
        audits.record(audits.event(user.organizationId(), AUDIT_OVERRIDE_RELEASED).actor(user).target("DEVICE", Long.toString(device.id()))
                .detail("capability", capability));
    }

    /**
     * API-ACT-02 기기별 명령 이력(커서 목록) — DEV_READ. action {@code GET /internal/action/devices/{device-id}/commands}로 넘기고
     * 출처 표시 이름(flowName·userName)과 기기 이름을 붙인다(ADR-043: action은 ID만 저장).
     */
    public JsonNode deviceHistory(long deviceId, Instant from, Instant to, String sourceType, String status, String capability,
                                  String cursor, Integer size) {
        Device device = access.device(deviceId, Permission.DEV_READ);
        if (from != null && to != null && !from.isBefore(to)) {
            throw ControlModels.invalid("from", "Range", null);
        }
        Map<String, Object> query = net.java21.data2flow.core.common.InternalHttp.query("from", from, "to", to,
                "sourceType", upperOrNull(sourceType, SOURCE_TYPES, "sourceType"), "status", upperOrNull(status, STATUSES, "status"),
                "capability", capability, "cursor", cursor, "size", size);
        JsonNode envelope = action.deviceCommands(deviceId, query);
        if (envelope == null) {
            return null;
        }
        tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) envelope.deepCopy();
        JsonNode responses = copy.get("responses");
        if (responses != null && responses.isArray()) {
            enrich(responses.values(), Map.of(device.id(), device.name()));
        }
        return copy;
    }

    /** 출처 표시 이름과 기기 이름을 붙인다 */
    void enrich(java.util.Collection<JsonNode> commands, Map<Long, String> deviceNames) {
        long orgId = roleChecker.currentUser().organizationId();
        java.util.Set<UUID> flows = new java.util.HashSet<>();
        java.util.Set<Long> users = new java.util.HashSet<>();
        for (JsonNode c : commands) {
            JsonNode src = c.path("source");
            String flowId = src.path("flowId").asString("");
            if (UUID_PATTERN.matcher(flowId).matches()) {
                flows.add(UUID.fromString(flowId));
            }
            String userId = src.path("userId").asString("");
            if (userId.matches("\\d{1,18}")) {
                users.add(Long.parseLong(userId));
            }
        }
        Map<String, String> flowNames = history.findFlowNames(orgId, flows);
        Map<String, String> userNames = history.findUserNames(orgId, users);
        for (JsonNode c : commands) {
            if (!(c instanceof tools.jackson.databind.node.ObjectNode o)) {
                continue;
            }
            String deviceName = deviceNames.get(c.path("deviceId").asLong(-1));
            if (deviceName != null && !o.has("deviceName")) {
                o.put("deviceName", deviceName);
            }
            if (o.get("source") instanceof tools.jackson.databind.node.ObjectNode src) {
                String flowName = flowNames.get(src.path("flowId").asString(""));
                if (flowName != null) {
                    src.put("flowName", flowName);
                }
                String userName = userNames.get(src.path("userId").asString(""));
                if (userName != null) {
                    src.put("userName", userName);
                }
            }
        }
    }

    /** API-ACT-02 전체 이력(공간 필터, 커서 목록) — DEV_READ + 공간 범위 */
    public CursorListApiResponse<Map<String, Object>> history(Long spaceId, Instant from, Instant to, String sourceType, String status,
                                                              String capability, String cursor, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        List<Long> spaces = null;
        if (spaceId != null) {
            access.space(spaceId);
            spaces = history.listSubtreeIds(orgId, spaceId).stream().filter(scope::includes).toList();
        } else if (!scope.unrestricted()) {
            spaces = List.copyOf(scope.allowedSpaceIds());
        }
        return page(null, spaces, from, to, sourceType, status, capability, cursor, size);
    }

    private CursorListApiResponse<Map<String, Object>> page(Long deviceId, List<Long> spaces, Instant from, Instant to, String sourceType,
                                                            String status, String capability, String cursor, Integer size) {
        long orgId = roleChecker.currentUser().organizationId();
        CursorParams params = CursorParams.of(cursor, size);
        Instant cursorAt = null;
        UUID cursorId = null;
        if (params.cursor() != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(params.cursor()), StandardCharsets.UTF_8).split("\\|", 2);
                cursorAt = Instant.parse(parts[0]);
                cursorId = UUID.fromString(parts[1]);
            } catch (RuntimeException ex) {
                throw ControlModels.invalid("cursor", "Pattern", null);
            }
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw ControlModels.invalid("from", "Range", null);
        }
        String st = upperOrNull(status, STATUSES, "status");
        String src = upperOrNull(sourceType, SOURCE_TYPES, "sourceType");
        String cap = capability == null || capability.isBlank() ? null : capability.strip();
        List<CommandRow> rows = history.search(new Search(orgId, deviceId, spaces, from, to, src, st, cap, cursorAt, cursorId,
                params.fetchSize()));
        boolean more = rows.size() > params.size();
        List<CommandRow> pageRows = more ? rows.subList(0, params.size()) : rows;
        Map<UUID, List<Map<String, Object>>> timelines = new HashMap<>();
        for (EventRow e : history.listEvents(orgId, pageRows.stream().map(CommandRow::id).toList())) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("status", e.toStatus());
            step.put("at", e.at());
            if (e.reason() != null) {
                step.put("reason", e.reason());
            }
            timelines.computeIfAbsent(e.commandId(), k -> new ArrayList<>()).add(step);
        }
        List<Map<String, Object>> items = pageRows.stream().map(r -> item(r, timelines.getOrDefault(r.id(), List.of()))).toList();
        String next = null;
        if (more) {
            CommandRow last = pageRows.getLast();
            next = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((last.requestedAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return CursorListApiResponse.of(params.size(), items, next);
    }

    /** 명령 이력 한 줄(API-ACT-02 Command + 화면 표시용 deviceName·source.flowName·source.userName) */
    private Map<String, Object> item(CommandRow r, List<Map<String, Object>> timeline) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", r.id().toString());
        item.put("status", r.status());
        if (r.statusReason() != null) {
            item.put("statusReason", r.statusReason());
        }
        item.put("deviceId", Long.toString(r.deviceId()));
        if (r.deviceName() != null) {
            item.put("deviceName", r.deviceName());
        }
        item.put("capability", r.capability());
        item.put("command", r.command());
        item.put("args", r.args() == null ? Map.of() : json.readValue(r.args(), MAP));
        item.put("priority", r.priority());
        Map<String, Object> source = r.source() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(json.readValue(r.source(), MAP));
        if (r.flowName() != null) {
            source.put("flowName", r.flowName());
        }
        if (r.userName() != null) {
            source.put("userName", r.userName());
        }
        item.put("source", source);
        item.put("validUntil", r.validUntil());
        if (r.executeAfter() != null) {
            item.put("executeAfter", r.executeAfter());
        }
        if (r.expectedDeliveryAt() != null) {
            item.put("expectedDeliveryAt", r.expectedDeliveryAt());
        }
        item.put("timeline", timeline);
        item.put("createdAt", r.requestedAt());
        return item;
    }

    private JsonNode fetch(UUID id) {
        try {
            JsonNode command = action.getCommand(id.toString());
            if (command == null) {
                throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
            }
            return command;
        } catch (BusinessException ex) {
            if ("RESOURCE_NOT_FOUND".equals(ex.getErrorCode().code())) {
                throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
            }
            throw ex;
        } catch (RelayedErrorException ex) {
            if (ex.status() == 404) {
                throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
            }
            throw ex;
        }
    }

    /** 명령의 기기가 조직·공간 범위 안인지(남의 것은 404 COMMAND_NOT_FOUND) */
    private Device requireVisible(JsonNode command, Permission permission) {
        String rawDevice = command.path("deviceId").asString("");
        long deviceId;
        try {
            deviceId = Long.parseLong(rawDevice);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
        }
        Device device = access.find(deviceId).orElseThrow(() -> new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND));
        roleChecker.require(permission, device.spaceId(), ControlErrorCode.COMMAND_NOT_FOUND);
        return device;
    }

    private static UUID commandId(String raw) {
        if (raw == null || !UUID_PATTERN.matcher(raw).matches()) {
            throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND);
        }
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || !node.isString() || node.asString().isBlank() || node.asString().length() > 64) {
            throw ControlModels.invalid(field, "NotBlank", null);
        }
        return node.asString().strip();
    }

    private static String upperOrNull(String raw, Set<String> allowed, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw ControlModels.invalid(field, "Pattern", null);
        }
        return value;
    }
}
