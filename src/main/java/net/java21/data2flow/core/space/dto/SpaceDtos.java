package net.java21.data2flow.core.space.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 공간 API 요청·응답(design/api/DEV-api.md §1·§12). ID는 문자열, 시각은 ISO-8601 UTC, 종류·관계는 대문자 enum 문자열.
 * {@code path}는 조상 ID를 / 로 이은 경로이고 끝 / 는 붙이지 않는다(예: {@code /1/4/9}, EVT-DEV-05와 같은 형식).
 */
public final class SpaceDtos {

    private SpaceDtos() {
    }

    /** API-DEV-02 요청 */
    public record CreateSpaceRequest(String parentId, String type, String name, String code, String timezone, String address,
                                     BigDecimal latitude, BigDecimal longitude, String usage, BigDecimal areaM2, Integer capacity,
                                     Integer sortOrder) {
    }

    /** API-DEV-02·03·04 응답(공간 한 개) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SpaceResponse(String id, String parentId, String type, String name, String code, String path, int depth,
                                int sortOrder, String usage, BigDecimal areaM2, Integer capacity, String timezone, String address,
                                BigDecimal latitude, BigDecimal longitude, Integer kmaNx, Integer kmaNy, String status, int version,
                                Instant updatedAt) {
    }

    /** API-DEV-04 요청 */
    public record MoveSpaceRequest(String newParentId, Integer sortOrder, Integer baseVersion) {
    }

    /** 공간 상세(GET /core/spaces/{space-id}, 사전 작업으로 추가): 속성 + 조상 경로·유효 시간대·개수·목표·시간표·모드 요약 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SpaceDetailResponse(String id, String parentId, String type, String name, String code, String path, int depth,
                                      int sortOrder, String usage, BigDecimal areaM2, Integer capacity, String timezone,
                                      String address, BigDecimal latitude, BigDecimal longitude, Integer kmaNx, Integer kmaNy,
                                      String status, int version, Instant updatedAt, List<SpaceRef> ancestors,
                                      String effectiveTimezone, long childCount, long deviceCount, boolean hasFloorplan,
                                      TargetsResponse targets, ScheduleResponse schedule, ModeResponse mode, boolean virtual,
                                      boolean sandbox) {
    }

    /** 공간 참조 */
    public record SpaceRef(String id, String name, String type) {
    }

    /** API-DEV-01 트리 노드 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpaceNode(String id, String parentId, String type, String name, String code, Integer depth, Integer sortOrder,
                            Boolean accessible, Counts counts, String mode, List<EffectiveTarget> targets,
                            List<SpaceNode> children, Boolean virtual, Boolean sandbox) {
    }

    /** 트리 노드 개수(하위 포함). alarms는 알람 기능(RUL, M4) 전까지 0 */
    public record Counts(long devices, long offline, long alarms) {
    }

    /** 삭제를 막는 것(API-DEV-05 409 SPACE_NOT_EMPTY의 response.blockers) */
    public record Blockers(long children, long devices, long markers, long workOrders) {
    }

    /** 409 SPACE_NOT_EMPTY 응답 본문의 response */
    public record NotEmptyResponse(Blockers blockers) {
    }

    /** API-DEV-06 요청 */
    public record TargetsRequest(Boolean inherit, List<TargetItem> items) {
    }

    /** 목표 한 항목(최소·최대 중 하나 이상) */
    public record TargetItem(String metricKey, Double min, Double max) {
    }

    /** 목표 응답: 이 공간이 정한 값(items)과 상속을 반영한 유효 값(effective) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TargetsResponse(boolean inherit, String inheritedFromSpaceId, String inheritedFromSpaceName,
                                  List<TargetItem> items, List<EffectiveTarget> effective) {
    }

    /** 유효 목표. inherited=true면 inheritedFromSpaceId 공간에서 물려받은 값 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EffectiveTarget(String metricKey, Double min, Double max, boolean inherited, String inheritedFromSpaceId,
                                  String inheritedFromSpaceName) {
    }

    /** API-DEV-07 요청 */
    public record ScheduleRequest(Boolean inherit, List<Slot> slots) {
    }

    /** 시간 구간(요일 1=월~7=일, "HH:mm") */
    public record Slot(Integer dayOfWeek, String start, String end) {
    }

    /** 유효 시간표 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScheduleResponse(boolean inherit, String inheritedFromSpaceId, String inheritedFromSpaceName, List<Slot> slots) {
    }

    /** API-DEV-08 조회 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ModeResponse(String mode, String source, Instant until, Instant nextChangeAt) {
    }

    /** API-DEV-09 응답·평면도 조회(API-DSH-03 기본 부분) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FloorplanResponse(String spaceId, String imageUrl, int widthPx, int heightPx, int width, int height,
                                    BigDecimal scaleMPerPx, String contentType, int version, Instant updatedAt,
                                    boolean markersNeedReview, List<MarkerDto> markers) {
    }

    /** API-DEV-10 요청 */
    public record MarkersRequest(List<MarkerItem> markers) {
    }

    /** 마커 입력(x·y는 이미지 대비 비율 0~1) */
    public record MarkerItem(String deviceId, Double x, Double y, Integer rotation) {
    }

    /** API-DEV-10 응답 */
    public record MarkersResponse(List<MarkerDto> markers) {
    }

    /** 마커 */
    public record MarkerDto(String deviceId, String deviceName, BigDecimal x, BigDecimal y, int rotation) {
    }

    /** API-DEV-14 요청 */
    public record RelationsRequest(List<RelationItem> items) {
    }

    /** 관계 입력 */
    public record RelationItem(String spaceId, String relation) {
    }

    /** API-DEV-14 응답: 유효 관계(auto=true는 설치 공간에서 자동으로 생긴 관계) */
    public record RelationsResponse(String deviceId, List<RelationDto> items) {
    }

    /** 관계 */
    public record RelationDto(String spaceId, String spaceName, String relation, boolean auto) {
    }

    /** API-DEV-18 목록 항목 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SpaceDeviceResponse(String id, String name, String kind, String status, String connectivity, String relation,
                                      String modelId, String modelName, List<String> capabilities, String spaceId,
                                      Instant lastSeenAt) {
    }

    /** API-DEV-128 항목 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InternalSpaceDeviceResponse(String deviceId, String name, String relation, String modelId,
                                              List<String> capabilities, String status, String connection, String spaceId) {
    }

    /** API-DEV-25 항목. openAlarms는 알람 기능(RUL, M4) 전까지 0, comfortScore는 쾌적도 분석(ANA) 전까지 null */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SiteSummaryResponse(String siteId, String name, BigDecimal lat, BigDecimal lng, long devices, long offline,
                                      long openAlarms, Integer comfortScore) {
    }

    /** API-DEV-131 시맨틱 문서(요청·응답) */
    public record SemanticDocument(String deviceId, List<EquipmentDto> equipment) {
    }

    /** 장비 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EquipmentDto(String id, String equipClass, String name, String spaceId, List<PointDto> points) {
    }

    /** 점 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PointDto(String id, String metricKey, String pointType, String quantity, List<String> tags, String source) {
    }
}
