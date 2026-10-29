package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.CreateReportRequest;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.FileLink;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.ReportCreated;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.ReportView;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.RevisionView;
import net.java21.data2flow.core.compliance.dto.ComplianceReportDtos.VerifyResult;
import net.java21.data2flow.core.compliance.service.ComplianceReportService;
import net.java21.data2flow.core.compliance.service.ComplianceReportService.Content;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 준수 리포트(CMP-02.02·02.04·02.06): API-CMP-16~20a */
@RestController
public class ComplianceReportController {

    private final ComplianceReportService service;

    public ComplianceReportController(ComplianceReportService service) {
        this.service = service;
    }

    /** API-CMP-16 생성 요청 — CMP_REPORT, 202 + Location */
    @PostMapping("/core/compliance/reports")
    @Idempotent
    public ResponseEntity<ApiResponse<ReportCreated>> create(@RequestBody(required = false) CreateReportRequest request) {
        ReportCreated created = service.create(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/core/compliance/reports/" + created.reportId() + "/revisions/" + created.revisionNo()))
                .body(ApiResponse.success(created));
    }

    /** API-CMP-17 목록 — CMP_READ. from·to는 기간이 겹치는 리포트 */
    @GetMapping("/core/compliance/reports")
    public ListApiResponse<ReportView> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String spaceId, @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return service.list(type, status, spaceId, from, to, page, size);
    }

    /** API-CMP-17 상세(판 모두) — CMP_READ */
    @GetMapping("/core/compliance/reports/{report-id}")
    public ApiResponse<ReportView> get(@PathVariable("report-id") long reportId) {
        return ApiResponse.success(service.get(reportId));
    }

    /** API-CMP-17 판 — CMP_READ */
    @GetMapping("/core/compliance/reports/{report-id}/revisions/{revision-no}")
    public ApiResponse<RevisionView> revision(@PathVariable("report-id") long reportId, @PathVariable("revision-no") int revisionNo) {
        return ApiResponse.success(service.revision(reportId, revisionNo));
    }

    /** API-CMP-18 발행 — CMP_REPORT, DRAFT만 */
    @PostMapping("/core/compliance/reports/{report-id}/revisions/{revision-no}/publish")
    @Idempotent
    public ApiResponse<RevisionView> publish(@PathVariable("report-id") long reportId, @PathVariable("revision-no") int revisionNo) {
        return ApiResponse.success(service.publish(reportId, revisionNo));
    }

    /** API-CMP-19 파일 서명 링크(10분) — pdf·preview CMP_READ, csv CMP_REPORT */
    @GetMapping("/core/compliance/reports/{report-id}/revisions/{revision-no}/files/{kind}")
    public ApiResponse<FileLink> file(@PathVariable("report-id") long reportId, @PathVariable("revision-no") int revisionNo,
                                      @PathVariable("kind") String kind) {
        return ApiResponse.success(service.file(reportId, revisionNo, kind));
    }

    /** 서명 링크의 파일 내용. preview는 inline */
    @GetMapping("/core/compliance/reports/{report-id}/revisions/{revision-no}/content/{kind}")
    public ResponseEntity<byte[]> content(@PathVariable("report-id") long reportId, @PathVariable("revision-no") int revisionNo,
                                          @PathVariable("kind") String kind, @RequestParam(required = false) Long expires,
                                          @RequestParam(required = false) String signature) {
        Content c = service.content(reportId, revisionNo, kind, expires, signature);
        ContentDisposition disposition = (c.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(c.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").contentType(MediaType.parseMediaType(c.contentType()))
                .contentLength(c.data().length).body(c.data());
    }

    /** API-CMP-20 해시 검증 — CMP_READ, multipart file(PDF ≤ 50MB) */
    @PostMapping(path = "/core/compliance/reports/verify-hash", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<VerifyResult> verifyHash(@RequestPart(name = "file", required = false) MultipartFile file) {
        return ApiResponse.success(service.verifyHash(file));
    }

    /** API-CMP-20a 판 삭제 — CMP_REPORT, DRAFT·FAILED만 */
    @DeleteMapping("/core/compliance/reports/{report-id}/revisions/{revision-no}")
    public ResponseEntity<Void> delete(@PathVariable("report-id") long reportId, @PathVariable("revision-no") int revisionNo) {
        service.delete(reportId, revisionNo);
        return ResponseEntity.noContent().build();
    }
}
