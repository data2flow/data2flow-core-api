package net.java21.data2flow.core.passwordreset.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.ClientInfo;
import net.java21.data2flow.core.passwordreset.dto.PasswordResetDtos.AcceptedResponse;
import net.java21.data2flow.core.passwordreset.dto.PasswordResetDtos.ConfirmPasswordResetRequest;
import net.java21.data2flow.core.passwordreset.dto.PasswordResetDtos.CreatePasswordResetRequest;
import net.java21.data2flow.core.passwordreset.service.PasswordResetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 비밀번호 재설정(IAM-02.04) — 공개 */
@RestController
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** API-IAM-13 재설정 요청(IAM-02.04, BR-IAM-11) — 공개, 항상 202 */
    @PostMapping("/core/password-resets")
    public ResponseEntity<ApiResponse<AcceptedResponse>> create(@Valid @RequestBody CreatePasswordResetRequest request,
                                                                HttpServletRequest http) {
        service.request(request.loginIdOrEmail(), ClientInfo.from(http).ip());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(new AcceptedResponse("ACCEPTED")));
    }

    /** API-IAM-14 새 비밀번호 설정(IAM-02.04, BR-IAM-12) — 공개, 204 */
    @PostMapping("/core/password-resets/{token}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable("token") String token, @Valid @RequestBody ConfirmPasswordResetRequest request) {
        service.confirm(token, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
