package net.java21.data2flow.core.devicegroup.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceSummaryResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.CreateGroupRequest;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.GroupResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.MembersAddedResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.MembersRemovedResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.MembersRequest;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.PreviewRequest;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.PreviewResponse;
import net.java21.data2flow.core.devicegroup.service.DeviceGroupService;
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

/** 기기 그룹(design/api/DEV-api.md §3: API-DEV-30~35, DEV-06.01·06.02) */
@RestController
public class DeviceGroupController {

    private final DeviceGroupService service;

    public DeviceGroupController(DeviceGroupService service) {
        this.service = service;
    }

    /** API-DEV-34 목록 — DEV_READ, 200 */
    @GetMapping("/core/device-groups")
    public ListApiResponse<GroupResponse> list(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                                               @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(q, type, page, size);
    }

    /** 그룹 상세(웹 UI-DEV-11이 쓰는 경로, 문서 추가 필요) — DEV_READ, 200 */
    @GetMapping("/core/device-groups/{group-id}")
    public ApiResponse<GroupResponse> get(@PathVariable("group-id") long groupId) {
        return ApiResponse.success(service.get(groupId));
    }

    /** API-DEV-34 구성원 — DEV_READ, 200 */
    @GetMapping("/core/device-groups/{group-id}/members")
    public ListApiResponse<DeviceSummaryResponse> members(@PathVariable("group-id") long groupId,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return service.members(groupId, page, size);
    }

    /** API-DEV-30 생성 — DEV_ADMIN, 201 + Location */
    @PostMapping("/core/device-groups")
    @Idempotent
    public ResponseEntity<ApiResponse<GroupResponse>> create(@Valid @RequestBody CreateGroupRequest request) {
        GroupResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/device-groups/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-31 부분 수정 — DEV_ADMIN, 200 */
    @PatchMapping("/core/device-groups/{group-id}")
    public ApiResponse<GroupResponse> update(@PathVariable("group-id") long groupId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(groupId, body));
    }

    /** API-DEV-32 삭제 — DEV_ADMIN, 204 */
    @DeleteMapping("/core/device-groups/{group-id}")
    public ResponseEntity<Void> delete(@PathVariable("group-id") long groupId) {
        service.delete(groupId);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-33 조건 미리 보기 — DEV_READ, 200 */
    @PostMapping("/core/device-groups/preview")
    public ApiResponse<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        return ApiResponse.success(service.preview(request.criteria()));
    }

    /** API-DEV-35 구성원 추가(정적) — DEV_ADMIN, 200 */
    @PostMapping("/core/device-groups/{group-id}/members/add")
    @Idempotent
    public ApiResponse<MembersAddedResponse> add(@PathVariable("group-id") long groupId, @Valid @RequestBody MembersRequest request) {
        return ApiResponse.success(service.addMembers(groupId, request.deviceIds()));
    }

    /** API-DEV-35 구성원 제거(정적) — DEV_ADMIN, 200 */
    @PostMapping("/core/device-groups/{group-id}/members/remove")
    @Idempotent
    public ApiResponse<MembersRemovedResponse> remove(@PathVariable("group-id") long groupId,
                                                      @Valid @RequestBody MembersRequest request) {
        return ApiResponse.success(service.removeMembers(groupId, request.deviceIds()));
    }
}
