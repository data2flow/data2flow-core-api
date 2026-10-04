package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Channel;
import net.java21.data2flow.core.notify.dto.NotifyDtos.ChannelTest;
import net.java21.data2flow.core.notify.repository.ChannelRepository;
import net.java21.data2flow.core.notify.repository.ChannelRepository.ChannelRow;
import net.java21.data2flow.core.notify.repository.PolicyRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 알림 채널(OPS-06.01·06.06, API-OPS-30·31·34). NOTIFY_CHANNEL_MANAGE(ADMIN). 채널 유형은 action에 등록된 SPI 키이고(지금 TELEGRAM만,
 * ADR-033), action이 응답하지 않으면 core 기본 목록을 쓴다. 설정은 유형의 configSchema 필수 항목으로 검사하고, 비밀값(봇 토큰·웹훅 시크릿)은
 * 암호화해 저장하며 응답에서는 {@code "***"}로 가린다. 저장·삭제하면 설정 변경 NOTIFICATION_CHANNEL을 내서 action이 다시 읽는다(텔레그램
 * {@code setWebhook} 등록은 action 채널 구현이 한다).
 */
@Service
public class ChannelService {

    static final String SECRET_CONTEXT = "data2flow_core.notification_channels.secret:";

    private final ChannelRepository channels;
    private final PolicyRepository policies;
    private final NotificationActionClient action;
    private final SecretCipher cipher;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ChannelService(ChannelRepository channels, PolicyRepository policies, NotificationActionClient action, SecretCipher cipher,
                          CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.channels = channels;
        this.policies = policies;
        this.action = action;
        this.cipher = cipher;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-OPS-34 채널 유형: action SPI 목록, 응답이 없으면 기본 목록 */
    public List<JsonNode> types() {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        return typeList();
    }

    List<JsonNode> typeList() {
        try {
            JsonNode envelope = action.channelTypes();
            JsonNode list = envelope == null ? null : envelope.has("responses") ? envelope.get("responses") : envelope.get("response");
            if (list != null && list.isArray() && !list.isEmpty()) {
                return new ArrayList<>(list.values());
            }
        } catch (BusinessException | RelayedErrorException ex) {
            // 기본 목록
        }
        return builtinTypes(json);
    }

    /** action이 없을 때의 기본 유형(ADR-033: TELEGRAM만 사용 가능, 나머지는 준비 중) */
    static List<JsonNode> builtinTypes(JsonMapper json) {
        List<JsonNode> out = new ArrayList<>();
        ObjectNode telegram = json.createObjectNode();
        telegram.put("key", "TELEGRAM");
        telegram.put("displayName", "Telegram");
        telegram.put("available", true);
        ObjectNode schema = telegram.putObject("configSchema");
        schema.put("type", "object");
        schema.putArray("required").add("chatIds");
        ObjectNode props = schema.putObject("properties");
        ObjectNode chatIds = props.putObject("chatIds");
        chatIds.put("type", "array");
        chatIds.put("minItems", 1);
        chatIds.put("title", "기본 대화방 chat_id");
        ObjectNode parseMode = props.putObject("parseMode");
        parseMode.put("type", "string");
        parseMode.putArray("enum").add("MarkdownV2").add("HTML").add("PLAIN");
        ObjectNode secret = telegram.putObject("secretSchema");
        secret.put("type", "object");
        secret.putArray("required").add("botToken");
        ObjectNode sp = secret.putObject("properties");
        sp.putObject("botToken").put("type", "string");
        sp.putObject("webhookSecret").put("type", "string");
        ObjectNode caps = telegram.putObject("capabilities");
        caps.put("buttons", true);
        caps.put("maxLength", 4096);
        caps.put("defaultRatePerMin", 20);
        out.add(telegram);
        for (String key : List.of("EMAIL", "SLACK", "KAKAO_ALIMTALK", "SMS", "WEBHOOK")) {
            ObjectNode t = json.createObjectNode();
            t.put("key", key);
            t.put("displayName", key);
            t.put("available", false);
            t.putObject("configSchema").put("type", "object");
            t.putObject("capabilities");
            out.add(t);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Channel> list() {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        return channels.list(roleChecker.currentUser().organizationId()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Channel get(long id) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        return view(require(roleChecker.currentUser().organizationId(), id));
    }

    @Transactional
    public Channel create(JsonNode body) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String type = body == null ? "" : body.path("type").asString("").strip();
        JsonNode typeDef = availableType(type);
        Values v = values(body, typeDef);
        if (channels.existsName(orgId, v.name(), null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        long id;
        try {
            id = channels.insert(orgId, v.name(), type, json.writeValueAsString(v.config()), v.rate(), v.digest(), v.enabled(), user.userId(),
                    clock.instant());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        storeSecret(orgId, id, body.get("secret"));
        changed(orgId, id, 1, false);
        registerWebhookAfterCommit(id);
        audits.record(audits.event(orgId, "NOTIFICATION_CHANNEL_CREATED").actor(user).target("NOTIFICATION_CHANNEL", Long.toString(id))
                .detail("type", type));
        return view(channels.findById(orgId, id).orElseThrow());
    }

    /** 수정 {name, config, secret?, rateLimitPerMin, digestWindowSec, enabled, version(=baseVersion)} — 유형은 바꾸지 않는다 */
    @Transactional
    public Channel update(long id, JsonNode body) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ChannelRow current = require(orgId, id);
        Values v = values(body, availableType(current.type()));
        int base = body.hasNonNull("baseVersion") ? body.get("baseVersion").asInt() : body.path("version").asInt(-1);
        if (base != current.version()) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        if (channels.existsName(orgId, v.name(), id)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        if (channels.update(orgId, id, base, v.name(), json.writeValueAsString(v.config()), v.rate(), v.digest(), v.enabled(), user.userId(),
                clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        storeSecret(orgId, id, body.get("secret"));
        changed(orgId, id, base + 1, false);
        registerWebhookAfterCommit(id);
        audits.record(audits.event(orgId, "NOTIFICATION_CHANNEL_UPDATED").actor(user).target("NOTIFICATION_CHANNEL", Long.toString(id)));
        return view(channels.findById(orgId, id).orElseThrow());
    }

    /** 삭제: 이 유형의 마지막 켜진 채널이고 정책이 그 유형을 쓰면 409 CHANNEL_IN_USE(정책 수) */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ChannelRow current = require(orgId, id);
        long using = policies.countUsingChannel(orgId, current.type());
        if (using > 0 && channels.countEnabledOfType(orgId, current.type(), id) == 0) {
            throw new BusinessException(AlarmErrorCode.CHANNEL_IN_USE, using);
        }
        channels.delete(orgId, id);
        changed(orgId, id, current.version() + 1, true);
        audits.record(audits.event(orgId, "NOTIFICATION_CHANNEL_DELETED").actor(user).target("NOTIFICATION_CHANNEL", Long.toString(id)));
    }

    /** API-OPS-31 저장한 채널 테스트 발송. 실패면 502 CHANNEL_TEST_FAILED(원인) */
    @Transactional(readOnly = true)
    public ChannelTest test(long id) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        ChannelRow c = require(orgId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", Long.toString(id));
        body.put("type", c.type());
        body.put("config", json.readTree(c.config()));
        body.put("secrets", secret(c));
        return test(body);
    }

    /** 저장 전 테스트(본문 그대로) */
    public ChannelTest testDraft(JsonNode draft) {
        roleChecker.require(Permission.NOTIFY_CHANNEL_MANAGE);
        String type = draft == null ? "" : draft.path("type").asString("");
        Values v = values(draft, availableType(type));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("config", v.config());
        body.put("secrets", draft.get("secret") != null ? draft.get("secret") : draft.get("secrets"));
        if (draft.hasNonNull("chatId")) {
            body.put("chatId", draft.get("chatId").asString());
        }
        return test(body);
    }

    ChannelTest test(Map<String, Object> body) {
        JsonNode r = action.testChannel(body);
        boolean ok = r != null && r.path("ok").asBoolean(false);
        if (!ok) {
            String cause = r == null ? "no response" : r.path("providerResponse").asString(r.path("error").asString("unknown"));
            throw new BusinessException(AlarmErrorCode.CHANNEL_TEST_FAILED, cause);
        }
        return new ChannelTest(true, r.hasNonNull("latencyMs") ? r.get("latencyMs").asLong() : null, r.get("providerResponse"));
    }

    /** action 내부 API용: 비밀값까지 푼 채널(API-OPS-35) */
    public JsonNode secret(ChannelRow c) {
        if (c.secretEnc() == null) {
            return null;
        }
        return json.readTree(new String(cipher.decryptBytes(c.secretEnc(), SECRET_CONTEXT + c.id()), StandardCharsets.UTF_8));
    }

    record Values(String name, JsonNode config, int rate, int digest, boolean enabled) {
    }

    Values values(JsonNode body, JsonNode typeDef) {
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 50) {
            throw invalid("name", "Size");
        }
        JsonNode config = body.get("config");
        if (config == null || !config.isObject()) {
            throw invalid("config", "Type");
        }
        for (JsonNode req : typeDef.path("configSchema").path("required").values()) {
            JsonNode v = config.get(req.asString());
            if (v == null || v.isNull() || (v.isArray() && v.isEmpty()) || (v.isString() && v.asString().isBlank())) {
                throw new BusinessException(net.java21.data2flow.core.common.CoreErrorCode.SETTING_INVALID,
                        List.of(new FieldErrorDetail("config." + req.asString(), "NotNull", null)), "config." + req.asString());
            }
        }
        int rate = body.path("rateLimitPerMin").asInt(typeDef.path("capabilities").path("defaultRatePerMin").asInt(20));
        if (rate < 1 || rate > 600) {
            throw invalid("rateLimitPerMin", "Range");
        }
        int digest = body.path("digestWindowSec").asInt(60);
        if (digest < 0 || digest > 900) {
            throw invalid("digestWindowSec", "Range");
        }
        boolean enabled = !body.has("enabled") || body.get("enabled").asBoolean(true);
        return new Values(name, config, rate, digest, enabled);
    }

    /** 등록되고 사용 가능한 유형(아니면 400 INVALID_REQUEST, TC-OPS-060) */
    JsonNode availableType(String type) {
        for (JsonNode t : typeList()) {
            if (t.path("key").asString("").equals(type) && t.path("available").asBoolean(false)) {
                return t;
            }
        }
        throw invalid("type", "Pattern");
    }

    /** 정책 채널 검사(CHANNEL_NOT_CONFIGURED): WEB은 늘, 나머지는 켜진 채널이 있어야 */
    public void requireConfigured(long orgId, List<String> requested) {
        Set<String> enabled = new java.util.HashSet<>(channels.listEnabledTypes(orgId));
        for (String ch : requested) {
            if (!"WEB".equals(ch) && !enabled.contains(ch)) {
                throw new BusinessException(AlarmErrorCode.CHANNEL_NOT_CONFIGURED, List.of(new FieldErrorDetail("channels", "NOT_CONFIGURED", ch)), ch);
            }
        }
    }

    /** 커밋 뒤 action에 웹훅 등록을 맡긴다(실패해도 저장은 그대로, 다음 저장·action 재시작 때 다시) */
    void registerWebhookAfterCommit(long id) {
        net.java21.data2flow.core.common.AfterCommit.run("notification-channel-webhook", () -> {
            try {
                action.registerWebhook(id);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(ChannelService.class).warn("알림 채널 {} 웹훅 등록 실패(나중에 다시): {}", id, ex.toString());
            }
        });
    }

    private void storeSecret(long orgId, long id, JsonNode secret) {
        if (secret == null || secret.isNull()) {
            return;
        }
        if (!secret.isObject()) {
            throw invalid("secret", "Type");
        }
        channels.updateSecret(orgId, id, cipher.encrypt(json.writeValueAsString(secret).getBytes(StandardCharsets.UTF_8), SECRET_CONTEXT + id));
    }

    private void changed(long orgId, long id, long version, boolean deleted) {
        if (deleted) {
            publisher.configDeleted(ConfigChangedMessage.EntityType.NOTIFICATION_CHANNEL, id, version, orgId);
        } else {
            publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFICATION_CHANNEL, id, version, orgId);
        }
    }

    ChannelRow require(long orgId, long id) {
        return channels.findById(orgId, id).orElseThrow(() -> new BusinessException(AlarmErrorCode.CHANNEL_NOT_FOUND));
    }

    Channel view(ChannelRow c) {
        JsonNode config = json.readTree(c.config());
        boolean configured = c.secretEnc() != null;
        return new Channel(Long.toString(c.id()), c.name(), c.type(), config, configured, configured ? "***" : null, c.rateLimitPerMin(),
                c.digestWindowSec(), c.enabled(), c.status(), c.version(), c.updatedAt());
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
