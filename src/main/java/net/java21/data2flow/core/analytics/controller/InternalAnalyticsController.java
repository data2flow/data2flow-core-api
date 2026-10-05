package net.java21.data2flow.core.analytics.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.analytics.service.AnalysisRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 분석 내부 API(core 제공). API-ANA-40 {@code GET /internal/core/analysis-runs/{run-id}}: ai가 해설(AIA-01)을 만들 때 요청 사용자(또는 장기 토큰) 신원
 * 헤더를 그대로 붙여 실행 결과를 읽는다. 판정은 API-ANA-09와 같다(분석 ID 없이 실행 ID만).
 */
@RestController
public class InternalAnalyticsController {

    private final AnalysisRunService runs;

    public InternalAnalyticsController(AnalysisRunService runs) {
        this.runs = runs;
    }

    /** API-ANA-41 해설 연결 — 204 */
    @org.springframework.web.bind.annotation.PutMapping("/internal/core/analysis-runs/{run-id}/ai-commentary")
    public org.springframework.http.ResponseEntity<Void> linkCommentary(@PathVariable("run-id") long runId,
                                                                        @org.springframework.web.bind.annotation.RequestBody(required = false) JsonNode body) {
        runs.linkCommentary(runId, body);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/internal/core/analysis-runs/{run-id}")
    public ApiResponse<JsonNode> run(@PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.getByRun(runId));
    }
}
