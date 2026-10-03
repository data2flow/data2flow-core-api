package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.source.domain.JsonSchemaLite;
import net.java21.data2flow.core.source.domain.SourceConfigValidator;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.SecretMeta;
import net.java21.data2flow.core.source.domain.SourceModels.SourceLimits;
import net.java21.data2flow.core.source.domain.SourceModels.SourceTopic;
import net.java21.data2flow.core.source.dto.SourceDtos.LifecycleResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SecretResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SecretRotationResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceDetailResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceLimitsResponse;
import net.java21.data2flow.core.source.repository.ConnectorCatalogRepository;
import net.java21.data2flow.core.source.repository.ConnectorCatalogRepository.Connector;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 데이터 소스 등록·수정·상태 변경·삭제(SRC_ADMIN, INTEGRATOR 이상). DSC-01.01·01.02·01.04~01.07·07.01·07.03, BR-DSC-01~06·21.
 *
 * <ul>
 *   <li>코드·유형은 만든 뒤 바꿀 수 없다. 기본 유형(MQTT 구독·플랫폼 브로커·가상 환경)과 카탈로그 커넥터(CONNECTOR, 설정 스키마 검증
 *       BR-DSC-22)를 만든다. 외부 맥락·Webhook·엣지 유형은 M5</li>
 *   <li>모든 변경은 {@code SOURCES} 설정 버전을 올리고 EVT-DSC-01(ConfigChangedMessage SOURCE)을 보낸다. 감사 코드
 *       SOURCE_CREATED·UPDATED·ACTIVATED·PAUSED·RESUMED·ARCHIVED·DELETED·SECRET_CHANGED·LIMIT_CHANGED·IGNORE_REMOVED(BR-DSC-21).
 *       감사 detail에는 비밀값 종류와 지문만 남긴다</li>
 *   <li>상태 전이(domain-model §3.1): DRAFT→ACTIVE(activate), ACTIVE→PAUSED(pause), PAUSED→ACTIVE(resume),
 *       ACTIVE·PAUSED→ARCHIVED(archive, 확인 필요), DRAFT·ARCHIVED→삭제(기기 0대)</li>
 * </ul>
 */
@Service
public class DataSourceService {

    public static final String AUDIT_CREATED = "SOURCE_CREATED";
    public static final String AUDIT_UPDATED = "SOURCE_UPDATED";
    public static final String AUDIT_ACTIVATED = "SOURCE_ACTIVATED";
    public static final String AUDIT_PAUSED = "SOURCE_PAUSED";
    public static final String AUDIT_RESUMED = "SOURCE_RESUMED";
    public static final String AUDIT_ARCHIVED = "SOURCE_ARCHIVED";
    public static final String AUDIT_DELETED = "SOURCE_DELETED";
    public static final String AUDIT_SECRET_CHANGED = "SOURCE_SECRET_CHANGED";
    public static final String AUDIT_LIMIT_CHANGED = "SOURCE_LIMIT_CHANGED";
    public static final String AUDIT_IGNORE_REMOVED = "SOURCE_IGNORE_REMOVED";
    static final String TARGET = "SOURCE";

    private static final Set<String> PATCHABLE = Set.of("name", "connection", "topics", "secret", "isDev", "decoderKey", "decoderConfig",
            "decodeScriptId", "unknownDevicePolicy", "defaultModelId", "defaultSpaceId", "autoregLimitPerHour", "noDataAlarmAfterSec",
            "baseVersion", "code", "type", "connectorKey");

    private final DataSourceRepository sources;
    private final SourceReferenceRepository references;
    private final ConnectorCatalogRepository catalog;
    private final SourceSecrets secrets;
    private final SourceStateService states;
    private final SourceQueryService queries;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public DataSourceService(DataSourceRepository sources, SourceReferenceRepository references, ConnectorCatalogRepository catalog,
                             SourceSecrets secrets, SourceStateService states, SourceQueryService queries, RoleChecker roleChecker,
                             Audits audits, Clock clock) {
        this.sources = sources;
        this.references = references;
        this.catalog = catalog;
        this.secrets = secrets;
        this.states = states;
        this.queries = queries;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 생성

    /** API-DSC-02 소스 생성. {@code activate=true}면 검증을 통과할 때 바로 ACTIVE */
    @Transactional
    public SourceDetailResponse create(JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        String code = text(b, "code");
        String name = text(b, "name");
        String type = text(b, "type") == null ? null : text(b, "type").toUpperCase(Locale.ROOT);
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (code == null || !SourceConfigValidator.CODE.matcher(code).matches()) {
            errors.add(new FieldErrorDetail("code", code == null ? "NotBlank" : "Pattern", null));
        }
        checkName(name, errors);
        if (type == null) {
            errors.add(new FieldErrorDetail("type", "NotBlank", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Connector connector = connectorFor(type, text(b, "connectorKey"));
        SourceLimits limits = references.findLimits(orgId);
        if (sources.countNotArchived(orgId) >= limits.maxSources()) {
            throw new BusinessException(SourceErrorCode.SOURCE_LIMIT_EXCEEDED, List.of(new FieldErrorDetail("sources", "MAX_SOURCES", null)));
        }
        if (sources.existsCode(orgId, code)) {
            throw new BusinessException(SourceErrorCode.SOURCE_CODE_DUPLICATE);
        }
        boolean isDev = b.path("isDev").asBoolean(false);
        Settings settings = settings(orgId, type, connector, b, null, isDev, limits);
        checkClientIdBase(orgId, type, settings.connection(), code, null);
        Map<String, Secret> incoming = SourceSecrets.parse(b.get("secret"));
        SourceSecrets.checkKinds(type, auth(type, settings.connection()), incoming);
        boolean activate = b.path("activate").asBoolean(false);
        if (activate) {
            requireActivatable(type, settings.connection(), settings.topics(), incoming.keySet());
        }
        Instant now = clock.instant();
        String lifecycle = activate ? SourceModels.ACTIVE : SourceModels.DRAFT;
        long id = sources.insert(orgId, code, name.strip(), type, connector == null ? null : connector.key(),
                connector == null ? null : connector.version(), lifecycle, settings.connection().toString(), isDev, settings.decoderKey(),
                settings.decoderConfig() == null ? null : settings.decoderConfig().toString(), settings.decodeScriptId(),
                settings.unknownDevicePolicy(), settings.defaultModelId(), settings.defaultSpaceId(), settings.autoregLimitPerHour(),
                settings.noDataAlarmAfterSec(), user.userId(), now);
        sources.replaceTopics(orgId, id, settings.topics(), now);
        Map<String, String> fingerprints = secrets.store(orgId, id, incoming, now);
        if (activate) {
            states.activated(orgId, id);
        }
        DataSource created = queries.find(orgId, id);
        Map<String, Object> detail = auditDetail(created, settings.topics());
        detail.put("secretKinds", fingerprints.keySet());
        audits.record(audits.event(orgId, AUDIT_CREATED).actor(user).target(TARGET, Long.toString(id)).detail("after", detail)
                .detail("connectorKey", created.connectorKey()).detail("connectorVersion", created.connectorVersion()));
        if (activate) {
            audits.record(audits.event(orgId, AUDIT_ACTIVATED).actor(user).target(TARGET, Long.toString(id))
                    .detail("from", SourceModels.DRAFT).detail("to", SourceModels.ACTIVE));
        }
        states.configChanged(orgId, id, created.version());
        return queries.detail(created);
    }

    // ---------------------------------------------------------------- 수정

    /** API-DSC-04 부분 수정(온 키만). 코드·유형은 바꿀 수 없다. ARCHIVED는 읽기 전용 */
    @Transactional
    public SourceDetailResponse patch(long sourceId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        DataSource s = lock(orgId, sourceId);
        VersionCheck.require(baseVersion, s.version());
        if (SourceModels.ARCHIVED.equals(s.lifecycle())) {
            throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (String key : body.propertyNames()) {
            if (!PATCHABLE.contains(key)) {
                errors.add(new FieldErrorDetail(key, "UNKNOWN_FIELD", null));
            }
        }
        immutable(body, "code", s.code(), errors);
        immutable(body, "type", s.type(), errors);
        if (body.has("connectorKey") && s.connectorKey() != null && !body.get("connectorKey").isNull()
                && !s.connectorKey().equals(body.get("connectorKey").asString(""))) {
            errors.add(new FieldErrorDetail("connectorKey", "IMMUTABLE", null));
        }
        String name = body.has("name") ? text(body, "name") : s.name();
        if (body.has("name")) {
            checkName(name, errors);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        boolean isDev = body.has("isDev") ? body.get("isDev").asBoolean(false) : s.isDev();
        Connector connector = s.connectorKey() == null ? null : catalog.findByKey(s.connectorKey()).orElse(null);
        SourceLimits limits = references.findLimits(orgId);
        Settings settings = settings(orgId, s.type(), connector, body, s, isDev, limits);
        checkClientIdBase(orgId, s.type(), settings.connection(), s.code(), s.id());
        String auth = auth(s.type(), settings.connection());
        Map<String, Secret> incoming = SourceSecrets.parse(body.get("secret"));
        SourceSecrets.checkKinds(s.type(), auth, incoming);
        Set<String> allowed = SourceSecrets.allowedKinds(s.type(), auth);
        Set<String> available = new HashSet<>(incoming.keySet());
        sources.findSecretMeta(orgId, sourceId).stream().map(SecretMeta::kind).filter(allowed::contains).forEach(available::add);
        if (!SourceModels.DRAFT.equals(s.lifecycle())) {
            requireActivatable(s.type(), settings.connection(), settings.topics(), available);
        }
        Instant now = clock.instant();
        DataSource merged = new DataSource(s.id(), orgId, s.code(), name.strip(), s.type(), s.connectorKey(), s.connectorVersion(),
                s.lifecycle(), settings.connection(), s.tls(), s.payload(), isDev, settings.decoderKey(), settings.decoderConfig(),
                settings.decodeScriptId(), settings.unknownDevicePolicy(), settings.defaultModelId(), settings.defaultSpaceId(),
                settings.autoregLimitPerHour(), settings.noDataAlarmAfterSec(), s.siteId(), s.archivedAt(), s.version(), s.createdAt(), now);
        VersionCheck.requireUpdated(sources.update(merged, settings.connection().toString(),
                settings.decoderConfig() == null ? null : settings.decoderConfig().toString(), baseVersion, user.userId(), now));
        if (body.has("topics")) {
            sources.replaceTopics(orgId, sourceId, settings.topics(), now);
        }
        int removed = sources.deleteSecretsExcept(orgId, sourceId, allowed);
        Map<String, String> fingerprints = secrets.store(orgId, sourceId, incoming, now);
        DataSource updated = queries.find(orgId, sourceId);
        Set<String> changed = body.propertyNames().stream().filter(k -> !"baseVersion".equals(k) && !"secret".equals(k))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        audits.record(audits.event(orgId, AUDIT_UPDATED).actor(user).target(TARGET, Long.toString(sourceId))
                .detail("fields", changed).detail("after", auditDetail(updated, sources.findTopics(orgId, sourceId))));
        if (!fingerprints.isEmpty() || removed > 0) {
            audits.record(audits.event(orgId, AUDIT_SECRET_CHANGED).actor(user).target(TARGET, Long.toString(sourceId))
                    .detail("kinds", fingerprints.keySet()).detail("fingerprints", masked(fingerprints)).detail("removed", removed));
        }
        states.configChanged(orgId, sourceId, updated.version());
        return queries.detail(updated);
    }

    // ---------------------------------------------------------------- 비밀값

    /** API-DSC-05 비밀값 교체({@code {kind, value}}). M2는 저장 즉시 반영(무중단 교체 DSC-07.02는 M5) */
    @Transactional
    public SecretRotationResponse replaceSecret(long sourceId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        long orgId = roleChecker.currentUser().organizationId();
        Map<String, Secret> incoming = SourceSecrets.parse(body);
        storeSecrets(orgId, sourceId, incoming);
        return new SecretRotationResponse(UUID.randomUUID().toString(), "DONE",
                sources.findSecretMeta(orgId, sourceId).stream().map(SourceSecrets::info).toList());
    }

    /** API-DSC-58 종류 하나 교체({@code {value}}) */
    @Transactional
    public SecretResponse replaceSecretKind(long sourceId, String rawKind, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        long orgId = roleChecker.currentUser().organizationId();
        String kind = rawKind == null ? "" : rawKind.toUpperCase(Locale.ROOT).replace('-', '_');
        if (!SourceSecrets.KINDS.contains(kind)) {
            throw new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("kind", "INVALID", null)), "kind");
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("kind", kind);
        JsonNode value = body == null ? null : body.get("value");
        if (value != null) {
            node.set("value", value);
        }
        storeSecrets(orgId, sourceId, SourceSecrets.parse(node));
        SecretMeta meta = sources.findSecretMeta(orgId, sourceId).stream().filter(m -> m.kind().equals(kind)).findFirst().orElseThrow();
        return new SecretResponse(kind, SourceSecrets.mask(meta.fingerprint()), meta.rotatedAt());
    }

    private void storeSecrets(long orgId, long sourceId, Map<String, Secret> incoming) {
        CurrentUser user = roleChecker.currentUser();
        DataSource s = lock(orgId, sourceId);
        if (SourceModels.ARCHIVED.equals(s.lifecycle())) {
            throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
        }
        if (incoming.isEmpty()) {
            throw new BusinessException(SourceErrorCode.SOURCE_SECRET_REQUIRED, List.of(new FieldErrorDetail("value", "NotBlank", null)));
        }
        SourceSecrets.checkKinds(s.type(), s.auth(), incoming);
        Instant now = clock.instant();
        Map<String, String> fingerprints = secrets.store(orgId, sourceId, incoming, now);
        sources.touch(orgId, sourceId, user.userId(), now);
        audits.record(audits.event(orgId, AUDIT_SECRET_CHANGED).actor(user).target(TARGET, Long.toString(sourceId))
                .detail("kinds", fingerprints.keySet()).detail("fingerprints", masked(fingerprints)));
        states.configChanged(orgId, sourceId, s.version() + 1L);
    }

    // ---------------------------------------------------------------- 상태 전이

    /** API-DSC-06 activate·pause·resume·archive */
    @Transactional
    public LifecycleResponse transition(long sourceId, String action, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        int baseVersion = (int) VersionCheck.baseVersion(b);
        DataSource s = lock(orgId, sourceId);
        VersionCheck.require(baseVersion, s.version());
        String from = s.lifecycle();
        String to;
        String audit;
        switch (action) {
            case "activate" -> {
                requireFrom(from, SourceModels.DRAFT);
                to = SourceModels.ACTIVE;
                audit = AUDIT_ACTIVATED;
            }
            case "pause" -> {
                requireFrom(from, SourceModels.ACTIVE);
                to = SourceModels.PAUSED;
                audit = AUDIT_PAUSED;
            }
            case "resume" -> {
                requireFrom(from, SourceModels.PAUSED);
                to = SourceModels.ACTIVE;
                audit = AUDIT_RESUMED;
            }
            case "archive" -> {
                requireFrom(from, SourceModels.ACTIVE, SourceModels.PAUSED);
                JsonNode confirm = b.get("confirm");
                boolean confirmed = confirm != null && (confirm.isBoolean() ? confirm.asBoolean() : s.code().equals(confirm.asString("")));
                if (!confirmed) {
                    throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("confirm", "REQUIRED", null)));
                }
                to = SourceModels.ARCHIVED;
                audit = AUDIT_ARCHIVED;
            }
            default -> throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (SourceModels.ACTIVE.equals(to)) {
            Set<String> kinds = sources.findSecretMeta(orgId, sourceId).stream().map(SecretMeta::kind).collect(Collectors.toSet());
            requireActivatable(s.type(), s.connection(), sources.findTopics(orgId, sourceId), kinds);
        }
        Instant now = clock.instant();
        Instant archivedAt = SourceModels.ARCHIVED.equals(to) ? now : null;
        VersionCheck.requireUpdated(sources.updateLifecycle(orgId, sourceId, baseVersion, to, archivedAt, user.userId(), now));
        if (SourceModels.ACTIVE.equals(to)) {
            states.activated(orgId, sourceId);
        } else {
            states.recompute(orgId, sourceId, to);
        }
        audits.record(audits.event(orgId, audit).actor(user).target(TARGET, Long.toString(sourceId)).detail("from", from).detail("to", to));
        states.configChanged(orgId, sourceId, baseVersion + 1L);
        return new LifecycleResponse(Long.toString(sourceId), to, archivedAt, baseVersion + 1);
    }

    private static void requireFrom(String lifecycle, String... allowed) {
        for (String a : allowed) {
            if (a.equals(lifecycle)) {
                return;
            }
        }
        throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
    }

    // ---------------------------------------------------------------- 삭제·복제

    /** API-DSC-07 삭제: DRAFT·ARCHIVED이고 연결된 기기가 없을 때만(BR-DSC-04) */
    @Transactional
    public void delete(long sourceId) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DataSource s = lock(orgId, sourceId);
        if (SourceModels.ACTIVE.equals(s.lifecycle()) || SourceModels.PAUSED.equals(s.lifecycle())) {
            throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
        }
        long devices = sources.countDevices(orgId, sourceId);
        if (devices > 0) {
            throw new BusinessException(SourceErrorCode.SOURCE_IN_USE, devices);
        }
        sources.delete(orgId, sourceId);
        audits.record(audits.event(orgId, AUDIT_DELETED).actor(user).target(TARGET, Long.toString(sourceId))
                .detail("code", s.code()).detail("lifecycle", s.lifecycle()));
        states.configDeleted(orgId, sourceId, s.version() + 1L);
    }

    /** API-DSC-12 복제(DSC-07.05, BR-DSC-11): 비밀값·기기는 복사하지 않고 DRAFT로 만든다 */
    @Transactional
    public SourceDetailResponse cloneSource(long sourceId, JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DataSource s = queries.find(orgId, sourceId);
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        String code = text(b, "code");
        String name = text(b, "name");
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (code == null || !SourceConfigValidator.CODE.matcher(code).matches()) {
            errors.add(new FieldErrorDetail("code", code == null ? "NotBlank" : "Pattern", null));
        }
        checkName(name, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        if (sources.countNotArchived(orgId) >= references.findLimits(orgId).maxSources()) {
            throw new BusinessException(SourceErrorCode.SOURCE_LIMIT_EXCEEDED, List.of(new FieldErrorDetail("sources", "MAX_SOURCES", null)));
        }
        if (sources.existsCode(orgId, code)) {
            throw new BusinessException(SourceErrorCode.SOURCE_CODE_DUPLICATE);
        }
        ObjectNode connection = s.connection() == null ? JsonNodeFactory.instance.objectNode() : ((ObjectNode) s.connection()).deepCopy();
        connection.remove("clientIdBase"); // 같은 base는 BR-DSC-01 위반이라 새 코드의 기본값을 쓴다
        Instant now = clock.instant();
        long id = sources.insert(orgId, code, name.strip(), s.type(), s.connectorKey(), s.connectorVersion(), SourceModels.DRAFT,
                connection.toString(), s.isDev(), s.decoderKey(), s.decoderConfig() == null ? null : s.decoderConfig().toString(),
                s.decodeScriptId(), s.unknownDevicePolicy(), s.defaultModelId(), s.defaultSpaceId(), s.autoregLimitPerHour(),
                s.noDataAlarmAfterSec(), user.userId(), now);
        sources.replaceTopics(orgId, id, sources.findTopics(orgId, sourceId), now);
        DataSource created = queries.find(orgId, id);
        audits.record(audits.event(orgId, AUDIT_CREATED).actor(user).target(TARGET, Long.toString(id))
                .detail("clonedFrom", Long.toString(sourceId)).detail("after", auditDetail(created, sources.findTopics(orgId, id))));
        states.configChanged(orgId, id, created.version());
        return queries.detail(created);
    }

    /** API-DSC-13 무시 목록에서 빼기(다음 수신 때 다시 승인 대기로 등록될 수 있다, BR-DEV-07) */
    @Transactional
    public void removeIgnoreEntry(long sourceId, String externalId) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DataSource s = queries.find(orgId, sourceId);
        if (references.deleteIgnoreEntry(orgId, sourceId, externalId) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        audits.record(audits.event(orgId, AUDIT_IGNORE_REMOVED).actor(user).target(TARGET, Long.toString(sourceId))
                .detail("externalId", externalId));
        states.configChanged(orgId, sourceId, s.version());
    }

    // ---------------------------------------------------------------- 한도

    /** 조직 소스 한도 조회(DSC-07.03) — SRC_READ */
    @Transactional(readOnly = true)
    public SourceLimitsResponse limits() {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return limitsResponse(orgId);
    }

    /** 조직 소스 한도 변경(AT-DSC-21.4, 감사 SOURCE_LIMIT_CHANGED) — ADMIN(OPS_MANAGE). 온 키만 바꾼다 */
    @Transactional
    public SourceLimitsResponse updateLimits(JsonNode body) {
        roleChecker.require(Permission.OPS_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        int baseVersion = (int) VersionCheck.baseVersion(body);
        SourceLimits before = references.findLimits(orgId);
        VersionCheck.require(baseVersion, before.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        SourceLimits after = new SourceLimits(
                intField(body, "maxSources", before.maxSources(), 1, 10000, errors),
                intField(body, "maxTopicsPerSource", before.maxTopicsPerSource(), 1, 200, errors),
                intField(body, "maxMessageBytes", before.maxMessageBytes(), 1024, 16 * 1024 * 1024, errors),
                intField(body, "maxMessagesPerSec", before.maxMessagesPerSec(), 1, 100000, errors), before.version(), null);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        VersionCheck.requireUpdated(references.upsertLimits(orgId, after, baseVersion, user.userId(), clock.instant()));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("maxSources", before.maxSources());
        b.put("maxTopicsPerSource", before.maxTopicsPerSource());
        b.put("maxMessageBytes", before.maxMessageBytes());
        b.put("maxMessagesPerSec", before.maxMessagesPerSec());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("maxSources", after.maxSources());
        a.put("maxTopicsPerSource", after.maxTopicsPerSource());
        a.put("maxMessageBytes", after.maxMessageBytes());
        a.put("maxMessagesPerSec", after.maxMessagesPerSec());
        audits.record(audits.event(orgId, AUDIT_LIMIT_CHANGED).actor(user).target("SOURCE_LIMITS", Long.toString(orgId))
                .detail("before", b).detail("after", a));
        // ingress가 새 초당 한도·크기 한도(API-DSC-50 rateLimit)를 다시 읽게 한다
        for (DataSource s : sources.listOthersNotArchived(orgId, null)) {
            states.configChanged(orgId, s.id(), s.version());
        }
        return limitsResponse(orgId);
    }

    private SourceLimitsResponse limitsResponse(long orgId) {
        SourceLimits l = references.findLimits(orgId);
        return new SourceLimitsResponse(l.maxSources(), l.maxTopicsPerSource(), l.maxMessageBytes(), l.maxMessagesPerSec(),
                sources.countNotArchived(orgId), l.version(), l.updatedAt());
    }

    // ---------------------------------------------------------------- 검증 도우미

    /** 정리한 설정 값 */
    record Settings(ObjectNode connection, List<SourceTopic> topics, String decoderKey, JsonNode decoderConfig, Long decodeScriptId,
                    String unknownDevicePolicy, Long defaultModelId, Long defaultSpaceId, int autoregLimitPerHour, int noDataAlarmAfterSec) {
    }

    /**
     * 요청 본문과 현재 값(수정이면 current, 생성이면 null)으로 설정을 정리하고 검증한다. 온 키만 바꾼다.
     */
    Settings settings(long orgId, String type, Connector connector, JsonNode b, DataSource current, boolean isDev, SourceLimits limits) {
        SourceConfigValidator v = SourceConfigValidator.start();
        ObjectNode connection;
        if (current == null || b.has("connection")) {
            connection = SourceTypes.CONNECTOR.equals(type) ? connectorConnection(connector, b.get("connection"), v)
                    : v.connection(type, b.get("connection"), isDev);
        } else {
            connection = current.connection() == null ? JsonNodeFactory.instance.objectNode() : ((ObjectNode) current.connection()).deepCopy();
            if (current.isDev() && !isDev && connection.path("tlsInsecure").asBoolean(false)) {
                throw new BusinessException(SourceErrorCode.SOURCE_TLS_VERIFY_REQUIRED,
                        List.of(new FieldErrorDetail("connection.tlsInsecure", "TLS_VERIFY_REQUIRED", null)));
            }
        }
        boolean mqtt = SourceTypes.MQTT_SUBSCRIBE.equals(type);
        List<SourceTopic> topics;
        if (current == null || b.has("topics")) {
            int defaultQos = connection.path("qos").isIntegralNumber() ? connection.get("qos").asInt() : 1;
            topics = mqtt ? v.topics(b.get("topics"), defaultQos, limits.maxTopicsPerSource(), true) : List.of();
        } else {
            topics = sources.findTopics(orgId, current.id());
        }
        String decoderKey = b.has("decoderKey") ? text(b, "decoderKey") : current == null ? null : current.decoderKey();
        if (decoderKey == null || decoderKey.isBlank()) {
            v.reject("decoderKey", "NotBlank");
            decoderKey = "";
        } else if (!SourceTypes.CONNECTOR.equals(type) && !SourceModels.BASIC_DECODERS.contains(decoderKey)) {
            v.reject("decoderKey", "INVALID");
        } else if (decoderKey.length() > 30) {
            v.reject("decoderKey", "Size");
        }
        JsonNode decoderConfigRaw = b.has("decoderConfig") || current == null ? b.get("decoderConfig") : current.decoderConfig();
        JsonNode decoderConfig = v.decoderConfig(decoderKey, decoderConfigRaw);
        Long scriptId = b.has("decodeScriptId") || current == null ? longValue(b, "decodeScriptId", v) : current.decodeScriptId();
        if (SourceModels.DECODER_SCRIPT.equals(decoderKey)) {
            if (scriptId == null) {
                v.reject("decodeScriptId", "NotNull");
            }
        } else {
            scriptId = null;
        }
        String policy = b.has("unknownDevicePolicy") ? text(b, "unknownDevicePolicy")
                : current == null ? SourceModels.POLICY_AUTO_REGISTER : current.unknownDevicePolicy();
        if (policy == null) {
            policy = SourceModels.POLICY_AUTO_REGISTER;
        }
        if (!SourceModels.POLICY_AUTO_REGISTER.equals(policy) && !SourceModels.POLICY_REJECT.equals(policy)) {
            v.reject("unknownDevicePolicy", "INVALID");
        }
        Long modelId = b.has("defaultModelId") || current == null ? longValue(b, "defaultModelId", v) : current.defaultModelId();
        Long spaceId = b.has("defaultSpaceId") || current == null ? longValue(b, "defaultSpaceId", v) : current.defaultSpaceId();
        int autoreg = intValue(b, "autoregLimitPerHour", current == null ? 100 : current.autoregLimitPerHour(), 0, 10000, v);
        int noData = intValue(b, "noDataAlarmAfterSec", current == null ? 600 : current.noDataAlarmAfterSec(), 60, 86400, v);
        v.throwIfInvalid();
        if (scriptId != null && !references.findScriptKind(orgId, scriptId).map("DECODE"::equals).orElse(false)) {
            throw new BusinessException(SourceErrorCode.SCRIPT_NOT_FOUND);
        }
        if (modelId != null && (current == null || !modelId.equals(current.defaultModelId())) && !references.existsModel(orgId, modelId)) {
            v.reject("defaultModelId", "NOT_FOUND");
        }
        if (spaceId != null && (current == null || !spaceId.equals(current.defaultSpaceId()))) {
            SpaceScope scope = roleChecker.spaceScope();
            if (!references.existsSpace(orgId, spaceId) || !scope.includes(spaceId)) {
                v.reject("defaultSpaceId", "NOT_FOUND");
            }
        }
        v.throwIfInvalid();
        return new Settings(connection, topics, decoderKey, decoderConfig, scriptId, policy, modelId, spaceId, autoreg, noData);
    }

    /** 카탈로그 커넥터 설정: 커넥터 스키마로 검증(BR-DSC-22, 스키마에 없는 필드 거부) */
    private static ObjectNode connectorConnection(Connector connector, JsonNode raw, SourceConfigValidator v) {
        if (raw == null || raw.isNull()) {
            raw = JsonNodeFactory.instance.objectNode();
        }
        if (!raw.isObject()) {
            v.reject("connection", "Type");
            return JsonNodeFactory.instance.objectNode();
        }
        for (FieldErrorDetail e : JsonSchemaLite.validate(connector.schema(), raw, "connection")) {
            v.reject(e.field(), e.code());
        }
        return ((ObjectNode) raw).deepCopy();
    }

    /** 유형 → 카탈로그 커넥터. 기본 유형은 시드 키(mqtt·platform-broker·simulation), CONNECTOR는 요청 키(사용 가능해야 함) */
    private Connector connectorFor(String type, String connectorKey) {
        if (SourceModels.BASIC_TYPES.contains(type)) {
            return catalog.findByKey(SourceModels.CONNECTOR_OF_TYPE.get(type)).orElse(null);
        }
        if (SourceTypes.CONNECTOR.equals(type)) {
            if (connectorKey == null || connectorKey.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("connectorKey", "NotBlank", null)));
            }
            Connector c = catalog.findByKey(connectorKey).orElseThrow(() -> new BusinessException(SourceErrorCode.CONNECTOR_NOT_FOUND));
            if (!c.enabled()) {
                throw new BusinessException(SourceErrorCode.CONNECTOR_UNAVAILABLE, c.disabledReason() == null ? "" : c.disabledReason());
            }
            return c;
        }
        // WEBHOOK·EDGE·외부 맥락(KMA_WEATHER …)·산업 프로토콜은 M5(DSC-01.03·06·08, DSC-05)
        throw new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("type", "UNSUPPORTED", null)), "type");
    }

    /** client-id base 중복 금지(BR-DSC-01, 조직 안). base가 비면 {@code data2flow-{code}} */
    private void checkClientIdBase(long orgId, String type, JsonNode connection, String code, Long exceptId) {
        if (!SourceTypes.MQTT_SUBSCRIBE.equals(type)) {
            return;
        }
        String base = SourceModels.effectiveClientIdBase(connection, code);
        for (DataSource other : sources.listOthersNotArchived(orgId, exceptId)) {
            if (SourceTypes.MQTT_SUBSCRIBE.equals(other.type()) && other.clientIdBase().equals(base)) {
                throw new BusinessException(SourceErrorCode.SOURCE_CLIENT_ID_DUPLICATE);
            }
        }
    }

    /** ACTIVE로 둘 수 있는가(필수 설정·비밀값, domain-model §3.1) */
    private static void requireActivatable(String type, JsonNode connection, List<SourceTopic> topics, Set<String> secretKinds) {
        if (SourceTypes.MQTT_SUBSCRIBE.equals(type)) {
            SourceConfigValidator v = SourceConfigValidator.start();
            String url = connection == null ? null : connection.path("url").asString(null);
            if (url == null || !SourceConfigValidator.checkUrl(url)) {
                v.reject("connection.url", "NotBlank");
            }
            if (topics.isEmpty()) {
                v.reject("topics", "NotEmpty");
            }
            v.throwIfInvalid();
        }
        SourceSecrets.requirePresent(type, auth(type, connection), secretKinds);
    }

    static String auth(String type, JsonNode connection) {
        if (!SourceTypes.MQTT_SUBSCRIBE.equals(type) || connection == null) {
            return SourceModels.AUTH_NONE;
        }
        return connection.path("auth").asString(SourceModels.AUTH_NONE).toUpperCase(Locale.ROOT);
    }

    private DataSource lock(long orgId, long sourceId) {
        return sources.lockById(orgId, sourceId).orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
    }

    private static Map<String, Object> auditDetail(DataSource s, List<SourceTopic> topics) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", s.code());
        d.put("name", s.name());
        d.put("type", s.type());
        d.put("lifecycle", s.lifecycle());
        d.put("connection", s.connection() == null ? null : s.connection().toString());
        d.put("topics", topics.stream().map(t -> t.topic() + "@" + t.qos()).toList());
        d.put("decoderKey", s.decoderKey());
        d.put("decodeScriptId", s.decodeScriptId());
        d.put("unknownDevicePolicy", s.unknownDevicePolicy());
        d.put("defaultModelId", s.defaultModelId());
        d.put("defaultSpaceId", s.defaultSpaceId());
        return d;
    }

    private static Map<String, String> masked(Map<String, String> fingerprints) {
        Map<String, String> m = new LinkedHashMap<>();
        fingerprints.forEach((k, v) -> m.put(k, SourceSecrets.mask(v)));
        return m;
    }

    private static void immutable(JsonNode body, String field, String current, List<FieldErrorDetail> errors) {
        if (body.has(field) && !body.get(field).isNull() && !current.equalsIgnoreCase(body.get(field).asString(""))) {
            errors.add(new FieldErrorDetail(field, "IMMUTABLE", null));
        }
    }

    private static void checkName(String name, List<FieldErrorDetail> errors) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            errors.add(new FieldErrorDetail("name", name == null || name.isBlank() ? "NotBlank" : "Size", null));
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString("");
    }

    /** ID 값: 숫자 또는 숫자 문자열(ID는 JSON 문자열, api-rules §3.5). 빈 문자열·null은 없음 */
    private static Long longValue(JsonNode b, String field, SourceConfigValidator v) {
        JsonNode n = b.get(field);
        if (n == null || n.isNull() || (n.isString() && n.asString().isBlank())) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isString() && n.asString().strip().matches("\\d{1,18}")) {
            return Long.parseLong(n.asString().strip());
        }
        v.reject(field, "Type");
        return null;
    }

    private static int intValue(JsonNode b, String field, int current, int min, int max, SourceConfigValidator v) {
        JsonNode n = b.get(field);
        if (n == null || n.isNull()) {
            return current;
        }
        if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
            v.reject(field, "Range");
            return current;
        }
        return n.asInt();
    }

    private static int intField(JsonNode body, String field, int current, int min, int max, List<FieldErrorDetail> errors) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return current;
        }
        if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
            errors.add(new FieldErrorDetail(field, "Range", null));
            return current;
        }
        return n.asInt();
    }
}
