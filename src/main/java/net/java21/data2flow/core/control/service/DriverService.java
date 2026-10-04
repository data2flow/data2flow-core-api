package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.dto.ControlDtos.DriverResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.DriverSummary;
import net.java21.data2flow.core.control.dto.ControlDtos.ModelDriverResponse;
import net.java21.data2flow.core.control.repository.DriverRepository;
import net.java21.data2flow.core.control.repository.DriverRepository.DriverRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 드라이버 정의(ACT-03.01·03.02, API-ACT-30~32)와 모델 연결(DEV-03.03, API-ACT-31 {@code PUT /device-models/{model-id}/driver}).
 * 모두 DRIVER_MANAGE(ADMIN·INTEGRATOR). 실제 연결 확인·지표는 action(드라이버 SPI)에 넘긴다. 비밀값은 암호화해 저장하고 응답에는
 * {@code hasSecret}만 준다. 정의가 바뀌면 {@code data2flow.config}(MODEL·SOURCE가 아닌 드라이버 연결은 MODEL)로 action 캐시를 지운다.
 *
 * <p>⏸ MQTT 드라이버의 공용 브로커 명령 발행과 LoRaWAN 다운링크는 결정 대기(CLAUDE.md §5)라 core는 정의만 저장한다.
 */
@Service
public class DriverService {

    static final String AUDIT_CREATED = "DRIVER_CREATED";
    static final String AUDIT_UPDATED = "DRIVER_UPDATED";
    static final String AUDIT_DELETED = "DRIVER_DELETED";
    static final String AUDIT_LINKED = "MODEL_DRIVER_LINKED";
    private static final String DEFAULT_RETRY = "{\"maxAttempts\":3,\"initialMs\":1000,\"multiplier\":2,\"maxMs\":10000}";
    private static final String DEFAULT_CIRCUIT = "{\"failureRate\":0.5,\"windowSec\":60,\"openSec\":30}";

    private final DriverRepository drivers;
    private final CapabilityService capabilities;
    private final ActionClient action;
    private final SecretCipher cipher;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public DriverService(DriverRepository drivers, CapabilityService capabilities, ActionClient action, SecretCipher cipher,
                         CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.drivers = drivers;
        this.capabilities = capabilities;
        this.action = action;
        this.cipher = cipher;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<DriverSummary> list(String type, Integer page, Integer size) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        String filter = type == null || type.isBlank() ? null : type.strip().toUpperCase(Locale.ROOT);
        PageParams params = PageParams.of(page, size);
        List<DriverSummary> rows = drivers.list(orgId, filter, params.size(), params.offset()).stream()
                .map(d -> new DriverSummary(Long.toString(d.id()), d.name(), d.type(), d.status(), d.deviceCount(), d.updatedAt()))
                .toList();
        return ListApiResponse.of(params, rows, drivers.count(orgId, filter));
    }

    @Transactional(readOnly = true)
    public DriverResponse get(long driverId) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        return response(require(roleChecker.currentUser().organizationId(), driverId));
    }

    /** API-ACT-30 생성 — 201. 종류별 설정 검사, 지원 기능 기본값은 종류가 정한다 */
    @Transactional
    public DriverResponse create(JsonNode body) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String name = name(body);
        String type = text(body, "type");
        if (type == null || !ControlModels.DRIVER_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            throw ControlModels.invalid("type", "Pattern", String.join("|", ControlModels.DRIVER_TYPES));
        }
        type = type.toUpperCase(Locale.ROOT);
        JsonNode config = config(body, type);
        List<String> caps = capabilitiesOf(orgId, body, type);
        if (drivers.existsName(orgId, name, null)) {
            throw ControlModels.invalid("name", "DUPLICATED", null);
        }
        long id;
        try {
            id = drivers.insert(orgId, name, type, json.writeValueAsString(config), null, intField(body, "pollingSec", 60, 0, 86400),
                    intField(body, "ackTimeoutSec", 30, 1, 3600), intField(body, "applyTimeoutSec", 60, 1, 3600),
                    objectField(body, "retry", DEFAULT_RETRY), objectField(body, "circuit", DEFAULT_CIRCUIT), caps, user.userId(),
                    clock.instant());
        } catch (DuplicateKeyException ex) {
            throw ControlModels.invalid("name", "DUPLICATED", null);
        }
        storeSecret(orgId, id, body);
        audits.record(audits.event(orgId, AUDIT_CREATED).actor(user).target("DRIVER", Long.toString(id))
                .detail("name", name).detail("type", type));
        return response(require(orgId, id));
    }

    /** API-ACT-30 수정(전체, baseVersion) — 종류는 바꾸지 않는다 */
    @Transactional
    public DriverResponse update(long driverId, JsonNode body) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DriverRow before = require(orgId, driverId);
        long base = VersionCheck.baseVersion(body);
        String name = name(body);
        if (drivers.existsName(orgId, name, driverId)) {
            throw ControlModels.invalid("name", "DUPLICATED", null);
        }
        JsonNode config = body.has("config") ? config(body, before.type()) : json.readTree(before.config());
        List<String> caps = body.has("capabilities") ? capabilitiesOf(orgId, body, before.type()) : before.capabilities();
        int updated = drivers.update(orgId, driverId, (int) base, name, json.writeValueAsString(config),
                intField(body, "pollingSec", before.pollingSec(), 0, 86400), intField(body, "ackTimeoutSec", before.ackTimeoutSec(), 1, 3600),
                intField(body, "applyTimeoutSec", before.applyTimeoutSec(), 1, 3600), objectField(body, "retry", before.retry()),
                objectField(body, "circuit", before.circuit()), caps, user.userId(), clock.instant());
        VersionCheck.requireUpdated(updated);
        storeSecret(orgId, driverId, body);
        driverChanged(orgId, driverId);
        audits.record(audits.event(orgId, AUDIT_UPDATED).actor(user).target("DRIVER", Long.toString(driverId)).detail("name", name));
        return response(require(orgId, driverId));
    }

    /** API-ACT-30 삭제 — 모델에 연결돼 있으면 409 DRIVER_IN_USE */
    @Transactional
    public void delete(long driverId) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DriverRow row = require(orgId, driverId);
        if (drivers.countBindings(orgId, driverId) > 0) {
            throw new BusinessException(ControlErrorCode.DRIVER_IN_USE);
        }
        drivers.delete(orgId, driverId);
        audits.record(audits.event(orgId, AUDIT_DELETED).actor(user).target("DRIVER", Long.toString(driverId)).detail("name", row.name()));
    }

    /** API-ACT-31 연결 확인 → action. 실패면 502 DRIVER_HEALTHCHECK_FAILED(원인), 상태 OK·ERROR 기록 */
    public JsonNode healthcheck(long driverId) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        var driver = require(orgId, driverId);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", driver.type());
        body.put("config", json.readTree(driver.config()));
        drivers.findSecret(orgId, driverId).ifPresent(enc -> body.put("secrets", json.readTree(new String(cipher.decryptBytes(enc,
                "data2flow_core.drivers.secret:" + driverId), StandardCharsets.UTF_8))));
        JsonNode result = action.healthcheck(driverId, body);
        boolean ok = result != null && result.path("ok").asBoolean(false);
        drivers.updateStatus(orgId, driverId, ok ? "OK" : "ERROR", clock.instant());
        if (!ok) {
            String cause = result == null ? "" : result.path("error").path("message").asString("");
            throw new BusinessException(ControlErrorCode.DRIVER_HEALTHCHECK_FAILED,
                    List.of(new FieldErrorDetail("driverId", result == null ? "UNKNOWN" : result.path("error").path("kind").asString("OTHER"),
                            cause)), cause);
        }
        return result;
    }

    /** API-ACT-32 지표 → action */
    public JsonNode metrics(long driverId, String window) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        require(roleChecker.currentUser().organizationId(), driverId);
        String w = window == null || window.isBlank() ? "1h" : window;
        if (!w.equals("1h") && !w.equals("24h")) {
            throw ControlModels.invalid("window", "Pattern", "1h|24h");
        }
        return action.metrics(driverId, w);
    }

    /**
     * DEV-03.03 · API-ACT-31 모델에 드라이버 연결(모델당 하나, {@code driverId=null}이면 끊음). 모델이 지원하는 기능을 드라이버가 모두
     * 지원해야 한다: 아니면 400 DRIVER_CAPABILITY_MISMATCH("이 드라이버는 {기능}을 지원하지 않습니다", TC-DEV-101·TC-ACT-078).
     */
    @Transactional
    public ModelDriverResponse linkModel(long modelId, JsonNode body) {
        roleChecker.require(Permission.DRIVER_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String modelCaps = drivers.findModelCapabilities(orgId, modelId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.MODEL_NOT_FOUND));
        String rawDriver = body == null ? null : text(body, "driverId");
        Long driverId = rawDriver == null ? null : parseId(rawDriver, "driverId");
        Long scriptId = null;
        String rawScript = body == null ? null : text(body, "encoderScriptRef");
        if (rawScript == null && body != null) {
            rawScript = text(body, "encoderScriptId");
        }
        if (rawScript != null) {
            scriptId = parseId(rawScript, "encoderScriptRef");
            if (!drivers.existsScript(orgId, scriptId)) {
                throw ControlModels.invalid("encoderScriptRef", "NOT_FOUND", null);
            }
        }
        List<String> warnings = new ArrayList<>();
        if (driverId != null) {
            DriverRow driver = require(orgId, driverId);
            Set<String> supported = new LinkedHashSet<>(driver.capabilities());
            for (String cap : ControlModels.capabilityNames(json.readTree(modelCaps))) {
                if (!supported.contains(cap)) {
                    throw new BusinessException(ControlErrorCode.DRIVER_CAPABILITY_MISMATCH,
                            List.of(new FieldErrorDetail("driverId", ControlErrorCode.DRIVER_CAPABILITY_MISMATCH.name(),
                                    "이 드라이버는 " + cap + "를 지원하지 않습니다")), cap);
                }
            }
            if (!"OK".equals(driver.status())) {
                warnings.add("DRIVER_" + driver.status());
            }
        }
        drivers.replaceBinding(orgId, modelId, driverId, scriptId, clock.instant());
        publisher.configChanged(EntityType.MODEL, modelId, clock.millis(), orgId);
        audits.record(audits.event(orgId, AUDIT_LINKED).actor(user).target("DEVICE_MODEL", Long.toString(modelId))
                .detail("driverId", driverId == null ? null : Long.toString(driverId)));
        return new ModelDriverResponse(Long.toString(modelId), driverId == null ? null : Long.toString(driverId),
                scriptId == null ? null : Long.toString(scriptId), warnings.isEmpty() ? null : warnings);
    }

    DriverRow require(long organizationId, long driverId) {
        return drivers.findById(organizationId, driverId).orElseThrow(() -> new BusinessException(ControlErrorCode.DRIVER_NOT_FOUND));
    }

    /** EVT-ACT-04 DRIVER: 드라이버가 바뀌면 action이 그 드라이버에 연결된 제어 프로필만 지운다(ADR-043) */
    private void driverChanged(long organizationId, long driverId) {
        drivers.findById(organizationId, driverId).ifPresent(d ->
                publisher.configChanged(EntityType.DRIVER, driverId, d.version(), organizationId));
    }

    private void storeSecret(long organizationId, long driverId, JsonNode body) {
        JsonNode secret = body == null ? null : body.get("secret");
        if (secret == null || secret.isNull()) {
            return;
        }
        if (!secret.isObject()) {
            throw ControlModels.invalid("secret", "Type", null);
        }
        byte[] enc = cipher.encrypt(json.writeValueAsString(secret).getBytes(StandardCharsets.UTF_8),
                "data2flow_core.drivers.secret:" + driverId);
        drivers.updateSecret(organizationId, driverId, enc);
    }

    private JsonNode config(JsonNode body, String type) {
        JsonNode config = body.get("config");
        if (config == null || config.isNull()) {
            config = json.createObjectNode();
        }
        if (!config.isObject()) {
            throw ControlModels.invalid("config", "Type", null);
        }
        switch (type) {
            case "MQTT" -> {
                if (!config.hasNonNull("sourceId")) {
                    throw ControlModels.invalid("config.sourceId", "NotNull", null);
                }
            }
            case "LORAWAN" -> {
                if (!config.hasNonNull("chirpstackUrl") || !config.hasNonNull("applicationId")) {
                    throw ControlModels.invalid("config.chirpstackUrl", "NotNull", null);
                }
            }
            default -> {
                // VIRTUAL·LG_THINQ·SMARTTHINGS: 필수 설정 없음(외부 키 연동은 파사드 + 가짜 구현, ADR-040)
            }
        }
        return config;
    }

    private List<String> capabilitiesOf(long organizationId, JsonNode body, String type) {
        JsonNode node = body.get("capabilities");
        if (node == null || node.isNull()) {
            return ControlModels.defaultCapabilities(type);
        }
        if (!node.isArray()) {
            throw ControlModels.invalid("capabilities", "Type", null);
        }
        CapabilityCatalog catalog = capabilities.catalog(organizationId);
        List<String> result = new ArrayList<>();
        for (JsonNode item : node.values()) {
            String cap = item.asString("");
            if (catalog.find(cap).isEmpty()) {
                throw ControlModels.invalid("capabilities", ControlErrorCode.CAPABILITY_NOT_SUPPORTED.name(), cap);
            }
            if (!result.contains(cap)) {
                result.add(cap);
            }
        }
        return result;
    }

    private DriverResponse response(DriverRow d) {
        return new DriverResponse(Long.toString(d.id()), d.name(), d.type(), json.readTree(d.config()), d.hasSecret(), d.pollingSec(),
                d.ackTimeoutSec(), d.applyTimeoutSec(), json.readTree(d.retry()), json.readTree(d.circuit()), d.status(), d.capabilities(),
                d.version(), d.createdAt(), d.updatedAt());
    }

    private static String name(JsonNode body) {
        String name = text(body, "name");
        if (name == null || name.length() > 100) {
            throw ControlModels.invalid("name", "Size", "1~100");
        }
        return name;
    }

    private String objectField(JsonNode body, String field, String fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isObject()) {
            throw ControlModels.invalid(field, "Type", null);
        }
        return json.writeValueAsString(node);
    }

    private static int intField(JsonNode body, String field, int fallback, int min, int max) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber() || node.asInt() < min || node.asInt() > max) {
            throw ControlModels.invalid(field, "Range", min + "~" + max);
        }
        return node.asInt();
    }

    static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isString() ? node.asString() : node.isNumber() ? node.asString() : null;
        return value == null || value.isBlank() ? null : value.strip();
    }

    static long parseId(String raw, String field) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 아래
        }
        throw ControlModels.invalid(field, "Pattern", null);
    }
}
