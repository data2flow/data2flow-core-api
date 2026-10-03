package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SiteSummaryResponse;
import net.java21.data2flow.core.space.service.SiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 사이트 요약(API-DEV-25, DEV-10.03) — DEV_READ */
@RestController
public class SiteController {

    private final SiteService service;

    public SiteController(SiteService service) {
        this.service = service;
    }

    @GetMapping("/core/sites/summary")
    public ApiResponse<List<SiteSummaryResponse>> summary() {
        return ApiResponse.success(service.summary());
    }
}
