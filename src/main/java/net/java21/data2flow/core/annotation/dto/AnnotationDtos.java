package net.java21.data2flow.core.annotation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 시계열 주석 DTO(API-TSD-06·07). ID는 JSON 문자열, 시각은 UTC */
public final class AnnotationDtos {

    private AnnotationDtos() {
    }

    /**
     * API-TSD-07 수동 주석. 기기·공간을 모두 비우면 조직 전체 주석(공간 범위가 전체인 사용자만).
     *
     * @param timeTo 없으면 시점 주석
     */
    public record CreateAnnotationRequest(@NotNull Instant timeFrom, Instant timeTo, String deviceId, String spaceId,
                                          @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$") String metricKey,
                                          @NotBlank @Size(max = 150) String title) {
    }

    /** 주석 하나. type은 ALARM·OFFLINE·SCRIPT_ERROR·ANOMALY·WORK_ORDER·REPLACEMENT·CALENDAR·USER(수동) */
    public record AnnotationResponse(String id, Instant timeFrom, Instant timeTo, String deviceId, String spaceId, String metricKey,
                                     String type, String title, String ref, String createdBy, Instant createdAt) {
    }
}
