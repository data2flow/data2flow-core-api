package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.capability.AttributeConstraint;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.service.CapabilityService;
import net.java21.data2flow.core.control.service.ControlSettingsService;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.domain.FlowValidator;
import net.java21.data2flow.core.flow.domain.FlowValidator.Targets;
import net.java21.data2flow.core.flow.domain.NodeCatalog;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowInfo;
import net.java21.data2flow.core.flow.dto.FlowDtos.UserRef;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowTargetRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 플로우 서비스들이 함께 쓰는 도우미: 조회·공간 범위(IAM-04.05), 노드 카탈로그·검증기, 대상 판정, 설정 변경 발행(EVT-FLW-04).
 * 공간 범위가 제한된 사용자에게는 대상 공간(또는 관련 공간)이 범위와 겹치는 플로우만 보인다(남의 것·범위 밖은 404 FLOW_NOT_FOUND).
 */
@Component
public class FlowSupport {

    private final FlowRepository flows;
    private final FlowTargetRepository targets;
    private final CapabilityService capabilities;
    private final ControlSettingsService settings;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final NodeCatalog catalog;
    private final FlowValidator validator;
    private final JsonMapper json;
    private final Clock clock;
    private final FlowEngineClient engine;

    public FlowSupport(FlowRepository flows, FlowTargetRepository targets, CapabilityService capabilities,
                       ControlSettingsService settings, CoreEventPublisher publisher, RoleChecker roleChecker, JsonMapper json,
                       Clock clock, FlowEngineClient engine) {
        this.engine = engine;
        this.flows = flows;
        this.targets = targets;
        this.capabilities = capabilities;
        this.settings = settings;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
        this.catalog = NodeCatalog.load(json);
        this.validator = new FlowValidator(catalog, json);
    }

    public NodeCatalog catalog() {
        return catalog;
    }

    public FlowValidator validator() {
        return validator;
    }

    public JsonMapper json() {
        return json;
    }

    /** 보이는 플로우. 없거나 다른 조직·범위 밖이면 404 FLOW_NOT_FOUND */
    public FlowRow require(long organizationId, String rawId) {
        UUID id = flowId(rawId);
        FlowRow row = flows.findById(organizationId, id).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        if (!visible(row)) {
            throw new BusinessException(FlowErrorCode.FLOW_NOT_FOUND);
        }
        return row;
    }

    public boolean visible(FlowRow row) {
        SpaceScope scope = roleChecker.spaceScope();
        if (scope.unrestricted()) {
            return true;
        }
        return row.spaceIds().stream().anyMatch(scope::includes) || row.relatedSpaceIds().stream().anyMatch(scope::includes);
    }

    public static UUID flowId(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.strip());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(FlowErrorCode.FLOW_NOT_FOUND);
        }
    }

    /**
     * 적용 전 검증(API-FLW-06·07, ADR-044): 구조·설정 검사는 엔진 컴파일러(API-FLW-84) 결과를 그대로 싣고, core는 업무 검사(대상 존재·공간 범위,
     * 제어 명령 인자·우선순위, 대상 기기 없음 경고)를 더한다. 엔진이 응답하지 않으면 core의 같은 규칙(노드 카탈로그)으로 전부 검사한다.
     */
    public FlowValidator.Result validate(long organizationId, String kind, UUID flowId, JsonNode definition) {
        Targets t = targets(organizationId, roleChecker.spaceScope());
        JsonNode engineResult;
        try {
            engineResult = engine.validate(organizationId, flowId, definition);
        } catch (BusinessException | net.java21.data2flow.core.common.RelayedErrorException ex) {
            engineResult = null;
        }
        if (engineResult == null || !engineResult.path("errors").isArray()) {
            return validator.validate(definition, kind, t, true);
        }
        FlowValidator.Result local = validator.validate(definition, kind, t, false);
        java.util.List<FlowValidator.Issue> errors = new java.util.ArrayList<>();
        for (JsonNode e : engineResult.get("errors").values()) {
            errors.add(new FlowValidator.Issue(e.path("field").asString("definition"), e.path("code").asString("INVALID_CONFIG"),
                    e.path("message").asString("")));
        }
        errors.addAll(local.errors());
        return new FlowValidator.Result(errors, local.warnings(), local.hasControlNode(), local.outOfScope(), local.definition());
    }

    /** API-FLW-30 노드 카탈로그: 엔진 레지스트리(API-FLW-83)가 원천, 엔진이 응답하지 않으면 core 기본 카탈로그 */
    public java.util.List<JsonNode> nodeTypes() {
        try {
            JsonNode envelope = engine.nodeTypes();
            if (envelope != null && envelope.path("responses").isArray() && !envelope.get("responses").isEmpty()) {
                return new java.util.ArrayList<>(envelope.get("responses").values());
            }
        } catch (BusinessException | net.java21.data2flow.core.common.RelayedErrorException ex) {
            // 아래 기본 카탈로그
        }
        return catalog.all().stream().map(t -> (JsonNode) json.valueToTree(t)).toList();
    }

    /** 검증용 대상 판정(조직·사용자 범위). 기능 카탈로그·절대 한계는 한 번만 읽는다 */
    public Targets targets(long organizationId, SpaceScope scope) {
        CapabilityCatalog capabilityCatalog = capabilities.catalog(organizationId);
        JsonNode limits = json.readTree(settings.settings(organizationId).absoluteLimits());
        return new Targets() {
            @Override
            public Status space(long spaceId) {
                if (!targets.existsSpace(organizationId, spaceId)) {
                    return Status.MISSING;
                }
                return scope == null || scope.includes(spaceId) ? Status.OK : Status.OUT_OF_SCOPE;
            }

            @Override
            public Status device(long deviceId) {
                Optional<Long> space = targets.findDeviceSpace(organizationId, deviceId);
                if (space.isEmpty()) {
                    return Status.MISSING;
                }
                if (scope == null || scope.unrestricted()) {
                    return Status.OK;
                }
                return space.get() != 0 && scope.includes(space.get()) ? Status.OK : Status.OUT_OF_SCOPE;
            }

            @Override
            public long related(long spaceId, String relation, boolean includeChildren, String capability) {
                return targets.countRelated(organizationId, spaceId, relation == null ? "MEASURES" : relation.toUpperCase(),
                        includeChildren, capability);
            }

            @Override
            public CapabilityCatalog capabilities() {
                return capabilityCatalog;
            }

            @Override
            public Map<String, AttributeConstraint> absoluteLimits(String capability) {
                return ControlModels.absoluteLimits(limits, capability);
            }
        };
    }

    /** EVT-FLW-04: {@code ConfigChangedMessage(entityType=FLOW, id=flowId, version=적용 버전)} — 엔진이 내부 API로 다시 읽는다 */
    public void flowChanged(long organizationId, UUID flowId, int version, boolean deleted) {
        publisher.config(new ConfigChangedMessage(ConfigChangedMessage.VERSION, UUID.randomUUID(), ConfigChangedMessage.EntityType.FLOW,
                flowId.toString(), version, deleted ? ConfigChangedMessage.Op.DELETE : ConfigChangedMessage.Op.UPSERT,
                Long.toString(organizationId), clock.instant()));
    }

    public FlowInfo info(FlowRow f) {
        return new FlowInfo(f.id().toString(), f.name(), f.purpose(), f.description(), f.kind(), f.status(), f.statusReason(),
                f.environment(), f.ownerUserId() == null ? null : Long.toString(f.ownerUserId()),
                f.relatedSpaceIds().stream().map(String::valueOf).toList(), f.tags(), f.pauseMode(), f.autoPauseOnDegraded(),
                f.errorRateThreshold(), f.catchFlowId() == null ? null : f.catchFlowId().toString(), f.activeVersion(), f.draftVersion(),
                f.rateLimitPerSec(), f.version(), user(f.updatedBy(), f.updatedByName()), f.createdAt(), f.updatedAt());
    }

    public static UserRef user(Long id, String name) {
        return id == null || id == 0 ? null : new UserRef(Long.toString(id), name);
    }

    public JsonNode tree(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public static String hash(String definition) {
        return Tokens.sha256Hex(definition);
    }

    /** 정의 크기(BR-FLW-16: 2MB) */
    public static int size(String definition) {
        return definition.getBytes(StandardCharsets.UTF_8).length;
    }

    public List<String> statusList(List<String> raw) {
        return raw == null ? List.of() : raw;
    }
}
