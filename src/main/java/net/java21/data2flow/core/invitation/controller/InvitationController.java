package net.java21.data2flow.core.invitation.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.AcceptInvitationRequest;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.AcceptInvitationResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.CreateInvitationsRequest;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.CreateInvitationsResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.InvitationInfoResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.InvitationSummaryResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.LoginIdAvailabilityResponse;
import net.java21.data2flow.core.invitation.dto.InvitationDtos.ResendInvitationResponse;
import net.java21.data2flow.core.invitation.service.InvitationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 초대(IAM-01.03). 토큰 경로는 공개, 나머지는 ADMIN */
@RestController
public class InvitationController {

    private final InvitationService service;

    public InvitationController(InvitationService service) {
        this.service = service;
    }

    /** API-IAM-20 초대(IAM-01.03) — ADMIN, 200(항목별 결과) */
    @PostMapping("/core/invitations")
    @Idempotent
    public ApiResponse<CreateInvitationsResponse> create(@Valid @RequestBody CreateInvitationsRequest request) {
        return ApiResponse.success(service.create(request));
    }

    /** API-IAM-33 초대 목록(IAM-01.03, 01.07) — ADMIN, 200 */
    @GetMapping("/core/invitations")
    public ListApiResponse<InvitationSummaryResponse> list(@RequestParam(required = false) String status,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return service.list(status, page, size);
    }

    /** API-IAM-22 재발송(BR-IAM-09) — ADMIN, 200 */
    @PostMapping("/core/invitations/{invitation-id}/resend")
    public ApiResponse<ResendInvitationResponse> resend(@PathVariable("invitation-id") long invitationId) {
        return ApiResponse.success(service.resend(invitationId));
    }

    /** API-IAM-29 취소(IAM-01.07) — ADMIN, 204 */
    @PostMapping("/core/invitations/{invitation-id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable("invitation-id") long invitationId) {
        service.cancel(invitationId);
        return ResponseEntity.noContent().build();
    }

    /** API-IAM-10 초대 확인(IAM-01.03) — 공개, 200 / 410 INVITATION_INVALID */
    @GetMapping("/core/invitations/{token}")
    public ApiResponse<InvitationInfoResponse> info(@PathVariable("token") String token) {
        return ApiResponse.success(service.info(token));
    }

    /** API-IAM-10a 아이디 사용 가능 여부(IAM-01.03) — 공개(초대 토큰), 200 */
    @GetMapping("/core/invitations/{token}/login-id-availability")
    public ApiResponse<LoginIdAvailabilityResponse> loginIdAvailability(@PathVariable("token") String token,
                                                                        @RequestParam("loginId") String loginId) {
        return ApiResponse.success(service.loginIdAvailability(token, loginId));
    }

    /** API-IAM-11 초대 수락(IAM-01.03, NFR-12.01) — 공개, 200 */
    @PostMapping("/core/invitations/{token}/accept")
    public ApiResponse<AcceptInvitationResponse> accept(@PathVariable("token") String token,
                                                        @Valid @RequestBody AcceptInvitationRequest request) {
        return ApiResponse.success(service.accept(token, request));
    }
}
