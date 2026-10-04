package net.java21.data2flow.core.retention.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 보관 정책·저장 현황·콜드 보관 파일 API(design/api/TSD-api.md §3·§4, 내부 API-TSD-60·61) */
public final class RetentionDtos {

    private RetentionDtos() {
    }

    /** 변경안 한 줄(API-TSD-41·42 items) */
    public record PolicyItem(String scope, String scopeRef, String dataClass, Integer retainDays, Integer compressAfterDays,
                             Boolean archiveBeforeDelete, String storeMode) {
    }

    /** API-TSD-41 요청 */
    public record PreviewRequest(List<PolicyItem> items) {
    }

    /** API-TSD-42 요청 */
    public record SaveRequest(List<PolicyItem> items, String confirmToken) {
    }

    /** 유효 정책 한 줄(API-TSD-40·42 effective, API-TSD-60 effective) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EffectivePolicy(String scope, String scopeRef, String dataClass, int retainDays, Integer compressAfterDays,
                                  boolean archiveBeforeDelete, String storeMode, int minDays, boolean inherited, int version) {
    }

    /** API-TSD-40 응답 */
    public record PoliciesResponse(List<EffectivePolicy> effective, long version) {
    }

    /** API-TSD-42 응답 */
    public record SaveResponse(List<EffectivePolicy> effective, long version, Instant appliesAt) {
    }

    /** 미리 보기 항목(pipeline API-TSD-62 byMetric) */
    public record PreviewItem(String scope, String scopeRef, String dataClass, long rows, long bytes) {
    }

    /**
     * API-TSD-41 응답. 줄어드는 항목이 없으면 pipeline을 부르지 않고 0이다(shortened=false). confirmToken은 이 변경안 그대로 저장할 때
     * API-TSD-42에 넣는다(10분 유효, 정책이 그 사이 바뀌면 다시 미리 보기)
     */
    public record PreviewResponse(long affectedRows, long affectedBytes, List<PreviewItem> byMetric, boolean shortened,
                                  String confirmToken, Instant expiresAt) {
    }

    /** API-TSD-43 응답 */
    public record StorageStatsResponse(List<PartitionStat> partitions, long totalBytes) {
    }

    public record Range(Instant from, Instant to) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PartitionStat(String table, String name, Range range, String state, Long rows, Long bytes, Double compressionRatio) {
    }

    /** 콜드 보관 파일(API-TSD-33 목록 항목) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ArchiveFileResponse(String id, String dataClass, Instant rangeFrom, Instant rangeTo, String objectKey, String format,
                                      long rowsCount, long bytes, String checksum, String restoredJobId, Instant createdAt) {
    }

    /** API-TSD-61 요청(pipeline). organizationId는 문자열·숫자 모두 받는다 */
    public record ArchiveFileRequest(String organizationId, String dataClass, Instant rangeFrom, Instant rangeTo, String objectKey,
                                     String format, Long rowsCount, Long bytes, String checksum) {
    }

    /** API-TSD-61 응답 */
    public record IdResponse(String id) {
    }

    /** API-TSD-60 조직 하나 */
    public record OrganizationPolicies(String organizationId, long version, List<EffectivePolicy> effective) {
    }

    /** API-TSD-60 응답 */
    public record InternalPoliciesResponse(List<OrganizationPolicies> organizations) {
    }
}
