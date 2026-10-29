package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ActionRequest;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ActionView;
import net.java21.data2flow.core.compliance.dto.CorrectiveActionDtos.ExcursionDetail;
import net.java21.data2flow.core.compliance.service.CorrectiveActionService;
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

/** 개선 조치(CMP-02.05): API-CMP-23·24 */
@RestController
public class CorrectiveActionController {

    private final CorrectiveActionService service;

    public CorrectiveActionController(CorrectiveActionService service) {
        this.service = service;
    }

    /** API-CMP-23 기록 — CMP_ACTION, 201 + Location */
    @PostMapping("/core/compliance/excursions/{excursion-id}/actions")
    @Idempotent
    public ResponseEntity<ApiResponse<ActionView>> create(@PathVariable("excursion-id") long excursionId,
                                                          @RequestBody(required = false) ActionRequest request) {
        ActionView view = service.create(excursionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/compliance/actions/" + view.actionId()))
                .body(ApiResponse.success(view));
    }

    /** API-CMP-23 수정 — CMP_ACTION */
    @PutMapping("/core/compliance/actions/{action-id}")
    public ApiResponse<ActionView> update(@PathVariable("action-id") long actionId, @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.success(service.update(actionId, request));
    }

    /** API-CMP-23 삭제 — CMP_ACTION */
    @DeleteMapping("/core/compliance/actions/{action-id}")
    public ResponseEntity<Void> delete(@PathVariable("action-id") long actionId) {
        service.delete(actionId);
        return ResponseEntity.noContent().build();
    }

    /** 이탈 구간과 조치 — CMP_READ(문서에 없던 조회. UI-CMP-10이 쓴다) */
    @GetMapping("/core/compliance/excursions/{excursion-id}")
    public ApiResponse<ExcursionDetail> excursion(@PathVariable("excursion-id") long excursionId) {
        return ApiResponse.success(service.excursion(excursionId));
    }

    /** 조치 하나 — CMP_READ(문서에 없던 조회. 편집 화면이 쓴다) */
    @GetMapping("/core/compliance/actions/{action-id}")
    public ApiResponse<ActionView> get(@PathVariable("action-id") long actionId) {
        return ApiResponse.success(service.get(actionId));
    }

    /** API-CMP-24 목록 — CMP_READ. excursionId를 주면 from·to 없이 그 구간의 조치만 */
    @GetMapping("/core/compliance/actions")
    public ListApiResponse<ActionView> list(@RequestParam(required = false) String spaceId, @RequestParam(required = false) String excursionId,
                                            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String cause, @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return service.list(spaceId, excursionId, from, to, cause, page, size);
    }
}
