package net.java21.data2flow.core.modelexchange.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ExportJobCreated;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ExportJobResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ImportResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.NgsiPushRequest;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.NgsiPushResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.StandardExportRequest;
import net.java21.data2flow.core.modelexchange.service.ModelExchangeService;
import net.java21.data2flow.core.modelexchange.service.StandardExportService;
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
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;

/** 모델 가져오기·내보내기(API-DEV-44·45, DEV-03.04), 표준 형식 내보내기·NGSI-LD 주기 전송(API-DEV-135·136, DEV-13.04) */
@RestController
public class ModelExchangeController {

    private final ModelExchangeService models;
    private final StandardExportService exports;

    public ModelExchangeController(ModelExchangeService models, StandardExportService exports) {
        this.models = models;
        this.exports = exports;
    }

    /** API-DEV-44 — DEV_READ. format=data2flow(기본)|dtdl */
    @GetMapping("/core/device-models/{model-id}/export")
    public ApiResponse<JsonNode> export(@PathVariable("model-id") long modelId, @RequestParam(required = false) String format) {
        return ApiResponse.success(models.export(modelId, format));
    }

    /** API-DEV-45 — DEV_ADMIN. multipart file + format?·createMissingMetrics(기본 true)·dryRun(기본 false). 만들면 201 */
    @PostMapping(value = "/core/device-models/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportResponse>> importModel(@RequestPart("file") MultipartFile file,
                                                                   @RequestParam(required = false) String format,
                                                                   @RequestParam(required = false) Boolean createMissingMetrics,
                                                                   @RequestParam(required = false) Boolean dryRun) throws IOException {
        ImportResponse res = models.importModel(file.getBytes(), format, createMissingMetrics, Boolean.TRUE.equals(dryRun));
        if (res.dryRun()) {
            return ResponseEntity.ok(ApiResponse.success(res));
        }
        return ResponseEntity.created(URI.create("/api/v1/core/device-models/" + res.model().id())).body(ApiResponse.success(res));
    }

    /** API-DEV-135 — DEV_ADMIN, 202 {jobId} */
    @PostMapping("/core/devices/export-standard")
    public ResponseEntity<ApiResponse<ExportJobCreated>> exportStandard(@Valid @RequestBody StandardExportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(exports.export(request)));
    }

    @GetMapping("/core/export-jobs/{job-id}")
    public ApiResponse<ExportJobResponse> job(@PathVariable("job-id") long jobId) {
        return ApiResponse.success(exports.job(jobId));
    }

    /** 내보낸 파일(downloadUrl) — DEV_ADMIN */
    @GetMapping("/core/export-jobs/{job-id}/file")
    public ResponseEntity<byte[]> file(@PathVariable("job-id") long jobId) {
        Blob blob = exports.file(jobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + blob.fileName() + "\"").body(blob.data());
    }

    /** API-DEV-136 — DEV_ADMIN */
    @GetMapping("/core/ngsi-pushes")
    public ItemsResponse<NgsiPushResponse> pushes() {
        return ItemsResponse.of(exports.pushes());
    }

    @PostMapping("/core/ngsi-pushes")
    public ResponseEntity<ApiResponse<NgsiPushResponse>> createPush(@Valid @RequestBody NgsiPushRequest request) {
        NgsiPushResponse created = exports.createPush(request);
        return ResponseEntity.created(URI.create("/api/v1/core/ngsi-pushes/" + created.id())).body(ApiResponse.success(created));
    }

    @DeleteMapping("/core/ngsi-pushes/{ngsi-push-id}")
    public ResponseEntity<Void> deletePush(@PathVariable("ngsi-push-id") long id) {
        exports.deletePush(id);
        return ResponseEntity.noContent().build();
    }
}
