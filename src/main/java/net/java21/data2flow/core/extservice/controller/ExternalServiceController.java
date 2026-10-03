package net.java21.data2flow.core.extservice.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.ExternalServiceResponse;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.TestExternalServiceRequest;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.TestResultResponse;
import net.java21.data2flow.core.extservice.dto.ExternalServiceDtos.UpdateExternalServiceRequest;
import net.java21.data2flow.core.extservice.service.ExternalServiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 외부 서비스 설정(OPS-07.02, NFR-03.02) — ADMIN(OPS_MANAGE) */
@RestController
public class ExternalServiceController {

    private final ExternalServiceService service;

    public ExternalServiceController(ExternalServiceService service) {
        this.service = service;
    }

    /** API-OPS-41 외부 서비스 목록(OPS-07.02) — ADMIN, 200. 비밀값은 설정 여부만 */
    @GetMapping("/core/external-services")
    public ApiResponse<List<ExternalServiceResponse>> list() {
        return ApiResponse.success(service.list());
    }

    /** API-OPS-41 외부 서비스 저장(OPS-07.02) — ADMIN, 200 */
    @PutMapping("/core/external-services/{kind}")
    public ApiResponse<ExternalServiceResponse> replace(@PathVariable("kind") String kind,
                                                        @Valid @RequestBody UpdateExternalServiceRequest request) {
        return ApiResponse.success(service.put(kind, request));
    }

    /** API-OPS-42 연결 테스트(OPS-07.02) — ADMIN, 200 / 실패 502 EXTERNAL_SERVICE_TEST_FAILED */
    @PostMapping("/core/external-services/{kind}/test")
    public ApiResponse<TestResultResponse> test(@PathVariable("kind") String kind,
                                                @RequestBody(required = false) TestExternalServiceRequest request) {
        return ApiResponse.success(service.test(kind, request));
    }
}
