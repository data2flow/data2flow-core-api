package net.java21.data2flow.core.compliance.dto;

import net.java21.data2flow.core.compliance.domain.ReportModels.DataRange;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 준수 리포트 API(API-CMP-16~20a) 요청·응답 */
public final class ComplianceReportDtos {

    private ComplianceReportDtos() {
    }

    /** API-CMP-16. options는 {includeRawCsv, includeActions, branding}(지금은 저장만 한다) */
    public record CreateReportRequest(String type, String title, List<String> spaceIds, String facilityType, String periodFrom, String periodTo,
                                      JsonNode options) {
    }

    public record ReportCreated(String reportId, String revisionId, int revisionNo, String status) {
    }

    public record ReportView(String reportId, String type, String title, List<String> spaceIds, String facilityType, LocalDate periodFrom,
                             LocalDate periodTo, JsonNode options, Instant createdAt, Instant updatedAt, RevisionView latest,
                             List<RevisionView> revisions) {
    }

    /** 판. profileVersions는 쓴 프로필 버전 ID */
    public record RevisionView(String reportId, int revisionNo, String status, int progress, String sha256, Long sizeBytes, DataRange dataRange,
                               List<String> profileVersions, UserRef generatedBy, UserRef publishedBy, Instant publishedAt, String failureReason,
                               Instant createdAt, Instant updatedAt) {
    }

    /** API-CMP-19 서명 링크(10분) */
    public record FileLink(String url, String sha256, Long sizeBytes, Instant expiresAt) {
    }

    /** API-CMP-20 해시 검증. matched=false면 나머지는 null */
    public record VerifyResult(boolean matched, String reportId, Integer revisionNo, String status, Instant publishedAt, String sha256) {
    }
}
