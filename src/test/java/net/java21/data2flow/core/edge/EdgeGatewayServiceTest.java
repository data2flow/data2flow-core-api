package net.java21.data2flow.core.edge;

import net.java21.data2flow.core.edge.domain.EdgeStates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 엣지 상태 규칙(DSC-08.03·08.04, BR-DSC-31~33) — TC-DSC-210·215 */
class EdgeGatewayServiceTest {

    static final Instant NOW = Instant.parse("2026-10-04T01:00:00Z");

    @Test
    @DisplayName("[DSC-08.03][TC-DSC-210][BR-DSC-31] 하트비트 30초 3회(90초) 누락이면 OFFLINE으로 보이고, 등록 전·폐기 상태는 그대로, 토큰은 24시간")
    void effectiveStatus() {
        assertThat(EdgeStates.effective("ONLINE", NOW.minusSeconds(30), NOW)).isEqualTo("ONLINE");
        assertThat(EdgeStates.effective("ONLINE", NOW.minusSeconds(91), NOW)).isEqualTo("OFFLINE");
        assertThat(EdgeStates.effective("UPDATING", null, NOW)).isEqualTo("OFFLINE");
        assertThat(EdgeStates.effective("REGISTERING", null, NOW)).isEqualTo("REGISTERING");
        assertThat(EdgeStates.effective("REVOKED", NOW.minusSeconds(1000), NOW)).isEqualTo("REVOKED");
        assertThat(EdgeStates.TOKEN_TTL).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("[DSC-08.04][AT-DSC-24.2][TC-DSC-215][BR-DSC-33] 업데이트 결과: 목표 버전이면 SUCCEEDED, 5분 지나 이전 버전이면 FAILED_ROLLED_BACK, 그 전엔 판정 보류")
    void updateOutcome() {
        Instant started = NOW.minus(Duration.ofMinutes(2));
        assertThat(EdgeStates.updateOutcome("1.1.0", "1.0.0", "1.1.0", started, NOW)).isEqualTo("SUCCEEDED");
        assertThat(EdgeStates.updateOutcome("1.0.0", "1.0.0", "1.1.0", started, NOW)).isNull();
        assertThat(EdgeStates.updateOutcome("1.0.0", "1.0.0", "1.1.0", NOW.minus(Duration.ofMinutes(5)), NOW)).isEqualTo("FAILED_ROLLED_BACK");
        assertThat(EdgeStates.updateOutcome("0.9.0", "1.0.0", "1.1.0", NOW.minus(Duration.ofMinutes(9)), NOW)).isNull();
    }
}
