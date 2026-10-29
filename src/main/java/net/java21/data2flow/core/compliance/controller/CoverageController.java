package net.java21.data2flow.core.compliance.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.compliance.dto.CoverageDtos.CoverageView;
import net.java21.data2flow.core.compliance.service.CoverageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 모니터링 커버리지 점검(CMP-03): API-CMP-25 */
@RestController
public class CoverageController {

    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    /** API-CMP-25 — CMP_READ. siteId는 사이트가 아니어도 된다(그 공간과 하위를 본다) */
    @GetMapping("/core/compliance/coverage")
    public ApiResponse<CoverageView> check(@RequestParam(required = false) String siteId, @RequestParam(required = false) String profileId) {
        return ApiResponse.success(service.check(siteId, profileId));
    }
}
