package net.java21.data2flow.core.alarm.domain;

import net.java21.data2flow.contracts.alarm.AlarmStatus;
import net.java21.data2flow.contracts.error.BusinessException;

/**
 * 알람 상태 전이(RUL domain-model §3, BR-RUL-07). 확인(ACK)과 해제(CLEAR)는 독립이다.
 *
 * <pre>
 * (없음) ─발생─▶ ACTIVE · (없음) ─발생(유지보수·상위 원인)─▶ SUPPRESSED
 * ACTIVE ─확인─▶ ACKNOWLEDGED
 * ACTIVE·ACKNOWLEDGED·SUPPRESSED ─조건 해소·수동 해제·상위 해제·규칙 삭제─▶ CLEARED
 * SUPPRESSED ─억제 사유 해소 & 조건 지속─▶ ACTIVE
 * CLEARED ─확인─▶ CLEARED(확인 기록만 남김)
 * </pre>
 * 허용되지 않은 전이는 {@code ALARM_STATE_CONFLICT}(409).
 */
public final class AlarmStateMachine {

    private AlarmStateMachine() {
    }

    /** 확인 결과: 다음 상태와 이미 확인했는지 */
    public record AckOutcome(AlarmStatus next, boolean alreadyAcked) {
    }

    /**
     * 확인. ACTIVE → ACKNOWLEDGED. 이미 확인한 알람(ACKNOWLEDGED, 또는 확인 기록이 있는 CLEARED)은 오류가 아니라
     * {@code alreadyAcked}(API-RUL-12). 확인하지 않은 CLEARED는 확인 기록만 남기고 CLEARED. SUPPRESSED는 확인할 수 없다(409).
     *
     * @param acked 이미 확인 기록(acked_at)이 있는가
     */
    public static AckOutcome ack(AlarmStatus current, boolean acked) {
        return switch (current) {
            case ACTIVE -> new AckOutcome(AlarmStatus.ACKNOWLEDGED, false);
            case ACKNOWLEDGED -> new AckOutcome(AlarmStatus.ACKNOWLEDGED, true);
            case CLEARED -> new AckOutcome(AlarmStatus.CLEARED, acked);
            default -> throw new BusinessException(AlarmErrorCode.ALARM_STATE_CONFLICT);
        };
    }

    /** 단건 확인 API가 쓰는 엄격한 판정: 이미 확인한 알람을 다시 확인하면 409(TC-RUL-046) */
    public static AlarmStatus ackStrict(AlarmStatus current, boolean acked) {
        AckOutcome outcome = ack(current, acked);
        if (outcome.alreadyAcked()) {
            throw new BusinessException(AlarmErrorCode.ALARM_STATE_CONFLICT);
        }
        return outcome.next();
    }

    /** 해제(조건 해소·수동·상위 해제·규칙 삭제). 열린 알람만, CLEARED는 409 */
    public static AlarmStatus clear(AlarmStatus current) {
        if (!current.open()) {
            throw new BusinessException(AlarmErrorCode.ALARM_STATE_CONFLICT);
        }
        return AlarmStatus.CLEARED;
    }

    /** 억제 사유 해소(유지보수 종료) & 조건 지속: SUPPRESSED → ACTIVE */
    public static AlarmStatus unsuppress(AlarmStatus current) {
        if (current != AlarmStatus.SUPPRESSED) {
            throw new BusinessException(AlarmErrorCode.ALARM_STATE_CONFLICT);
        }
        return AlarmStatus.ACTIVE;
    }

    /** 열린 알람을 억제로 돌림(유지보수 시작·상위 알람 발생). ACTIVE·ACKNOWLEDGED만 */
    public static AlarmStatus suppress(AlarmStatus current) {
        if (current != AlarmStatus.ACTIVE && current != AlarmStatus.ACKNOWLEDGED) {
            throw new BusinessException(AlarmErrorCode.ALARM_STATE_CONFLICT);
        }
        return AlarmStatus.SUPPRESSED;
    }
}
