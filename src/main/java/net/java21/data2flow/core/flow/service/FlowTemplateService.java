package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.domain.FlowModels;
import net.java21.data2flow.core.flow.domain.FlowTemplates;
import net.java21.data2flow.core.flow.domain.FlowTemplates.Template;
import net.java21.data2flow.core.flow.dto.FlowDtos.SaveResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.TemplateResult;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;

/**
 * 플로우 템플릿(FLW-01.05, API-FLW-20, UC-FLW-01). 목록·생성 모두 FLOW_WRITE(VIEWER·ANALYST 403, TC-FLW-110·111). 대상 공간이 없거나
 * 범위 밖이면 404. 만든 플로우는 DRAFT이고(BR-FLW-15) 대상 기기가 없으면 경고(TARGET_EMPTY "대상 기기 없음")만 준다(TC-FLW-020).
 */
@Service
public class FlowTemplateService {

    private final FlowService flowService;
    private final FlowRepository flows;
    private final FlowTargetRepository targets;
    private final RoleChecker roleChecker;
    private final JsonMapper json;

    public FlowTemplateService(FlowService flowService, FlowRepository flows, FlowTargetRepository targets, RoleChecker roleChecker,
                               JsonMapper json) {
        this.flowService = flowService;
        this.flows = flows;
        this.targets = targets;
        this.roleChecker = roleChecker;
        this.json = json;
    }

    public List<Template> list(String category) {
        roleChecker.require(Permission.FLOW_WRITE);
        String filter = category == null || category.isBlank() ? null : category.strip().toUpperCase(Locale.ROOT);
        return FlowTemplates.all(json).stream().filter(t -> filter == null || t.category().equals(filter)).toList();
    }

    /** API-FLW-20 템플릿으로 만들기 {name, params} — 201 */
    @Transactional
    public TemplateResult instantiate(String key, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        Template template = FlowTemplates.find(key, json).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_TEMPLATE_NOT_FOUND));
        if (body == null || !body.isObject()) {
            throw FlowModels.invalid("body", "NotNull");
        }
        String name = FlowService.name(body.get("name"));
        JsonNode params = body.hasNonNull("params") && body.get("params").isObject() ? body.get("params") : json.createObjectNode();
        return create(template, name, params);
    }

    /** 가상 환경 프리셋·키트가 넘긴 묶음(bindings)으로 만들기(SIM-04.05, API-SIM-18). 이름이 겹치면 번호를 붙인다 */
    @Transactional
    public TemplateResult instantiateBindings(String key, JsonNode bindings, String baseName) {
        Template template = FlowTemplates.find(key, json).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_TEMPLATE_NOT_FOUND));
        long orgId = roleChecker.currentUser().organizationId();
        String name = baseName.length() > 90 ? baseName.substring(0, 90) : baseName;
        for (int i = 2; flows.existsName(orgId, name, null) && i < 1000; i++) {
            name = (baseName.length() > 90 ? baseName.substring(0, 90) : baseName) + " (" + i + ")";
        }
        return create(template, name, bindings);
    }

    private TemplateResult create(Template template, String name, JsonNode params) {
        long orgId = roleChecker.currentUser().organizationId();
        long spaceId = Long.parseLong(FlowTemplates.spaceId(params));
        if (!targets.existsSpace(orgId, spaceId) || !roleChecker.spaceScope().includes(spaceId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        JsonNode definition = FlowTemplates.build(template.key(), params, json);
        SaveResult saved = flowService.createFlow(name, template.description(), "PROD", definition, template.key());
        return new TemplateResult(saved.flowId(), saved.draftVersion(), saved.validation().warnings());
    }
}
