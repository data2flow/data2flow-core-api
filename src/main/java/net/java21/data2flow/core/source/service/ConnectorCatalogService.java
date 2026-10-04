package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.connector.AckMode;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.dto.SourceDtos.CatalogResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.ConnectorResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.ConnectorSchemaResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.PlatformBrokerResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.TemplateResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.TemplateSummary;
import net.java21.data2flow.core.source.repository.ConnectorCatalogRepository;
import net.java21.data2flow.core.source.repository.ConnectorCatalogRepository.Connector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 커넥터 카탈로그(DSC-09.01, UC-DSC-12): 카드 목록·템플릿(API-DSC-55), 설정 스키마(API-DSC-56), 템플릿 preset, 플랫폼 브로커 안내(API-DSC-23).
 * 카탈로그는 ingress가 시작할 때 보고하고(EVT-DSC-09) 기본 유형 3개는 마이그레이션 시드다. 화면은 스키마로 폼을 만든다(웹 코드 변경 없이 새 커넥터).
 */
@Service
public class ConnectorCatalogService {

    /** 플랫폼 브로커 토픽 규칙(DSC-03.03, BR-DSC-12) */
    static final List<String> TOPIC_RULES = List.of("devices/{deviceKey}/telemetry", "devices/{deviceKey}/state",
            "devices/{deviceKey}/command", "devices/{deviceKey}/command/ack");
    static final String PLATFORM_WSS_URL = "wss://iot-data.java21.net/mqtt";

    private final ConnectorCatalogRepository catalog;
    private final RoleChecker roleChecker;

    public ConnectorCatalogService(ConnectorCatalogRepository catalog, RoleChecker roleChecker) {
        this.catalog = catalog;
        this.roleChecker = roleChecker;
    }

    /** API-DSC-55. category·q(이름·키·표준·전송 방식)로 거른다 */
    @Transactional(readOnly = true)
    public CatalogResponse list(String category, String q) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String cat = category == null || category.isBlank() ? null : category.strip().toUpperCase(Locale.ROOT);
        String query = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<ConnectorResponse> connectors = catalog.findAll().stream()
                .filter(c -> cat == null || cat.equals(c.category()))
                .filter(c -> query == null || matches(c, query))
                .map(ConnectorCatalogService::toResponse).toList();
        List<TemplateSummary> templates = catalog.findTemplates(orgId).stream()
                .map(t -> new TemplateSummary(t.key(), t.name(), t.connectorKey(), t.description())).toList();
        return new CatalogResponse(connectors, templates);
    }

    private static boolean matches(Connector c, String q) {
        return c.key().contains(q) || (c.name() != null && c.name().toLowerCase(Locale.ROOT).contains(q))
                || (c.standard() != null && c.standard().toLowerCase(Locale.ROOT).contains(q))
                || c.transports().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(q));
    }

    /** API-DSC-56 설정 스키마. {@code x-ui}(탭·필드 순서)는 uiHints로 따로 준다 */
    @Transactional(readOnly = true)
    public ConnectorSchemaResponse schema(String key) {
        roleChecker.require(Permission.SRC_READ);
        Connector c = catalog.findByKey(key).orElseThrow(() -> new BusinessException(SourceErrorCode.CONNECTOR_NOT_FOUND));
        JsonNode ui = c.schema().get("x-ui");
        return new ConnectorSchemaResponse(c.key(), c.version(), c.schema(), ui == null ? JsonNodeFactory.instance.objectNode() : ui,
                SourceSecrets.matrixFor(c.authMethods()));
    }

    /** API-DSC-56 템플릿 preset(조직 템플릿이 플랫폼 템플릿보다 우선) */
    @Transactional(readOnly = true)
    public TemplateResponse template(String key) {
        roleChecker.require(Permission.SRC_READ);
        long orgId = roleChecker.currentUser().organizationId();
        var t = catalog.findTemplate(orgId, key).orElseThrow(() -> new BusinessException(SourceErrorCode.CONNECTOR_NOT_FOUND));
        ObjectNode preset = t.preset().isObject() ? ((ObjectNode) t.preset()).deepCopy() : JsonNodeFactory.instance.objectNode();
        if (t.decoderKey() != null && !preset.has("decoderKey")) {
            preset.put("decoderKey", t.decoderKey());
        }
        return new TemplateResponse(t.key(), t.connectorKey(), t.name(), t.description(), t.docsUrl(), t.builtin(), preset);
    }

    /** API-DSC-23 플랫폼 브로커 안내(ADR-029: 기존 iot-data.java21.net WSS, nginx Basic, 기기별 HMAC 서명) */
    public PlatformBrokerResponse platformBroker() {
        roleChecker.require(Permission.SRC_READ);
        return new PlatformBrokerResponse(PLATFORM_WSS_URL, "BASIC", "HMAC-SHA256", TOPIC_RULES);
    }

    static ConnectorResponse toResponse(Connector c) {
        boolean lossPossible;
        try {
            lossPossible = AckMode.valueOf(c.ackMode()).lossPossible();
        } catch (IllegalArgumentException ex) {
            lossPossible = true;
        }
        Map<String, String> typeOf = SourceModels.CONNECTOR_OF_TYPE.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        return new ConnectorResponse(c.key(), c.version(), c.name() == null ? c.key() : c.name(), c.category(), c.standard(),
                c.transports(), c.authMethods(), c.payloadFormats(), c.ackMode(), c.scaling(), c.supportsSend(), lossPossible,
                c.enabled(), c.disabledReason(), typeOf.getOrDefault(c.key(), "CONNECTOR"));
    }
}
