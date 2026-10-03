package net.java21.data2flow.core.telemetry.domain;

import java.time.Duration;
import java.util.Locale;

/**
 * 조회 집계 단위(BR-TSD-08). 저장된 단위(원본, 1분·1시간·1일 집계) 중에서만 고른다(AT-TSD-01.1 참고).
 * 보관 기간 기본값은 TSD-02.01·domain-model §2.7 표(TELEMETRY 365일, AGG_1M 90일, AGG_1H 1,095일, AGG_1D 무기한)다.
 * 조직별 보관 정책(retention_policies)은 M5(TSD-05.01)에서 붙는다. 그때 {@link #defaultRetention()} 대신 정책 값을 넘긴다.
 */
public enum Resolution {
    RAW("raw", Duration.ZERO, "telemetry", Duration.ofDays(365)),
    M1("1m", Duration.ofMinutes(1), "telemetry_1m", Duration.ofDays(90)),
    H1("1h", Duration.ofHours(1), "telemetry_1h", Duration.ofDays(1095)),
    D1("1d", Duration.ofDays(1), "telemetry_1d", Duration.ZERO);

    private final String key;
    private final Duration bucket;
    private final String table;
    private final Duration retention;

    Resolution(String key, Duration bucket, String table, Duration retention) {
        this.key = key;
        this.bucket = bucket;
        this.table = table;
        this.retention = retention;
    }

    /** API 값({@code raw}·{@code 1m}·{@code 1h}·{@code 1d}) */
    public String key() {
        return key;
    }

    /** 구간 길이. 원본은 0 */
    public Duration bucket() {
        return bucket;
    }

    /** {@code data2flow_pipeline} 아래 테이블 이름(정해진 값만 쓰므로 SQL에 그대로 넣어도 안전하다) */
    public String table() {
        return table;
    }

    /** 기본 보관 기간. 0이면 무기한 */
    public Duration defaultRetention() {
        return retention;
    }

    public boolean aggregated() {
        return this != RAW;
    }

    /** {@code auto}·빈 값이면 null, 모르는 값이면 IllegalArgumentException */
    public static Resolution parse(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        for (Resolution r : values()) {
            if (r.key.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException(raw);
    }
}
