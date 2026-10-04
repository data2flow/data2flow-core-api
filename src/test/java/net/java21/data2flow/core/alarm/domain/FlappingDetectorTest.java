package net.java21.data2flow.core.alarm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlappingDetectorTest {

    static final Instant T = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    @DisplayName("[RUL-04.02][TC-RUL-087][TC-RUL-088] BR-RUL-10 30분 안 발생 5회는 정상, 6번째에 FLAPPING_ON, 30분 변화 없으면 FLAPPING_OFF")
    void sixRaisesWithinThirtyMinutes() {
        List<Instant> raises = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            raises.add(T.plus(Duration.ofMinutes(i * 5L)));
        }
        assertThat(FlappingDetector.flapping(raises, T.plus(Duration.ofMinutes(20)))).isFalse();
        raises.add(T.plus(Duration.ofMinutes(25)));
        assertThat(FlappingDetector.flapping(raises, T.plus(Duration.ofMinutes(25)))).isTrue();
        // 창 밖(31분 전) 발생은 세지 않는다
        assertThat(FlappingDetector.flapping(raises, T.plus(Duration.ofMinutes(31)))).isFalse();
        assertThat(FlappingDetector.settled(T.plus(Duration.ofMinutes(25)), T.plus(Duration.ofMinutes(54)))).isFalse();
        assertThat(FlappingDetector.settled(T.plus(Duration.ofMinutes(25)), T.plus(Duration.ofMinutes(55)))).isTrue();
        assertThat(FlappingDetector.settled(null, T)).isTrue();
    }

    @Test
    @DisplayName("[RUL-04.03][TC-RUL-090] BR-RUL-11 같은 공간 5분 안 다른 규칙 알람 2건 이상이면 공간 이벤트(열린 묶음에 합류·새 묶음·묶지 않음)")
    void spaceEventGrouping() {
        Instant now = T.plus(Duration.ofMinutes(3));
        assertThat(SpaceEventGrouper.decide(T, null, now)).isEqualTo(SpaceEventGrouper.Decision.JOIN);
        assertThat(SpaceEventGrouper.decide(null, T, now)).isEqualTo(SpaceEventGrouper.Decision.OPEN);
        assertThat(SpaceEventGrouper.decide(T.minus(Duration.ofMinutes(10)), T.minus(Duration.ofMinutes(9)), now))
                .isEqualTo(SpaceEventGrouper.Decision.NONE);
        assertThat(SpaceEventGrouper.decide(null, null, now)).isEqualTo(SpaceEventGrouper.Decision.NONE);
    }
}
