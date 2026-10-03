package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp.Raw;
import net.java21.data2flow.core.sim.domain.SimErrorCode;
import net.java21.data2flow.core.sim.repository.SimRepository;
import net.java21.data2flow.core.sim.repository.SimRepository.VirtualSpace;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 가상 환경 외부 API 중계(SIM-api §1·§2): 권한을 본 뒤 simulator 내부 API로 넘긴다. 조회 SIM_READ(ANALYST+), 실행 SIM_RUN(ANALYST+),
 * 관리 SIM_MANAGE(INTEGRATOR+), 샌드박스·정리 SIM_ADMIN(ADMIN). simulator 오류(SIM_* 4xx, {@code errors[{field, code, message}]})는 그대로 전한다.
 * 실행·시나리오·장애·프로필은 조직 단위(simulator가 X-ORG-ID로 나눔)이고, 장애 대상 기기는 core가 가상 기기인지 먼저 본다.
 */
@Service
public class SimRelayService {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final Set<String> RUN_ACTIONS = Set.of("pause", "resume", "stop", "reset");
    static final String AUDIT_RUN_STARTED = "SIM_RUN_STARTED";
    static final String AUDIT_FAULT_INJECTED = "SIM_FAULT_INJECTED";
    static final String AUDIT_PROFILE_CHANGED = "SIM_PROFILE_CHANGED";
    static final String AUDIT_SCENARIO_CHANGED = "SIM_SCENARIO_CHANGED";
    static final String AUDIT_DATA_PURGED = "SIM_DATA_PURGED";

    private final SimulatorClient simulator;
    private final SimRepository sim;
    private final SimDeviceService devices;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SimRelayService(SimulatorClient simulator, SimRepository sim, SimDeviceService devices, RoleChecker roleChecker, Audits audits,
                           JsonMapper json, Clock clock) {
        this.simulator = simulator;
        this.sim = sim;
        this.devices = devices;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-SIM-01 요약. 공간 이름은 core에서, 프리셋 시나리오 ID는 프리셋 목록에서 붙인다(웹 보충 요청) */
    public JsonNode overview() {
        roleChecker.require(Permission.SIM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        JsonNode overview = simulator.call(HttpMethod.GET, "/internal/sim/overview", null, null);
        if (overview == null || !overview.isObject()) {
            return overview;
        }
        ObjectNode result = (ObjectNode) overview.deepCopy();
        Map<String, String> names = new HashMap<>();
        for (VirtualSpace s : sim.listVirtualSpaces(orgId)) {
            names.put(Long.toString(s.id()), s.name());
        }
        if (result.path("spaces").isArray()) {
            ArrayNode filtered = json.createArrayNode();
            for (JsonNode s : result.get("spaces").values()) {
                String id = s.path("spaceId").asString("");
                if (!names.containsKey(id) || !roleChecker.spaceScope().includes(Long.parseLong(id))) {
                    continue;
                }
                ObjectNode copy = (ObjectNode) s.deepCopy();
                copy.put("name", names.get(id));
                filtered.add(copy);
            }
            result.set("spaces", filtered);
        }
        JsonNode presets = simulator.call(HttpMethod.GET, "/internal/sim/presets", null, null);
        Map<String, JsonNode> byKey = new HashMap<>();
        if (presets != null) {
            presets.values().forEach(p -> byKey.put(p.path("key").asString(""), p));
        }
        if (result.path("presets").isArray()) {
            for (JsonNode p : result.get("presets").values()) {
                JsonNode full = byKey.get(p.path("key").asString(""));
                if (p instanceof ObjectNode o && full != null) {
                    o.set("scenarioId", full.get("scenarioId"));
                }
            }
        }
        return result;
    }

    public JsonNode catalog(String category) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.call(HttpMethod.GET, "/internal/sim/catalog", net.java21.data2flow.core.common.InternalHttp.query("category", category),
                null);
    }

    /** API-SIM-08 프로필 목록(페이징 없음) */
    public List<JsonNode> profiles() {
        roleChecker.require(Permission.SIM_READ);
        return list(simulator.call(HttpMethod.GET, "/internal/sim/profiles", null, null));
    }

    public JsonNode profile(String profileId) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.call(HttpMethod.GET, "/internal/sim/profiles/" + id(profileId), null, null);
    }

    public JsonNode writeProfile(HttpMethod method, String profileId, JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        String path = profileId == null ? "/internal/sim/profiles" : "/internal/sim/profiles/" + id(profileId);
        JsonNode result = simulator.call(method, path, null, body);
        audits.record(audits.event(user.organizationId(), AUDIT_PROFILE_CHANGED).actor(user).target("SIM_PROFILE",
                profileId == null && result != null ? result.path("id").asString("") : profileId).detail("method", method.name()));
        return result;
    }

    /** API-SIM-11 미리 보기(저장 없음) */
    public JsonNode preview(JsonNode body) {
        roleChecker.require(Permission.SIM_READ);
        if (body != null && body.hasNonNull("spaceId")) {
            Long spaceId = SpaceSupport.parseId(body.get("spaceId").asString(""), "spaceId");
            roleChecker.requireSpace(spaceId, SimErrorCode.SIM_NOT_FOUND);
        }
        return simulator.call(HttpMethod.POST, "/internal/sim/preview", null, body);
    }

    /** API-SIM-12 목록(오프셋 페이지, simulator 목록 봉투 그대로) */
    public JsonNode scenarios(Integer page, Integer size, String keyword) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.envelope(HttpMethod.GET, "/internal/sim/scenarios", query("page", page, "size", size, "keyword", keyword), null);
    }

    public JsonNode scenario(String scenarioId) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.call(HttpMethod.GET, "/internal/sim/scenarios/" + id(scenarioId), null, null);
    }

    /** 시나리오 만들기·수정·삭제·복제 — SIM_MANAGE */
    public JsonNode writeScenario(HttpMethod method, String path, JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        JsonNode result = simulator.call(method, "/internal/sim/scenarios" + path, null, body);
        audits.record(audits.event(user.organizationId(), AUDIT_SCENARIO_CHANGED).actor(user).target("SIM_SCENARIO", path.isEmpty()
                ? result == null ? "" : result.path("scenarioId").asString("") : path.substring(1)).detail("method", method.name()));
        return result;
    }

    /** API-SIM-26 내보내기(파일, 봉투 없음) — SIM_MANAGE */
    public Raw export(String scenarioId, String format) {
        roleChecker.require(Permission.SIM_MANAGE);
        String f = format == null || format.isBlank() ? "json" : format;
        if (!f.equals("json") && !f.equals("yaml")) {
            throw SpaceSupport.invalid("format", "Pattern");
        }
        return simulator.download("/internal/sim/scenarios/" + id(scenarioId) + "/export", Map.of("format", f));
    }

    /** API-SIM-18 프리셋 목록(문서 보충: scenarioId·state 포함) */
    public List<JsonNode> presets() {
        roleChecker.require(Permission.SIM_READ);
        return list(simulator.call(HttpMethod.GET, "/internal/sim/presets", null, null));
    }

    /** API-SIM-14 실행 시작 — SIM_RUN, 201 */
    public JsonNode startRun(JsonNode body) {
        roleChecker.require(Permission.SIM_RUN);
        CurrentUser user = roleChecker.currentUser();
        JsonNode result = simulator.call(HttpMethod.POST, "/internal/sim/runs", null, body);
        audits.record(audits.event(user.organizationId(), AUDIT_RUN_STARTED).actor(user)
                .target("SIM_RUN", result == null ? "" : result.path("runId").asString(""))
                .detail("scenarioId", body == null ? null : body.path("scenarioId").asString(null)));
        return result;
    }

    public JsonNode run(String runId) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.call(HttpMethod.GET, "/internal/sim/runs/" + id(runId), null, null);
    }

    /** API-SIM-15 실행 제어(pause·resume·stop·reset) — SIM_RUN */
    public JsonNode controlRun(String runId, String action) {
        roleChecker.require(Permission.SIM_RUN);
        if (!RUN_ACTIONS.contains(action)) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        return simulator.call(HttpMethod.POST, "/internal/sim/runs/" + id(runId) + "/" + action, null, null);
    }

    /** API-SIM-15 가속 변경 {acceleration} — SIM_RUN */
    public JsonNode patchRun(String runId, JsonNode body) {
        roleChecker.require(Permission.SIM_RUN);
        return simulator.call(HttpMethod.PATCH, "/internal/sim/runs/" + id(runId), null, body);
    }

    public JsonNode report(String runId) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.call(HttpMethod.GET, "/internal/sim/runs/" + id(runId) + "/report", null, null);
    }

    /** API-SIM-17 보관 연장 {retainUntil} — SIM_ADMIN */
    public JsonNode patchReport(String runId, JsonNode body) {
        roleChecker.require(Permission.SIM_ADMIN);
        return simulator.call(HttpMethod.PATCH, "/internal/sim/runs/" + id(runId) + "/report", null, body);
    }

    /** API-SIM-20 장애 주입 — SIM_RUN, 201. DEVICE 대상은 조직의 보이는 가상 기기여야 한다(아니면 400 SIM_TARGET_NOT_VIRTUAL) */
    public JsonNode injectFault(JsonNode body) {
        roleChecker.require(Permission.SIM_RUN);
        CurrentUser user = roleChecker.currentUser();
        if (body != null && "DEVICE".equals(body.path("targetType").asString("")) && body.path("targetIds").isArray()) {
            for (JsonNode target : body.get("targetIds").values()) {
                long deviceId;
                try {
                    deviceId = Long.parseLong(target.asString(""));
                } catch (NumberFormatException ex) {
                    throw new BusinessException(SimErrorCode.SIM_TARGET_NOT_VIRTUAL);
                }
                if (sim.findVirtualDevice(user.organizationId(), deviceId).isEmpty()) {
                    throw new BusinessException(SimErrorCode.SIM_TARGET_NOT_VIRTUAL);
                }
                devices.device(deviceId);
            }
        }
        JsonNode result = simulator.call(HttpMethod.POST, "/internal/sim/faults", null, body);
        audits.record(audits.event(user.organizationId(), AUDIT_FAULT_INJECTED).actor(user).target("SIM_FAULT",
                        result == null ? "" : result.path("faultIds").toString())
                .detail("kind", body == null ? null : body.path("kind").asString(null)));
        return result;
    }

    public JsonNode faults(String runId, String status, Integer page, Integer size) {
        roleChecker.require(Permission.SIM_READ);
        return simulator.envelope(HttpMethod.GET, "/internal/sim/faults", query("runId", runId, "status", status, "page", page, "size", size),
                null);
    }

    public JsonNode cancelFault(String faultId) {
        roleChecker.require(Permission.SIM_RUN);
        return simulator.call(HttpMethod.POST, "/internal/sim/faults/" + id(faultId) + "/cancel", null, null);
    }

    /**
     * API-SIM-25 가상 데이터 정리 요청 {runIds?} 또는 {from, to, spaceIds?} — SIM_ADMIN, 202 {jobId}. 작업을 기록하고 감사에 남긴다.
     * 실제 행 삭제는 각 소유 서비스(pipeline 시계열·원본, action 명령 이력)의 정리 API가 정해지면 넘긴다(문서 미정, 보고서 참고).
     */
    @Transactional
    public Map<String, Object> purge(JsonNode body) {
        roleChecker.require(Permission.SIM_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        List<String> runIds = new ArrayList<>();
        Instant from = null;
        Instant to = null;
        List<Long> spaceIds = new ArrayList<>();
        if (body != null && body.path("runIds").isArray()) {
            body.get("runIds").values().forEach(r -> runIds.add(r.asString("")));
        }
        try {
            from = body != null && body.hasNonNull("from") ? Instant.parse(body.get("from").asString("")) : null;
            to = body != null && body.hasNonNull("to") ? Instant.parse(body.get("to").asString("")) : null;
        } catch (DateTimeParseException ex) {
            throw SpaceSupport.invalid("from", "Pattern");
        }
        if (body != null && body.path("spaceIds").isArray()) {
            for (JsonNode s : body.get("spaceIds").values()) {
                long id = SpaceSupport.parseId(s.asString(""), "spaceIds");
                roleChecker.requireSpace(id, SimErrorCode.SIM_NOT_FOUND);
                spaceIds.add(id);
            }
        }
        if (runIds.isEmpty() && (from == null || to == null)) {
            throw SpaceSupport.invalid("runIds", "NotNull");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw SpaceSupport.invalid("to", "Range");
        }
        long jobId = sim.insertPurgeJob(user.organizationId(), "USER", runIds, from, to, spaceIds, user.userId(), clock.instant());
        audits.record(audits.event(user.organizationId(), AUDIT_DATA_PURGED).actor(user).target("SIM_PURGE_JOB", Long.toString(jobId))
                .detail("runIds", runIds));
        return Map.of("jobId", Long.toString(jobId));
    }

    /** simulator 보관 기한 정리(POST /internal/core/sim/data/purge {organizationId, runIds}) — 202 {jobId} */
    @Transactional
    public Map<String, Object> purgeInternal(long organizationId, JsonNode body) {
        List<String> runIds = new ArrayList<>();
        if (body != null && body.path("runIds").isArray()) {
            body.get("runIds").values().forEach(r -> runIds.add(r.asString("")));
        }
        if (runIds.isEmpty()) {
            throw SpaceSupport.invalid("runIds", "NotNull");
        }
        long jobId = sim.insertPurgeJob(organizationId, "RETENTION", runIds, null, null, List.of(), null, clock.instant());
        return Map.of("jobId", Long.toString(jobId), "runIds", runIds);
    }

    /** API-SIM-35 내부 맥락(simulator): SIM 소스, 가상 기기·공간 기준 정보 */
    @Transactional(readOnly = true)
    public Map<String, Object> context(long organizationId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("organizationId", Long.toString(organizationId));
        context.put("simSourceId", sim.findSimSource(organizationId).map(String::valueOf).orElse(null));
        context.put("devices", sim.listVirtualDevices(organizationId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", Long.toString(d.id()));
            m.put("name", d.name());
            m.put("externalId", d.externalId());
            m.put("spaceId", d.spaceId() == null ? null : Long.toString(d.spaceId()));
            m.put("modelId", d.modelId() == null ? null : Long.toString(d.modelId()));
            m.put("kind", d.kind());
            m.put("payloadFormat", "CHIRPSTACK_V4");
            return m;
        }).toList());
        context.put("spaces", sim.listVirtualSpaces(organizationId).stream()
                .map(s -> Map.of("spaceId", Long.toString(s.id()), "name", s.name(), "sandbox", s.sandbox())).toList());
        context.put("payloadFormats", Map.of());
        context.put("platformBrokerCredentialRef", null);
        return context;
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.values().forEach(out::add);
        } else if (node != null && node.path("responses").isArray()) {
            node.get("responses").values().forEach(out::add);
        }
        return out;
    }

    static String id(String raw) {
        if (raw == null || !ID.matcher(raw).matches()) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        return raw;
    }

    private static Map<String, Object> query(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null && !(pairs[i + 1] instanceof String s && s.isBlank())) {
                map.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return map;
    }
}
