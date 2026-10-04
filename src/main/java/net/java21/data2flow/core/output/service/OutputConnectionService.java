package net.java21.data2flow.core.output.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.output.domain.OutputConnectionRules;
import net.java21.data2flow.core.output.domain.OutputConnectionRules.Filter;
import net.java21.data2flow.core.output.domain.OutputErrorCode;
import net.java21.data2flow.core.output.domain.OutputSample;
import net.java21.data2flow.core.output.dto.OutputDtos.DeviceContext;
import net.java21.data2flow.core.output.dto.OutputDtos.OutputConnectionResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.ReplayResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.StatPoint;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository.OutputRow;
import net.java21.data2flow.core.output.repository.OutputConnectionRepository.SampleDeviceRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 출력 연결(DSC-04.01, BR-DSC-19, API-DSC-30~33). 쓰기 SRC_ADMIN(INTEGRATOR 이상), 조회 SRC_READ. 정의·비밀값은 core에 두고 실행(구독·발송·재시도·
 * 실패 보관)은 action {@code output} 패키지가 한다(ADR-048). 비밀값은 종류별 AES-256-GCM 암호문({@code output_secrets})으로만 두고 응답에는
 * {@code secretConfigured}·{@code secretKinds}만 준다. 바뀔 때마다 실행 설정 버전(API-DSC-73)을 올리고 EVT-DSC-01({@code ConfigChangedMessage}
 * OUTPUT)을 아웃박스로 보낸다. 감사 OUTPUT_CREATED·UPDATED·DELETED·TESTED·REPLAYED(비밀값은 종류만).
 */
@Service
public class OutputConnectionService {

    static final String TARGET = "OUTPUT";
    static final Duration MAX_STATS_RANGE = Duration.ofDays(7);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final OutputConnectionRepository outputs;
    private final OutputActionClient action;
    private final SecretCipher cipher;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public OutputConnectionService(OutputConnectionRepository outputs, OutputActionClient action, SecretCipher cipher,
                                   CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.outputs = outputs;
        this.action = action;
        this.cipher = cipher;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 정리된 생성·수정 값 */
    record Draft(String name, String type, ObjectNode target, Filter filter, String format, String template, boolean enabled,
                 Map<String, String> secrets) {
    }

    // ---------------------------------------------------------------- 조회

    /** API-DSC-31 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<OutputConnectionResponse> list(String type, Integer page, Integer size) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String t = type == null || type.isBlank() ? null : type.strip().toUpperCase(Locale.ROOT);
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, outputs.list(orgId, t, params.size(), params.offset()).stream().map(this::response).toList(),
                outputs.count(orgId, t));
    }

    @Transactional(readOnly = true)
    public OutputConnectionResponse get(long id) {
        roleChecker.require(Permission.SRC_READ);
        return response(load(roleChecker.currentUser().organizationId(), id));
    }

    /** API-DSC-31 1분 발송 지표. 기본 최근 1시간, 최대 7일 */
    @Transactional(readOnly = true)
    public List<StatPoint> stats(long id, String from, String to) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        load(orgId, id);
        Instant end = to == null || to.isBlank() ? clock.instant() : instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(Duration.ofHours(1)) : instant(from, "from");
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_STATS_RANGE) > 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("from", "Range", null)));
        }
        return outputs.stats(orgId, id, start, end).stream()
                .map(s -> new StatPoint(s.minute(), s.sent(), s.failed(), s.retried(), s.lagMs())).toList();
    }

    // ---------------------------------------------------------------- 쓰기

    /** API-DSC-30 생성(201) */
    @Transactional
    public OutputConnectionResponse create(JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        Draft d = draft(orgId, b, null);
        if (outputs.existsName(orgId, d.name(), null)) {
            throw OutputConnectionRules.invalid(List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
        }
        Instant now = clock.instant();
        long id;
        try {
            id = outputs.insert(orgId, d.name(), d.type(), json.writeValueAsString(d.target()), json.writeValueAsString(d.filter().toJson(false)),
                    d.format(), d.template(), d.enabled(), user.userId(), now);
        } catch (DuplicateKeyException ex) {
            throw OutputConnectionRules.invalid(List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
        }
        storeSecrets(orgId, id, d.secrets(), now);
        OutputRow created = outputs.findById(orgId, id).orElseThrow();
        changed(orgId, created, now);
        audits.record(audits.event(orgId, "OUTPUT_CREATED").actor(user).target(TARGET, Long.toString(id)).detail("name", d.name())
                .detail("type", d.type()).detail("format", d.format()).detail("secretKinds", created.secretKinds()));
        return response(created);
    }

    /** API-DSC-30 부분 수정(온 키만, baseVersion 필수). 유형은 바꿀 수 없다 */
    @Transactional
    public OutputConnectionResponse patch(long id, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        OutputRow current = outputs.lockById(orgId, id).orElseThrow(() -> new BusinessException(OutputErrorCode.OUTPUT_NOT_FOUND));
        VersionCheck.require(baseVersion, current.version());
        List<FieldErrorDetail> unknown = new ArrayList<>();
        for (String key : body.propertyNames()) {
            if (!Set.of("name", "target", "filter", "format", "template", "secret", "enabled", "baseVersion", "type").contains(key)) {
                unknown.add(new FieldErrorDetail(key, "UNKNOWN_FIELD", null));
            }
        }
        if (body.has("type") && !current.type().equalsIgnoreCase(body.get("type").asString(""))) {
            unknown.add(new FieldErrorDetail("type", "IMMUTABLE", null));
        }
        OutputConnectionRules.throwIfInvalid(unknown);
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        merged.put("type", current.type());
        merged.set("name", body.has("name") ? body.get("name") : JsonNodeFactory.instance.stringNode(current.name()));
        merged.set("target", body.has("target") ? body.get("target") : json.readTree(current.target()));
        merged.set("filter", body.has("filter") ? body.get("filter") : json.readTree(current.filter()));
        merged.set("format", body.has("format") ? body.get("format") : JsonNodeFactory.instance.stringNode(current.format()));
        merged.set("template", body.has("template") ? body.get("template")
                : current.template() == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.stringNode(current.template()));
        merged.put("enabled", body.has("enabled") ? body.get("enabled").asBoolean(current.enabled()) : current.enabled());
        if (body.has("secret")) {
            merged.set("secret", body.get("secret"));
        }
        Draft d = draft(orgId, merged, id);
        if (outputs.existsName(orgId, d.name(), id)) {
            throw OutputConnectionRules.invalid(List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
        }
        Instant now = clock.instant();
        VersionCheck.requireUpdated(outputs.update(orgId, id, baseVersion, d.name(), json.writeValueAsString(d.target()),
                json.writeValueAsString(d.filter().toJson(false)), d.format(), d.template(), d.enabled(), user.userId(), now));
        storeSecrets(orgId, id, d.secrets(), now);
        OutputRow updated = outputs.findById(orgId, id).orElseThrow();
        changed(orgId, updated, now);
        List<String> fields = body.propertyNames().stream().filter(k -> !"baseVersion".equals(k) && !"secret".equals(k)).sorted().toList();
        audits.record(audits.event(orgId, "OUTPUT_UPDATED").actor(user).target(TARGET, Long.toString(id)).detail("fields", fields)
                .detail("secretKinds", d.secrets().keySet()).detail("enabled", d.enabled()));
        return response(updated);
    }

    /** API-DSC-30 삭제(204). action은 설정 변경을 받고 연결을 닫는다(밀린 발송은 버린다) */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        OutputRow current = load(orgId, id);
        outputs.delete(orgId, id);
        Instant now = clock.instant();
        outputs.bumpVersion(orgId, now);
        publisher.configDeleted(EntityType.OUTPUT, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "OUTPUT_DELETED").actor(user).target(TARGET, Long.toString(id)).detail("name", current.name()));
    }

    // ---------------------------------------------------------------- 테스트·재전송(action 중계)

    /**
     * API-DSC-32 저장 전 테스트(생성 본문 + {@code sampleDeviceId}, {@code outputConnectionId?}가 있으면 저장된 비밀값으로 빈 칸을 채운다).
     * 실패는 HTTP 오류가 아니라 200 + {@code ok=false}·{@code failureKind}(action 응답 그대로)
     * 외부 대상에 실제로 보내므로(최대 10초) DB 트랜잭션을 잡지 않는다.
     */
    public JsonNode test(JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        Long existing = optionalId(b.get("outputConnectionId"), "outputConnectionId");
        Map<String, String> stored = Map.of();
        ObjectNode merged = b.isObject() ? ((ObjectNode) b).deepCopy() : JsonNodeFactory.instance.objectNode();
        merged.remove("sampleDeviceId");
        merged.remove("outputConnectionId");
        if (!merged.has("name")) {
            merged.put("name", "test");
        }
        if (existing != null) {
            OutputRow row = load(orgId, existing);
            stored = decrypt(row.id(), outputs.secrets(List.of(row.id())));
            merged.put("type", row.type());
        }
        Draft d = draft(orgId, merged, existing);
        Long sampleId = optionalId(b.get("sampleDeviceId"), "sampleDeviceId");
        if (sampleId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("sampleDeviceId", "NotNull", null)));
        }
        SampleDeviceRow device = outputs.sampleDevice(orgId, sampleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("sampleDeviceId", "NOT_FOUND", null))));
        CanonicalTelemetry sample = sample(device, d.filter());
        Map<String, String> secrets = new TreeMap<>(stored);
        d.secrets().forEach((k, v) -> {
            if (v == null) {
                secrets.remove(k);
            } else {
                secrets.put(k, v);
            }
        });
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("organizationId", Long.toString(orgId));
        req.put("type", d.type());
        req.put("target", d.target());
        req.put("filter", d.filter().toJson(true));
        req.put("format", d.format());
        req.put("template", d.template());
        req.put("secrets", secrets);
        req.put("sample", json.valueToTree(sample));
        req.put("context", json.valueToTree(context(device)));
        JsonNode result = action.test(req);
        audits.record(audits.event(orgId, "OUTPUT_TESTED").actor(user).target(TARGET, existing == null ? "draft" : Long.toString(existing))
                .detail("type", d.type()).detail("ok", result == null ? null : result.path("ok").asBoolean(false))
                .detail("failureKind", result == null || !result.hasNonNull("failureKind") ? null : result.get("failureKind").asString()));
        return result;
    }

    /** API-DSC-33 실패 보관 재전송 {@code {from, to}} → action API-DSC-77 */
    public ReplayResponse replayFailed(long id, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        load(orgId, id);
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        Instant from = instant(b.path("from").asString(null), "from");
        Instant to = b.hasNonNull("to") ? instant(b.get("to").asString(null), "to") : clock.instant();
        if (!from.isBefore(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("from", "Range", null)));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("organizationId", Long.toString(orgId));
        req.put("from", from.toString());
        req.put("to", to.toString());
        JsonNode r = action.replayFailed(id, req);
        int queued = r == null ? 0 : r.path("queued").asInt(0);
        audits.record(audits.event(orgId, "OUTPUT_REPLAYED").actor(user).target(TARGET, Long.toString(id)).detail("from", from.toString())
                .detail("to", to.toString()).detail("queued", queued));
        return new ReplayResponse(queued);
    }

    // ---------------------------------------------------------------- 도우미

    /** 본문 검사(조직 안 참조 확인 포함) */
    Draft draft(long orgId, JsonNode b, Long existingId) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = OutputConnectionRules.name(b.get("name"), errors);
        String type = OutputConnectionRules.type(b.get("type"), errors);
        ObjectNode target = OutputConnectionRules.target(type, b.get("target"), errors);
        Filter filter = OutputConnectionRules.filter(b.get("filter"), errors);
        String format = OutputConnectionRules.format(b.get("format"), errors);
        String template = OutputConnectionRules.template(format, b.get("template"), errors);
        Map<String, String> secrets = type == null ? Map.of() : OutputConnectionRules.secrets(type, b.get("secret"), errors);
        JsonNode enabledNode = b.get("enabled");
        if (enabledNode != null && !enabledNode.isNull() && !enabledNode.isBoolean()) {
            errors.add(new FieldErrorDetail("enabled", "Type", null));
        }
        boolean enabled = enabledNode == null || enabledNode.isNull() || enabledNode.asBoolean(true);
        OutputConnectionRules.throwIfInvalid(errors);
        missing(orgId, "devices", filter.deviceIds(), "filter.deviceIds", errors);
        missing(orgId, "device_groups", filter.groupIds(), "filter.groupIds", errors);
        missing(orgId, "spaces", filter.spaceIds(), "filter.spaceIds", errors);
        OutputConnectionRules.throwIfInvalid(errors);
        return new Draft(name, type, target, filter, format, template, enabled, secrets);
    }

    private void missing(long orgId, String table, List<Long> ids, String field, List<FieldErrorDetail> errors) {
        if (!outputs.missing(orgId, table, ids).isEmpty()) {
            errors.add(new FieldErrorDetail(field, "NOT_FOUND", null));
        }
    }

    private void storeSecrets(long orgId, long id, Map<String, String> secrets, Instant now) {
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            if (e.getValue() == null) {
                outputs.deleteSecret(orgId, id, e.getKey());
            } else {
                Secret secret = Secret.of(e.getValue());
                byte[] enc = cipher.encrypt(secret, context(id, e.getKey()));
                outputs.upsertSecret(orgId, id, e.getKey(), enc, cipher.keyId(enc), fingerprint(e.getValue()), now);
            }
        }
    }

    /** 복호화(내부 실행 설정·테스트 전용, 로그 금지) */
    Map<String, String> decrypt(long outputId, List<OutputConnectionRepository.SecretRow> rows) {
        Map<String, String> out = new TreeMap<>();
        for (OutputConnectionRepository.SecretRow row : rows) {
            if (row.outputId() == outputId) {
                out.put(row.kind(), cipher.decrypt(row.ciphertext(), context(outputId, row.kind())).reveal());
            }
        }
        return out;
    }

    private void changed(long orgId, OutputRow row, Instant now) {
        outputs.bumpVersion(orgId, now);
        publisher.configChanged(EntityType.OUTPUT, row.id(), row.version(), orgId);
    }

    CanonicalTelemetry sample(SampleDeviceRow device, Filter filter) {
        Map<String, Object> latest = device.latest() == null || device.latest().isBlank() ? Map.of() : json.readValue(device.latest(), MAP);
        return OutputSample.build(device.organizationId(), device.sourceId(), device.externalId(), device.deviceId(), device.status(),
                        device.modelId(), device.spaceId(), latest, filter.toContract(), clock.instant())
                .orElseThrow(() -> new BusinessException(OutputErrorCode.OUTPUT_SAMPLE_UNAVAILABLE,
                        List.of(new FieldErrorDetail("sampleDeviceId", "NO_LATEST_VALUE", null))));
    }

    private DeviceContext context(SampleDeviceRow device) {
        var rows = outputs.deviceContexts(List.of(device.organizationId()), List.of(device.deviceId()));
        return rows.isEmpty() ? null : OutputInternalService.toContext(rows.getFirst());
    }

    private OutputRow load(long orgId, long id) {
        return outputs.findById(orgId, id).orElseThrow(() -> new BusinessException(OutputErrorCode.OUTPUT_NOT_FOUND));
    }

    OutputConnectionResponse response(OutputRow row) {
        Filter filter = OutputConnectionRules.storedFilter(json.readTree(row.filter()));
        return new OutputConnectionResponse(Long.toString(row.id()), row.name(), row.type(), json.readTree(row.target()), filter.toJson(true),
                row.format(), row.template(), !row.secretKinds().isEmpty(), row.secretKinds(), row.enabled(), row.version(), row.createdAt(),
                row.updatedAt());
    }

    static String context(long outputId, String kind) {
        return "data2flow_core.output_secrets:" + outputId + ":" + kind;
    }

    static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Long optionalId(JsonNode n, String field) {
        if (n == null || n.isNull() || (n.isString() && n.asString().isBlank())) {
            return null;
        }
        if (n.isIntegralNumber() && n.asLong() > 0) {
            return n.asLong();
        }
        if (n.isString() && n.asString().strip().matches("\\d{1,18}")) {
            return Long.parseLong(n.asString().strip());
        }
        throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Type", null)));
    }

    private static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "NotNull", null)));
        }
        try {
            return java.time.OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Format", null)));
        }
    }
}
