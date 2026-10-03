package net.java21.data2flow.core.session.controller;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.RevokeAllResponse;
import net.java21.data2flow.core.session.dto.SessionDtos.SessionResponse;
import net.java21.data2flow.core.session.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 활성 로그인 목록·개별 종료(IAM-03.02, IAM-07.08)와 관리자 강제 종료(IAM-03.03) */
@RestController
public class SessionController {

    /** BFF가 현재 세션 표시를 위해 넣는 헤더(세션 쿠키 안의 sid). 문서에 없는 확장 — 보고서 참조 */
    public static final String SESSION_ID_HEADER = DataflowHeaders.SESSION_ID;

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    /** API-IAM-16 내 활성 로그인(IAM-03.02) — 로그인, 200 */
    @GetMapping("/core/accounts/me/sessions")
    public ApiResponse<List<SessionResponse>> mySessions(@RequestHeader(value = SESSION_ID_HEADER, required = false) String sid) {
        return ApiResponse.success(service.mySessions(sid));
    }

    /** API-IAM-17 내 세션 종료(IAM-03.02, AT-IAM-08.4) — 로그인, 204 */
    @DeleteMapping("/core/accounts/me/sessions/{sid}")
    public ResponseEntity<Void> revokeMine(@PathVariable("sid") String sid) {
        service.revokeMySession(sid);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-24 사용자 세션 강제 종료(IAM-03.03) — ADMIN, 200 */
    @PostMapping("/core/users/{user-id}/sessions/revoke-all")
    public ApiResponse<RevokeAllResponse> revokeAll(@PathVariable("user-id") long userId) {
        return ApiResponse.success(service.revokeAllByAdmin(userId));
    }
}
