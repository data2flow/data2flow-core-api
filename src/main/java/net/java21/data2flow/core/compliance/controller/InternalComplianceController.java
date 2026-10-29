package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.InternalCriteria;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ScoreView;
import net.java21.data2flow.core.compliance.service.ComplianceApplicationService;
import net.java21.data2flow.core.compliance.service.ComplianceScoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 환경 기준 내부 API(ADR-021: 토큰 없음·ClusterIP). 조직은 공간 행에서 정하고 배포 조직(ADR-030) 밖은 404 */
@RestController
public class InternalComplianceController {

    private final ComplianceApplicationService applications;
    private final ComplianceScoreService scores;

    public InternalComplianceController(ComplianceApplicationService applications, ComplianceScoreService scores) {
        this.applications = applications;
        this.scores = scores;
    }

    /** API-CMP-26 유효 기준(flow-engine·analytics) */
    @GetMapping("/internal/core/compliance/spaces/{space-id}/effective-criteria")
    public ApiResponse<InternalCriteria> effectiveCriteria(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(applications.internalCriteria(spaceId));
    }

    /** API-CMP-27 현재 점수(OCC 공개 화면·data2flow-ai). 입력값을 늘 싣는다 */
    @GetMapping("/internal/core/compliance/spaces/{space-id}/score")
    public ApiResponse<ScoreView> score(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(scores.internalScore(spaceId));
    }
}
