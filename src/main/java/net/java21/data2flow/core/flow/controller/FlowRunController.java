package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.flow.service.FlowRunService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** API-FLW-12 시험 실행, API-FLW-13 과거 재생(flow-engine 중계, ADR-051) */
@RestController
public class FlowRunController {

    private final FlowRunService runs;

    public FlowRunController(FlowRunService runs) {
        this.runs = runs;
    }

    @PostMapping("/core/flows/{flow-id}/test-run")
    public ApiResponse<JsonNode> testRun(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return ApiResponse.success(runs.testRun(flowId, body));
    }

    @PostMapping("/core/flows/{flow-id}/replay")
    public ResponseEntity<ApiResponse<JsonNode>> replay(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(runs.replay(flowId, body)));
    }

    @GetMapping("/core/flow-replays/{job-id}")
    public ApiResponse<JsonNode> replayJob(@PathVariable("job-id") String jobId) {
        return ApiResponse.success(runs.replayJob(jobId));
    }

    @PostMapping("/core/flow-replays/{job-id}/cancel")
    public ApiResponse<JsonNode> cancelReplay(@PathVariable("job-id") String jobId) {
        return ApiResponse.success(runs.cancelReplay(jobId));
    }

    @GetMapping("/core/flows/{flow-id}/traces/{message-id}")
    public ApiResponse<JsonNode> trace(@PathVariable("flow-id") String flowId, @PathVariable("message-id") String messageId) {
        return ApiResponse.success(runs.trace(flowId, messageId));
    }
}
