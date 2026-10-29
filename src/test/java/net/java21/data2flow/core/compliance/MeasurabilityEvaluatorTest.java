package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.DeviceCoverage;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.Named;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.Result;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CMP-01.04 측정 가능성(BR-CMP-06) — TC-CMP-025 단위 몫 */
class MeasurabilityEvaluatorTest {

    @Test
    @DisplayName("[TC-CMP-025] 말단 공간 둘 중 CO2는 둘 다 → MEASURABLE, PM2.5는 하나 → PARTIAL(없는 공간), TVOC는 없음 → NOT_MEASURABLE(NO_SENSOR)")
    void statuses() {
        List<Named> leaves = List.of(new Named(11, "실습실 A"), new Named(12, "실습실 B"));
        List<DeviceCoverage> devices = List.of(new DeviceCoverage(1, "AM103 A", Set.of(11L), Set.of("co2", "pm2_5")),
                new DeviceCoverage(2, "EM500 B", Set.of(12L), Set.of("co2")));
        List<Result> out = MeasurabilityEvaluator.evaluate(List.of("co2", "pm2_5", "tvoc", "co2"), leaves, devices);
        assertThat(out).extracting(Result::metricKey).containsExactly("co2", "pm2_5", "tvoc");
        assertThat(out.get(0).status()).isEqualTo(Status.MEASURABLE);
        assertThat(out.get(0).spacesWithout()).isEmpty();
        assertThat(out.get(0).devices()).extracting(Named::id).containsExactly(1L, 2L);
        assertThat(out.get(1).status()).isEqualTo(Status.PARTIAL);
        assertThat(out.get(1).spacesWithout()).extracting(Named::name).containsExactly("실습실 B");
        assertThat(out.get(2).status()).isEqualTo(Status.NOT_MEASURABLE);
        assertThat(out.get(2).reason()).isEqualTo(MeasurabilityEvaluator.NO_SENSOR);
    }

    @Test
    @DisplayName("[BR-CMP-06] 상위 공간에 놓인 기기는 아래 말단을 모두 덮는다(호출 쪽이 덮는 말단을 넘김)")
    void parentDevice() {
        List<Named> leaves = List.of(new Named(11, "A"), new Named(12, "B"));
        List<Result> out = MeasurabilityEvaluator.evaluate(List.of("co2"), leaves,
                List.of(new DeviceCoverage(9, "복도 센서", Set.of(11L, 12L), Set.of("co2"))));
        assertThat(out.getFirst().status()).isEqualTo(Status.MEASURABLE);
    }
}
