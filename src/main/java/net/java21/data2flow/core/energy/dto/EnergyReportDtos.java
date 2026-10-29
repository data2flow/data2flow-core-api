package net.java21.data2flow.core.energy.dto;

import net.java21.data2flow.core.energy.dto.EnergySettingDtos.UserRef;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** 월간 에너지 리포트(API-ENE-13, ENE-01.05). ID는 문자열 */
public final class EnergyReportDtos {

    private EnergyReportDtos() {
    }

    /** {@code scopeSpaceId} 없으면 포트폴리오(허용 범위 전체), {@code month} YYYY-MM, {@code format} PDF(기본)·JSON */
    public record CreateReportRequest(String scopeSpaceId, String month, String format) {
    }

    public record Accepted(String jobId, String reportId) {
    }

    /** 목록 한 줄과 상세. model은 상세(READY)에만, fileUrl은 PDF가 있을 때 */
    public record ReportView(String reportId, String scopeSpaceId, String scopeName, String month, String format, String status, int progress,
                             String sha256, Long sizeBytes, String fileUrl, String failureReason, UserRef createdBy, Instant createdAt, JsonNode model) {
    }
}
