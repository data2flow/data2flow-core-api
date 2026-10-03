package net.java21.data2flow.core.telemetry.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 시계열 점 하나. 응답에서는 {@code [시각, 값, 품질(원본) 또는 표본 수(집계)]} 배열이다(API-TSD-02).
 * 채우기(BR-TSD-10)로 만든 점은 표본 수 0이다.
 *
 * @param t     구간 시작(집계) 또는 측정 시각(원본), UTC
 * @param value 값. 표본이 없으면 null
 * @param third 원본은 품질 코드, 집계는 표본 수
 */
public record SeriesPoint(Instant t, Double value, Integer third) {

    /** 응답 배열 {@code ["2026-10-03T00:00:00Z", 22.4, 0]} */
    public List<Object> toArray() {
        return Arrays.asList(t.toString(), value, third);
    }
}
