package net.java21.data2flow.core.board.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.board.dto.BoardDtos.Created;
import net.java21.data2flow.core.board.dto.BoardDtos.DashboardResponse;
import net.java21.data2flow.core.board.dto.BoardDtos.DashboardSummary;
import net.java21.data2flow.core.board.dto.BoardDtos.DefaultDashboardRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.DefaultDashboardResponse;
import net.java21.data2flow.core.board.dto.BoardDtos.ExportedDashboard;
import net.java21.data2flow.core.board.dto.BoardDtos.ImportResult;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkCreated;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkItem;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.SharedDashboard;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetData;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetDataRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetPreviewRequest;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetTypeResponse;
import net.java21.data2flow.core.board.service.AnalysisPinService;
import net.java21.data2flow.core.board.service.DashboardBoardService;
import net.java21.data2flow.core.board.service.ShareLinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;

/** 사용자 정의 대시보드·위젯·공유 링크(design/api/DSH-api.md API-DSH-06·07·09·10·12·13·15, DSH-04·06.03) */
@RestController
public class DashboardBoardController {

    private final DashboardBoardService service;
    private final ShareLinkService shareLinks;
    private final AnalysisPinService pins;

    public DashboardBoardController(DashboardBoardService service, ShareLinkService shareLinks, AnalysisPinService pins) {
        this.pins = pins;
        this.service = service;
        this.shareLinks = shareLinks;
    }

    /** API-DSH-06 목록(tab=mine|shared|favorite) */
    @GetMapping("/core/dashboards")
    public ListApiResponse<DashboardSummary> list(@RequestParam(required = false) String tab, @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(tab, keyword, page, size);
    }

    /** API-DSH-06 상세 */
    @GetMapping("/core/dashboards/{dashboard-id}")
    public ApiResponse<DashboardResponse> get(@PathVariable("dashboard-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** API-DSH-07 생성 — 201 + Location */
    @Idempotent
    @PostMapping("/core/dashboards")
    public ResponseEntity<ApiResponse<Created>> create(@RequestBody JsonNode body) {
        Created created = service.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/dashboards/" + created.id()))
                .body(ApiResponse.success(created));
    }

    /** API-DSH-07 저장(baseVersion) */
    @PutMapping("/core/dashboards/{dashboard-id}")
    public ApiResponse<DashboardResponse> update(@PathVariable("dashboard-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(id, body));
    }

    /** API-DSH-07 삭제 — 204 */
    @DeleteMapping("/core/dashboards/{dashboard-id}")
    public ResponseEntity<Void> delete(@PathVariable("dashboard-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-DSH-07 복제 — 201 */
    @Idempotent
    @PostMapping("/core/dashboards/{dashboard-id}/duplicate")
    public ResponseEntity<ApiResponse<Created>> duplicate(@PathVariable("dashboard-id") long id) {
        Created created = service.duplicate(id);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/dashboards/" + created.id()))
                .body(ApiResponse.success(created));
    }

    /** API-DSH-07 내보내기(JSON) */
    @GetMapping("/core/dashboards/{dashboard-id}/export")
    public ApiResponse<ExportedDashboard> export(@PathVariable("dashboard-id") long id) {
        return ApiResponse.success(service.export(id));
    }

    /** API-DSH-07 가져오기 — 201 + 매핑 실패 목록 */
    @Idempotent
    @PostMapping("/core/dashboards/import")
    public ResponseEntity<ApiResponse<ImportResult>> importDashboard(@RequestBody JsonNode body) {
        ImportResult result = service.importDashboard(body);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/dashboards/" + result.id()))
                .body(ApiResponse.success(result));
    }

    /** API-DSH-09 위젯 데이터 */
    @PostMapping("/core/dashboards/{dashboard-id}/widgets/{widget-id}/data")
    public ApiResponse<WidgetData> widgetData(@PathVariable("dashboard-id") long id, @PathVariable("widget-id") String widgetId,
                                              @RequestBody(required = false) WidgetDataRequest request) {
        return ApiResponse.success(service.widgetData(id, widgetId, request));
    }

    /** API-DSH-08 분석 결과 위젯 고정(DSH-04.04) — {dashboardId, widgetId, version} */
    @PostMapping("/core/dashboards/{dashboard-id}/widgets/pin-analysis")
    public ApiResponse<java.util.Map<String, Object>> pinAnalysis(@PathVariable("dashboard-id") long id,
                                                                 @RequestBody(required = false) tools.jackson.databind.JsonNode body) {
        return ApiResponse.success(pins.pin(id, body));
    }

    /** API-DSH-09 미리 보기 */
    @PostMapping("/core/widgets/preview")
    public ApiResponse<WidgetData> preview(@RequestBody(required = false) WidgetPreviewRequest request) {
        return ApiResponse.success(service.preview(request));
    }

    /** API-DSH-13 위젯 종류 */
    @GetMapping("/core/widget-types")
    public ApiResponse<List<WidgetTypeResponse>> widgetTypes() {
        return ApiResponse.success(service.widgetTypes());
    }

    /** API-DSH-12 기본 대시보드 지정 */
    @PutMapping("/core/accounts/me/default-dashboard")
    public ApiResponse<DefaultDashboardResponse> setDefault(@RequestBody(required = false) DefaultDashboardRequest request) {
        return ApiResponse.success(service.setDefault(request == null ? null : request.dashboardId()));
    }

    /** API-DSH-10 공유 링크 만들기 — 201 */
    @Idempotent
    @PostMapping("/core/dashboards/{dashboard-id}/share-links")
    public ResponseEntity<ApiResponse<ShareLinkCreated>> createShareLink(@PathVariable("dashboard-id") long id,
                                                                        @RequestBody(required = false) ShareLinkRequest request) {
        ShareLinkCreated created = shareLinks.create(id, request == null ? null : request.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/core/dashboards/" + id + "/share-links/" + created.id()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.success(created));
    }

    /** API-DSH-10 공유 링크 목록 */
    @GetMapping("/core/dashboards/{dashboard-id}/share-links")
    public ApiResponse<List<ShareLinkItem>> shareLinks(@PathVariable("dashboard-id") long id) {
        return ApiResponse.success(shareLinks.list(id));
    }

    /** API-DSH-10 공유 링크 폐기 — 204 */
    @DeleteMapping("/core/dashboards/{dashboard-id}/share-links/{share-link-id}")
    public ResponseEntity<Void> revokeShareLink(@PathVariable("dashboard-id") long id, @PathVariable("share-link-id") long linkId) {
        shareLinks.revoke(id, linkId);
        return ResponseEntity.noContent().build();
    }

    /** API-DSH-15 공유 링크 보기(공개, 로그인 없음) */
    @GetMapping("/core/public/share/{share-token}")
    public ResponseEntity<ApiResponse<SharedDashboard>> shared(@PathVariable("share-token") String token) {
        return ResponseEntity.ok().headers(publicHeaders()).body(ApiResponse.success(shareLinks.view(token)));
    }

    /** API-DSH-15 공유 링크 위젯 데이터(공개) */
    @PostMapping("/core/public/share/{share-token}/widgets/{widget-id}/data")
    public ResponseEntity<ApiResponse<WidgetData>> sharedWidget(@PathVariable("share-token") String token,
                                                                @PathVariable("widget-id") String widgetId,
                                                                @RequestBody(required = false) WidgetDataRequest request) {
        return ResponseEntity.ok().headers(publicHeaders()).body(ApiResponse.success(shareLinks.widgetData(token, widgetId, request)));
    }

    /** 공유 링크 응답: 주소가 새지 않게·검색 엔진이 담지 않게(TC-DSH-068과 같은 헤더) */
    private static HttpHeaders publicHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Robots-Tag", "noindex");
        h.setCacheControl("no-store");
        return h;
    }
}
