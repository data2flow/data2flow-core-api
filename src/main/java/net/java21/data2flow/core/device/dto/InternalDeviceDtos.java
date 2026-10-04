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
                                        Map<String, JsonNode> metricOverrides, int version, List<String> tags) {
    }

    /**
     * API-DEV-130 항목. 선택 필드(없으면 null, pipeline은 시스템 기본값 300초·3배): 기기 값 {@code expectedIntervalSec}·{@code offlineMultiplier},
     * 모델 기본값 {@code modelExpectedIntervalSec}·{@code modelOfflineMultiplier}, 사이트 시간대 {@code timezone}(BR-DEV-08, BR-TSD-05)
     */
    public record ChangedDevice(String deviceId, String organizationId, String sourceId, String externalId, String status, String modelId,
                                String spaceId, boolean virtual, int version, Instant updatedAt, Integer expectedIntervalSec,
                                Double offlineMultiplier, Integer modelExpectedIntervalSec, Double modelOfflineMultiplier,
                                String timezone) {
    }
}
