package net.java21.data2flow.core.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 사용자 정의 대시보드·위젯·공유 링크 API(design/api/DSH-api.md API-DSH-06·07·09·10·13·15) */
public final class BoardDtos {

    private BoardDtos() {
    }

    /** API-DSH-06 목록 항목 */
    public record DashboardSummary(String id, String name, String description, String visibility, String ownerUserId, String ownerName,
                                   boolean favorite, int widgetCount, Instant updatedAt) {
    }

    /** API-DSH-06 상세·API-DSH-07 저장 응답 */
    public record DashboardResponse(String id, String name, String description, String visibility, String ownerUserId, JsonNode layout,
                                    JsonNode variables, JsonNode timeRange, String resolution, String refresh, String templateSource,
                                    boolean editable, int version, String updatedBy, Instant updatedAt) {
    }

    /** API-DSH-07 생성·복제 201 */
    public record Created(String id, String name, String visibility, int version) {
    }

    /** 409 DASHBOARD_VERSION_CONFLICT 응답에 싣는 최신 판(BR-DSH-07) */
    public record Conflict(int version, String updatedBy, String updatedByName, Instant updatedAt) {
    }

    /** API-DSH-07 내보내기 */
    public record ExportedDashboard(int formatVersion, Instant exportedAt, Map<String, Object> dashboard, List<ExportTarget> targets) {
    }

    /** 내보낸 대상 참조. ref는 {@code 위젯id/대상 순번} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExportTarget(String ref, String kind, String deviceId, String deviceExternalId, String deviceName, String spaceId,
                               String spaceName, String metricKey) {
    }

    /** API-DSH-07 가져오기 201 */
    public record ImportResult(String id, String name, int version, List<Unmapped> unmapped) {
    }

    public record Unmapped(String ref, String reason) {
    }

    /** API-DSH-12 기본 대시보드 */
    public record DefaultDashboardRequest(String dashboardId) {
    }

    public record DefaultDashboardResponse(String defaultDashboardId) {
    }

    /** API-DSH-09 요청: 시간 범위·단위·변수 값(이름 → ID 문자열) */
    public record WidgetDataRequest(JsonNode timeRange, String resolution, Map<String, String> variables) {
    }

    /** API-DSH-09 미리 보기(대시보드 없이): 위젯 정의 + 변수 정의 + 요청 */
    public record WidgetPreviewRequest(JsonNode widget, JsonNode variableDefinitions, JsonNode timeRange, String resolution,
                                       Map<String, String> variables) {
    }

    /** API-DSH-09 응답 {type, data} */
    public record WidgetData(String type, Object data) {
    }

    public record SeriesData(List<Series> series, String effectiveResolution, List<Object> annotations) {
    }

    public record Series(String key, String label, String unit, boolean virtual, List<Object[]> points) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatData(Double value, String unit, Integer quality, Instant at, List<Object[]> sparkline) {
    }

    public record GaugeData(Double value, String unit, Double min, Double max) {
    }

    public record HeatmapData(List<String> xLabels, List<String> yLabels, List<List<Double>> values, String unit) {
    }

    public record TableData(List<String> columns, List<List<Object>> rows) {
    }

    public record StatusItem(String deviceId, String name, String connection, Double battery, Double rssi, long alarms) {
    }

    public record ItemsData(List<?> items) {
    }

    public record AlarmEntry(String alarmId, String severity, String title, String state, Instant at) {
    }

    public record FloorplanData(String spaceId, String imageUrl, Integer width, Integer height, List<MarkerData> markers) {
    }

    public record MarkerData(String deviceId, double x, double y, Double value, String unit, String state) {
    }

    /** API-DSH-13 */
    public record WidgetTypeResponse(String type, String label, Map<String, Object> optionsSchema, TargetRule targetRule) {
    }

    public record TargetRule(int min, int max, List<String> kinds) {
    }

    /** API-DSH-10 */
    public record ShareLinkRequest(Integer expiresInDays) {
    }

    public record ShareLinkCreated(String id, String url, Instant expiresAt) {
    }

    public record ShareLinkItem(String id, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, String createdBy, Instant createdAt) {
    }

    /** API-DSH-15 */
    public record SharedDashboard(Map<String, Object> dashboard, Instant expiresAt, Map<String, Object> branding) {
    }
}
