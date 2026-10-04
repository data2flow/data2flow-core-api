package net.java21.data2flow.core.alarm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 알람 API(API-RUL-10~14·20) 응답. ID는 문자열(api-rules) */
public final class AlarmDtos {

    private AlarmDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRef(String userId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String type, String ruleId, String ruleName, String flowId, String nodeId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceRef(String id, String name) {
    }

    /** 공간: path는 이름 경로(예: "캠퍼스 / 본관 / 실습실") */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpaceRef(String id, String name, String path) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Threshold(Double raise, Double clear) {
    }

    /** API-RUL-10 Alarm */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Alarm(String id, String severity, String status, boolean flapping, String title, Source source, DeviceRef device,
                        SpaceRef space, String metric, Double triggerValue, Double peakValue, Double lastValue, Threshold threshold,
                        int occurrenceCount, Instant raisedAt, Instant lastRaisedAt, UserRef ackedBy, Instant ackedAt, Instant clearedAt,
                        String clearReason, UserRef assignee, String parentAlarmId, int childCount, String spaceEventId,
                        String suppressedReason, boolean virtual, int version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimelineEvent(String eventId, String type, Instant at, Actor actor, Map<String, Object> data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Actor(String type, String id, String name) {
    }

    public record Chart(String metric, Instant from, Instant to) {
    }

    /** API-RUL-11 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlarmDetail(Alarm alarm, List<TimelineEvent> events, List<Alarm> children, Chart chart) {
    }

    /** API-RUL-12 단건 결과 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HandleResult(boolean ok, String alarmId, String status, Boolean alreadyAcked, Instant ackedAt, Instant clearedAt,
                               String clearReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkItem(String alarmId, boolean ok, String code, Boolean alreadyAcked) {
    }

    public record BulkResult(List<BulkItem> results) {
    }

    /** API-RUL-13 메모·조치 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoteResult(String eventId, String alarmId, String type, String text, String actionType, UserRef actor, Instant at) {
    }

    public record AssigneeResult(String alarmId, UserRef assignee, int version, Instant updatedAt) {
    }

    public record RankItem(String id, String name, long count) {
    }

    public record DailyCount(String date, long raised) {
    }

    /** API-RUL-20 */
    public record AlarmStats(long raised, Long mttaSec, Long mttrSec, double unackedRatio, List<RankItem> topRules,
                             List<RankItem> topSpaces, List<RankItem> topDevices, List<DailyCount> daily) {
    }

    /** SSE {@code alarms} 토픽(API-DSH-20) */
    public record AlarmTopicEvent(String alarmId, String state, String severity, String spaceId, String title) {
    }
}
