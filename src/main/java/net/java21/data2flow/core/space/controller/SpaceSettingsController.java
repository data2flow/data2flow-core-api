package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.ModeResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.ScheduleRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.ScheduleResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsResponse;
import net.java21.data2flow.core.space.service.SpaceSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 공간 목표 범위(DEV-01.04)·운영 시간표(DEV-11.01)·운영 모드 조회(API-DEV-08) */
@RestController
public class SpaceSettingsController {

    private final SpaceSettingsService service;

    public SpaceSettingsController(SpaceSettingsService service) {
        this.service = service;
    }

    /** 목표 조회(사전 작업으로 추가) — DEV_READ, 200 */
    @GetMapping("/core/spaces/{space-id}/targets")
    public ApiResponse<TargetsResponse> targets(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(service.targets(spaceId));
    }

    /** API-DEV-06 목표 저장 — DEV_ADMIN, 200 */
    @PutMapping("/core/spaces/{space-id}/targets")
    public ApiResponse<TargetsResponse> replaceTargets(@PathVariable("space-id") long spaceId, @RequestBody TargetsRequest request) {
        return ApiResponse.success(service.replaceTargets(spaceId, request));
    }

    /** 시간표 조회(사전 작업으로 추가) — DEV_READ, 200 */
    @GetMapping("/core/spaces/{space-id}/schedule")
    public ApiResponse<ScheduleResponse> schedule(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(service.schedule(spaceId));
    }

    /** API-DEV-07 시간표 저장 — DEV_ADMIN, 200 */
    @PutMapping("/core/spaces/{space-id}/schedule")
    public ApiResponse<ScheduleResponse> replaceSchedule(@PathVariable("space-id") long spaceId,
                                                         @RequestBody ScheduleRequest request) {
        return ApiResponse.success(service.replaceSchedule(spaceId, request));
    }

    /** API-DEV-08 운영 모드 조회 — DEV_READ, 200 */
    @GetMapping("/core/spaces/{space-id}/mode")
    public ApiResponse<ModeResponse> mode(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(service.mode(spaceId));
    }
}
