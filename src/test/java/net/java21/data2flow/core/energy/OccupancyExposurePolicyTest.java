package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.energy.domain.OccupancyExposurePolicy;
import net.java21.data2flow.core.energy.domain.OccupancyExposurePolicy.Exposure;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.OccupancyPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** TC-ENE-040 재실 추정 노출 규칙(BR-ENE-10, NFR-13.02): 3명 미만이거나 정원 4명 이하인 공간은 "사용 중"만, 숫자는 어디에도 없다 */
class OccupancyExposurePolicyTest {

    @ParameterizedTest(name = "추정 {0}명 · 정원 {1} → {2} {3}")
    @CsvSource(nullValues = "-", value = {
            "2,10,OCCUPIED,-",
            "5,4,OCCUPIED,-",
            "3,10,OCCUPIED,3~4",
            "12,40,OCCUPIED,10~14",
            "7,-,OCCUPIED,-",
            "1,10,OCCUPIED,-",
            "0,10,VACANT,-",
            "-,10,UNKNOWN,-"})
    @DisplayName("[TC-ENE-040] 추정 인원·정원으로 상태와 인원 구간을 정한다")
    void expose(Double estimate, Integer capacity, String state, String band) {
        assertThat(OccupancyExposurePolicy.expose(estimate, capacity)).isEqualTo(new Exposure(state, band));
    }

    @Test
    @DisplayName("[TC-ENE-040] API 응답 항목에는 숫자 필드가 없다(상태·구간 문자열과 시각만)")
    void noNumbersInResponse() {
        for (Class<?> type : java.util.List.of(OccupancyPoint.class, net.java21.data2flow.core.energy.dto.VirtualSensorDtos.OccupancyNow.class)) {
            assertThat(java.util.Arrays.stream(type.getRecordComponents()).map(c -> c.getType()))
                    .as(type.getSimpleName()).allMatch(t -> t == String.class || t == Instant.class);
        }
        String body = JsonMapper.builder().build().writeValueAsString(new OccupancyPoint(null, "OCCUPIED", null));
        assertThat(body).doesNotContainPattern(":\\s*\\d");
    }
}
