package net.java21.data2flow.core.signup.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.common.ClientInfo;
import net.java21.data2flow.core.signup.dto.SignupDtos.AcceptedResponse;
import net.java21.data2flow.core.signup.dto.SignupDtos.ApproveSignupRequest;
import net.java21.data2flow.core.signup.dto.SignupDtos.CreateSignupRequest;
import net.java21.data2flow.core.signup.dto.SignupDtos.RejectSignupRequest;
import net.java21.data2flow.core.signup.dto.SignupDtos.SignupDecisionResponse;
import net.java21.data2flow.core.signup.dto.SignupDtos.SignupSettingsResponse;
import net.java21.data2flow.core.signup.dto.SignupDtos.SignupSummaryResponse;
import net.java21.data2flow.core.signup.service.SignupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 승인형 가입 신청(IAM-01.08). 신청·확인은 공개, 목록·승인·거절은 ADMIN */
@RestController
public class SignupController {

    private final SignupService service;

    public SignupController(SignupService service) {
        this.service = service;
    }

    /** API-IAM-74 공개 가입 설정(IAM-01.08) — 공개, 200. 로그인 화면의 "가입 신청" 링크 표시 여부 */
    @GetMapping("/core/public/signup-settings")
    public ApiResponse<SignupSettingsResponse> publicSettings() {
        return ApiResponse.success(service.publicSettings());
    }

    /** API-IAM-67 가입 신청(IAM-01.08) — 공개(설정 켜짐), 202 */
    @PostMapping("/core/signup-requests")
    public ResponseEntity<ApiResponse<AcceptedResponse>> create(@Valid @RequestBody CreateSignupRequest request,
                                                                HttpServletRequest http) {
        service.create(request, ClientInfo.from(http).ip());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(new AcceptedResponse("ACCEPTED")));
    }

    /** API-IAM-68 이메일 확인(IAM-01.08) — 공개, 204 */
    @PostMapping("/core/signup-requests/{token}/verify")
    public ResponseEntity<Void> verify(@PathVariable("token") String token) {
        service.verify(token);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-69 신청 목록(IAM-01.08) — ADMIN, 200 */
    @GetMapping("/core/signup-requests")
    public ListApiResponse<SignupSummaryResponse> list(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return service.list(status, page, size);
    }

    /** API-IAM-69 승인(IAM-01.08) — ADMIN, 200 */
    @PostMapping("/core/signup-requests/{signup-request-id}/approve")
    public ApiResponse<SignupDecisionResponse> approve(@PathVariable("signup-request-id") long id,
                                                       @RequestBody(required = false) ApproveSignupRequest request) {
        return ApiResponse.success(service.approve(id, request));
    }

    /** API-IAM-69 거절(IAM-01.08) — ADMIN, 200 */
    @PostMapping("/core/signup-requests/{signup-request-id}/reject")
    public ApiResponse<SignupDecisionResponse> reject(@PathVariable("signup-request-id") long id,
                                                      @RequestBody(required = false) RejectSignupRequest request) {
        return ApiResponse.success(service.reject(id, request == null ? null : request.reason()));
    }
}
