package net.java21.data2flow.core.annotation.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.annotation.dto.AnnotationDtos.AnnotationResponse;
import net.java21.data2flow.core.annotation.dto.AnnotationDtos.CreateAnnotationRequest;
import net.java21.data2flow.core.annotation.service.AnnotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** 시계열 주석(TSD-01.04): API-TSD-06 목록, API-TSD-07 작성·삭제 */
@RestController
public class AnnotationController {

    private final AnnotationService service;

    public AnnotationController(AnnotationService service) {
        this.service = service;
    }

    /** API-TSD-06 주석 목록 — TS_READ, 200. from·to 필수(ISO-8601 UTC), types는 쉼표 목록(USER = 수동) */
    @GetMapping("/core/annotations")
    public ListApiResponse<AnnotationResponse> list(@RequestParam(required = false) String deviceId,
                                                    @RequestParam(required = false) String spaceId,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(required = false) List<String> types,
                                                    @RequestParam(required = false) String metric,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return service.list(deviceId, spaceId, from, to, types, metric, page, size);
    }

    /** API-TSD-07 수동 주석 — DEV_PLACE(OPERATOR+), 201 + Location */
    @PostMapping("/core/annotations")
    @Idempotent
    public ResponseEntity<ApiResponse<AnnotationResponse>> create(@Valid @RequestBody CreateAnnotationRequest request) {
        AnnotationResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/annotations/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-TSD-07 삭제 — 작성자·ADMIN, 204(남의 것 403 ANNOTATION_FORBIDDEN) */
    @DeleteMapping("/core/annotations/{annotation-id}")
    public ResponseEntity<Void> delete(@PathVariable("annotation-id") long annotationId) {
        service.delete(annotationId);
        return ResponseEntity.noContent().build();
    }
}
