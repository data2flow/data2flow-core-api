package net.java21.data2flow.core.telemetry.domain;

import net.java21.data2flow.contracts.error.BusinessException;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 집계 단위 고르기(BR-TSD-08, BR-TSD-09, TSD-03.01, TSD-06.01).
 * <ul>
 *   <li>auto: 1,000포인트를 넘지 않는 가장 작은 저장 단위. 원본은 실제 점 수(최대 1,001까지 센 값)로, 집계는 기간 ÷ 구간 길이로 판단한다.
 *       보관 기간 밖이라 건너뛴 단위가 있으면 reason=CAPPED, 아니면 AUTO</li>
 *   <li>직접 지정: 원본은 31일까지(넘으면 TSD_RANGE_TOO_LARGE), 보관 기간 밖이면 TSD_RESOLUTION_UNAVAILABLE(AT-TSD-01.5),
 *       집계는 10,000점까지(AT-TSD-01.1 참고 문단). reason=REQUESTED</li>
 *   <li>원본을 쓸 수 없는 조회(공간 집계)에서 원본을 지정하면 1분 집계로 바꾸고 reason=CAPPED</li>
 * </ul>
 */
public final class ResolutionPlanner {

    /** 자동 선택의 점 수 상한(TSD-03.01) */
    public static final int AUTO_MAX_POINTS = 1000;
    /** 응답당 최대 점 수(TSD-06.01) */
    public static final int MAX_POINTS = 10_000;
    /** 원본 한 번에 조회할 수 있는 기간(BR-TSD-09) */
    public static final Duration RAW_MAX_SPAN = Duration.ofDays(31);

    private ResolutionPlanner() {
    }

    /** 고른 단위와 사유(AUTO·REQUESTED·CAPPED) */
    public record Plan(Resolution resolution, String reason) {
    }

    /**
     * @param requested  요청 단위(null이면 auto)
     * @param rawAllowed 원본 조회가 가능한가(공간 집계는 false)
     * @param rawCount   원본 점 수(가장 많은 계열 기준, 1,001에서 멈춰도 된다). auto에서 원본이 후보일 때만 부른다
     */
    public static Plan plan(Resolution requested, Instant from, Instant to, Instant now, boolean rawAllowed, LongSupplier rawCount) {
        Duration span = Duration.between(from, to);
        if (requested == Resolution.RAW && !rawAllowed) {
            return new Plan(checkAggregated(Resolution.M1, from, span, now), "CAPPED");
        }
        if (requested == Resolution.RAW) {
            if (span.compareTo(RAW_MAX_SPAN) > 0) {
                throw new BusinessException(TelemetryErrorCode.TSD_RANGE_TOO_LARGE);
            }
            if (!retained(Resolution.RAW, from, now)) {
                throw new BusinessException(TelemetryErrorCode.TSD_RESOLUTION_UNAVAILABLE, Resolution.RAW.key());
            }
            return new Plan(Resolution.RAW, "REQUESTED");
        }
        if (requested != null) {
            return new Plan(checkAggregated(requested, from, span, now), "REQUESTED");
        }
        boolean capped = false;
        if (rawAllowed && span.compareTo(RAW_MAX_SPAN) <= 0) {
            if (retained(Resolution.RAW, from, now)) {
                if (rawCount.getAsLong() <= AUTO_MAX_POINTS) {
                    return new Plan(Resolution.RAW, "AUTO");
                }
            } else {
                capped = true;
            }
        }
        for (Resolution level : new Resolution[]{Resolution.M1, Resolution.H1}) {
            if (points(span, level) <= AUTO_MAX_POINTS) {
                if (retained(level, from, now)) {
                    return new Plan(level, capped ? "CAPPED" : "AUTO");
                }
                capped = true;
            }
        }
        return new Plan(Resolution.D1, capped ? "CAPPED" : "AUTO");
    }

    private static Resolution checkAggregated(Resolution level, Instant from, Duration span, Instant now) {
        if (!retained(level, from, now)) {
            throw new BusinessException(TelemetryErrorCode.TSD_RESOLUTION_UNAVAILABLE, level.key());
        }
        if (points(span, level) > MAX_POINTS) {
            throw new BusinessException(TelemetryErrorCode.TSD_RANGE_TOO_LARGE);
        }
        return level;
    }

    /** 기간을 이 단위로 나눈 구간 수(올림) */
    static long points(Duration span, Resolution level) {
        long bucket = level.bucket().toSeconds();
        return (span.toSeconds() + bucket - 1) / bucket;
    }

    /** 조회 시작이 이 단위의 보관 기간 안인가 */
    public static boolean retained(Resolution level, Instant from, Instant now) {
        Duration retention = level.defaultRetention();
        return retention.isZero() || !from.isBefore(now.minus(retention));
    }
}
