package net.java21.data2flow.core.device.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 기기 내부 API 응답(API-DEV-122·130) */
public final class InternalDeviceDtos {

    private InternalDeviceDtos() {
    }

    /** scope: MODEL 또는 DEVICE, version: 활성 버전 번호(없으면 null) */
    public record TransformScript(String scope, String scriptId, Long version) {
    }

    public record RuntimeAttributes(Map<String, JsonNode> server, Map<String, JsonNode> shared) {
    }

    /** API-DEV-122. metricOverrides는 M2에 원천이 없어 빈 객체(측정 항목별 보정은 TRANSFORM 스크립트·서버 속성으로 한다) */
    public record DeviceRuntimeResponse(String deviceId, String organizationId, String modelId, String status, boolean virtual,
                                        List<TransformScript> transformScripts, RuntimeAttributes attributes,
                                        Map<String, JsonNode> metricOverrides, int version) {
    }

    /** API-DEV-130 항목 */
    public record ChangedDevice(String deviceId, String organizationId, String sourceId, String externalId, String status, String modelId,
                                String spaceId, boolean virtual, int version, Instant updatedAt) {
    }
}
