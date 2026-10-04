package net.java21.data2flow.core.bim.dto;

import java.time.Instant;
import java.util.List;

/** 층 전환·IFC 모델 API(design/api/DSH-api.md API-DSH-24, DSH-12.04) */
public final class BimDtos {

    private BimDtos() {
    }

    /** 층 목록(층 전환 평면도, AT-DSH-13.1). imageUrl은 API-DEV-09 평면도 이미지 */
    public record FloorItem(String spaceId, String name, String code, int sortOrder, boolean hasFloorplan, String imageUrl, Integer width,
                            Integer height) {
    }

    /** POST …/models 201 */
    public record ModelCreated(String id, String name, long sizeBytes, String status, int version) {
    }

    public record ModelSummary(String id, String name, long sizeBytes, String ifcSchema, String status, Integer elementCount, int version,
                               Instant createdAt) {
    }

    /** GET …/models/{model-id} */
    public record ModelDetail(String id, String name, String ifcSchema, String status, String error, Integer elementCount, String downloadUrl,
                              List<Mapping> mappings, List<SpaceElement> spaceElements, int version) {
    }

    public record Mapping(String ifcGlobalId, String spaceId) {
    }

    /** IFC 안의 공간 요소(IfcSpace) — 연결 화면용 */
    public record SpaceElement(String ifcGlobalId, String name) {
    }

    public record MappingRequest(List<Mapping> mappings) {
    }

    public record MappingResult(String modelId, int mapped, int unmappedElements) {
    }
}
