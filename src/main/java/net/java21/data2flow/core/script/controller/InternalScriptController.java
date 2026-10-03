package net.java21.data2flow.core.script.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.AutoDisableRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.AutoDisableResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployAckRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.RuntimeBundle;
import net.java21.data2flow.core.script.service.ScriptRuntimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스크립트 내부 API(SCR-api.md §2). 호출자 pipeline·flow-engine, 내부망 신뢰(ADR-021, {@code X-CALLER-SERVICE}), 사용자 신원 없음.
 */
@RestController
public class InternalScriptController {

    private final ScriptRuntimeService runtime;

    public InternalScriptController(ScriptRuntimeService runtime) {
        this.runtime = runtime;
    }

    /**
     * API-SCR-32 실행 묶음(인스턴스 시작·재연결·EVT-SCR-01 수신 시 적재, 30초 주기 폴링 TC-SCR-054). 200, sinceVersion이 같으면 204.
     * organizationId를 생략하면 배포 조직 범위 전체.
     */
    @GetMapping("/internal/core/scripts/runtime-bundle")
    public ResponseEntity<ApiResponse<RuntimeBundle>> bundle(@RequestParam(required = false) Long organizationId,
                                                             @RequestParam(required = false) Long sinceVersion) {
        return runtime.bundle(organizationId, sinceVersion)
                .map(b -> ResponseEntity.ok(ApiResponse.success(b)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** API-SCR-33 자동 비활성(BR-SCR-11) — 200 */
    @PostMapping("/internal/core/scripts/{script-id}/auto-disable")
    public ApiResponse<AutoDisableResponse> autoDisable(@PathVariable("script-id") long scriptId,
                                                        @Valid @RequestBody AutoDisableRequest request) {
        return ApiResponse.success(runtime.autoDisable(scriptId, request));
    }

    /** API-SCR-34 인스턴스 적용 보고(SCR-03.04) — 204 */
    @PostMapping("/internal/core/scripts/deploy-acks")
    public ResponseEntity<Void> acknowledge(@Valid @RequestBody DeployAckRequest request) {
        runtime.acknowledge(request);
        return ResponseEntity.noContent().build();
    }
}
