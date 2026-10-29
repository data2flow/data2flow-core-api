package net.java21.data2flow.core.compliance.dto;

import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 개선 조치 API(API-CMP-23·24) 요청·응답 */
public final class CorrectiveActionDtos {

    private CorrectiveActionDtos() {
    }

    /** API-CMP-23 저장. actorUserId가 없으면 본인 */
    public record ActionRequest(String cause, String causeNote, String actionText, String actorUserId, Instant actedAt,
                                List<String> linkedAlarmIds, List<String> linkedWorkOrderIds, String resultMode, String resultNote) {
    }

    public record ExcursionRef(String spaceId, String metricKey, Instant startedAt, Instant endedAt, BigDecimal peakValue) {
    }

    /**
     * 조치. resultStatus는 문서에 없던 값으로 RECORDED(수동)·PENDING(24시간 전)·CONFIRMED·UNVERIFIABLE(결측 과다)이다
     */
    public record ActionView(String actionId, String excursionId, ExcursionRef excursion, String cause, String causeNote, String actionText,
                             UserRef actor, Instant actedAt, List<String> linkedAlarmIds, List<String> linkedWorkOrderIds, String resultMode,
                             BigDecimal resultRatio24h, String resultNote, String resultStatus, Instant updatedAt) {
    }

    /** 이탈 구간 하나와 그 조치(문서에 없던 조회. UI-CMP-10이 쓴다) */
    public record ExcursionDetail(String excursionId, String spaceId, String spaceName, String metricKey, Instant startedAt, Instant endedAt,
                                  BigDecimal peakValue, String alarmId, List<ActionView> actions) {
    }
}
