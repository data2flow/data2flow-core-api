package net.java21.data2flow.core.retention.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 보관 대상 데이터 종류와 기본값·최소값(spec/detail/TSD/domain-model.md §2.7, NFR-04.03). 측정 항목·모델 재정의는 시계열과 집계에만 둔다.
 * AGG_1D는 무기한(0)이고 줄일 수 없다(BR-OPS-08).
 */
public enum DataClass {
    RAW_MESSAGE(30, 7, false),
    TELEMETRY(365, 30, true),
    LINK(90, 7, false),
    AGG_1M(90, 30, true),
    AGG_1H(1095, 365, true),
    AGG_1D(0, 0, true),
    FLOW_EXECUTION(30, 7, false),
    ANALYSIS_RESULT(365, 30, false),
    AUDIT_LOG(365, 365, false),
    NOTIFICATION_DELIVERY(90, 30, false),
    COMMAND(365, 90, false),
    DEVICE_STATE_HISTORY(365, 90, false),
    WEBHOOK_DELIVERY(90, 30, false);

    /** 보관 일수 상한(10년). ANALYSIS_RESULT는 1,095일(API-ANA-25) */
    public static final int MAX_DAYS = 3650;
    /** 시계열 정렬 재작성(압축) 기본 일수(TSD-02.03) */
    public static final int DEFAULT_COMPRESS_AFTER_DAYS = 7;

    private final int defaultDays;
    private final int minDays;
    private final boolean overridable;

    DataClass(int defaultDays, int minDays, boolean overridable) {
        this.defaultDays = defaultDays;
        this.minDays = minDays;
        this.overridable = overridable;
    }

    public int defaultDays() {
        return defaultDays;
    }

    public int minDays() {
        return minDays;
    }

    /** MODEL·METRIC 범위 재정의를 받는 종류(TELEMETRY·AGG_*) */
    public boolean overridable() {
        return overridable;
    }

    public int maxDays() {
        return this == ANALYSIS_RESULT ? 1095 : MAX_DAYS;
    }

    /** 이 일수가 허용 범위 안인가. AGG_1D는 무기한(0)만 */
    public boolean allows(int days) {
        if (this == AGG_1D) {
            return days == 0;
        }
        return days >= minDays && days <= maxDays();
    }

    public static Optional<DataClass> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(d -> d.name().equals(v)).findFirst();
    }

    /** a보다 b가 짧은가(0 = 무기한은 가장 길다) */
    public static boolean shorter(int newDays, int oldDays) {
        if (newDays == oldDays) {
            return false;
        }
        if (newDays == 0) {
            return false;
        }
        return oldDays == 0 || newDays < oldDays;
    }
}
