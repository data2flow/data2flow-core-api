package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.core.space.dto.SpaceDtos.CreateSpaceRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.MoveSpaceRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.NotEmptyResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceDetailResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceNode;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceResponse;
import net.java21.data2flow.core.space.service.SpaceNotEmptyException;
import net.java21.data2flow.core.space.service.SpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;

/** 공간 트리(DEV-01.01·01.02·10.01) — 조회 DEV_READ(VIEWER+), 바꾸기 DEV_ADMIN(INTEGRATOR+) */
@RestController
public class SpaceController {

    private final SpaceService service;
    private final ErrorMessages messages;

    public SpaceController(SpaceService service, ErrorMessages messages) {
        this.service = service;
        this.messages = messages;
    }

    /** API-DEV-01 공간 트리(권한 범위, 범위 밖 조상은 accessible=false) — 200 */
    @GetMapping("/core/spaces")
    public ApiResponse<List<SpaceNode>> tree(@RequestParam(required = false) String rootId,
                                             @RequestParam(required = false) Integer depth,
                                             @RequestParam(required = false) String include,
                                             @RequestParam(required = false) Boolean virtual) {
        return ApiResponse.success(service.tree(rootId, depth, include, virtual));
    }

    /** 공간 상세(사전 작업으로 추가한 API) — 200 */
    @GetMapping("/core/spaces/{space-id}")
    public ApiResponse<SpaceDetailResponse> detail(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(service.detail(spaceId));
    }

    /** API-DEV-02 공간 만들기 — 201 + Location */
    @PostMapping("/core/spaces")
    @Idempotent
    public ResponseEntity<ApiResponse<SpaceResponse>> create(@RequestBody CreateSpaceRequest request) {
        SpaceResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/spaces/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-03 속성 수정(온 키만, baseVersion) — 200 */
    @PatchMapping("/core/spaces/{space-id}")
    public ApiResponse<SpaceResponse> update(@PathVariable("space-id") long spaceId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(spaceId, body));
    }

    /** API-DEV-04 이동 — 200(새 path) */
    @PostMapping("/core/spaces/{space-id}/move")
    @Idempotent
    public ApiResponse<SpaceResponse> move(@PathVariable("space-id") long spaceId, @RequestBody MoveSpaceRequest request) {
        return ApiResponse.success(service.move(spaceId, request));
    }

    /** API-DEV-05 삭제 — 204. 막히면 409 SPACE_NOT_EMPTY + response.blockers */
    @DeleteMapping("/core/spaces/{space-id}")
    public ResponseEntity<Void> delete(@PathVariable("space-id") long spaceId) {
        service.delete(spaceId);
        return ResponseEntity.noContent().build();
    }

    /** 409 SPACE_NOT_EMPTY: 공통 오류 머리 + {@code response.blockers{children, devices, markers, workOrders}} */
    @ExceptionHandler(SpaceNotEmptyException.class)
    public ResponseEntity<ApiResponse<NotEmptyResponse>> notEmpty(SpaceNotEmptyException ex) {
        ApiHeader header = ApiHeader.failure(ex.getErrorCode().code(), messages.resolve(ex.getErrorCode()));
        return ResponseEntity.status(ex.getErrorCode().httpStatus()).body(new ApiResponse<>(header, new NotEmptyResponse(ex.blockers())));
    }
}
