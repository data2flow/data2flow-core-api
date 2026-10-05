package net.java21.data2flow.core.external.dto;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 외부 맥락 API(design/api/DSC-api.md §4 API-DSC-40~43 + 사이트별 카드 API-DSC-44·45) */
public final class ExternalDtos {

    private ExternalDtos() {
    }

    /** 파사드 구현(available=false면 "준비 중", simulated=true면 가짜 값) */
    public record ProviderView(String key, boolean available, boolean simulated) {
    }

    /** 마지막 동기화(core가 직접 갱신하는 HOLIDAY·ICAL) 또는 마지막 [지금 갱신] 결과 */
    public record LastSync(Instant at, String status, int added, int updated, int removed, String error, Instant nextDueAt) {
    }

    /** 오늘 호출량 */
    public record UsageToday(LocalDate day, int calls, int failures, Integer quota, boolean warning, boolean exhausted, Instant resumeAt) {
    }

    /**
     * 외부 맥락 카드 하나(UI-DSC-04). config는 비밀값을 뺀 소스 connection(nx·ny·items·forecast·stationName·countryCode·url·fileObjectKey·
     * typeMapping·refreshHours·dailyQuota·unitCost)
     */
    public record ContextSourceView(String type, String sourceId, boolean enabled, String lifecycle, String connectionState,
                                    ProviderView provider, JsonNode config, boolean apiKeyConfigured, LastSync lastSync,
                                    UsageToday usageToday, Integer version) {
    }

    /** 사이트의 외부 맥락(API-DSC-44 GET). 좌표가 없으면 locationRequired=true */
    public record SiteContextResponse(String siteId, String siteName, BigDecimal latitude, BigDecimal longitude, boolean locationRequired,
                                      Integer kmaNx, Integer kmaNy, List<ContextSourceView> sources) {
    }

    /** API-DSC-40 하루 항목. cost는 단가(unitCost)를 정한 소스만 */
    public record UsageDay(LocalDate day, int calls, int failures, Integer quota, boolean warning, boolean exhausted, BigDecimal cost) {
    }

    /** API-DSC-41 */
    public record RefreshResponse(String jobId, String sourceId, String status, int added, int updated, int removed, String error) {
    }

    /** API-DSC-42 항목 */
    public record StationView(String stationName, String address, double lat, double lng, double distanceKm, List<String> items) {
    }

    /** API-DSC-43 */
    public record IcalUploadResponse(String fileObjectKey, int eventCount, List<String> categories) {
    }

    /** 내부 API-DSC-80 호출량 기록 응답 */
    public record UsageRecorded(LocalDate day, int calls, int failures, Integer quota, boolean warning, boolean exhausted,
                                Instant resumeAt) {
    }
}
