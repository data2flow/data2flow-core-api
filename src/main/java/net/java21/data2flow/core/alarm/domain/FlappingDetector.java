package net.java21.data2flow.core.alarm.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 플래핑 판정(RUL-04.02, BR-RUL-10): 같은 alarm_key가 30분 안에 발생 6회 이상이면 FLAPPING_ON, 30분 동안 상태 변화가 없으면
 * FLAPPING_OFF. 플래핑 중 알림은 SKIPPED(FLAPPING).
 */
public final class FlappingDetector {

    public static final Duration WINDOW = Duration.ofMinutes(30);
    /** 창 안 발생 횟수가 이 값 이상이면 플래핑(5회는 정상, 6번째에 켜짐) */
    public static final int THRESHOLD = 6;

    private FlappingDetector() {
    }

    /**
     * 이번 발생까지 포함한 발생 시각 목록으로 플래핑인지 본다.
     *
     * @param raises 같은 alarm_key의 발생 시각(이번 발생 포함, 순서 무관)
     * @param now    이번 발생 시각
     */
    public static boolean flapping(List<Instant> raises, Instant now) {
        Instant from = now.minus(WINDOW);
        long count = raises.stream().filter(t -> !t.isBefore(from) && !t.isAfter(now)).count();
        return count >= THRESHOLD;
    }

    /** 마지막 상태 변화 뒤 30분이 지났으면 플래핑을 끈다 */
    public static boolean settled(Instant lastChangeAt, Instant now) {
        return lastChangeAt == null || !lastChangeAt.plus(WINDOW).isAfter(now);
    }
}
