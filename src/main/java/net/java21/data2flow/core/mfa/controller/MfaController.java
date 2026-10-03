package net.java21.data2flow.core.mfa.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.mfa.dto.MfaDtos.CurrentPasswordRequest;
import net.java21.data2flow.core.mfa.dto.MfaDtos.MfaCodeRequest;
import net.java21.data2flow.core.mfa.dto.MfaDtos.MfaSetupResponse;
import net.java21.data2flow.core.mfa.dto.MfaDtos.MfaVerifyResponse;
import net.java21.data2flow.core.mfa.dto.MfaDtos.RecoveryCodesResponse;
import net.java21.data2flow.core.mfa.service.MfaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 2단계 인증(IAM-02.05) — 본인 / ADMIN / 내부(auth) */
@RestController
public class MfaController {

    private final MfaService service;

    public MfaController(MfaService service) {
        this.service = service;
    }

    /** API-IAM-60 등록 시작(IAM-02.05) — 로그인, 200 */
    @PostMapping("/core/accounts/me/mfa/setup")
    public ApiResponse<MfaSetupResponse> setup() {
        return ApiResponse.success(service.setup());
    }

    /** API-IAM-61 등록 확인(IAM-02.05, AT-IAM-14.1) — 로그인, 200 */
    @PostMapping("/core/accounts/me/mfa/confirm")
    public ApiResponse<RecoveryCodesResponse> confirm(@Valid @RequestBody MfaCodeRequest request) {
        return ApiResponse.success(service.confirm(request.code()));
    }

    /** API-IAM-61a 끄기(IAM-02.05) — 로그인, 204 */
    @DeleteMapping("/core/accounts/me/mfa")
    public ResponseEntity<Void> disable(@Valid @RequestBody CurrentPasswordRequest request) {
        service.disable(request.currentPassword());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-61c 복구 코드 재발급(BR-IAM-26) — 로그인, 200 */
    @PostMapping("/core/accounts/me/mfa/recovery-codes")
    public ApiResponse<RecoveryCodesResponse> regenerate(@Valid @RequestBody CurrentPasswordRequest request) {
        return ApiResponse.success(service.regenerate(request.currentPassword()));
    }

    /** API-IAM-63 관리자 초기화(IAM-02.05) — ADMIN, 204 */
    @DeleteMapping("/core/users/{user-id}/mfa")
    public ResponseEntity<Void> reset(@PathVariable("user-id") long userId) {
        service.resetByAdmin(userId);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-61b 로그인 코드 확인(IAM-02.05) — 내부(auth), 200 / 401 MFA_CODE_INVALID */
    @PostMapping("/internal/core/users/{user-id}/mfa/verify")
    public ApiResponse<MfaVerifyResponse> verify(@PathVariable("user-id") long userId, @Valid @RequestBody MfaCodeRequest request) {
        return ApiResponse.success(service.verifyForLogin(userId, request.code()));
    }
}
