package net.java21.data2flow.core.device.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.QuotaResetRequest;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.QuotaResponse;
import net.java21.data2flow.core.device.service.DeviceDiscoveryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-ING-16 자동 등록 한도 해제(ING-07.02, BR-ING-09) */
@RestController
public class AutoRegisterQuotaController {

    private final DeviceDiscoveryService discovery;

    public AutoRegisterQuotaController(DeviceDiscoveryService discovery) {
        this.discovery = discovery;
    }

    /**
     * API-ING-16 — ADMIN(OPS_MANAGE), 200. 경로는 ING-api의 {@code …/reset}이 정본이고, 테스트 계획(TC-ING-085)이 쓰는
     * {@code …/release}도 같은 동작으로 받는다.
     */
    @PostMapping({"/core/ingest/sources/{source-id}/auto-register-quota/reset",
            "/core/ingest/sources/{source-id}/auto-register-quota/release"})
    public ApiResponse<QuotaResponse> reset(@PathVariable("source-id") long sourceId,
                                            @Valid @RequestBody(required = false) QuotaResetRequest request) {
        return ApiResponse.success(discovery.resetQuota(sourceId, request));
    }
}
