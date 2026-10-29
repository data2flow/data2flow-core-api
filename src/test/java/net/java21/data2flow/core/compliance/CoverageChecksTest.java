package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.CoverageChecks;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.CalibrationStatus;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.DeviceSamples;
import net.java21.data2flow.core.compliance.domain.CoverageChecks.SpaceArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageChecksTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    static final long DAY = 86_400;

    @Test
    @DisplayName("[TC-CMP-072] 기준 500㎡: 1,200㎡는 필요 3대·실제 2대면 부족 1, 1,000㎡는 2대, 1,001㎡는 3대, 면적 없음·0은 판단 불가")
    void density() {
        assertThat(CoverageChecks.density(new BigDecimal("1200"), 500, 2)).isEqualTo(new CoverageChecks.Density(3, 1, null));
        assertThat(CoverageChecks.density(new BigDecimal("1000"), 500, 2).required()).isEqualTo(2);
        assertThat(CoverageChecks.density(new BigDecimal("1001"), 500, 5)).isEqualTo(new CoverageChecks.Density(3, 0, null));
        assertThat(CoverageChecks.density(null, 500, 1)).isEqualTo(new CoverageChecks.Density(null, null, "NO_AREA"));
        assertThat(CoverageChecks.density(BigDecimal.ZERO, 500, 1).undeterminedReason()).isEqualTo("NO_AREA");
    }

    @Test
    @DisplayName("[TC-CMP-075] 정기 재실 1,100㎡에 공개 화면 1개면 필요 3·부족 2, 추천은 넓은 순, 3개면 충족")
    void publicDisplay() {
        List<SpaceArea> spaces = List.of(new SpaceArea(1, "회의실", new BigDecimal("200")), new SpaceArea(2, "사무실", new BigDecimal("600")),
                new SpaceArea(3, "강의실", new BigDecimal("300")), new SpaceArea(4, "면적 없음", null));
        CoverageChecks.Display d = CoverageChecks.publicDisplay(spaces, 500, 1);
        assertThat(d.scopeAreaM2()).isEqualByComparingTo("1100");
        assertThat(d.required()).isEqualTo(3);
        assertThat(d.shortage()).isEqualTo(2);
        assertThat(d.recommendations()).extracting(SpaceArea::name).containsExactly("사무실", "강의실");
        assertThat(CoverageChecks.publicDisplay(spaces, 500, 3).shortage()).isZero();
        assertThat(CoverageChecks.publicDisplay(List.of(), 500, 0).required()).isZero();
    }

    @Test
    @DisplayName("[TC-CMP-077] 기한까지 31일이면 정상, 30일이면 임박, 지나면 초과, 400일 전 교정은 초과, 기록 없으면 판단 불가")
    void calibration() {
        assertThat(CoverageChecks.calibration(TODAY.minusDays(365 - 31), 365, TODAY).status()).isEqualTo(CalibrationStatus.OK);
        assertThat(CoverageChecks.calibration(TODAY.minusDays(365 - 30), 365, TODAY).status()).isEqualTo(CalibrationStatus.DUE_SOON);
        assertThat(CoverageChecks.calibration(TODAY.minusDays(366), 365, TODAY).status()).isEqualTo(CalibrationStatus.OVERDUE);
        CoverageChecks.Calibration old = CoverageChecks.calibration(TODAY.minusDays(400), 365, TODAY);
        assertThat(old.status()).isEqualTo(CalibrationStatus.OVERDUE);
        assertThat(old.elapsedDays()).isEqualTo(400);
        assertThat(old.dueOn()).isEqualTo(TODAY.minusDays(35));
        assertThat(CoverageChecks.calibration(null, 365, TODAY).status()).isEqualTo(CalibrationStatus.NO_RECORD);
    }

    @Test
    @DisplayName("[TC-CMP-079] 결측 15%는 미달, 10.0%는 아님. 비정상 품질은 결측으로 센다. 수신 간격이 기준보다 길면 주기 미달")
    void quality() {
        // 1분 주기 1일 = 1440개
        assertThat(CoverageChecks.quality(List.of(new DeviceSamples(DAY, 60, 1224, 1440)), 0.10, 900))
                .isEqualTo(new CoverageChecks.Quality(0.15, 0, 0.15, true));
        CoverageChecks.Quality ten = CoverageChecks.quality(List.of(new DeviceSamples(DAY, 60, 1296, 1296)), 0.10, 900);
        assertThat(ten.missingRatio()).isEqualTo(0.10);
        assertThat(ten.belowStandard()).isFalse();
        // 기기 기본 주기 10분인데 30분마다 왔다
        CoverageChecks.Quality slow = CoverageChecks.quality(List.of(new DeviceSamples(DAY, 600, 48, 48), new DeviceSamples(DAY, 60, 1440, 1440)),
                0.10, 900);
        assertThat(slow.intervalViolations()).isEqualTo(1);
        assertThat(slow.missingRatio()).isEqualTo(0.3333);
        assertThat(CoverageChecks.quality(List.of(new DeviceSamples(DAY, 60, 0, 0)), 0.10, 900).intervalViolations()).isEqualTo(1);
        assertThat(CoverageChecks.quality(List.of(), 0.10, 900).missingRatio()).isNull();
    }
}
