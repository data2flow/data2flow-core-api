package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.config.LoopProperties;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.PayloadSchemaRepository;
import net.java21.data2flow.core.source.repository.PayloadSchemaRepository.SchemaRow;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * payload 스키마와 토픽 템플릿(DSC-09.07·09.08, ADR-056).
 * <ul>
 *   <li>API-DSC-59 업로드(SRC_ADMIN): {@code .proto}·{@code .desc}/{@code .binpb}(FileDescriptorSet) → PROTOBUF, {@code .avsc} → AVRO, 1MiB 이하.
 *       ingress API-DSC-82로 <b>수집과 같은 해석기</b>로 검사한 뒤(실패는 400 SOURCE_SCHEMA_INVALID 그대로) 원문을 보관하고 새 불변 참조
 *       {@code schemaRef}를 소스 {@code payload.schemaRef}에 넣는다(형식·messageType도). 소스 판이 올라 ingress가 다시 읽는다</li>
 *   <li>API-DSC-81({@code GET /internal/core/payload-schemas/{schema-ref}}): ingress가 원문(base64)을 읽는다. 배포 조직 밖·없으면 404</li>
 *   <li>토픽 템플릿 미리보기: 화면(UI-DSC-08)이 core에 보내면 ingress API-DSC-83으로 넘긴다(규칙은 ingress 한 곳)</li>
 * </ul>
 */
@Service
public class PayloadSchemaService {

    static final int MAX_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RoleChecker roleChecker;
    private final DataSourceRepository sources;
    private final PayloadSchemaRepository schemas;
    private final SourceStateService states;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final InternalHttp ingress;
    private final JsonMapper json;
    private final Clock clock;

    public PayloadSchemaService(RoleChecker roleChecker, DataSourceRepository sources, PayloadSchemaRepository schemas, SourceStateService states,
                                DeploymentOrganization deployment, Audits audits, CoreProperties core, LoopProperties loop, JsonMapper json,
                                Clock clock) {
        this.roleChecker = roleChecker;
        this.sources = sources;
        this.schemas = schemas;
        this.states = states;
        this.deployment = deployment;
        this.audits = audits;
        this.ingress = new InternalHttp("ingress", core.ingressBaseUrl(), loop.relayTimeout(), json);
        this.json = json;
        this.clock = clock;
    }

    /** API-DSC-59 — {schemaRef, messageTypes[], messageType} */
    @Transactional
    public Map<String, Object> upload(long sourceId, String fileName, byte[] content, String messageType) {
        roleChecker.require(Permission.SRC_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DataSource s = sources.lockById(orgId, sourceId).orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
        if (SourceModels.ARCHIVED.equals(s.lifecycle())) {
            throw new BusinessException(SourceErrorCode.SOURCE_STATE_CONFLICT);
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("file", "REQUIRED", null)));
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(SourceErrorCode.SOURCE_SCHEMA_INVALID, List.of(new FieldErrorDetail("file", "TOO_LARGE", "1MiB")),
                    "-", "1MiB");
        }
        String name = fileName == null || fileName.isBlank() ? "schema" : fileName.strip();
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        String format = format(name);
        String type = messageType == null || messageType.isBlank() ? null : messageType.strip();
        Map<String, Object> inspect = new LinkedHashMap<>();
        inspect.put("format", format);
        inspect.put("fileName", name);
        inspect.put("content", Base64.getEncoder().encodeToString(content));
        if (type != null) {
            inspect.put("messageType", type);
        }
        JsonNode result = ingress.call(HttpMethod.POST, "/internal/ingress/payload-schemas/inspect", null, inspect);
        List<String> types = new ArrayList<>();
        if (result != null) {
            result.path("messageTypes").values().forEach(t -> types.add(t.asString()));
        }
        String chosen = result != null && result.hasNonNull("messageType") ? result.get("messageType").asString() : type;
        String schemaRef = "ps_" + HexFormat.of().formatHex(randomBytes(12));
        Instant now = clock.instant();
        schemas.insert(orgId, sourceId, schemaRef, format, name, content, Tokens.sha256Hex(Base64.getEncoder().encodeToString(content)), types,
                chosen, user.userId(), now);
        ObjectNode payload = s.payload() != null && s.payload().isObject() ? (ObjectNode) s.payload().deepCopy() : json.createObjectNode();
        payload.put("format", format);
        if (!payload.has("compression")) {
            payload.put("compression", "NONE");
        }
        payload.put("schemaRef", schemaRef);
        if (chosen != null) {
            payload.put("messageType", chosen);
        } else {
            payload.remove("messageType");
        }
        sources.updatePayloadAndBump(orgId, sourceId, json.writeValueAsString(payload), user.userId(), now);
        audits.record(audits.event(orgId, "SOURCE_UPDATED").actor(user).target("SOURCE", Long.toString(sourceId))
                .detail("fields", List.of("payload.schemaRef")).detail("schemaRef", schemaRef).detail("format", format)
                .detail("fileName", name).detail("messageType", chosen));
        states.configChanged(orgId, sourceId, s.version() + 1L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaRef", schemaRef);
        out.put("format", format);
        out.put("messageTypes", types);
        out.put("messageType", chosen);
        return out;
    }

    /** API-DSC-81 — ingress용 원문 */
    @Transactional(readOnly = true)
    public Map<String, Object> internalGet(String schemaRef) {
        SchemaRow row = schemaRef == null || schemaRef.length() > 40 ? null : schemas.findByRef(schemaRef).orElse(null);
        if (row == null || !deployment.includes(row.organizationId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaRef", row.schemaRef());
        out.put("organizationId", Long.toString(row.organizationId()));
        out.put("sourceId", Long.toString(row.sourceId()));
        out.put("format", row.format());
        out.put("fileName", row.fileName());
        out.put("content", Base64.getEncoder().encodeToString(row.content()));
        out.put("messageTypes", row.messageTypes());
        out.put("messageType", row.messageType());
        return out;
    }

    /** 토픽 템플릿 미리보기(API-DSC-83 중계) — SRC_ADMIN. {template(≤256), topics[](≤20)} */
    public JsonNode previewTopicTemplate(JsonNode body) {
        roleChecker.require(Permission.SRC_ADMIN);
        String template = body == null ? null : body.path("template").asString(null);
        if (template == null || template.isBlank() || template.length() > 256) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("template", "SIZE", "1~256")));
        }
        JsonNode topics = body.get("topics");
        if (topics != null && !topics.isNull() && (!topics.isArray() || topics.size() > 20)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("topics", "SIZE", "≤20")));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("template", template);
        req.put("topics", topics == null || topics.isNull() ? List.of() : topics);
        return ingress.call(HttpMethod.POST, "/internal/ingress/topic-templates/preview", null, req);
    }

    /** 이 조직이 올린 스키마 참조인가(소스 payload.schemaRef 판정) */
    public boolean exists(long organizationId, String schemaRef) {
        return schemas.exists(organizationId, schemaRef);
    }

    static String format(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".proto") || lower.endsWith(".desc") || lower.endsWith(".binpb") || lower.endsWith(".pb")) {
            return "PROTOBUF";
        }
        if (lower.endsWith(".avsc") || lower.endsWith(".json")) {
            return "AVRO";
        }
        throw new BusinessException(SourceErrorCode.SOURCE_SCHEMA_INVALID, List.of(new FieldErrorDetail("file", "EXTENSION",
                ".proto|.desc|.binpb|.avsc")), "-", fileName);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
