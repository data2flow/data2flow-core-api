package net.java21.data2flow.core.dataexchange.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportScheduleResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetRequest;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetResponse;
import net.java21.data2flow.core.dataexchange.service.ExportScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 정기 내보내기(design/api/TSD-api.md API-TSD-23·56, TSD-04.03·07.02) — TS_EXPORT(본인), ADMIN(전체) */
@RestController
public class ExportScheduleController {

    private final ExportScheduleService service;

    public ExportScheduleController(ExportScheduleService service) {
        this.service = service;
    }

    @GetMapping("/core/export-schedules")
    public ListApiResponse<ExportScheduleResponse> list(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @GetMapping("/core/export-schedules/{export-schedule-id}")
    public ApiResponse<ExportScheduleResponse> get(@PathVariable("export-schedule-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** 201 + Location */
    @PostMapping("/core/export-schedules")
    public ResponseEntity<ApiResponse<ExportScheduleResponse>> create(@RequestBody JsonNode body) {
        ExportScheduleResponse created = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/export-schedules/" + created.id())).body(ApiResponse.success(created));
    }

    /** 온 키만, baseVersion */
    @PatchMapping("/core/export-schedules/{export-schedule-id}")
    public ApiResponse<ExportScheduleResponse> update(@PathVariable("export-schedule-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(id, body));
    }

    @DeleteMapping("/core/export-schedules/{export-schedule-id}")
    public ResponseEntity<Void> delete(@PathVariable("export-schedule-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-TSD-56 — 성공 200 {ok, steps}, 실패 502 EXPORT_TARGET_UNWRITABLE + response{ok:false, steps} */
    @PostMapping("/core/export-schedules/test-target")
    public ApiResponse<TestTargetResponse> testTarget(@RequestBody TestTargetRequest request) {
        return ApiResponse.success(service.testTarget(request));
    }
}
