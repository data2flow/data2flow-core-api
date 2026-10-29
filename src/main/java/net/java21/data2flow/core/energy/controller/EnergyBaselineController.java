package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.Accepted;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.BaselineView;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.CreateVersionRequest;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.VersionDetail;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.VersionView;
import net.java21.data2flow.core.energy.service.EnergyBaselineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기준선(ENE-01.04, API-ENE-12): 조회 ENE_READ, 학습·적용 ENE_ANALYZE */
@RestController
public class EnergyBaselineController {

    private final EnergyBaselineService service;

    public EnergyBaselineController(EnergyBaselineService service) {
        this.service = service;
    }

    @GetMapping("/core/energy/baselines")
    public ListApiResponse<BaselineView> list(@RequestParam(required = false) String scopeSpaceId, @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.list(scopeSpaceId, page, size);
    }

    @GetMapping("/core/energy/baselines/{baselineId}/versions")
    public ListApiResponse<VersionView> versions(@PathVariable String baselineId, @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return service.versions(baselineId, page, size);
    }

    @GetMapping("/core/energy/baselines/{baselineId}/versions/{versionId}")
    public ApiResponse<VersionDetail> version(@PathVariable String baselineId, @PathVariable String versionId) {
        return ApiResponse.success(service.version(baselineId, versionId));
    }

    /** 비동기 학습(analytics API-ENE-81) → 202 {jobId, versionId}. 결과는 EVT-ENE-02로 READY·FAILED */
    @PostMapping("/core/energy/baselines/versions")
    public ResponseEntity<ApiResponse<Accepted>> create(@RequestBody(required = false) CreateVersionRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.create(body)));
    }

    @PostMapping("/core/energy/baselines/versions/{versionId}/activate")
    public ApiResponse<VersionView> activate(@PathVariable String versionId) {
        return ApiResponse.success(service.activate(versionId));
    }
}
