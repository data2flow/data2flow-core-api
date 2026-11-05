package net.java21.data2flow.core.energy.domain;

/**
 * 재실 추정 노출 규칙(BR-ENE-10, BR-ANA-25, NFR-13.02). 추정 인원이 3명 미만이거나 정원이 4명 이하(또는 모름)인 공간은 "사용 중/비어 있음"만
 * 내보내고, 그 밖에는 인원 구간(3~4, 5~9, 10~14 …)까지 낸다. 정확한 인원 숫자는 어디에도(API·내보내기·화면) 내보내지 않는다.
 * 지금 analytics 재실 추정은 0(비어 있음)/1(사용 중)만 내므로 구간은 나오지 않는다.
 */
public final class OccupancyExposurePolicy {

    public static final String OCCUPIED = "OCCUPIED";
    public static final String VACANT = "VACANT";
    public static final String UNKNOWN = "UNKNOWN";
    static final int MIN_COUNT = 3;
    static final int MAX_HIDDEN_CAPACITY = 4;

    private OccupancyExposurePolicy() {
    }

    /** 노출할 값: 상태와(가능하면) 인원 구간 */
    public record Exposure(String state, String band) {
    }

    /**
     * @param estimate 추정 인원(occupancy_est). null이면 UNKNOWN
     * @param capacity 공간 정원. null이면 모름(구간을 내지 않는다)
     */
    public static Exposure expose(Double estimate, Integer capacity) {
        if (estimate == null || estimate.isNaN()) {
            return new Exposure(UNKNOWN, null);
        }
        if (estimate < 0.5) {
            return new Exposure(VACANT, null);
        }
        long persons = Math.round(estimate);
        if (persons < MIN_COUNT || capacity == null || capacity <= MAX_HIDDEN_CAPACITY) {
            return new Exposure(OCCUPIED, null);
        }
        return new Exposure(OCCUPIED, band(persons));
    }

    /** 3~4, 5~9, 10~14, 15~19 … */
    static String band(long persons) {
        if (persons < 5) {
            return "3~4";
        }
        long low = persons / 5 * 5;
        return low + "~" + (low + 4);
    }
}
