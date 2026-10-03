package net.java21.data2flow.core.sim.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.InternalHttp.Raw;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.sim.service.SimDeviceService;
import net.java21.data2flow.core.sim.service.SimRelayService;
import net.java21.data2flow.core.sim.service.SimSpaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 가상 환경 외부 API(SIM-api §1, {@code /api/v1/core/sim/**}). core가 권한 확인·기준 정보 생성을 하고 simulator 내부 API로 넘긴다.
 * 조회 SIM_READ, 실행 SIM_RUN, 관리 SIM_MANAGE, 샌드박스·정리 SIM_ADMIN.
 */
@RestController
public class SimController {

    private final SimRelayService relay;
    private final SimDeviceService devices;
    private final SimSpaceService spaces;

    public SimController(SimRelayService relay, SimDeviceService devices, SimSpaceService spaces) {
        this.relay = relay;
        this.devices = devices;
        this.spaces = spaces;
    }

    @GetMapping("/core/sim/overview")
    public ApiResponse<JsonNode> overview() {
        return ApiResponse.success(relay.overview());
    }

    @GetMapping("/core/sim/catalog")
    public ApiResponse<JsonNode> catalog(@RequestParam(required = false) String category) {
        return ApiResponse.success(relay.catalog(category));
    }

    // ---------------------------------------------------------------- 가상 기기·키트(API-SIM-05~07·09)

    @PostMapping("/core/sim/devices")
    @Idempotent
    public ResponseEntity<ApiResponse<Map<String, Object>>> placeDevices(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.success(devices.place(body)));
    }

    @GetMapping("/core/sim/devices/{device-id}")
    public ApiResponse<JsonNode> device(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(devices.get(deviceId));
    }

    @PatchMapping("/core/sim/devices/{device-id}")
    public ApiResponse<JsonNode> patchDevice(@PathVariable("device-id") long deviceId, @RequestBody JsonNode body) {
        return ApiResponse.success(devices.patch(deviceId, body));
    }

    @DeleteMapping("/core/sim/devices/{device-id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable("device-id") long deviceId,
                                             @RequestParam(required = false, defaultValue = "false") boolean purgeData) {
        devices.delete(deviceId, purgeData);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/sim/kits/{kit-key}/place")
    @Idempotent
    public ResponseEntity<ApiResponse<JsonNode>> placeKit(@PathVariable("kit-key") String kitKey, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.success(devices.placeKit(kitKey, body)));
    }

    // ---------------------------------------------------------------- 프로필(API-SIM-08)

    @GetMapping("/core/sim/profiles")
    public ItemsResponse<JsonNode> profiles() {
        return ItemsResponse.of(relay.profiles());
    }

    @PostMapping("/core/sim/profiles")
    public ResponseEntity<ApiResponse<JsonNode>> createProfile(@RequestBody JsonNode body) {
        JsonNode created = relay.writeProfile(HttpMethod.POST, null, body);
        String id = created == null ? "" : created.path("id").asString("");
        return ResponseEntity.created(URI.create("/api/v1/core/sim/profiles/" + id)).body(ApiResponse.success(created));
    }

    @GetMapping("/core/sim/profiles/{profile-id}")
    public ApiResponse<JsonNode> profile(@PathVariable("profile-id") String profileId) {
        return ApiResponse.success(relay.profile(profileId));
    }

    @PutMapping("/core/sim/profiles/{profile-id}")
    public ApiResponse<JsonNode> updateProfile(@PathVariable("profile-id") String profileId, @RequestBody JsonNode body) {
        return ApiResponse.success(relay.writeProfile(HttpMethod.PUT, profileId, body));
    }

    @DeleteMapping("/core/sim/profiles/{profile-id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable("profile-id") String profileId) {
        relay.writeProfile(HttpMethod.DELETE, profileId, null);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- 가상 공간·샌드박스(API-SIM-10·11·24)

    @GetMapping("/core/sim/spaces")
    public ItemsResponse<Map<String, Object>> spaces() {
        return ItemsResponse.of(spaces.list());
    }

    @PostMapping("/core/sim/spaces")
    @Idempotent
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSpace(@RequestBody JsonNode body) {
        Map<String, Object> created = spaces.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/sim/spaces/" + created.get("spaceId"))).body(ApiResponse.success(created));
    }

    @GetMapping("/core/sim/spaces/{space-id}")
    public ApiResponse<Map<String, Object>> space(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(spaces.get(spaceId));
    }

    @PutMapping("/core/sim/spaces/{space-id}")
    public ApiResponse<Map<String, Object>> updateSpace(@PathVariable("space-id") long spaceId, @RequestBody JsonNode body) {
        return ApiResponse.success(spaces.update(spaceId, body));
    }

    @DeleteMapping("/core/sim/spaces/{space-id}")
    public ResponseEntity<Void> deleteSpace(@PathVariable("space-id") long spaceId) {
        spaces.delete(spaceId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/core/sim/spaces/{space-id}/sandbox")
    public ApiResponse<Map<String, Object>> sandbox(@PathVariable("space-id") long spaceId, @RequestBody JsonNode body) {
        return ApiResponse.success(spaces.sandbox(spaceId, body));
    }

    @PostMapping("/core/sim/preview")
    public ApiResponse<JsonNode> preview(@RequestBody JsonNode body) {
        return ApiResponse.success(relay.preview(body));
    }

    // ---------------------------------------------------------------- 시나리오·프리셋(API-SIM-12·13·18·26)

    @GetMapping("/core/sim/scenarios")
    public JsonNode scenarios(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                              @RequestParam(required = false) String keyword) {
        return relay.scenarios(page, size, keyword);
    }

    @PostMapping("/core/sim/scenarios")
    @Idempotent
    public ResponseEntity<ApiResponse<JsonNode>> createScenario(@RequestBody JsonNode body) {
        JsonNode created = relay.writeScenario(HttpMethod.POST, "", body);
        String id = created == null ? "" : created.path("scenarioId").asString("");
        return ResponseEntity.created(URI.create("/api/v1/core/sim/scenarios/" + id)).body(ApiResponse.success(created));
    }

    @GetMapping("/core/sim/scenarios/{scenario-id}")
    public ApiResponse<JsonNode> scenario(@PathVariable("scenario-id") String scenarioId) {
        return ApiResponse.success(relay.scenario(scenarioId));
    }

    @PutMapping("/core/sim/scenarios/{scenario-id}")
    public ApiResponse<JsonNode> updateScenario(@PathVariable("scenario-id") String scenarioId, @RequestBody JsonNode body) {
        return ApiResponse.success(relay.writeScenario(HttpMethod.PUT, "/" + SimRelayServiceIds.id(scenarioId), body));
    }

    @DeleteMapping("/core/sim/scenarios/{scenario-id}")
    public ResponseEntity<Void> deleteScenario(@PathVariable("scenario-id") String scenarioId) {
        relay.writeScenario(HttpMethod.DELETE, "/" + SimRelayServiceIds.id(scenarioId), null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/sim/scenarios/{scenario-id}/clone")
    public ResponseEntity<ApiResponse<JsonNode>> cloneScenario(@PathVariable("scenario-id") String scenarioId,
                                                               @RequestBody(required = false) JsonNode body) {
        JsonNode created = relay.writeScenario(HttpMethod.POST, "/" + SimRelayServiceIds.id(scenarioId) + "/clone", body);
        String id = created == null ? "" : created.path("id").asString("");
        return ResponseEntity.created(URI.create("/api/v1/core/sim/scenarios/" + id)).body(ApiResponse.success(created));
    }

    @GetMapping("/core/sim/scenarios/{scenario-id}/export")
    public ResponseEntity<byte[]> export(@PathVariable("scenario-id") String scenarioId, @RequestParam(required = false) String format) {
        Raw raw = relay.export(scenarioId, format);
        HttpHeaders headers = new HttpHeaders();
        for (String name : List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_DISPOSITION)) {
            String value = raw.headers().getFirst(name);
            if (value != null) {
                headers.set(name, value);
            }
        }
        return ResponseEntity.ok().headers(headers).body(raw.body());
    }

    @GetMapping("/core/sim/presets")
    public ItemsResponse<JsonNode> presets() {
        return ItemsResponse.of(relay.presets());
    }

    @PostMapping("/core/sim/presets/{preset-key}/prepare")
    @Idempotent
    public ApiResponse<Map<String, Object>> prepare(@PathVariable("preset-key") String presetKey) {
        return ApiResponse.success(devices.preparePreset(presetKey));
    }

    // ---------------------------------------------------------------- 실행(API-SIM-14~17)

    @PostMapping("/core/sim/runs")
    @Idempotent
    public ResponseEntity<ApiResponse<JsonNode>> startRun(@RequestBody JsonNode body) {
        JsonNode created = relay.startRun(body);
        String id = created == null ? "" : created.path("runId").asString("");
        return ResponseEntity.created(URI.create("/api/v1/core/sim/runs/" + id)).body(ApiResponse.success(created));
    }

    @GetMapping("/core/sim/runs/{run-id}")
    public ApiResponse<JsonNode> run(@PathVariable("run-id") String runId) {
        return ApiResponse.success(relay.run(runId));
    }

    @PatchMapping("/core/sim/runs/{run-id}")
    public ApiResponse<JsonNode> patchRun(@PathVariable("run-id") String runId, @RequestBody JsonNode body) {
        return ApiResponse.success(relay.patchRun(runId, body));
    }

    @PostMapping("/core/sim/runs/{run-id}/{action}")
    public ApiResponse<JsonNode> controlRun(@PathVariable("run-id") String runId, @PathVariable("action") String action) {
        return ApiResponse.success(relay.controlRun(runId, action));
    }

    @GetMapping("/core/sim/runs/{run-id}/report")
    public ApiResponse<JsonNode> report(@PathVariable("run-id") String runId) {
        return ApiResponse.success(relay.report(runId));
    }

    @PatchMapping("/core/sim/runs/{run-id}/report")
    public ApiResponse<JsonNode> patchReport(@PathVariable("run-id") String runId, @RequestBody JsonNode body) {
        return ApiResponse.success(relay.patchReport(runId, body));
    }

    // ---------------------------------------------------------------- 장애·정리(API-SIM-20·21·25)

    @PostMapping("/core/sim/faults")
    public ResponseEntity<ApiResponse<JsonNode>> injectFault(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.success(relay.injectFault(body)));
    }

    @GetMapping("/core/sim/faults")
    public JsonNode faults(@RequestParam(required = false) String runId, @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return relay.faults(runId, status, page, size);
    }

    @PostMapping("/core/sim/faults/{fault-id}/cancel")
    public ApiResponse<JsonNode> cancelFault(@PathVariable("fault-id") String faultId) {
        return ApiResponse.success(relay.cancelFault(faultId));
    }

    @PostMapping("/core/sim/data/purge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> purge(@RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(ApiResponse.success(relay.purge(body)));
    }

    /** ID 형식 확인(경로 주입 방지) */
    static final class SimRelayServiceIds {
        private SimRelayServiceIds() {
        }

        static String id(String raw) {
            if (raw == null || !raw.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new net.java21.data2flow.contracts.error.BusinessException(
                        net.java21.data2flow.core.sim.domain.SimErrorCode.SIM_NOT_FOUND);
            }
            return raw;
        }
    }
}
