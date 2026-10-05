package net.java21.data2flow.core.commissioning.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** QR 라벨·현장 설치·설치 현황판 API(design/api/DEV-api.md API-DEV-24·26·137·138) */
public final class CommissioningDtos {

    private CommissioningDtos() {
    }

    /** 기기 QR(내용 url = {웹}/d/{qrToken}) */
    public record QrResponse(String deviceId, String qrToken, String url) {
    }

    /** API-DEV-24 라벨 인쇄 */
    public record QrLabelsRequest(@NotEmpty @Size(max = 200) List<String> deviceIds, String layout) {
    }

    /** API-DEV-26 QR 토큰 해석(BFF가 /d/{qrToken}에서 불러 /devices/{id}로 302) */
    public record QrResolveResponse(String deviceId) {
    }

    /** API-DEV-137 응답 */
    public record CommissionResponse(String deviceId, String status, Instant installedAt, String installedBy, Instant waitUntil) {
    }

    /** 현장 설치 상태(대기 화면 UI-DEV-21): 첫 수신이면 최근값, 10분 무수신이면 체크리스트 */
    public record CommissioningStatusResponse(String deviceId, String status, String spaceId, BigDecimal x, BigDecimal y, Instant installedAt,
                                              String installedBy, String installedByName, Instant waitUntil, Instant firstSeenAt,
                                              Map<String, Boolean> checklist, JsonNode latest, List<String> photoUrls) {
    }

    /** COMMISSION_CONFLICT(409) 응답의 서버 값(AT-DEV-27.5 비교 표시) */
    public record ServerCommission(String deviceId, String status, String spaceId, BigDecimal x, BigDecimal y, Instant installedAt,
                                   String installedBy, String installedByName) {
    }

    /** API-DEV-138 층별 수 */
    public record Floor(String spaceId, String name, long planned, long installed, long verified, long problem) {
    }

    public record BoardResponse(String siteId, List<Floor> floors) {
    }

    /** API-DEV-138 기기 목록 항목 */
    public record BoardDeviceResponse(String deviceId, String name, String spaceId, String status, Map<String, Boolean> checklist,
                                      Instant installedAt) {
    }
}
