package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 위젯 집계 단위(DSH-04.06, BR-DSH-04, TC-DSH-044). 자동이면 차트 하나의 점이 2,000개를 넘지 않는 가장 작은 단위를 고른다.
 * 저장된 단위(원본·1분·1시간·1일, ADR-019 자체 집계 테이블)에 더해 1분 집계를 5분으로 묶은 단위를 쓴다(3일 범위가 1분이면 4,320점이라 1시간까지
 * 건너뛰지 않게). 원본을 고정했는데 범위가 2,000점을 넘을 만큼 길면(1분 간격 기준) WIDGET_QUERY_INVALID와 권장 단위를 준다.
 */
public enum WidgetResolution {
    RAW("raw", Duration.ZERO, "telemetry"),
    M1("1m", Duration.ofMinutes(1), "telemetry_1m"),
    M5("5m", Duration.ofMinutes(5), "telemetry_1m"),
    H1("1h", Duration.ofHours(1), "telemetry_1h"),
    D1("1d", Duration.ofDays(1), "telemetry_1d");

    /** 차트 하나의 점 수 상한(BR-DSH-04) */
    public static final int MAX_POINTS = 2000;
    /** 자동에서 원본을 후보로 보는 최대 범위(그보다 길면 집계부터 본다. 1일 → 1분, TC-DSH-044) */
    public static final Duration RAW_AUTO_MAX_SPAN = Duration.ofHours(6);

    private final String key;
    private final Duration bucket;
    private final String table;

    WidgetResolution(String key, Duration bucket, String table) {
        this.key = key;
        this.bucket = bucket;
        this.table = table;
    }

    public String key() {
        return key;
    }

    public Duration bucket() {
        return bucket;
    }

    /** data2flow_pipeline 아래 읽을 표(정해진 값만) */
    public String table() {
        return table;
    }

    /** 집계 표의 구간을 다시 묶어야 하는가(5분) */
    public boolean rebucket() {
        return this == M5;
    }

    /**
     * 단위를 고른다.
     *
     * @param requested 대시보드·위젯 값(AUTO·RAW·1m·5m·1h·1d, 대소문자 무시). null·AUTO면 자동
     * @param span      조회 범위
     * @param rawPoints 원본 점 수 추정(자동에서 원본이 후보일 때만 쓴다). 모르면 음수
     */
    public static WidgetResolution choose(String requested, Duration span, long rawPoints) {
        String r = requested == null ? "auto" : requested.strip().toLowerCase(Locale.ROOT);
        if (!"auto".equals(r)) {
            WidgetResolution fixed = parse(r);
            if (fixed == RAW) {
                long estimate = rawPoints >= 0 ? rawPoints : span.toMinutes();
                if (estimate > MAX_POINTS) {
                    throw new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID,
                            List.of(new FieldErrorDetail("resolution", "TOO_MANY_POINTS", auto(span, -1).key())));
                }
                return RAW;
            }
            if (points(span, fixed) > MAX_POINTS) {
                throw new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID,
                        List.of(new FieldErrorDetail("resolution", "TOO_MANY_POINTS", auto(span, -1).key())));
            }
            return fixed;
        }
        return auto(span, rawPoints);
    }

    static WidgetResolution auto(Duration span, long rawPoints) {
        if (rawPoints >= 0 && rawPoints <= MAX_POINTS && span.compareTo(RAW_AUTO_MAX_SPAN) <= 0) {
            return RAW;
        }
        for (WidgetResolution level : new WidgetResolution[]{M1, M5, H1}) {
            if (points(span, level) <= MAX_POINTS) {
                return level;
            }
        }
        return D1;
    }

    /** API 값 → 단위. 모르는 값은 400 INVALID_REQUEST 대신 WIDGET_QUERY_INVALID(필드 resolution) */
    public static WidgetResolution parse(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        for (WidgetResolution w : values()) {
            if (w.key.equals(v)) {
                return w;
            }
        }
        throw new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID, List.of(new FieldErrorDetail("resolution", "INVALID", raw)));
    }

    static long points(Duration span, WidgetResolution level) {
        long bucket = level.bucket.toSeconds();
        return (span.toSeconds() + bucket - 1) / bucket;
    }
}
