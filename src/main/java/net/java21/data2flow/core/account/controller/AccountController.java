package net.java21.data2flow.core.account.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangePasswordRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.MeResponse;
import net.java21.data2flow.core.account.service.AccountService;
import net.java21.data2flow.core.session.controller.SessionController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 내 정보(IAM-01.05, IAM-01.09) — 로그인 */
@RestController
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** API-IAM-04 내 정보(IAM-01.05) — 로그인, 200 */
    @GetMapping("/core/accounts/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.success(service.me());
    }

    /** API-IAM-15 프로필 수정(IAM-01.05) — 로그인, 200 */
    @PatchMapping("/core/accounts/me")
    public ApiResponse<MeResponse> patch(@RequestBody JsonNode body) {
        return ApiResponse.success(service.patchMe(body));
    }

    /** API-IAM-12 비밀번호 변경(IAM-01.09, BR-IAM-12) — 로그인(임시 비밀번호 상태 포함), 204 */
    @PutMapping("/core/accounts/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               @RequestHeader(value = SessionController.SESSION_ID_HEADER, required = false) String sid) {
        service.changePassword(request, sid);
        return ResponseEntity.noContent().build();
    }
}
