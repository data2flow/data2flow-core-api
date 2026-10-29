package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 모니터링 커버리지 점검 계산(CMP-03, UC-CMP-11).
 *
 * <ul>
 *   <li>설치 밀도(BR-CMP-19): 필요 대수 = ceil(면적 / 기준 면적). 면적이 없거나 0이면 판단 불가</li>
 *   <li>공개 표시(BR-CMP-20): 필요 수 = ceil(정기 재실 공간 면적 합 / 기준 면적). 추천 위치는 면적이 넓은 정기 재실 공간 순</li>
 *   <li>교정 기한(BR-CMP-21): 기한 = 마지막 교정일 + 주기. 기한 30일 전부터 임박, 넘으면 초과. 기록이 없으면 판단 불가</li>
 *   <li>데이터 품질(CMP-03.04): 결측률(품질 코드가 정상이 아닌 값도 결측)이 기준을 넘으면 미달, 평균 수신 간격이 기준보다 길면 주기 미달</li>
 * </ul>
 */
public final class CoverageChecks {

    /** 정기 재실 공간으로 보는 용도(공간에 따로 표시가 없어 용도로 정한다) */
    public static final Set<String> REGULAR_USAGES = Set.of("OFFICE", "CLASSROOM", "LAB", "MEETING");
    public static final int DUE_SOON_DAYS = 30;
    public static final int DEFAULT_CALIBRATION_DAYS = 365;
    public static final double DEFAULT_MAX_MISSING_RATIO = 0.10;
    public static final int DEFAULT_MAX_INTERVAL_SEC = 900;

    private CoverageChecks() {
    }

    public record Density(Integer required, Integer shortage, String undeterminedReason) {
    }

    /** 설치 밀도 한 공간 */
    public static Density density(BigDecimal areaM2, int maxAreaPerDeviceM2, int actual) {
        if (areaM2 == null || areaM2.signum() <= 0) {
            return new Density(null, null, "NO_AREA");
        }
        int required = areaM2.divide(BigDecimal.valueOf(maxAreaPerDeviceM2), 0, RoundingMode.CEILING).intValueExact();
        return new Density(required, Math.max(0, required - actual), null);
    }

    public record SpaceArea(long spaceId, String name, BigDecimal areaM2) {
    }

    public record Display(BigDecimal scopeAreaM2, int required, int shortage, List<SpaceArea> recommendations) {
    }

    /** 공개 표시. 면적이 없는 공간은 범위에서 뺀다. 추천은 부족한 수만큼 넓은 순으로 */
    public static Display publicDisplay(List<SpaceArea> regularSpaces, int areaPerDisplayM2, int actual) {
        List<SpaceArea> withArea = regularSpaces.stream().filter(s -> s.areaM2() != null && s.areaM2().signum() > 0)
                .sorted(Comparator.comparing(SpaceArea::areaM2).reversed().thenComparing(SpaceArea::spaceId)).toList();
        BigDecimal scope = withArea.stream().map(SpaceArea::areaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
        int required = scope.signum() == 0 ? 0 : scope.divide(BigDecimal.valueOf(areaPerDisplayM2), 0, RoundingMode.CEILING).intValueExact();
        int shortage = Math.max(0, required - actual);
        return new Display(scope, required, shortage, withArea.subList(0, Math.min(shortage, withArea.size())));
    }

    public enum CalibrationStatus { OK, DUE_SOON, OVERDUE, NO_RECORD }

    public record Calibration(LocalDate dueOn, Long elapsedDays, CalibrationStatus status) {
    }

    /** 교정 기한. 남은 날이 30일 이하면 임박, 기한이 지났으면 초과 */
    public static Calibration calibration(LocalDate lastCalibratedOn, int intervalDays, LocalDate today) {
        if (lastCalibratedOn == null) {
            return new Calibration(null, null, CalibrationStatus.NO_RECORD);
        }
        LocalDate due = lastCalibratedOn.plusDays(intervalDays);
        long left = ChronoUnit.DAYS.between(today, due);
        CalibrationStatus status = left < 0 ? CalibrationStatus.OVERDUE : left <= DUE_SOON_DAYS ? CalibrationStatus.DUE_SOON : CalibrationStatus.OK;
        return new Calibration(due, ChronoUnit.DAYS.between(lastCalibratedOn, today), status);
    }

    /** 기기 하나의 기간 수신 통계: 기대 시간(초), 정상 품질 수, 전체 수 */
    public record DeviceSamples(long expectedSeconds, int intervalSec, long good, long all) {
    }

    public record Quality(Double missingRatio, int intervalViolations, Double abnormalQualityRatio, boolean belowStandard) {
    }

    /**
     * 공간 하나의 데이터 품질. 결측률은 기기별 1 − 정상 수 / 기대 수(모델 기본 주기)의 평균이고, 평균 수신 간격(기대 시간 / 전체 수)이
     * 기준보다 길면 주기 미달 기기다. 기준 결측률을 넘을 때만(같으면 아님) 미달이다
     */
    public static Quality quality(List<DeviceSamples> devices, double maxMissingRatio, int maxIntervalSec) {
        double missing = 0;
        int counted = 0;
        int violations = 0;
        long good = 0;
        long all = 0;
        for (DeviceSamples d : devices) {
            if (d.expectedSeconds() <= 0 || d.intervalSec() <= 0) {
                continue;
            }
            double expected = (double) d.expectedSeconds() / d.intervalSec();
            missing += Math.max(0, 1 - Math.min(1, d.good() / expected));
            counted++;
            if (d.all() == 0 || (double) d.expectedSeconds() / d.all() > maxIntervalSec) {
                violations++;
            }
            good += d.good();
            all += d.all();
        }
        Double ratio = counted == 0 ? null : round4(missing / counted);
        Double abnormal = all == 0 ? null : round4(1 - (double) good / all);
        return new Quality(ratio, violations, abnormal, ratio != null && ratio > maxMissingRatio);
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
