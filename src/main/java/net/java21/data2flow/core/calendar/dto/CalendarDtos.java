package net.java21.data2flow.core.calendar.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 조직 달력·운영 모드 API(design/api/DEV-api.md §10 API-DEV-100~102, §1 API-DEV-08 POST) */
public final class CalendarDtos {

    private CalendarDtos() {
    }

    /**
     * 일정(API-DEV-100~102 응답). 시각은 "HH:mm"(하루 종일이면 null), 날짜는 사이트 시간대 기준.
     * sourceId·locallyModified·originDeleted는 자동 생성 일정 표시용(BR-DSC-18 "원본 삭제됨")
     */
    public record CalendarEventResponse(String id, String title, String type, LocalDate startsOn, LocalDate endsOn, String startTime,
                                        String endTime, List<String> scopeSpaceIds, String origin, String affectsMode, String sourceId,
                                        boolean locallyModified, boolean originDeleted, int version, Instant updatedAt) {
    }

    /** API-DEV-08 응답과 같은 모양 */
    public record ModeView(String mode, String source, Instant until, Instant nextChangeAt) {
    }
}
