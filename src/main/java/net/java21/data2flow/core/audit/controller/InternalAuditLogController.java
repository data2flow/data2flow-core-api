package net.java21.data2flow.core.audit.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.AcceptedResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.CreateAuditLogRequest;
import net.java21.data2flow.core.audit.service.AuditLogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 다른 서비스의 감사 기록 접수(IAM-06.01) — 내부 전용 */
@RestController
public class InternalAuditLogController {

    private final AuditLogService service;

    public InternalAuditLogController(AuditLogService service) {
        this.service = service;
    }

    /** API-IAM-39 감사 기록(IAM-06.01) — 내부(모든 서비스), 202 */
    @PostMapping("/internal/core/audit-logs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AcceptedResponse> create(@Valid @RequestBody CreateAuditLogRequest request,
                                                @RequestHeader(value = DataflowHeaders.CALLER_SERVICE, required = false) String caller) {
        service.recordExternal(request, caller);
        return ApiResponse.success(new AcceptedResponse("ACCEPTED"));
    }
}
