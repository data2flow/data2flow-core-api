package net.java21.data2flow.core.apitoken.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.VerifyRequest;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.VerifyResponse;
import net.java21.data2flow.core.apitoken.service.ApiTokenVerifyService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-IAM-46 장기 토큰 검증 — 내부(auth introspection). 쓸 수 없는 토큰도 200 {@code active=false} */
@RestController
public class InternalApiTokenController {

    private final ApiTokenVerifyService verifier;

    public InternalApiTokenController(ApiTokenVerifyService verifier) {
        this.verifier = verifier;
    }

    @PostMapping("/internal/core/api-tokens/verify")
    public ApiResponse<VerifyResponse> verify(@RequestBody(required = false) VerifyRequest req) {
        return ApiResponse.success(req == null ? VerifyResponse.inactive() : verifier.verify(req.tokenHash(), req.ip()));
    }
}
