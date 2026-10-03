package net.java21.data2flow.core.account.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangeRoleRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.ChangeRoleResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.CreateUserRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.CreateUserResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.DeleteUserRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.DisableUserRequest;
import net.java21.data2flow.core.account.dto.AccountDtos.UserDetailResponse;
import net.java21.data2flow.core.account.dto.AccountDtos.UserSummaryResponse;
import net.java21.data2flow.core.account.service.UserAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** 회원 관리(IAM-01.03·01.04·01.07·01.10, IAM-02.03) — ADMIN(IAM_MANAGE) */
@RestController
public class UserController {

    private final UserAdminService service;

    public UserController(UserAdminService service) {
        this.service = service;
    }

    /** API-IAM-31 회원 목록(IAM-01.07) — ADMIN, 200 */
    @GetMapping("/core/users")
    public ListApiResponse<UserSummaryResponse> list(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String role,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String spaceId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     HttpServletRequest request) {
        // sort=필드,방향을 반복해서 받는다. List 바인딩은 쉼표로 쪼개므로 원본 값을 그대로 읽는다(api-rules §5)
        String[] sort = request.getParameterValues("sort");
        return service.list(keyword, role, status, spaceId, sort == null ? null : List.of(sort), page, size);
    }

    /** API-IAM-32 회원 상세(IAM-01.07) — ADMIN, 200 */
    @GetMapping("/core/users/{user-id}")
    public ApiResponse<UserDetailResponse> detail(@PathVariable("user-id") long userId) {
        return ApiResponse.success(service.detail(userId));
    }

    /**
     * API-IAM-21 직접 생성(IAM-01.03) — ADMIN, 201. 자동 생성한 임시 비밀번호가 응답에 1회 들어가므로 Idempotency-Key 응답 저장을 하지 않는다
     * (멱등 저장소에 평문 비밀번호가 24시간 남는 것을 막음, NFR-03.02). 같은 아이디로 다시 오면 409 LOGIN_ID_DUPLICATED다.
     */
    @PostMapping("/core/users")
    public ResponseEntity<ApiResponse<CreateUserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        CreateUserResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/users/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-IAM-23 역할·공간 권한 변경(IAM-01.07) — ADMIN, 200 */
    @PutMapping("/core/users/{user-id}/role")
    public ApiResponse<ChangeRoleResponse> changeRole(@PathVariable("user-id") long userId,
                                                      @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.success(service.changeRole(userId, request));
    }

    /** API-IAM-25 비활성화(IAM-01.04) — ADMIN, 204 */
    @PostMapping("/core/users/{user-id}/disable")
    public ResponseEntity<Void> disable(@PathVariable("user-id") long userId,
                                        @Valid @RequestBody(required = false) DisableUserRequest request) {
        service.disable(userId, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-26 재활성화(IAM-01.10) — ADMIN, 204 */
    @PostMapping("/core/users/{user-id}/enable")
    public ResponseEntity<Void> enable(@PathVariable("user-id") long userId) {
        service.enable(userId);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-27 잠금 해제(IAM-02.03) — ADMIN, 204 */
    @PostMapping("/core/users/{user-id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable("user-id") long userId) {
        service.unlock(userId);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-28 삭제(익명화, IAM-01.10) — ADMIN, 204 */
    @DeleteMapping("/core/users/{user-id}")
    public ResponseEntity<Void> delete(@PathVariable("user-id") long userId, @Valid @RequestBody DeleteUserRequest request) {
        service.delete(userId, request.confirmLoginId());
        return ResponseEntity.noContent().build();
    }
}
