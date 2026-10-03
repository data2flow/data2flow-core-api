package net.java21.data2flow.core.telemetry.domain;

import java.util.Locale;

/**
 * 집계 함수(TSD-06.02): avg, min, max, sum, last, count, twa(시간 가중 평균). 집계 테이블의 같은 이름 열을 읽는다
 * (design/erd/pipeline.md telemetry_1m 열). avg·min·max·sum·twa는 정상 품질(0, 4) 값 기준이다(BR-TSD-04).
 */
public enum AggFunction {
    AVG("avg"), MIN("min"), MAX("max"), SUM("sum"), LAST("last"), COUNT("count"), TWA("twa");

    private final String key;

    AggFunction(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 집계 테이블 열 이름(정해진 값만) */
    public String column() {
        return key;
    }

    /** 대소문자 무시. 빈 값이면 null, 모르는 값이면 IllegalArgumentException */
    public static AggFunction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 측정 항목에 쓸 수 있는가: 열거형(ENUM) 값은 코드 번호라 평균·합이 뜻이 없으므로 last·count만 허용한다(TSD_INVALID_AGG).
     *
     * @param valueType 측정 항목 값 종류(NUMBER·BOOLEAN·ENUM), 모르면 null
     */
    public boolean allowedFor(String valueType) {
        if ("ENUM".equals(valueType)) {
            return this == LAST || this == COUNT;
        }
        return true;
    }
}
