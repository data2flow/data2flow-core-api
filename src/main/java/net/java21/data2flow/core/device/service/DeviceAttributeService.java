package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.event.DeviceChanged.Change;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.AttributeSchema;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeEntry;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeHistoryResponse;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeResponse;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributesResponse;
import net.java21.data2flow.core.device.repository.DeviceAttributeRepository;
import net.java21.data2flow.core.device.repository.DeviceAttributeRepository.AttributeRow;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 기기 속성(DEV-07.01·07.04·07.05, BR-DEV-24). SERVER는 DEV_ADMIN, SHARED는 DEV_ADMIN + DEVICE_CONTROL, CLIENT(기기 보고)는 화면·API로
 * 고칠 수 없다(403 ATTRIBUTE_READONLY). 값이 바뀔 때마다 이력 1건(같은 값이면 없음, 2026-10-03 확정). 모델 속성 스키마가 있으면 타입·필수를
 * 검사한다(400 ATTRIBUTE_SCHEMA_VIOLATION). SHARED를 기기로 내려보내는 일(desired/reported, DEV-07.02)은 M3이다.
 */
@Service
public class DeviceAttributeService {

    static final Set<String> SCOPES = Set.of("SERVER", "SHARED", "CLIENT");
    static final int MAX_VALUE_BYTES = 4 * 1024;

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final DeviceAttributeRepository attributes;
    private final DeviceRepository devices;
    private final DeviceReferenceRepository refs;
    private final DeviceEvents events;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public DeviceAttributeService(RoleChecker roleChecker, DeviceAccess access, DeviceAttributeRepository attributes,
                                  DeviceRepository devices, DeviceReferenceRepository refs, DeviceEvents events,
                                  CoreEventPublisher publisher, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.attributes = attributes;
        this.devices = devices;
        this.refs = refs;
        this.events = events;
        this.publisher = publisher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-83 */
    @Transactional(readOnly = true)
    public AttributesResponse list(long deviceId) {
        roleChecker.require(Permission.DEV_READ);
        Device device = access.device(deviceId, Permission.DEV_READ);
        Map<String, AttributeEntry> server = new LinkedHashMap<>();
        Map<String, AttributeEntry> shared = new LinkedHashMap<>();
        Map<String, AttributeEntry> client = new LinkedHashMap<>();
        for (AttributeRow row : attributes.findByDevice(device.organizationId(), device.id())) {
            AttributeResponse r = toResponse(row);
            AttributeEntry entry = new AttributeEntry(r.value(), r.desired(), r.reported(), r.applyState(), r.updatedAt());
            switch (row.scope()) {
                case "SERVER" -> server.put(row.key(), entry);
                case "SHARED" -> shared.put(row.key(), entry);
                default -> client.put(row.key(), entry);
            }
        }
        return new AttributesResponse(server, shared, client);
    }

    /** API-DEV-80 */
    @Transactional
    public AttributeResponse put(long deviceId, String rawScope, String key, JsonNode value) {
        roleChecker.require(Permission.DEV_READ);
        String scope = scope(rawScope);
        Device device = writable(deviceId, scope);
        key(key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("value", "REQUIRED", null)));
        }
        String valueJson = json.writeValueAsString(value);
        if (valueJson.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("value", "TOO_LARGE", null)));
        }
        if (!schema(device).accepts(key, value)) {
            throw new BusinessException(DeviceErrorCode.ATTRIBUTE_SCHEMA_VIOLATION);
        }
        long org = device.organizationId();
        if (attributes.existsSameValue(org, deviceId, scope, key, valueJson)) {
            return toResponse(attributes.find(org, deviceId, scope, key).orElseThrow());
        }
        AttributeRow before = attributes.find(org, deviceId, scope, key).orElse(null);
        CurrentUser user = roleChecker.currentUser();
        Instant now = clock.instant();
        attributes.upsert(org, deviceId, scope, key, valueJson, user.userId(), now);
        changed(device, scope, key, before == null ? null : before.value(), valueJson, user, now);
        return toResponse(attributes.find(org, deviceId, scope, key).orElseThrow());
    }

    /** API-DEV-81. 없는 키는 그대로 204. 스키마 필수(기본값 없음) 키는 지울 수 없다 */
    @Transactional
    public void delete(long deviceId, String rawScope, String key) {
        roleChecker.require(Permission.DEV_READ);
        String scope = scope(rawScope);
        Device device = writable(deviceId, scope);
        key(key);
        if (!schema(device).accepts(key, null)) {
            throw new BusinessException(DeviceErrorCode.ATTRIBUTE_SCHEMA_VIOLATION);
        }
        long org = device.organizationId();
        AttributeRow before = attributes.find(org, deviceId, scope, key).orElse(null);
        if (before == null) {
            return;
        }
        attributes.delete(org, deviceId, scope, key);
        changed(device, scope, key, before.value(), null, roleChecker.currentUser(), clock.instant());
    }

    /** API-DEV-82 최신순 */
    @Transactional(readOnly = true)
    public ListApiResponse<AttributeHistoryResponse> history(long deviceId, String key, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        Device device = access.device(deviceId, Permission.DEV_READ);
        PageParams params = PageParams.of(page, size);
        String k = PageParams.keyword(key);
        List<AttributeHistoryResponse> items = attributes.findHistory(device.organizationId(), deviceId, k, params.size(), params.offset())
                .stream().map(h -> new AttributeHistoryResponse(Long.toString(h.id()), h.scope(), h.key(), node(h.oldValue()),
                        node(h.newValue()), h.changedBy() == null ? null : Long.toString(h.changedBy()), h.changedByName(), h.changedAt()))
                .toList();
        return ListApiResponse.of(params, items, attributes.countHistory(device.organizationId(), deviceId, k));
    }

    /** 모델 스키마 기본값을 채운 서버 속성(내부 API-DEV-122) */
    public Map<String, JsonNode> withDefaults(Device device, Map<String, JsonNode> server) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        schema(device).defaults().forEach(result::put);
        result.putAll(server);
        return result;
    }

    private Device writable(long deviceId, String scope) {
        Device device = access.device(deviceId, Permission.DEV_READ);
        if ("CLIENT".equals(scope)) {
            throw new BusinessException(DeviceErrorCode.ATTRIBUTE_READONLY);
        }
        Long spaceKey = access.scopeKey(device.spaceId());
        roleChecker.require(Permission.DEV_ADMIN, spaceKey, DeviceErrorCode.DEVICE_NOT_FOUND);
        if ("SHARED".equals(scope)) {
            roleChecker.require(Permission.DEVICE_CONTROL, spaceKey, DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        return device;
    }

    private void changed(Device device, String scope, String key, String oldJson, String newJson, CurrentUser user, Instant now) {
        long org = device.organizationId();
        attributes.insertHistory(org, device.id(), scope, key, oldJson, newJson, user.userId(), now);
        devices.updateVersion(org, device.id(), user.userId(), now);
        Device after = devices.findById(org, device.id()).orElseThrow();
        publisher.configChanged(EntityType.ATTRIBUTE, device.id(), after.version(), org);
        events.changed(after, Change.UPDATED, List.of("attributes." + scope.toLowerCase(Locale.ROOT) + "." + key));
        audits.record(audits.event(org, DeviceAudits.DEVICE_ATTRIBUTE_CHANGED).actor(user).target("DEVICE", Long.toString(device.id()))
                .detail("scope", scope).detail("key", key).detail("before", oldJson).detail("after", newJson));
    }

    private AttributeSchema schema(Device device) {
        if (device.modelId() == null) {
            return AttributeSchema.of(null);
        }
        String raw = refs.findModel(device.organizationId(), device.modelId()).map(m -> m.attributeSchema()).orElse(null);
        return AttributeSchema.of(raw == null ? null : json.readTree(raw));
    }

    private AttributeResponse toResponse(AttributeRow row) {
        boolean shared = "SHARED".equals(row.scope());
        String applyState = shared ? (row.reported() != null && Objects.equals(node(row.reported()), node(row.desired())) ? "APPLIED" : "PENDING")
                : null;
        return new AttributeResponse(row.scope(), row.key(), node(row.value()), shared ? node(row.desired()) : null,
                shared ? node(row.reported()) : null, applyState, row.updatedAt());
    }

    private JsonNode node(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    static String scope(String raw) {
        String scope = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("scope", "INVALID", raw)));
        }
        return scope;
    }

    static void key(String key) {
        if (key == null || !key.matches("^[a-zA-Z][a-zA-Z0-9_.]{0,63}$")) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("key", "INVALID", null)));
        }
    }
}
