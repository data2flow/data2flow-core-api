package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 준수 리포트 모델(CMP-02.02). 판에 JSON으로 저장하고 PDF는 이 값만으로 그린다. 같은 조건이면 같은 모델이 나와야 하므로(BR-CMP-12) 생성 시각·
 * 생성자·판 번호처럼 판마다 다른 값은 넣지 않고 {@link Meta}로 따로 넘긴다. 비율은 모두 %(소수 둘째 자리), 평균·최대는 소수 첫째 자리다.
 */
public final class ReportModels {

    public static final String KR_SELF_CHECK = "KR_SELF_CHECK";
    public static final List<String> TYPES = List.of(KR_SELF_CHECK, "RESET_SUPPORT", "WELL_A08_SUPPORT");

    /** 첫 장 고지(BR-CMP-13). 끌 수 없다 */
    public static final String DISCLAIMER = "법정 측정(공인 측정기관 측정)이나 인증기관 심사를 대체하지 않는 자가 점검·신청 보조 자료";

    private ReportModels() {
    }

    public record Model(String type, String title, String facilityType, LocalDate periodFrom, LocalDate periodTo, String timezone,
                        List<SpaceItem> spaces, List<ProfileItem> profiles, Summary summary, List<MetricStat> metrics,
                        List<DeviceItem> devices, List<Unmeasurable> unmeasurable, List<ExcursionItem> excursions, DataRange dataRange) {
    }

    public record SpaceItem(long id, String name, String path) {
    }

    public record ProfileItem(long profileId, String name, long versionId, int versionNo, String sourceTitle, LocalDate effectiveDate) {
    }

    /** 전체 충족률·결측률, 품질 경고(결측 10% 초과, UC-CMP-07 3a), 이탈 구간 수 */
    public record Summary(BigDecimal ratio, BigDecimal missingRatio, boolean qualityWarning, int excursions) {
    }

    /** 공간 × 항목 통계: 평균·최대·기준 초과 횟수(기간 안에 시작한 이탈 구간 수) */
    public record MetricStat(long spaceId, String spaceName, String metricKey, String unit, String evalWindow, BigDecimal lower,
                             BigDecimal upper, BigDecimal average, BigDecimal max, int exceedances, BigDecimal exceededHours, BigDecimal ratio,
                             BigDecimal missingRatio, int days) {
    }

    /** 측정 기기와 교정 이력(BR-CMP-21). calibration: OK, DUE_SOON, OVERDUE, NO_RECORD */
    public record DeviceItem(long id, String name, String spacePath, String model, List<String> metrics, LocalDate lastCalibratedOn,
                             int intervalDays, LocalDate dueOn, String calibration) {
    }

    /** 측정 불가·일부 공간만 측정되는 항목(BR-CMP-06) */
    public record Unmeasurable(String metricKey, String status, List<String> spacesWithout) {
    }

    /** 이탈 구간과 개선 조치(CMP-02.05가 붙으면 조치 내용이 채워진다) */
    public record ExcursionItem(long spaceId, String spaceName, String metricKey, Instant startedAt, Instant endedAt, BigDecimal peakValue,
                                String action) {
    }

    /** 실제 쓴 데이터 범위(UTC), 1분 집계 행 수, 결측률, 뺀 가상 기기 수, 이전 판 이후 늘어난 행 수(BR-CMP-12) */
    public record DataRange(Instant from, Instant to, long rows, BigDecimal missingRatio, int excludedVirtualDevices, long lateRowsSincePrevious) {
    }

    /** 판마다 다른 값(PDF 표지·쪽 아래). draft면 모든 쪽에 "초안" 워터마크 */
    public record Meta(long reportId, int revisionNo, Instant generatedAt, String generatedBy, Instant publishedAt, String publishedBy,
                       boolean draft) {
    }
}
