package net.java21.data2flow.core.script.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.script.service.ScriptAiDraftService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** API-SCR-16 AI 작성 도우미(SCR-03.07) — 초안과 테스트 실행 결과만, 저장·배포 없음 */
@RestController
public class ScriptAiController {

    private final ScriptAiDraftService service;

    public ScriptAiController(ScriptAiDraftService service) {
        this.service = service;
    }

    @PostMapping("/core/scripts/ai-draft")
    public ApiResponse<JsonNode> draft(@RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.draft(body));
    }
}
