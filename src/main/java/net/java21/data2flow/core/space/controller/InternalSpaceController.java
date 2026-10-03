package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.InternalSpaceDeviceResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.ModeResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsResponse;
import net.java21.data2flow.core.space.service.DeviceRelationService;
import net.java21.data2flow.core.space.service.SpaceSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공간 내부 API(ADR-021: 토큰 없음·ClusterIP, 호출자는 {@code X-CALLER-SERVICE}로 표시). 조직은 공간 행에서 정하고 배포 조직(ADR-030) 밖은 404.
 */
@RestController
public class InternalSpaceController {

    private final SpaceSettingsService settings;
    private final DeviceRelationService relations;

    public InternalSpaceController(SpaceSettingsService settings, DeviceRelationService relations) {
        this.settings = settings;
        this.relations = relations;
    }

    /** API-DEV-126 운영 모드(flow-engine·analytics) */
    @GetMapping("/internal/core/spaces/{space-id}/mode")
    public ApiResponse<ModeResponse> mode(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(settings.internalMode(spaceId));
    }

    /** API-DEV-126 유효 목표 범위 */
    @GetMapping("/internal/core/spaces/{space-id}/targets")
    public ApiResponse<TargetsResponse> targets(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(settings.internalTargets(spaceId));
    }

    /** API-DEV-128 공간과 관계가 있는 기기(flow-engine·action, 조직 범위) */
    @GetMapping("/internal/core/spaces/{space-id}/devices")
    public ApiResponse<List<InternalSpaceDeviceResponse>> devices(@PathVariable("space-id") long spaceId,
                                                                  @RequestParam(required = false) String relation,
                                                                  @RequestParam(required = false) String capability,
                                                                  @RequestParam(required = false) Boolean includeDescendants) {
        return ApiResponse.success(relations.internalSpaceDevices(spaceId, relation, capability, includeDescendants));
    }
}
