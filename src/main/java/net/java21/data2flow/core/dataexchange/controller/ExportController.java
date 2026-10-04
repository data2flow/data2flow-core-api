package net.java21.data2flow.core.dataexchange.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportCreatedResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportJobResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportRequest;
import net.java21.data2flow.core.dataexchange.service.ExportService;
import net.java21.data2flow.core.dataexchange.service.ExportService.Download;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 내보내기 작업 API(design/api/TSD-api.md §2 API-TSD-20~22, TSD-04.01) — TS_EXPORT(ANALYST 이상) */
@RestController
public class ExportController {

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    /** API-TSD-20 — 동기면 200 {mode: SYNC, downloadUrl}, 비동기면 202 + Location {mode: ASYNC, jobId, estimatedRows} */
    @PostMapping("/core/exports")
    public ResponseEntity<ApiResponse<ExportCreatedResponse>> create(@RequestBody ExportRequest request) {
        ExportCreatedResponse created = service.create(request);
        if ("SYNC".equals(created.mode())) {
            return ResponseEntity.ok(ApiResponse.success(created));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/core/exports/" + created.jobId()))
                .body(ApiResponse.success(created));
    }

    /** API-TSD-21 목록 */
    @GetMapping("/core/exports")
    public ListApiResponse<ExportJobResponse> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    /** API-TSD-21 상세 */
    @GetMapping("/core/exports/{export-id}")
    public ApiResponse<ExportJobResponse> get(@PathVariable("export-id") long exportId) {
        return ApiResponse.success(service.get(exportId));
    }

    /** API-TSD-22 취소 */
    @PostMapping("/core/exports/{export-id}/cancel")
    public ApiResponse<ExportJobResponse> cancel(@PathVariable("export-id") long exportId) {
        return ApiResponse.success(service.cancel(exportId));
    }

    /** 결과 파일(서명 링크 1시간, BR-TSD-14). 응답 본문은 파일 그대로 */
    @GetMapping("/core/exports/{export-id}/file")
    public ResponseEntity<InputStreamResource> file(@PathVariable("export-id") long exportId,
                                                    @RequestParam(required = false) Long expires,
                                                    @RequestParam(required = false) String signature) {
        Download d = service.download(exportId, expires, signature);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType(d.contentType()));
        if (d.bytes() >= 0) {
            b.contentLength(d.bytes());
        }
        return b.body(new InputStreamResource(d.content()));
    }
}
