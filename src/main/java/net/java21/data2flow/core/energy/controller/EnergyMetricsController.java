package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.energy.dto.EnergyMetricDtos.Breakdown;
import net.java21.data2flow.core.energy.service.EnergyMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 에너지 대시보드(ENE-01.03): API-ENE-10 지표, API-ENE-11 하위 단위·순위 — ENE_READ */
@RestController
public class EnergyMetricsController {

    private final EnergyMetricsService metrics;

    public EnergyMetricsController(EnergyMetricsService metrics) {
        this.metrics = metrics;
    }

    /** API-ENE-10 — scopeSpaceId가 없으면 포트폴리오(허용 범위 전체) */
    @GetMapping("/core/energy/metrics")
    public ApiResponse<JsonNode> metrics(@RequestParam(required = false) String scopeSpaceId, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to, @RequestParam(required = false) String granularity,
                                         @RequestParam(required = false) String compare) {
        return ApiResponse.success(metrics.metrics(scopeSpaceId, from, to, granularity, compare));
    }

    /** API-ENE-11 — 직전 같은 길이 기간 대비 diffRatio, 정렬 KWH|KRW|EUI|DIFF(내림차순) */
    @GetMapping("/core/energy/breakdown")
    public ApiResponse<Breakdown> breakdown(@RequestParam(required = false) String scopeSpaceId, @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to, @RequestParam(required = false) String by,
                                            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(metrics.breakdown(scopeSpaceId, from, to, by, sort, page, size));
    }
}
