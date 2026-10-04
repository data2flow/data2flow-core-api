package net.java21.data2flow.core.workorder.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 작업 지시 규칙(DEV-08.02·08.05·08.06, BR-DEV-21·28). 상태: OPEN → ASSIGNED → IN_PROGRESS → DONE / CANCELLED.
 */
public final class WorkOrderRules {

    public static final List<String> TYPES = List.of("BATTERY", "CALIBRATION", "INSPECTION", "REPAIR", "REPLACE", "RELOCATE", "OTHER");
    public static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");
    public static final List<String> ORIGINS = List.of("MANUAL", "ALARM", "ANALYSIS", "FLOW", "SCHEDULE");
    public static final List<String> STATUSES = List.of("OPEN", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED");
    public static final Set<String> OPEN_STATUSES = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS");
    public static final List<String> ACTIONS = List.of("ASSIGN", "START", "COMPLETE", "CANCEL");
    /** "마감 임박" 기준(DEV-08.06, 2026-10-03 확정) */
    public static final Duration DUE_SOON = Duration.ofHours(48);
    public static final int MAX_TITLE = 150;
    public static final int MAX_CHECKLIST = 50;
    public static final int MAX_TARGETS = 200;

    /** 동작별 허용 이전 상태 → 다음 상태 */
    private static final Map<String, Set<String>> FROM = Map.of(
            "ASSIGN", Set.of("OPEN", "ASSIGNED"),
            "START", Set.of("OPEN", "ASSIGNED"),
            "COMPLETE", Set.of("IN_PROGRESS"),
            "CANCEL", OPEN_STATUSES);
    private static final Map<String, String> TO = Map.of("ASSIGN", "ASSIGNED", "START", "IN_PROGRESS", "COMPLETE", "DONE",
            "CANCEL", "CANCELLED");

    private WorkOrderRules() {
    }

    /** 허용되면 다음 상태, 아니면 빈 값(409 WORKORDER_STATE_CONFLICT) */
    public static Optional<String> next(String status, String action) {
        Set<String> from = FROM.get(action);
        return from != null && from.contains(status) ? Optional.of(TO.get(action)) : Optional.empty();
    }

    public static boolean isOpen(String status) {
        return OPEN_STATUSES.contains(status);
    }

    /** BR-DEV-28: 계획을 실행할 날인가(next_due_on - lead_days <= 오늘) */
    public static boolean planDue(LocalDate nextDueOn, int leadDays, LocalDate today) {
        return !nextDueOn.minusDays(leadDays).isAfter(today);
    }

    /** 계획으로 만든 작업 지시의 출처 참조(같은 계획·같은 예정일·같은 기기는 한 번만) */
    public static String planOriginRef(long planId, LocalDate dueOn, long deviceId) {
        return "plan:" + planId + ":" + dueOn + ":" + deviceId;
    }
}
