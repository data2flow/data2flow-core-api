package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.RunRequested;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.RunView;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.ScheduleRequest;
import net.java21.data2flow.core.compliance.dto.ReportScheduleDtos.ScheduleView;
import net.java21.data2flow.core.compliance.service.ReportScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 정기 발행(CMP-02.04): API-CMP-21·22 */
@RestController
public class ReportScheduleController {

    private final ReportScheduleService service;

    public ReportScheduleController(ReportScheduleService service) {
        this.service = service;
    }

    /** API-CMP-21 목록 — CMP_READ */
    @GetMapping("/core/compliance/report-schedules")
    public ListApiResponse<ScheduleView> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    /** API-CMP-21 만들기 — CMP_REPORT, 201 + Location */
    @PostMapping("/core/compliance/report-schedules")
    @Idempotent
    public ResponseEntity<ApiResponse<ScheduleView>> create(@RequestBody(required = false) ScheduleRequest request) {
        ScheduleView view = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/compliance/report-schedules/" + view.reportScheduleId()))
                .body(ApiResponse.success(view));
    }

    /** 일정 하나 — CMP_READ(문서에 없던 조회. 편집 화면이 쓴다) */
    @GetMapping("/core/compliance/report-schedules/{report-schedule-id}")
    public ApiResponse<ScheduleView> get(@PathVariable("report-schedule-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** API-CMP-21 수정 — CMP_REPORT */
    @PutMapping("/core/compliance/report-schedules/{report-schedule-id}")
    public ApiResponse<ScheduleView> update(@PathVariable("report-schedule-id") long id, @RequestBody(required = false) ScheduleRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    /** API-CMP-21 삭제 — CMP_REPORT */
    @DeleteMapping("/core/compliance/report-schedules/{report-schedule-id}")
    public ResponseEntity<Void> delete(@PathVariable("report-schedule-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-CMP-21 지금 실행 — CMP_REPORT, 202 */
    @PostMapping("/core/compliance/report-schedules/{report-schedule-id}/run-now")
    @Idempotent
    public ResponseEntity<ApiResponse<RunRequested>> runNow(@PathVariable("report-schedule-id") long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.runNow(id)));
    }

    /** API-CMP-22 실행 이력 — CMP_READ */
    @GetMapping("/core/compliance/report-schedules/{report-schedule-id}/runs")
    public ListApiResponse<RunView> runs(@PathVariable("report-schedule-id") long id, @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return service.runs(id, page, size);
    }
}
