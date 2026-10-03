package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.dto.ControlDtos.ControlSettingsResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.InternalControlSettings;
import net.java21.data2flow.core.control.dto.ControlDtos.UserRef;
import net.java21.data2flow.core.control.repository.ControlSettingsRepository;
import net.java21.data2flow.core.control.repository.ControlSettingsRepository.SettingsRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 조직 제어 설정(API-ACT-17, ACT-06.04 절대 한계, FLW 제어 노드 배포 승인). CONTROL_SETTINGS(ADMIN).
 * 바꾸면 {@code data2flow.config}에 {@code ConfigChangedMessage(entityType=SETTING, id=control)}을 보내 action이 캐시를 다시 읽는다(API-ACT-42).
 */
@Service
public class ControlSettingsService {

    public static final String CONFIG_ID = "control";
    static final String AUDIT_CHANGED = "CONTROL_SETTINGS_CHANGED";

    private final ControlSettingsRepository repository;
    private final CapabilityService capabilities;
    private final RoleChecker roleChecker;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ControlSettingsService(ControlSettingsRepository repository, CapabilityService capabilities, RoleChecker roleChecker,
                                  CoreEventPublisher publisher, Audits audits, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.roleChecker = roleChecker;
        this.publisher = publisher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 조직 설정(없으면 기본값) */
    public SettingsRow settings(long organizationId) {
        return repository.find(organizationId).orElseGet(() -> new SettingsRow(organizationId, "{}", 30, 10,
                "{\"windowSec\":60,\"flips\":3}", 600, true, false, 0, 0, null, null));
    }

    /** API-ACT-17 조회 — CONTROL_SETTINGS */
    @Transactional(readOnly = true)
    public ControlSettingsResponse get() {
        roleChecker.require(Permission.CONTROL_SETTINGS);
        return response(settings(roleChecker.currentUser().organizationId()));
    }

    /** API-ACT-17 바꾸기(전체, baseVersion) — CONTROL_SETTINGS. 모델 범위보다 넓은 한계는 400 LIMIT_WIDER_THAN_MODEL */
    @Transactional
    public ControlSettingsResponse put(JsonNode body) {
        roleChecker.require(Permission.CONTROL_SETTINGS);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.isObject()) {
            throw ControlModels.invalid("body", "NotNull", null);
        }
        long base = VersionCheck.baseVersion(body);
        SettingsRow before = settings(orgId);
        JsonNode limits = body.has("absoluteLimits") ? body.get("absoluteLimits") : json.readTree(before.absoluteLimits());
        List<JsonNode> models = repository.listModelCapabilities(orgId).stream().map(json::readTree).toList();
        Map<String, ?> normalized = ControlModels.validateLimits(limits, capabilities.catalog(orgId), models);
        int manualOverride = intField(body, "manualOverrideMinutes", before.manualOverrideMinutes(), 0, 1440);
        int minInterval = intField(body, "minIntervalSec", before.minIntervalSec(), 0, 3600);
        int validity = intField(body, "defaultValiditySec", before.defaultValiditySec(), 60, 3600);
        JsonNode oscillation = body.has("oscillation") && body.get("oscillation").isObject() ? body.get("oscillation")
                : json.readTree(before.oscillation());
        boolean respects = boolField(body, "scheduleRespectsManualOverride", before.scheduleRespectsManualOverride());
        boolean approval = boolField(body, "requireApprovalForControlNodes", before.requireApprovalForControlNodes());
        int updated = repository.upsert(orgId, (int) base, json.writeValueAsString(normalized), manualOverride, minInterval,
                json.writeValueAsString(oscillation), validity, respects, approval, user.userId(), clock.instant());
        VersionCheck.requireUpdated(updated);
        SettingsRow after = settings(orgId);
        publisher.config(new ConfigChangedMessage(ConfigChangedMessage.VERSION, java.util.UUID.randomUUID(),
                ConfigChangedMessage.EntityType.SETTING, CONFIG_ID, after.version(), ConfigChangedMessage.Op.UPSERT,
                Long.toString(orgId), clock.instant()));
        audits.record(audits.event(orgId, AUDIT_CHANGED).actor(user).target("CONTROL_SETTINGS", Long.toString(orgId))
                .detail("version", after.version()).detail("requireApprovalForControlNodes", approval));
        return response(after);
    }

    /** API-ACT-42 내부 */
    @Transactional(readOnly = true)
    public InternalControlSettings internal(long organizationId) {
        SettingsRow s = settings(organizationId);
        return new InternalControlSettings(Long.toString(organizationId), json.readTree(s.absoluteLimits()), s.manualOverrideMinutes(),
                s.minIntervalSec(), json.readTree(s.oscillation()), s.defaultValiditySec(), s.scheduleRespectsManualOverride(),
                s.version());
    }

    private ControlSettingsResponse response(SettingsRow s) {
        UserRef by = s.updatedBy() == 0 ? null : new UserRef(Long.toString(s.updatedBy()), s.updatedByName());
        return new ControlSettingsResponse(json.readTree(s.absoluteLimits()), s.manualOverrideMinutes(), s.minIntervalSec(),
                json.readTree(s.oscillation()), s.defaultValiditySec(), s.scheduleRespectsManualOverride(),
                s.requireApprovalForControlNodes(), s.version(), by, s.updatedAt());
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

    private static boolean boolField(JsonNode body, String field, boolean fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new net.java21.data2flow.contracts.error.FieldErrorDetail(field, "Type", null)));
        }
        return node.asBoolean();
    }
}
