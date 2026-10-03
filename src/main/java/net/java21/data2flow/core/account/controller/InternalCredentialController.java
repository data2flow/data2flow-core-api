package net.java21.data2flow.core.account.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.account.dto.LoginDtos.VerifyCredentialsRequest;
import net.java21.data2flow.core.account.dto.LoginDtos.VerifyCredentialsResponse;
import net.java21.data2flow.core.account.service.LoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 자격 확인 — 내부 전용(data2flow-auth, design/auth.md §3.2) */
@RestController
public class InternalCredentialController {

    private final LoginService service;

    public InternalCredentialController(LoginService service) {
        this.service = service;
    }

    /**
     * API-IAM-30 자격 확인(IAM-02.01·02.03) — 내부(auth), 200. 실패는 원인과 관계없이 401 AUTH_INVALID_CREDENTIALS,
     * 승인 대기 계정의 맞는 비밀번호는 403 AUTH_PENDING_APPROVAL
     */
    @PostMapping("/internal/core/users/verify-credentials")
    public ApiResponse<VerifyCredentialsResponse> verify(@Valid @RequestBody VerifyCredentialsRequest request) {
        return ApiResponse.success(service.verify(request));
    }
}
