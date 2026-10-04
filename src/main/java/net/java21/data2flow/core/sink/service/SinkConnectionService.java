package net.java21.data2flow.core.sink.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.config.LoopProperties;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.sink.repository.SinkConnectionRepository;
import net.java21.data2flow.core.sink.repository.SinkConnectionRepository.SinkRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sink 연결(FLW-04.01, API-FLW-50·51·85, BR-FLW-27). 쓰기 SINK_CONNECTION_MANAGE, 조회 FLOW_READ(비밀값 제외). 비밀값(password·token)은
 * AES-256-GCM으로 저장하고 응답에서는 {@code secretConfigured}만 준다. 사용자 이름(username)은 비밀이 아니라 설정에 둔다(API-FLW-85 config.username).
 * 연결 테스트·스키마·dead-letter는 action {@code sink} 패키지에 위임한다(ADR-049). 쓰는 플로우(초안·적용 정의의 {@code sink.database} 노드)가
 * 있으면 삭제 409 SINK_CONNECTION_IN_USE. 저장·삭제는 설정 변경 SINK_CONNECTION(action 연결 풀 다시 만들기).
 */
@Service
public class SinkConnectionService {

    static final Set<String> TYPES = Set.of("POSTGRESQL", "MYSQL", "INFLUXDB");
    static final String CONTEXT = "data2flow_core.sink_connections.secret:";

    private final SinkConnectionRepository connections;
    private final SecretCipher cipher;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final InternalOrganizations organizations;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;
    private final InternalHttp action;

    public SinkConnectionService(SinkConnectionRepository connections, SecretCipher cipher, CoreEventPublisher publisher, RoleChecker roleChecker,
                                 InternalOrganizations organizations, Audits audits, JsonMapper json, Clock clock, LoopProperties loop) {
        this.connections = connections;
        this.cipher = cipher;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.organizations = organizations;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
        this.action = new InternalHttp("action", loop.actionBaseUrl(), loop.relayTimeout().plusSeconds(4), json);
    }

    @Transactional(readOnly = true)
    public ListApiResponse<Map<String, Object>> list(Integer page, Integer size) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, connections.list(orgId, params.size(), params.offset()).stream().map(this::summary).toList(),
                connections.count(orgId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) {
        roleChecker.require(Permission.FLOW_READ);
        return view(require(roleChecker.currentUser().organizationId(), id));
    }

    @Transactional
    public Map<String, Object> create(JsonNode body) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String name = name(body);
        String type = body.path("type").asString("").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw invalid("type");
        }
        ObjectNode config = config(body, type);
        JsonNode secret = secret(body, config);
        if (secret == null || secret.isEmpty()) {
            throw invalid("secret");
        }
        if (connections.existsName(orgId, name, null)) {
            throw invalid("name");
        }
        long id;
        try {
            id = connections.insert(orgId, name, type, json.writeValueAsString(config), user.userId(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw invalid("name");
        }
        connections.updateSecret(orgId, id, encrypt(id, secret));
        publisher.configChanged(ConfigChangedMessage.EntityType.SINK_CONNECTION, id, 1, orgId);
        audits.record(audits.event(orgId, "SINK_CONNECTION_CREATED").actor(user).target("SINK_CONNECTION", Long.toString(id)).detail("type", type));
        return view(connections.findById(orgId, id).orElseThrow());
    }

    /** PATCH {name?, config?, secret?, baseVersion} — 보낸 값만 바꾼다 */
    @Transactional
    public Map<String, Object> update(long id, JsonNode body) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SinkRow current = require(orgId, id);
        if (body == null || !body.hasNonNull("baseVersion")) {
            throw invalid("baseVersion");
        }
        String name = body.has("name") ? name(body) : current.name();
        ObjectNode config = body.has("config") ? config(body, current.type()) : (ObjectNode) json.readTree(current.config());
        JsonNode secret = secret(body, config);
        if (connections.existsName(orgId, name, id)) {
            throw invalid("name");
        }
        if (connections.update(orgId, id, body.get("baseVersion").asInt(), name, json.writeValueAsString(config), user.userId(),
                clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        if (secret != null && !secret.isEmpty()) {
            connections.updateSecret(orgId, id, encrypt(id, secret));
        }
        publisher.configChanged(ConfigChangedMessage.EntityType.SINK_CONNECTION, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "SINK_CONNECTION_UPDATED").actor(user).target("SINK_CONNECTION", Long.toString(id)));
        return view(connections.findById(orgId, id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SinkRow current = require(orgId, id);
        if (current.usedFlowCount() > 0) {
            throw new BusinessException(FlowErrorCode.SINK_CONNECTION_IN_USE);
        }
        connections.delete(orgId, id);
        publisher.configDeleted(ConfigChangedMessage.EntityType.SINK_CONNECTION, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "SINK_CONNECTION_DELETED").actor(user).target("SINK_CONNECTION", Long.toString(id)));
    }

    /** API-FLW-51 저장 전 테스트 {type, config, secret} */
    public JsonNode testDraft(JsonNode body) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        String type = body == null ? "" : body.path("type").asString("").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw invalid("type");
        }
        ObjectNode config = config(body, type);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", type);
        req.put("config", config);
        req.put("secrets", secret(body, config));
        return tested(null, action.call(HttpMethod.POST, "/internal/action/sinks/connections/test", null, req));
    }

    /** 저장된 연결 테스트. 결과 상태(OK·ERROR)는 실패해도 남아야 하므로 트랜잭션으로 묶지 않는다 */
    public JsonNode test(long id) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        long orgId = roleChecker.currentUser().organizationId();
        require(orgId, id);
        JsonNode r = action.call(HttpMethod.POST, "/internal/action/sinks/connections/" + id + "/test", null, Map.of());
        return tested(id, r);
    }

    JsonNode tested(Long id, JsonNode r) {
        boolean ok = r != null && r.path("ok").asBoolean(false);
        if (id != null) {
            long orgId = roleChecker.currentUser().organizationId();
            connections.updateStatus(orgId, id, ok ? "OK" : "ERROR", ok ? null : r == null ? "no response"
                    : truncate(r.path("error").path("kind").asString("OTHER") + ": " + r.path("error").path("message").asString(""), 500));
        }
        if (!ok) {
            String kind = r == null ? "OTHER" : r.path("error").path("kind").asString("OTHER");
            throw new BusinessException(FlowErrorCode.SINK_CONNECTION_TEST_FAILED,
                    List.of(new FieldErrorDetail("connection", kind, r == null ? null : r.path("error").path("message").asString(null))), kind);
        }
        return r;
    }

    public JsonNode schema(long id, String target, String columns) {
        roleChecker.require(Permission.FLOW_READ);
        require(roleChecker.currentUser().organizationId(), id);
        return action.call(HttpMethod.GET, "/internal/action/sinks/connections/" + id + "/schema",
                InternalHttp.query("target", target, "columns", columns), null);
    }

    public JsonNode createSchema(long id, JsonNode body) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        require(roleChecker.currentUser().organizationId(), id);
        return action.call(HttpMethod.POST, "/internal/action/sinks/connections/" + id + "/schema", null, body);
    }

    public JsonNode deadLetters(long id, String cursor, Integer size) {
        roleChecker.require(Permission.FLOW_READ);
        require(roleChecker.currentUser().organizationId(), id);
        return action.envelope(HttpMethod.GET, "/internal/action/sinks/connections/" + id + "/dead-letters",
                InternalHttp.query("cursor", cursor, "size", size), null);
    }

    public JsonNode resend(long id, JsonNode body) {
        roleChecker.require(Permission.SINK_CONNECTION_MANAGE);
        require(roleChecker.currentUser().organizationId(), id);
        return action.call(HttpMethod.POST, "/internal/action/sinks/connections/" + id + "/dead-letters/resend", null, body);
    }

    /** API-FLW-85 action 내부: 비밀값 복호화 */
    @Transactional(readOnly = true)
    public Map<String, Object> internal(long id) {
        SinkRow c = connections.findAnyOrganization(id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        organizations.resolve(c.organizationId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connectionId", c.id());
        m.put("organizationId", c.organizationId());
        m.put("name", c.name());
        m.put("type", c.type());
        m.put("config", json.readTree(c.config()));
        m.put("secrets", c.secretEnc() == null || c.secretEnc().length == 0 ? json.createObjectNode()
                : json.readTree(new String(cipher.decryptBytes(c.secretEnc(), CONTEXT + c.id()), StandardCharsets.UTF_8)));
        m.put("version", c.version());
        return m;
    }

    ObjectNode config(JsonNode body, String type) {
        JsonNode c = body.get("config");
        if (c == null || !c.isObject()) {
            throw invalid("config");
        }
        ObjectNode config = (ObjectNode) c.deepCopy();
        if ("INFLUXDB".equals(type)) {
            if (config.path("url").asString("").isBlank() && config.path("host").asString("").isBlank()) {
                throw invalid("config.url");
            }
            if (config.path("bucket").asString("").isBlank()) {
                throw invalid("config.bucket");
            }
        } else {
            if (config.path("host").asString("").isBlank() || config.path("database").asString("").isBlank()) {
                throw invalid("config.host");
            }
            int port = config.path("port").asInt(0);
            if (config.has("port") && (port < 1 || port > 65535)) {
                throw invalid("config.port");
            }
        }
        config.remove("password");
        config.remove("token");
        return config;
    }

    /** 비밀값: password·token만 비밀로 두고 username은 설정으로 옮긴다 */
    JsonNode secret(JsonNode body, ObjectNode config) {
        JsonNode s = body == null ? null : body.get("secret");
        if (s == null || s.isNull()) {
            return null;
        }
        if (!s.isObject()) {
            throw invalid("secret");
        }
        ObjectNode copy = (ObjectNode) s.deepCopy();
        if (copy.hasNonNull("username")) {
            config.put("username", copy.get("username").asString());
            copy.remove("username");
        }
        return copy;
    }

    byte[] encrypt(long id, JsonNode secret) {
        return cipher.encrypt(json.writeValueAsString(secret).getBytes(StandardCharsets.UTF_8), CONTEXT + id);
    }

    SinkRow require(long orgId, long id) {
        return connections.findById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    Map<String, Object> summary(SinkRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sinkConnectionId", Long.toString(c.id()));
        m.put("name", c.name());
        m.put("type", c.type());
        m.put("status", c.status());
        m.put("lastError", c.lastError());
        m.put("usedFlowCount", c.usedFlowCount());
        m.put("updatedAt", c.updatedAt());
        return m;
    }

    Map<String, Object> view(SinkRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sinkConnectionId", Long.toString(c.id()));
        m.put("name", c.name());
        m.put("type", c.type());
        m.put("config", json.readTree(c.config()));
        m.put("secretConfigured", c.secretEnc() != null && c.secretEnc().length > 0);
        m.put("status", c.status());
        m.put("lastError", c.lastError());
        m.put("usedFlowCount", c.usedFlowCount());
        m.put("version", c.version());
        m.put("createdAt", c.createdAt());
        m.put("updatedAt", c.updatedAt());
        return m;
    }

    static String name(JsonNode body) {
        String name = body == null ? "" : body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name");
        }
        return name;
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
