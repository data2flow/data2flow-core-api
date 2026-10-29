package net.java21.data2flow.core.compliance.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 정기 발행 API(API-CMP-21·22) 요청·응답 */
public final class ReportScheduleDtos {

    private ReportScheduleDtos() {
    }

    /** API-CMP-16 본문과 같은 리포트 조건(기간은 일정이 정한다) */
    public record Template(String type, String title, List<String> spaceIds, String facilityType, JsonNode options) {
    }

    public record Recipients(List<String> userIds, List<String> emails) {
    }

    /** API-CMP-21 저장. publishTime은 "HH:mm"(조직 시간대). 수정은 version이 맞아야 한다 */
    public record ScheduleRequest(String name, Template reportTemplate, String cadence, Integer dayOfPeriod, String publishTime, String periodRule,
                                  Recipients recipients, String format, Boolean enabled, Integer version) {
    }

    /** lastRun은 문서에 없던 값(목록의 마지막 결과) */
    public record ScheduleView(String reportScheduleId, String name, Template reportTemplate, String cadence, int dayOfPeriod, String publishTime,
                               String periodRule, Recipients recipients, String format, boolean enabled, Instant nextRunAt, int version,
                               Instant updatedAt, RunView lastRun) {
    }

    public record Delivery(String recipient, String status) {
    }

    /** API-CMP-22. trigger(SCHEDULED·MANUAL)는 문서에 없던 값 */
    public record RunView(String runId, Instant scheduledAt, String trigger, String status, int attempts, String reportId, Integer revisionNo,
                          String error, List<Delivery> deliveries) {
    }

    /** run-now 202 */
    public record RunRequested(String runId, String reportScheduleId, Instant scheduledAt) {
    }
}
