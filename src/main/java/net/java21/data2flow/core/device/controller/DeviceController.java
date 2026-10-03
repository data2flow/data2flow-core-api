package net.java21.data2flow.core.device.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.BaseVersionRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.CreateDeviceRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceDetailResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceStatusResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceSummaryResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.ImportReport;
import net.java21.data2flow.core.device.dto.DeviceDtos.ModelSuggestion;
import net.java21.data2flow.core.device.dto.DeviceDtos.RejectRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.RejectResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.TagRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.TagResponse;
import net.java21.data2flow.core.device.service.DeviceApprovalService;
import net.java21.data2flow.core.device.service.DeviceImportService;
import net.java21.data2flow.core.device.service.DeviceService;
import net.java21.data2flow.core.device.service.DeviceService.ListQuery;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 기기(design/api/DEV-api.md §2: API-DEV-11~13·15~17·19~21·23·28·29) */
@RestController
public class DeviceController {

    private final DeviceService service;
    private final DeviceApprovalService approvals;
    private final DeviceImportService imports;

    public DeviceController(DeviceService service, DeviceApprovalService approvals, DeviceImportService imports) {
        this.service = service;
        this.approvals = approvals;
        this.imports = imports;
    }

    /** API-DEV-11 목록(DEV-02.01, IAM-04.06) — DEV_READ, 200 */
    @GetMapping("/core/devices")
    public ListApiResponse<DeviceSummaryResponse> list(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) List<String> status,
                                                       @RequestParam(required = false) List<String> connectivity,
                                                       @RequestParam(required = false) List<String> kind,
                                                       @RequestParam(required = false) String modelId,
                                                       @RequestParam(required = false) String spaceId,
                                                       @RequestParam(required = false) Boolean includeDescendants,
                                                       @RequestParam(required = false) String sourceId,
                                                       @RequestParam(required = false) List<String> tag,
                                                       @RequestParam(required = false) String groupId,
                                                       @RequestParam(required = false) Boolean virtual,
                                                       @RequestParam(required = false) String onboarding,
                                                       @RequestParam(required = false) List<String> sort,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return service.list(new ListQuery(q, status, connectivity, kind, modelId, spaceId, includeDescendants, sourceId, tag, groupId,
                virtual, onboarding, sort), page, size);
    }

    /** API-DEV-12 수동 등록(DEV-02.04) — DEV_ADMIN, 201 + Location */
    @PostMapping("/core/devices")
    @Idempotent
    public ResponseEntity<ApiResponse<DeviceDetailResponse>> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceDetailResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/devices/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-23 상세 — DEV_READ, 200 */
    @GetMapping("/core/devices/{device-id}")
    public ApiResponse<DeviceDetailResponse> get(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.get(deviceId));
    }

    /** API-DEV-13 부분 수정 — DEV_ADMIN(이름·모델·주기·종류)·DEV_PLACE(공간·태그), 200 */
    @PatchMapping("/core/devices/{device-id}")
    public ApiResponse<DeviceDetailResponse> update(@PathVariable("device-id") long deviceId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(deviceId, body));
    }

    /** API-DEV-16 활성화(DEV-02.02) — DEV_PLACE, 200 */
    @PostMapping("/core/devices/{device-id}/activate")
    @Idempotent
    public ApiResponse<DeviceStatusResponse> activate(@PathVariable("device-id") long deviceId,
                                                      @Valid @RequestBody BaseVersionRequest request) {
        return ApiResponse.success(service.activate(deviceId, request.baseVersion()));
    }

    /** API-DEV-16 비활성화(DEV-02.02) — DEV_PLACE, 200 */
    @PostMapping("/core/devices/{device-id}/deactivate")
    @Idempotent
    public ApiResponse<DeviceStatusResponse> deactivate(@PathVariable("device-id") long deviceId,
                                                        @Valid @RequestBody BaseVersionRequest request) {
        return ApiResponse.success(service.deactivate(deviceId, request.baseVersion()));
    }

    /** API-DEV-17 삭제 — DEV_ADMIN, 204 */
    @DeleteMapping("/core/devices/{device-id}")
    public ResponseEntity<Void> delete(@PathVariable("device-id") long deviceId,
                                       @RequestParam(required = false) Integer baseVersion) {
        service.delete(deviceId, baseVersion);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-21 태그 일괄(DEV-02.10) — DEV_PLACE, 200 */
    @PostMapping("/core/devices/tag")
    @Idempotent
    public ApiResponse<TagResponse> tag(@Valid @RequestBody TagRequest request) {
        return ApiResponse.success(service.tag(request));
    }

    /**
     * API-DEV-15 일괄 승인(DEV-02.03) — DEV_PLACE, 200. 플랫폼 브로커 기기의 서명 키가 응답에 한 번만 들어 있어
     * 멱등 저장소(응답 본문을 24시간 보관)에 남기지 않으려고 {@code @Idempotent}를 붙이지 않는다. 다시 보내면 baseVersion이 맞지 않아
     * 항목별 DEVICE_STATE_CONFLICT가 되므로 중복 승인은 생기지 않는다.
     */
    @PostMapping("/core/devices/approve")
    public ApiResponse<ApproveResponse> approve(@Valid @RequestBody ApproveRequest request) {
        return ApiResponse.success(approvals.approve(request));
    }

    /** API-DEV-28 거부(BR-DEV-07) — DEV_PLACE, 200 */
    @PostMapping("/core/devices/reject")
    @Idempotent
    public ApiResponse<RejectResponse> reject(@Valid @RequestBody RejectRequest request) {
        return ApiResponse.success(approvals.reject(request));
    }

    /** API-DEV-29 모델 추천 — DEV_PLACE, 200 */
    @GetMapping("/core/devices/{device-id}/model-suggestions")
    public ApiResponse<List<ModelSuggestion>> modelSuggestions(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(approvals.modelSuggestions(deviceId));
    }

    /** API-DEV-19 CSV 가져오기(DEV-02.04) — DEV_ADMIN, 200. dryRun 기본 true */
    @PostMapping("/core/devices/import")
    public ApiResponse<ImportReport> importCsv(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false, defaultValue = "true") boolean dryRun,
                                               @RequestParam(required = false) String mode) throws IOException {
        return ApiResponse.success(imports.importCsv(file.getBytes(), dryRun, mode));
    }

    /** API-DEV-20 CSV 내보내기(AT-DEV-04.4) — DEV_READ, 200 text/csv */
    @GetMapping("/core/devices/export")
    public void export(@RequestParam(required = false) String q, @RequestParam(required = false) List<String> status,
                       @RequestParam(required = false) List<String> connectivity, @RequestParam(required = false) List<String> kind,
                       @RequestParam(required = false) String modelId, @RequestParam(required = false) String spaceId,
                       @RequestParam(required = false) Boolean includeDescendants, @RequestParam(required = false) String sourceId,
                       @RequestParam(required = false) List<String> tag, @RequestParam(required = false) String groupId,
                       @RequestParam(required = false) Boolean virtual, @RequestParam(required = false) String onboarding,
                       @RequestParam(required = false, defaultValue = "csv") String format,
                       HttpServletResponse response) throws IOException {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("format", "INVALID", format)));
        }
        ListQuery query = new ListQuery(q, status, connectivity, kind, modelId, spaceId, includeDescendants, sourceId, tag, groupId,
                virtual, onboarding, null);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"devices.csv\"");
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        imports.exportCsv(query, writer);
    }
}
