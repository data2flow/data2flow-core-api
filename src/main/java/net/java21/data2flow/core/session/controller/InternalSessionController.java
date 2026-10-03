package net.java21.data2flow.core.session.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.CreateRefreshTokenRequest;
import net.java21.data2flow.core.session.dto.SessionDtos.RefreshTokenResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RevocationsResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RevokeRequest;
import net.java21.data2flow.core.session.dto.SessionDtos.RotateRefreshTokenRequest;
import net.java21.data2flow.core.session.dto.SessionDtos.RotateRefreshTokenResponse;
import net.java21.data2flow.core.session.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

/** Refresh 계보·폐기 원천 — 내부 전용(data2flow-auth가 부른다, IAM-api §7) */
@RestController
public class InternalSessionController {

    private final SessionService service;

    public InternalSessionController(SessionService service) {
        this.service = service;
    }

    /** API-IAM-36 Refresh 계보 등록(IAM-07.03) — 내부(auth), 201 */
    @PostMapping("/internal/core/refresh-tokens")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> create(@Valid @RequestBody CreateRefreshTokenRequest request) {
        RefreshTokenResponse created = service.register(request);
        return ResponseEntity.created(URI.create("/internal/core/refresh-tokens/" + created.jti()))
                .body(ApiResponse.success(created));
    }

    /** API-IAM-35 회전 판정(IAM-07.03) — 내부(auth), 200. 재사용이면 401 AUTH_SESSION_REVOKED */
    @PostMapping("/internal/core/refresh-tokens/rotate")
    public ApiResponse<RotateRefreshTokenResponse> rotate(@Valid @RequestBody RotateRefreshTokenRequest request) {
        return ApiResponse.success(service.rotate(request));
    }

    /** API-IAM-37 Refresh 하나 폐기(IAM-07.05) — 내부(auth), 204(멱등) */
    @DeleteMapping("/internal/core/refresh-tokens/{jti}")
    public ResponseEntity<Void> deleteRefreshToken(@PathVariable("jti") String jti,
                                                   @RequestBody(required = false) RevokeRequest request) {
        service.revokeJti(jti, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-37 세션(sid 계보) 폐기(IAM-07.05) — 내부(auth), 204(멱등) */
    @DeleteMapping("/internal/core/sessions/{sid}")
    public ResponseEntity<Void> deleteSession(@PathVariable("sid") String sid,
                                              @RequestBody(required = false) RevokeRequest request) {
        service.revokeSessionInternal(sid, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-39a 최근 폐기 목록(IAM-07.05·07.10, ADR-022) — 내부(auth), 200 */
    @GetMapping("/internal/core/revocations")
    public ApiResponse<RevocationsResponse> revocations(@RequestParam(required = false) Instant since) {
        return ApiResponse.success(service.revocationsSince(since));
    }
}
