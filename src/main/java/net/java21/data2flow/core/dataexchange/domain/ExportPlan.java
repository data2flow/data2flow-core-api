package net.java21.data2flow.core.dataexchange.domain;

import java.time.Instant;
import java.util.List;

/**
 * 요청 시점에 권한 범위로 확정한 내보내기 계획(export_jobs.plan). 비동기 실행기·정기 실행기는 다시 권한을 묻지 않고 이 계획대로 쓴다
 * (AT-TSD-04.5: 공간 제한 사용자는 권한 범위 기기만).
 *
 * @param resolution      raw·1m·1h·1d
 * @param qualities       원본에서 포함할 품질 코드(normal = 0·4, all = 0~4, 예보 포함 시 5)
 * @param layout          LONG·WIDE
 * @param temperatureUnit 표시 온도 단위 C·F(DEV-04.04). F면 ℃ 측정 항목에 표시 값·표시 단위 열을 더한다
 */
public record ExportPlan(String resolution, Instant from, Instant to, String timezone, String layout, boolean includeQuality,
                         List<Integer> qualities, boolean virtual, String temperatureUnit, List<PlanSeries> series) {

    public ExportPlan {
        qualities = qualities == null ? List.of(0, 4) : List.copyOf(qualities);
        series = series == null ? List.of() : List.copyOf(series);
    }

    public boolean raw() {
        return "raw".equals(resolution);
    }

    public boolean wide() {
        return "WIDE".equals(layout);
    }

    /**
     * 계열 하나. kind=DEVICE면 deviceId, kind=SPACE면 spaceId와 권한 범위 안 대상 기기(deviceIds).
     *
     * @param agg 집계 단위일 때 쓰는 값 열(avg·min·max·sum·last·count·twa), 공간이면 공간 함수
     */
    public record PlanSeries(String kind, Long deviceId, String deviceName, Long spaceId, String spacePath, String metric, String unit,
                             String agg, String label, List<Long> deviceIds) {

        public PlanSeries {
            deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
        }

        public boolean space() {
            return "SPACE".equals(kind);
        }

        /** 넓은 형식 열 이름: label → {기기|공간 이름}.{측정 항목} */
        public String columnName() {
            if (label != null && !label.isBlank()) {
                return label;
            }
            return (deviceName == null ? "" : deviceName) + "." + metric;
        }
    }
}
