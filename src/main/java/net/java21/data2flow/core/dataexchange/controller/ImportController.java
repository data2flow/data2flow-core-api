package net.java21.data2flow.core.dataexchange.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ImportErrorResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ImportJobResponse;
import net.java21.data2flow.core.dataexchange.service.ImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 과거 데이터 가져오기(design/api/TSD-api.md API-TSD-30~32, TSD-04.02) — TS_IMPORT(ADMIN·INTEGRATOR). 처리는 비동기(202) */
@RestController
public class ImportController {

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    /** API-TSD-30 CSV(multipart: file, mapping, dryRun, originLabel) */
    @PostMapping(path = "/core/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportJobResponse>> createCsv(@RequestPart(name = "file", required = false) MultipartFile file,
                                                                    @RequestParam(required = false) String mapping,
                                                                    @RequestParam(required = false) Boolean dryRun,
                                                                    @RequestParam(required = false) String originLabel) {
        return accepted(service.createCsv(file, mapping, dryRun, originLabel));
    }

    /** API-TSD-30 InfluxDB(JSON) */
    @PostMapping(path = "/core/imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ImportJobResponse>> createInflux(@RequestBody JsonNode body) {
        return accepted(service.createInflux(body));
    }

    @GetMapping("/core/imports")
    public ListApiResponse<ImportJobResponse> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    /** API-TSD-31 */
    @GetMapping("/core/imports/{import-id}")
    public ApiResponse<ImportJobResponse> get(@PathVariable("import-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** API-TSD-31 오류 목록 */
    @GetMapping("/core/imports/{import-id}/errors")
    public ListApiResponse<ImportErrorResponse> errors(@PathVariable("import-id") long id, @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return service.errors(id, page, size);
    }

    /** API-TSD-32 실제 실행 — 202 */
    @PostMapping("/core/imports/{import-id}/run")
    public ResponseEntity<ApiResponse<ImportJobResponse>> run(@PathVariable("import-id") long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.run(id)));
    }

    private static ResponseEntity<ApiResponse<ImportJobResponse>> accepted(ImportJobResponse job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/core/imports/" + job.id())).body(ApiResponse.success(job));
    }
}
