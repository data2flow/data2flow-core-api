package net.java21.data2flow.core.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 게이트웨이 API(design/api/DEV-api.md §6 API-DEV-60~62, 내부 API-DEV-125) */
public final class GatewayDtos {

    private GatewayDtos() {
    }

    public record Ref(String id, String name) {
    }

    /**
     * API-DEV-60 항목·상세. status는 마지막 수신 시각과 offlineAfterSec으로 계산(마지막 수신이 없으면 UNKNOWN).
     * deviceCount24h는 최근 24시간 이 게이트웨이를 최적 경로로 수신한 기기 수, uplinks24h는 최근 24시간 신호 기록 수
     */
    public record GatewayResponse(String id, String gatewayEui, String name, Ref source, Ref space, String status, Instant lastSeenAt,
                                  int offlineAfterSec, long deviceCount24h, long uplinks24h, Instant updatedAt) {
    }

    public record TouchItem(@NotNull Long sourceId, @NotBlank @Size(max = 32) String gatewayEui, Instant seenAt) {
    }

    /** API-DEV-125 (1분 묶음) */
    public record TouchRequest(@NotEmpty @Size(max = 5000) List<@Valid TouchItem> items) {
    }
}
