package net.java21.data2flow.core.device.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceRulesTest {

    @Test
    @DisplayName("[DEV-02.01][BR-DEV-06][AT-DEV-04.1] 외부 ID는 소문자·공백 제거로 정규화 — TC-DEV-031")
    void normalizesExternalId() {
        assertThat(DeviceRules.normalizeExternalId(" 24E1 2413 6D15 1606 ")).isEqualTo("24e124136d151606");
        assertThat(DeviceRules.normalizeExternalId(null)).isNull();
    }

    @Test
    @DisplayName("[DEV-02.10][BR-DEV-11][AT-DEV-08.2] 'pilot'에 'Pilot'을 더하면 중복으로 보고 처음 표기 1개 유지 — TC-DEV-080")
    void tagsAreCaseInsensitiveAndKeepFirstSpelling() {
        assertThat(DeviceRules.mergeTags(List.of("pilot"), List.of("Pilot", "3층"), null)).containsExactly("pilot", "3층");
        assertThat(DeviceRules.mergeTags(List.of("pilot", "x"), null, List.of("PILOT"))).containsExactly("x");
        assertThat(DeviceRules.cleanTags(List.of(" a ", "A", "b"), "tags")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("[DEV-02.10][BR-DEV-11] 21번째 태그는 거부(null → DEVICE_TAG_LIMIT), 40자 초과·허용 밖 문자는 400 — TC-DEV-080")
    void tagLimits() {
        List<String> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add("t" + i);
        }
        assertThat(DeviceRules.mergeTags(twenty, List.of("T0"), null)).hasSize(20);
        assertThat(DeviceRules.mergeTags(twenty, List.of("t20"), null)).isNull();
        assertThatThrownBy(() -> DeviceRules.cleanTags(List.of("a".repeat(41)), "tags")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DeviceRules.cleanTags(List.of("a b"), "tags")).isInstanceOf(BusinessException.class);
        assertThat(DeviceRules.cleanTags(null, "tags")).isEmpty();
        assertThat(DeviceRules.validTag("실습실:A-1_2.3")).isTrue();
    }

    @ParameterizedTest(name = "기기 {0}/{1}, 모델 {2}/{3} → {4}초×{5} ({6})")
    @CsvSource(nullValues = "-", value = {
            "600, -,   60, 3.0, 600, 3.0, DEVICE",
            "-,   -,   60, 3.0, 60,  3.0, MODEL",
            "-,   2.5, 60, 3.0, 60,  2.5, MODEL",
            "-,   -,   -,  -,   300, 3.0, SYSTEM"})
    @DisplayName("[DEV-02.08][BR-DEV-08] 오프라인 기준 상속 기기 → 모델 → 시스템(300초, 3배) — TC-DEV-071")
    void offlineThresholdInheritance(Integer deviceInterval, BigDecimal deviceMultiplier, Integer modelInterval,
                                     BigDecimal modelMultiplier, int interval, BigDecimal multiplier, String from) {
        DeviceRules.Effective e = DeviceRules.effective(deviceInterval, deviceMultiplier, modelInterval, modelMultiplier);
        assertThat(e.expectedIntervalSec()).isEqualTo(interval);
        assertThat(e.offlineMultiplier()).isEqualByComparingTo(multiplier);
        assertThat(e.inheritedFrom()).isEqualTo(from);
    }

    @Test
    @DisplayName("[DEV-02.08][AT-DEV-07.1·07.2] 모델 60초×3=180초, 기기 600초×3=1,800초(1,500초는 아직 ONLINE) — TC-DEV-056·071")
    void offlineAfter() {
        assertThat(DeviceRules.effective(null, null, 60, new BigDecimal("3.0")).offlineAfterSec()).isEqualTo(180);
        assertThat(DeviceRules.effective(600, null, 60, new BigDecimal("3.0")).offlineAfterSec()).isEqualTo(1800).isGreaterThan(1500);
    }

    @Test
    @DisplayName("[DEV-02.03][BR-DEV-26] 온보딩: 센서는 규칙 적용(M4)까지 미완료, 액추에이터는 첫 수신·모델·공간이면 완료")
    void onboarding() {
        assertThat(DeviceRules.onboarding(true, true, true, "SENSOR").complete()).isFalse();
        assertThat(DeviceRules.onboarding(true, true, true, "ACTUATOR").complete()).isTrue();
        assertThat(DeviceRules.onboarding(false, true, true, "ACTUATOR").complete()).isFalse();
        assertThat(DeviceRules.autoRelations("HYBRID")).containsExactly("MEASURES", "CONTROLS");
        assertThat(DeviceRules.autoRelations("GATEWAY")).isEmpty();
        assertThat(DeviceRules.autoRelations(null)).isEmpty();
    }
}
