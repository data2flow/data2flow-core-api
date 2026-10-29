package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.Accepted;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.CreateReportRequest;
import net.java21.data2flow.core.energy.dto.EnergyReportDtos.ReportView;
import net.java21.data2flow.core.energy.service.EnergyReportService;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
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

import java.nio.charset.StandardCharsets;

/** 월간 에너지 리포트(ENE-01.05, API-ENE-13): 생성 ENE_ANALYZE, 조회·내려받기 ENE_READ */
@RestController
public class EnergyReportController {

    private final EnergyReportService service;

    public EnergyReportController(EnergyReportService service) {
        this.service = service;
    }

    /** 비동기 생성 → 202 {jobId, reportId}. 진행은 상세의 status·progress */
    @PostMapping("/core/energy/reports")
    public ResponseEntity<ApiResponse<Accepted>> create(@RequestBody(required = false) CreateReportRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.create(body)));
    }

    @GetMapping("/core/energy/reports")
    public ListApiResponse<ReportView> list(@RequestParam(required = false) String scopeSpaceId, @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return service.list(scopeSpaceId, page, size);
    }

    @GetMapping("/core/energy/reports/{reportId}")
    public ApiResponse<ReportView> get(@PathVariable String reportId) {
        return ApiResponse.success(service.get(reportId));
    }

    @GetMapping("/core/energy/reports/{reportId}/file")
    public ResponseEntity<byte[]> file(@PathVariable String reportId) {
        Blob blob = service.file(reportId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(blob.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600").body(blob.data());
    }
}
