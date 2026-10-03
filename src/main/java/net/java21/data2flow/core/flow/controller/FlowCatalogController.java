package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.flow.domain.FlowTemplates.Template;
import net.java21.data2flow.core.flow.dto.FlowDtos.TemplateResult;
import net.java21.data2flow.core.flow.service.FlowSupport;
import net.java21.data2flow.core.flow.service.FlowTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 노드 카탈로그(API-FLW-30, FLOW_READ, 원천은 엔진 API-FLW-83)와 플로우 템플릿(API-FLW-20, FLOW_WRITE) */
@RestController
public class FlowCatalogController {

    private final FlowSupport support;
    private final FlowTemplateService templates;
    private final RoleChecker roleChecker;

    public FlowCatalogController(FlowSupport support, FlowTemplateService templates, RoleChecker roleChecker) {
        this.support = support;
        this.templates = templates;
        this.roleChecker = roleChecker;
    }

    @GetMapping("/core/flow-nodes")
    public ItemsResponse<tools.jackson.databind.JsonNode> nodes() {
        roleChecker.require(Permission.FLOW_READ);
        return ItemsResponse.of(support.nodeTypes());
    }

    @GetMapping("/core/flow-templates")
    public ItemsResponse<Template> templates(@RequestParam(required = false) String category) {
        return ItemsResponse.of(templates.list(category));
    }

    @PostMapping("/core/flow-templates/{flow-template-key}/instantiate")
    @Idempotent
    public ResponseEntity<ApiResponse<TemplateResult>> instantiate(@PathVariable("flow-template-key") String key, @RequestBody JsonNode body) {
        TemplateResult created = templates.instantiate(key, body);
        return ResponseEntity.created(URI.create("/api/v1/core/flows/" + created.flowId())).body(ApiResponse.success(created));
    }
}
