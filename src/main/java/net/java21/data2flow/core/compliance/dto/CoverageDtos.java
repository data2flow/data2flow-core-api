package net.java21.data2flow.core.compliance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 커버리지 점검 API-CMP-25 응답. 문서의 모양에 summary(점검 4종 신호등 GREEN·YELLOW·RED·NA), profile, period, 공개 표시의
 * actualAvailable(공개 화면·QR 기능이 아직 없으면 false)을 더했다. 결측률·비정상 비율은 %다.
 */
public final class CoverageDtos {

    private CoverageDtos() {
    }

    public record Ref(String id, String name) {
    }

    public record ProfileRef(String profileId, String name, int versionNo) {
    }

    public record Summary(String density, String publicDisplay, String calibration, String quality) {
    }

    public record DensityRow(Ref space, String usage, BigDecimal areaM2, Integer required, int actual, Integer shortage, String undeterminedReason) {
    }

    public record DisplayActual(int links, int qr) {
    }

    public record Recommendation(String spaceId, String name, BigDecimal areaM2) {
    }

    public record PublicDisplay(BigDecimal scopeAreaM2, int required, DisplayActual actual, int shortage, boolean actualAvailable,
                                List<Recommendation> recommendations) {
    }

    public record CalibrationRow(Ref device, Ref space, String model, int intervalDays, LocalDate lastCalibratedOn, LocalDate dueOn,
                                 Long elapsedDays, String status) {
    }

    public record QualityRow(Ref space, int devices, Double missingRatio, int intervalViolations, Double abnormalQualityRatio,
                             boolean belowStandard) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    public record CoverageView(String siteId, ProfileRef profile, Period period, Summary summary, List<DensityRow> density,
                               PublicDisplay publicDisplay, List<CalibrationRow> calibration, List<QualityRow> quality) {
    }
}
