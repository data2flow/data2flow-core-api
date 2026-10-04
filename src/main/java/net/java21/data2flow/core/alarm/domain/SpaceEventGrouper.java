package net.java21.data2flow.core.alarm.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 같은 원인 묶기(RUL-04.03, BR-RUL-11): 같은 공간에서 5분 안에 서로 다른 규칙 알람이 2건 이상이면 space_event로 묶는다.
 * 표시용이고 알림은 정책의 묶기 설정을 따른다.
 */
public final class SpaceEventGrouper {

    public static final Duration WINDOW = Duration.ofMinutes(5);

    private SpaceEventGrouper() {
    }

    /** 결정: 기존 묶음에 넣기, 새 묶음 만들기(직전 알람과 함께), 묶지 않기 */
    public enum Decision { JOIN, OPEN, NONE }

    /**
     * @param openEventAt          그 공간의 열린 묶음 시작 시각(없으면 null)
     * @param previousOtherRuleAt  5분 안에 같은 공간에서 다른 규칙(또는 다른 출처) 알람이 난 시각(없으면 null)
     * @param raisedAt             이번 알람 발생 시각
     */
    public static Decision decide(Instant openEventAt, Instant previousOtherRuleAt, Instant raisedAt) {
        if (openEventAt != null && !openEventAt.plus(WINDOW).isBefore(raisedAt)) {
            return Decision.JOIN;
        }
        if (previousOtherRuleAt != null && !previousOtherRuleAt.plus(WINDOW).isBefore(raisedAt)) {
            return Decision.OPEN;
        }
        return Decision.NONE;
    }
}
