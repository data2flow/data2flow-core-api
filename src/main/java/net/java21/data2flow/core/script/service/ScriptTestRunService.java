package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.common.InMemoryRateLimiter;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;
import net.java21.data2flow.core.script.dto.ScriptDtos.TestRunRequest;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptTargetRepository;
import net.java21.data2flow.core.script.repository.ScriptTargetRepository.DeviceRef;
import net.java21.data2flow.core.script.repository.ScriptTargetRepository.RawMessageRef;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 테스트 실행(SCR-03.02, API-SCR-08 {@code POST /core/scripts/test-run}) → pipeline API-SCR-31. 아무것도 저장하지 않는다(BR-SCR-08):
 * core는 입력을 만들어 넘기고 pipeline 응답을 그대로 돌려준다.
 *
 * <ul>
 *   <li>입력은 직접 입력({@code input}) 또는 최근 원본({@code rawMessageId}, pipeline raw_messages 읽기 전용). 원본은 같은 조직이고
 *       기기가 정해졌다면 그 기기의 공간이 권한 범위 안이어야 한다(밖이면 404, TC-SCR-045)</li>
 *   <li>입력 JSON 256KB 초과 400 INVALID_REQUEST(errors[input]), 코드 64KB 초과 400 SCRIPT_CODE_TOO_LARGE</li>
 *   <li>사용자당 분당 60회(파드 단위) 넘으면 429 RATE_LIMITED + Retry-After</li>
 *   <li>context를 생략하면 실제 기기 값(속성·직전 값)과 scriptId의 설정값으로 채운다</li>
 *   <li>TRANSFORM을 원본으로 실행하면 core가 디코드할 수 없으므로 원본 입력과 rawMessageId를 함께 넘기고 pipeline이 소스 디코더로 먼저 푼다</li>
 * </ul>
 */
@Service
public class ScriptTestRunService {

    static final int PER_MINUTE = 60;

    private final ScriptTargetRepository targets;
    private final ScriptSupport support;
    private final PipelineScriptClient pipeline;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final InMemoryRateLimiter limiter;

    public ScriptTestRunService(ScriptTargetRepository targets, ScriptSupport support, PipelineScriptClient pipeline,
                                RoleChecker roleChecker, JsonMapper json, Clock clock) {
        this.targets = targets;
        this.support = support;
        this.pipeline = pipeline;
        this.roleChecker = roleChecker;
        this.json = json;
        this.limiter = new InMemoryRateLimiter(PER_MINUTE, Duration.ofMinutes(1), clock);
    }

    public JsonNode testRun(TestRunRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        OptionalLong retry = limiter.tryAcquire(orgId + ":" + user.userId());
        if (retry.isPresent()) {
            throw new BusinessException(CommonErrorCode.RATE_LIMITED, retry.getAsLong())
                    .withHeader("Retry-After", Long.toString(retry.getAsLong()));
        }
        ScriptKind kind = ScriptModels.kind(request.kind());
        String code = ScriptModels.requireCodeSize(request.code());
        ScriptRow script = request.scriptId() == null || request.scriptId().isBlank() ? null
                : support.require(orgId, ScriptSupport.parseId(request.scriptId(), "scriptId"));

        JsonNode input = request.input();
        Long deviceId = null;
        RawMessageRef raw = null;
        if (request.rawMessageId() != null && !request.rawMessageId().isBlank()) {
            raw = targets.findRawMessage(orgId, ScriptSupport.parseId(request.rawMessageId(), "rawMessageId"))
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            if (raw.deviceId() != null) {
                DeviceRef device = targets.findDevice(orgId, raw.deviceId())
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
                roleChecker.requireSpace(device.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
                deviceId = device.id();
            }
            input = rawInput(raw);
        }
        if (input == null || input.isNull()) {
            throw ScriptModels.invalid("input", "NotNull");
        }
        if (json.writeValueAsBytes(input).length > ScriptModels.MAX_TEST_INPUT_BYTES) {
            throw ScriptModels.invalid("input", "Size");
        }
        JsonNode context = request.context();
        if (deviceId == null && context != null && context.path("device").path("id").canConvertToLong()) {
            long id = context.path("device").path("id").asLong();
            DeviceRef device = targets.findDevice(orgId, id).orElse(null);
            if (device != null && roleChecker.spaceScope().includes(device.spaceId())) {
                deviceId = device.id();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", Long.toString(orgId));
        body.put("kind", kind.name());
        body.put("code", code);
        body.put("input", input);
        body.put("context", context(orgId, context, deviceId, script));
        if (script != null) {
            body.put("scriptId", Long.toString(script.id()));
        }
        if (raw != null) {
            body.put("rawMessageId", Long.toString(raw.id()));
        }
        return pipeline.testRun(body);
    }

    /**
     * AI 초안 샘플(API-SCR-16 {@code sampleRawMessageIds})을 DECODE 입력 모양으로 꺼낸다. 테스트 실행과 같은 규칙: 같은 조직이고
     * 기기가 정해졌다면 그 공간이 권한 범위 안이어야 한다(밖·없음 404)
     */
    public JsonNode sampleInput(long orgId, String rawMessageId) {
        RawMessageRef raw = targets.findRawMessage(orgId, ScriptSupport.parseId(rawMessageId, "sampleRawMessageIds"))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (raw.deviceId() != null) {
            DeviceRef device = targets.findDevice(orgId, raw.deviceId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(device.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return rawInput(raw);
    }

    /** DECODE 입력 모양(design/api/SCR-api.md §3.1 DecodeInput)으로 원본을 바꾼다 */
    private JsonNode rawInput(RawMessageRef raw) {
        ObjectNode node = json.createObjectNode();
        node.put("topic", raw.topic());
        String encoding = raw.payloadEncoding() == null ? "BINARY" : raw.payloadEncoding();
        node.put("payloadEncoding", encoding);
        byte[] payload = raw.payload() == null ? new byte[0] : raw.payload();
        if ("JSON".equals(encoding)) {
            try {
                node.set("payload", json.readTree(payload));
            } catch (tools.jackson.core.JacksonException ex) {
                node.put("payload", new String(payload, StandardCharsets.UTF_8));
            }
        } else if ("TEXT".equals(encoding)) {
            node.put("payload", new String(payload, StandardCharsets.UTF_8));
        } else {
            node.put("payload", Base64.getEncoder().encodeToString(payload));
        }
        node.put("receivedAt", raw.receivedAt() == null ? null : raw.receivedAt().toString());
        ObjectNode source = node.putObject("source");
        source.put("code", raw.sourceCode());
        source.set("config", raw.decoderConfig() == null ? json.createObjectNode() : json.readTree(raw.decoderConfig()));
        return node;
    }

    /** 컨텍스트: 요청 값이 우선이고 빠진 부분(device·last·config)을 실제 값으로 채운다 */
    private JsonNode context(long orgId, JsonNode requested, Long deviceId, ScriptRow script) {
        ObjectNode ctx = requested != null && requested.isObject() ? ((ObjectNode) requested).deepCopy() : json.createObjectNode();
        if (deviceId != null && !ctx.has("device")) {
            DeviceRef device = targets.findDevice(orgId, deviceId).orElse(null);
            if (device != null) {
                ObjectNode d = ctx.putObject("device");
                d.put("id", device.id());
                d.put("name", device.name());
                d.put("modelId", device.modelCode());
                d.put("modelName", device.modelName());
                if (device.spaceId() != null) {
                    d.put("spaceId", device.spaceId());
                }
                ObjectNode attributes = d.putObject("attributes");
                targets.findDeviceAttributes(orgId, deviceId).forEach((k, v) -> attributes.set(k, json.readTree(v)));
            }
        }
        if (deviceId != null && !ctx.has("last")) {
            ObjectNode last = ctx.putObject("last");
            targets.findDeviceLatest(orgId, deviceId).map(json::readTree).ifPresent(latest -> latest.properties().forEach(e -> {
                JsonNode v = e.getValue();
                ObjectNode item = last.putObject(e.getKey());
                item.set("value", v.has("v") ? v.get("v") : v);
                if (v.has("t")) {
                    item.set("measuredAt", v.get("t"));
                }
            }));
        }
        if (script != null && !ctx.has("config")) {
            ctx.set("config", json.readTree(script.config()));
        }
        return ctx;
    }
}
