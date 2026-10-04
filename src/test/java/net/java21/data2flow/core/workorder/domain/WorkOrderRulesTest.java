package net.java21.data2flow.core.workorder.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-08.02·08.05 작업 지시 규칙 — TC-DEV-208·226 */
class WorkOrderRulesTest {

    @Test
    @DisplayName("[DEV-08.02][TC-DEV-208] 상태 OPEN → ASSIGNED → IN_PROGRESS → DONE / CANCELLED, 닫힌 작업은 더 바꿀 수 없음")
    void transitions() {
        assertThat(WorkOrderRules.next("OPEN", "ASSIGN")).contains("ASSIGNED");
        assertThat(WorkOrderRules.next("ASSIGNED", "ASSIGN")).contains("ASSIGNED");
        assertThat(WorkOrderRules.next("ASSIGNED", "START")).contains("IN_PROGRESS");
        assertThat(WorkOrderRules.next("OPEN", "START")).contains("IN_PROGRESS");
        assertThat(WorkOrderRules.next("IN_PROGRESS", "COMPLETE")).contains("DONE");
        assertThat(WorkOrderRules.next("ASSIGNED", "COMPLETE")).isEmpty();
        assertThat(WorkOrderRules.next("IN_PROGRESS", "CANCEL")).contains("CANCELLED");
        assertThat(WorkOrderRules.next("DONE", "CANCEL")).isEmpty();
        assertThat(WorkOrderRules.next("CANCELLED", "START")).isEmpty();
        assertThat(WorkOrderRules.next("OPEN", "FLY")).isEmpty();
        assertThat(WorkOrderRules.isOpen("IN_PROGRESS")).isTrue();
        assertThat(WorkOrderRules.isOpen("DONE")).isFalse();
    }

    @Test
    @DisplayName("[DEV-08.05][BR-DEV-28][TC-DEV-226] next_due_on - lead_days <= 오늘이면 실행, 출처 참조는 계획·예정일·기기마다 하나")
    void planDue() {
        LocalDate due = LocalDate.parse("2026-10-12");
        assertThat(WorkOrderRules.planDue(due, 7, LocalDate.parse("2026-10-04"))).isFalse();
        assertThat(WorkOrderRules.planDue(due, 7, LocalDate.parse("2026-10-05"))).isTrue();
        assertThat(WorkOrderRules.planDue(due, 0, LocalDate.parse("2026-10-12"))).isTrue();
        assertThat(WorkOrderRules.planOriginRef(3, due, 9)).isEqualTo("plan:3:2026-10-12:9");
    }
}
