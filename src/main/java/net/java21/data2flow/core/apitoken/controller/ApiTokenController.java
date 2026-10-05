package net.java21.data2flow.core.apitoken.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.DecisionRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.IssueRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.IssueResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.RotateRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.RotateResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.ServiceAccountRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.ServiceAccountResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.TokenItem;
import net.java21.data2flow.core.apitoken.service.ApiTokenService;
import net.java21.data2flow.core.apitoken.service.ServiceAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 장기 토큰(API 키·MCP)·서비스 계정 외부 API(IAM-api §5, API-IAM-40~45; MCP 토큰 화면 API-AIA-09도 이 API를 쓴다).
 * 발급은 원문을 응답에 한 번만 담으므로 멱등 키 저장(응답 보관)을 쓰지 않는다(BR-IAM-18).
 */
@RestController
public class ApiTokenController {

    private final ApiTokenService tokens;
    private final ServiceAccountService accounts;

    public ApiTokenController(ApiTokenService tokens, ServiceAccountService accounts) {
        this.tokens = tokens;
        this.accounts = accounts;
    }

    /** API-IAM-40 발급 — 201 + Location, 원문 1회 */
    @PostMapping("/core/api-tokens")
    public ResponseEntity<ApiResponse<IssueResponse>> issue(@RequestBody(required = false) IssueRequest req) {
        IssueResponse created = tokens.issue(req);
        return ResponseEntity.created(URI.create("/api/v1/core/api-tokens/" + created.id()))
                .header("Cache-Control", "no-store").body(ApiResponse.success(created));
    }

    /** API-IAM-41 목록(원문 없음). kind는 UI-AIA-07(MCP 토큰 화면)이 쓰는 거르기 */
    @GetMapping("/core/api-tokens")
    public ListApiResponse<TokenItem> list(@RequestParam(required = false) String owner, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String kind, @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return tokens.list(owner, status, kind, page, size);
    }

    /** API-IAM-42 승인 — 204 */
    @PostMapping("/core/api-tokens/{api-token-id}/approve")
    public ResponseEntity<Void> approve(@PathVariable("api-token-id") long id, @RequestBody(required = false) DecisionRequest req) {
        tokens.approve(id, req == null ? null : req.reason());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-42 거절 — 204 */
    @PostMapping("/core/api-tokens/{api-token-id}/reject")
    public ResponseEntity<Void> reject(@PathVariable("api-token-id") long id, @RequestBody(required = false) DecisionRequest req) {
        tokens.reject(id, req == null ? null : req.reason());
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-43 폐기 — 204 */
    @DeleteMapping("/core/api-tokens/{api-token-id}")
    public ResponseEntity<Void> revoke(@PathVariable("api-token-id") long id) {
        tokens.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-44 교체 — 새 원문 1회 */
    @PostMapping("/core/api-tokens/{api-token-id}/rotate")
    public ResponseEntity<ApiResponse<RotateResponse>> rotate(@PathVariable("api-token-id") long id,
                                                              @RequestBody(required = false) RotateRequest req) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.success(tokens.rotate(id, req == null ? null : req.graceHours())));
    }

    /** API-IAM-45 서비스 계정 목록 */
    @GetMapping("/core/service-accounts")
    public ListApiResponse<ServiceAccountResponse> accounts(@RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return accounts.list(page, size);
    }

    /** API-IAM-45 서비스 계정 만들기 — 201 */
    @PostMapping("/core/service-accounts")
    public ResponseEntity<ApiResponse<ServiceAccountResponse>> createAccount(@RequestBody(required = false) ServiceAccountRequest req) {
        ServiceAccountResponse created = accounts.create(req);
        return ResponseEntity.created(URI.create("/api/v1/core/service-accounts/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-IAM-45 서비스 계정 비활성화 */
    @PostMapping("/core/service-accounts/{service-account-id}/disable")
    public ApiResponse<ServiceAccountResponse> disable(@PathVariable("service-account-id") long id) {
        return ApiResponse.success(accounts.disable(id));
    }
}
