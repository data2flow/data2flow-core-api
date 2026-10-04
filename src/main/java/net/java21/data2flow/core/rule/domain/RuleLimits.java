package net.java21.data2flow.core.rule.domain;

/** 한도(RUL-06.04, BR-RUL-21) */
public final class RuleLimits {

    /** 조직당 규칙(삭제·변환 제외) */
    public static final int MAX_RULES = 1000;
    /** 규칙당 대상 기기 */
    public static final int MAX_TARGETS = 5000;
    /** 조건 항목 수(묶음 안 항목 합) */
    public static final int MAX_CONDITIONS = 10;
    /** 묶음 중첩 깊이 */
    public static final int MAX_DEPTH = 2;
    /** 알람 메모 길이 */
    public static final int MAX_NOTE = 2000;
    /** 일괄 처리 건수 */
    public static final int MAX_BULK = 200;
    /** 시뮬레이션 기간(일) */
    public static final int MAX_SIMULATION_DAYS = 30;

    private RuleLimits() {
    }

    /** 규칙 수 한도 안인가(새로 하나 더 만들 수 있는가) */
    public static boolean canCreate(long currentCount) {
        return currentCount < MAX_RULES;
    }

    public static boolean targetsWithin(long targetCount) {
        return targetCount <= MAX_TARGETS;
    }

    public static boolean noteWithin(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_NOTE;
    }

    public static boolean bulkWithin(int size) {
        return size <= MAX_BULK;
    }
}
