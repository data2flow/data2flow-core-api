package net.java21.data2flow.core.dashboard.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.HomeSummaryResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.IngestMonitorResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.PreferencesResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.RecentRequest;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.RecentResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.SpaceOverviewResponse;
import net.java21.data2flow.core.dashboard.service.HomeSummaryService;
import net.java21.data2flow.core.dashboard.service.IngestFlowService;
import net.java21.data2flow.core.dashboard.service.PreferencesService;
import net.java21.data2flow.core.dashboard.service.SpaceOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 홈·공간 요약·수집 흐름·내 화면 설정(DSH-01·02·03·07, API-DSH-01·02·05·12) */
@RestController
public class DashboardController {

    private final HomeSummaryService home;
    private final SpaceOverviewService overview;
    private final IngestFlowService ingest;
    private final PreferencesService preferences;

    public DashboardController(HomeSummaryService home, SpaceOverviewService overview, IngestFlowService ingest,
                               PreferencesService preferences) {
        this.home = home;
        this.overview = overview;
        this.ingest = ingest;
        this.preferences = preferences;
    }

    /** API-DSH-01 홈 요약 — VIEWER 이상, 공간 범위 필터 */
    @GetMapping("/core/home/summary")
    public ApiResponse<HomeSummaryResponse> homeSummary() {
        return ApiResponse.success(home.summary());
    }

    /** API-DSH-02 공간 요약 — VIEWER 이상, 범위 밖·없는 공간 404 SPACE_NOT_FOUND */
    @GetMapping("/core/spaces/{space-id}/overview")
    public ApiResponse<SpaceOverviewResponse> spaceOverview(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(overview.overview(spaceId));
    }

    /** API-DSH-05 수집 흐름 스냅샷 — OPERATOR 이상(INGEST_READ) */
    @GetMapping("/core/monitoring/ingest")
    public ApiResponse<IngestMonitorResponse> ingestMonitor(@RequestParam(value = "window", required = false) String window) {
        return ApiResponse.success(ingest.snapshot(window));
    }

    /** API-DSH-12 내 화면 설정 조회 — 본인 */
    @GetMapping("/core/accounts/me/preferences")
    public ApiResponse<PreferencesResponse> myPreferences() {
        return ApiResponse.success(preferences.get());
    }

    /** API-DSH-12 내 화면 설정 저장(온 키만, baseVersion) — 본인 */
    @PutMapping("/core/accounts/me/preferences")
    public ApiResponse<PreferencesResponse> updateMyPreferences(@RequestBody JsonNode body) {
        return ApiResponse.success(preferences.update(body));
    }

    /** API-DSH-12 최근 본 항목 기록 — 본인. 같은 항목은 맨 앞으로(결과가 같아 멱등) */
    @PostMapping("/core/accounts/me/recent")
    public ApiResponse<RecentResponse> recordRecent(@RequestBody RecentRequest request) {
        return ApiResponse.success(preferences.recordRecent(request));
    }
}
