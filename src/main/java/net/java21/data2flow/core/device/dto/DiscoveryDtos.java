package net.java21.data2flow.core.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 기기 발견 내부 API(API-DEV-120·121)와 자동 등록 한도 해제(API-ING-16) */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /**
     * API-DEV-121 요청. sourceMeta는 원본 deviceName·tags(location, point) 등(8KB 이하로 줄여 저장),
     * metrics는 처음 메시지에 있던 측정 항목 키(모델 추천용).
     */
    public record AutoRegisterRequest(@NotNull Long organizationId, @NotNull Long sourceId, @NotBlank @Size(max = 256) String externalId,
                                      @Size(max = 200) String name, JsonNode sourceMeta, Instant firstSeenAt,
                                      @Size(max = 100) List<String> metrics) {
    }

    /** API-DEV-121 응답. status는 기존 기기면 그 상태 */
    public record AutoRegisterResponse(String deviceId, String status, boolean created) {
    }

    /**
     * API-DEV-120 응답. 무시 목록에만 있으면(기기 없음·삭제) deviceId·status가 null이고 ignored=true. 선택 필드는 API-DEV-130과 같다
     * ({@code expectedIntervalSec}·{@code offlineMultiplier}·{@code modelExpectedIntervalSec}·{@code modelOfflineMultiplier}·{@code timezone})
     */
    public record DeviceLookupResponse(String deviceId, String status, String organizationId, String modelId, String spaceId,
                                       boolean virtual, boolean ignored, Integer expectedIntervalSec, Double offlineMultiplier,
                                       Integer modelExpectedIntervalSec, Double modelOfflineMultiplier, String timezone) {
    }

    /** API-ING-16 요청 */
    public record QuotaResetRequest(@Min(1) @Max(10000) Integer newHourlyLimit) {
    }

    /** API-ING-16 응답 */
    public record QuotaResponse(String sourceId, int hourlyLimit, boolean blocked, int usedThisHour) {
    }
}
