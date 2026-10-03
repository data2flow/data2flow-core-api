package net.java21.data2flow.core.audit.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.AuditLogDetailResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.AuditLogSummaryResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.ExportAuditLogsRequest;
import net.java21.data2flow.core.audit.service.AuditLogService;
import net.java21.data2flow.core.audit.service.AuditLogService.Filter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 감사 로그 조회(IAM-06.03·06.04, NFR-12.02) — ADMIN(AUDIT_READ) */
@RestController
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    /** API-IAM-50 감사 로그 검색(IAM-06.03) — ADMIN, 200 커서 목록 */
    @GetMapping("/core/audit-logs")
    public CursorListApiResponse<AuditLogSummaryResponse> list(@RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to,
                                                               @RequestParam(required = false) String actorType,
                                                               @RequestParam(required = false) String actor,
                                                               @RequestParam(required = false) String action,
                                                               @RequestParam(required = false) String targetType,
                                                               @RequestParam(required = false) String targetId,
                                                               @RequestParam(required = false) String result,
                                                               @RequestParam(required = false) String ip,
                                                               @RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer size) {
        return service.search(new Filter(from, to, actorType, actor, action, targetType, targetId, result, ip), cursor, size);
    }

    /** API-IAM-52 감사 상세(IAM-06.04) — ADMIN, 200 */
    @GetMapping("/core/audit-logs/{audit-log-id}")
    public ApiResponse<AuditLogDetailResponse> detail(@PathVariable("audit-log-id") long auditLogId) {
        return ApiResponse.success(service.detail(auditLogId));
    }

    /** API-IAM-51 CSV 내보내기(IAM-06.03) — ADMIN, 200 text/csv */
    @PostMapping("/core/audit-logs/export")
    public ResponseEntity<byte[]> export(@RequestBody(required = false) ExportAuditLogsRequest request) {
        ExportAuditLogsRequest req = request == null
                ? new ExportAuditLogsRequest(null, null, null, null, null, null, null, null, null) : request;
        byte[] body = service.exportCsv(req).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
                .body(body);
    }
}
