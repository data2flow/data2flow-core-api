package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.InternalCriteria;
import net.java21.data2flow.core.compliance.service.ComplianceApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 환경 기준 내부 API(ADR-021: 토큰 없음·ClusterIP). 조직은 공간 행에서 정하고 배포 조직(ADR-030) 밖은 404 */
@RestController
public class InternalComplianceController {

    private final ComplianceApplicationService applications;

    public InternalComplianceController(ComplianceApplicationService applications) {
        this.applications = applications;
    }

    /** API-CMP-26 유효 기준(flow-engine·analytics) */
    @GetMapping("/internal/core/compliance/spaces/{space-id}/effective-criteria")
    public ApiResponse<InternalCriteria> effectiveCriteria(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(applications.internalCriteria(spaceId));
    }
}
