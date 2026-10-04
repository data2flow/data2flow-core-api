package net.java21.data2flow.core.alarm.domain;

import net.java21.data2flow.contracts.alarm.AlarmStatus;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.alarm.SuppressedReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlarmStateMachineTest {

    @ParameterizedTest(name = "[{index}] {0} 확인={1} → {2}")
    @CsvSource({"ACTIVE,false,ACKNOWLEDGED,false", "ACKNOWLEDGED,true,ACKNOWLEDGED,true", "CLEARED,false,CLEARED,false",
            "CLEARED,true,CLEARED,true"})
    @DisplayName("[RUL-02.02][TC-RUL-045] BR-RUL-07 확인과 해제는 독립: 확인은 상태만, 해제된 알람의 확인은 기록만 남기고 CLEARED 유지")
    void ackIsIndependentFromClear(AlarmStatus from, boolean acked, AlarmStatus to, boolean already) {
        AlarmStateMachine.AckOutcome outcome = AlarmStateMachine.ack(from, acked);
        assertThat(outcome.next()).isEqualTo(to);
        assertThat(outcome.alreadyAcked()).isEqualTo(already);
    }

    @Test
    @DisplayName("[RUL-02.02][TC-RUL-046] 전이표: ACTIVE→ACK 허용, ACK→ACK·확인한 CLEARED→ACK·SUPPRESSED→ACK는 ALARM_STATE_CONFLICT, 열린 상태→CLEARED")
    void transitionTable() {
        assertThat(AlarmStateMachine.ackStrict(AlarmStatus.ACTIVE, false)).isEqualTo(AlarmStatus.ACKNOWLEDGED);
        assertThatThrownBy(() -> AlarmStateMachine.ackStrict(AlarmStatus.ACKNOWLEDGED, true))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", AlarmErrorCode.ALARM_STATE_CONFLICT);
        assertThatThrownBy(() -> AlarmStateMachine.ackStrict(AlarmStatus.CLEARED, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AlarmStateMachine.ack(AlarmStatus.SUPPRESSED, false)).isInstanceOf(BusinessException.class);
        for (AlarmStatus open : new AlarmStatus[]{AlarmStatus.ACTIVE, AlarmStatus.ACKNOWLEDGED, AlarmStatus.SUPPRESSED}) {
            assertThat(AlarmStateMachine.clear(open)).isEqualTo(AlarmStatus.CLEARED);
        }
        assertThatThrownBy(() -> AlarmStateMachine.clear(AlarmStatus.CLEARED)).isInstanceOf(BusinessException.class);
        assertThat(AlarmStateMachine.unsuppress(AlarmStatus.SUPPRESSED)).isEqualTo(AlarmStatus.ACTIVE);
        assertThatThrownBy(() -> AlarmStateMachine.unsuppress(AlarmStatus.ACTIVE)).isInstanceOf(BusinessException.class);
        assertThat(AlarmStateMachine.suppress(AlarmStatus.ACKNOWLEDGED)).isEqualTo(AlarmStatus.SUPPRESSED);
        assertThatThrownBy(() -> AlarmStateMachine.suppress(AlarmStatus.CLEARED)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest(name = "[{index}] 유지보수={0} 상위={1} 오프라인={2} 연결알람={3} → {4}")
    @CsvSource(nullValues = "null", value = {"true,false,false,false,MAINTENANCE", "true,true,true,true,MAINTENANCE",
            "false,true,false,true,PARENT", "false,true,false,false,null", "false,false,true,false,DEVICE_OFFLINE",
            "false,false,true,true,null", "false,false,false,false,null"})
    @DisplayName("[RUL-02.05][TC-RUL-056] BR-RUL-08 억제: 유지보수 > 상위 원인(연결 알람만) > 기기 오프라인(측정 알람만)")
    void suppression(boolean maintenance, boolean parent, boolean offline, boolean connectivity, SuppressedReason expected) {
        assertThat(AlarmSuppressionPolicy.decide(maintenance, parent, offline, connectivity)).isEqualTo(expected);
    }
}
