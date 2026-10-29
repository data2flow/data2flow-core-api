package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Aggregate;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Device;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Excursion;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Request;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Rows;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Version;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ReportModels.DeviceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportModels.SpaceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CMP-02.02 리포트 모델 — TC-CMP-045(수치), TC-CMP-047(늦게 온 데이터), TC-CMP-048(교정 기한, BR-CMP-21) */
class KrSelfCheckReportBuilderTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    static final MetricRule CO2 = new MetricRule("co2", null, "INSTANT", null, BigDecimal.valueOf(1000), true, "ppm", "LIMIT", null, List.of());

    private static Model build(List<Excursion> excursions, Rows rows) {
        Request req = new Request("점검", "GENERAL", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), SEOUL);
        List<SpaceItem> spaces = List.of(new SpaceItem(2, "사무실", "/1/2/"), new SpaceItem(3, "실습실", "/1/3/"));
        List<Version> versions = List.of(new Version(7, "국내법", 70, 1, "실내공기질관리법", LocalDate.of(2025, 1, 1), List.of(CO2)));
        // 실습실: 충족 1200·이탈 120·결측 120, 평균 (800×1200 + 1200×120) / 1320
        List<Aggregate> aggregates = List.of(
                new Aggregate(3, 70, "co2", 1200, 120, 120, BigDecimal.valueOf(1200), BigDecimal.valueOf(800L * 1200 + 1200L * 120), 1),
                new Aggregate(2, 70, "co2", 1440, 0, 0, BigDecimal.valueOf(600), BigDecimal.valueOf(500L * 1440), 1));
        List<Device> devices = List.of(
                new Device(11, "b", "실습실", "AM103", List.of("co2"), LocalDate.of(2026, 3, 1), 180),
                new Device(10, "a", "실습실", "AM103", List.of("co2"), LocalDate.of(2026, 5, 1), 180),
                new Device(12, "c", "사무실", "AM103", List.of("co2"), null, 180));
        return KrSelfCheckReportBuilder.build(req, spaces, versions, aggregates, excursions, devices, List.of(), rows, TODAY, Map.of("co2", "ppm"));
    }

    @Test
    @DisplayName("[TC-CMP-045] 항목 통계: 평균·최대·충족률·결측률·이탈 시간, 기간 안에 시작한 이탈 구간만 센다, 결과는 공간 경로 순")
    void metrics() {
        List<Excursion> excursions = List.of(
                new Excursion(3, 70, "co2", Instant.parse("2026-10-02T02:00:00Z"), Instant.parse("2026-10-02T04:00:00Z"), BigDecimal.valueOf(1200)),
                // 기간 시작(서울 10/1 00:00 = 9/30 15:00Z) 전에 시작한 구간은 뺀다
                new Excursion(3, 70, "co2", Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T16:00:00Z"), BigDecimal.valueOf(1100)));
        Model m = build(excursions, new Rows(2880, 1, null));

        assertThat(m.metrics()).extracting(s -> s.spaceName()).containsExactly("사무실", "실습실");
        var lab = m.metrics().get(1);
        assertThat(lab.average()).isEqualByComparingTo("836.4");
        assertThat(lab.max()).isEqualByComparingTo("1200.0");
        assertThat(lab.ratio()).isEqualByComparingTo("90.91");
        assertThat(lab.missingRatio()).isEqualByComparingTo("8.33");
        assertThat(lab.exceededHours()).isEqualByComparingTo("2.0");
        assertThat(lab.exceedances()).isEqualTo(1);
        assertThat(lab.upper()).isEqualByComparingTo("1000");
        assertThat(m.excursions()).hasSize(1);
        assertThat(m.summary().excursions()).isEqualTo(1);
        assertThat(m.summary().ratio()).isEqualByComparingTo("95.65");
        assertThat(m.summary().qualityWarning()).isFalse();
        assertThat(m.dataRange().from()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(m.dataRange().to()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
        assertThat(m.dataRange().excludedVirtualDevices()).isEqualTo(1);
        assertThat(m.dataRange().lateRowsSincePrevious()).isZero();
    }

    @Test
    @DisplayName("[TC-CMP-047] 이전 판보다 행이 늘면 늘어난 수, 줄면 0")
    void lateRows() {
        assertThat(build(List.of(), new Rows(2900, 0, 2880L)).dataRange().lateRowsSincePrevious()).isEqualTo(20);
        assertThat(build(List.of(), new Rows(2800, 0, 2880L)).dataRange().lateRowsSincePrevious()).isZero();
    }

    @Test
    @DisplayName("[TC-CMP-048] 교정 기한 = 마지막 교정 + 주기: 지나면 OVERDUE, 30일 안이면 DUE_SOON, 기록 없으면 NO_RECORD")
    void calibration() {
        List<DeviceItem> devices = build(List.of(), new Rows(0, 0, null)).devices();
        assertThat(devices).extracting(DeviceItem::id).containsExactly(12L, 10L, 11L);
        assertThat(devices.get(0).calibration()).isEqualTo("NO_RECORD");
        assertThat(devices.get(0).dueOn()).isNull();
        // 5/1 + 180 = 10/28: 25일 남음
        assertThat(devices.get(1).dueOn()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(devices.get(1).calibration()).isEqualTo("DUE_SOON");
        // 3/1 + 180 = 8/28: 지남
        assertThat(devices.get(2).calibration()).isEqualTo("OVERDUE");
        Device fresh = new Device(13, "d", "실습실", null, List.of(), LocalDate.of(2026, 9, 1), 365);
        assertThat(KrSelfCheckReportBuilder.build(new Request("t", null, TODAY, TODAY, SEOUL), List.of(), List.of(), List.of(), List.of(),
                List.of(fresh), List.of(), new Rows(0, 0, null), TODAY, Map.of()).devices().getFirst().calibration()).isEqualTo("OK");
    }
}
