package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSH-04.08 대시보드 JSON 가져오기·내보내기 — TC-DSH-050 */
class DashboardImportExportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String EXPORTED = """
            {"formatVersion":1,"exportedAt":"2026-10-04T00:00:00Z",
             "dashboard":{"schemaVersion":1,"name":"실습동","description":null,"resolution":"AUTO","refresh":"LIVE",
               "timeRange":{"relative":"24h"},"variables":[{"name":"space","type":"SPACE","default":"10"}],
               "layout":{"widgets":[
                 {"id":"t","type":"stat","x":0,"y":0,"w":6,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"100","metricKey":"co2"}]},
                 {"id":"l","type":"line","x":6,"y":0,"w":18,"h":8,"targets":[
                    {"kind":"DEVICE_METRIC","deviceId":"100","metricKey":"temperature"},
                    {"kind":"DEVICE_METRIC","deviceId":"200","metricKey":"temperature"},
                    {"kind":"SPACE_AGGREGATE","spaceId":"${space}","metricKey":"humidity"}]},
                 {"id":"a","type":"alarm-list","x":0,"y":8,"w":12,"h":6,"targets":[{"kind":"SPACE","spaceId":"10"}]},
                 {"id":"m","type":"markdown","x":12,"y":8,"w":12,"h":6,"options":{"content":"메모"}}]}},
             "targets":[{"ref":"t/0","kind":"DEVICE_METRIC","deviceId":"100","deviceExternalId":"24e124136d151547","metricKey":"co2"},
                        {"ref":"l/0","kind":"DEVICE_METRIC","deviceId":"100","deviceExternalId":"24e124136d151547","metricKey":"temperature"},
                        {"ref":"l/1","kind":"DEVICE_METRIC","deviceId":"200","deviceExternalId":"unknown-dev","metricKey":"temperature"},
                        {"ref":"a/0","kind":"SPACE","spaceId":"10","spaceName":"실습실"}]}""";

    /** 같은 조직: 모든 ID가 그대로 있다 */
    static final DashboardTransfer.Lookup SAME_ORG = new DashboardTransfer.Lookup() {
        @Override
        public Optional<String> deviceExternalId(long deviceId) {
            return Optional.ofNullable(Map.of(100L, "24e124136d151547", 200L, "unknown-dev").get(deviceId));
        }

        @Override
        public Optional<Long> deviceByExternalId(String externalId) {
            return Optional.empty();
        }

        @Override
        public boolean spaceExists(long spaceId) {
            return spaceId == 10;
        }

        @Override
        public Optional<Long> spaceByName(String name) {
            return Optional.empty();
        }
    };

    /** 다른 조직: 외부 ID·이름으로만 찾는다(기기 100 → 7, 공간 실습실 → 70, unknown-dev 없음) */
    static final DashboardTransfer.Lookup OTHER_ORG = new DashboardTransfer.Lookup() {
        @Override
        public Optional<String> deviceExternalId(long deviceId) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> deviceByExternalId(String externalId) {
            return "24e124136d151547".equals(externalId) ? Optional.of(7L) : Optional.empty();
        }

        @Override
        public boolean spaceExists(long spaceId) {
            return false;
        }

        @Override
        public Optional<Long> spaceByName(String name) {
            return "실습실".equals(name) ? Optional.of(70L) : Optional.empty();
        }
    };

    @Test
    @DisplayName("[DSH-04.08][TC-DSH-050] 같은 조직에 다시 가져오면 같은 정의(배치·변수·위젯), 매핑 실패 없음")
    void roundTrip() {
        JsonNode body = JSON.readTree(EXPORTED);
        DashboardTransfer.Mapped mapped = DashboardTransfer.map(body, SAME_ORG);
        assertThat(mapped.unmapped()).isEmpty();
        assertThat(mapped.dashboard().get("layout")).isEqualTo(body.get("dashboard").get("layout"));
        assertThat(mapped.dashboard().get("variables")).isEqualTo(body.get("dashboard").get("variables"));
        assertThat(mapped.dashboard().has("schemaVersion")).isFalse();
    }

    @Test
    @DisplayName("[DSH-04.08][TC-DSH-050] 다른 조직: 외부 ID·이름으로 다시 매핑, 못 찾은 기기는 '매핑 필요' 목록, 대상이 모자란 위젯은 빠짐")
    void otherOrganization() {
        DashboardTransfer.Mapped mapped = DashboardTransfer.map(JSON.readTree(EXPORTED), OTHER_ORG);
        assertThat(mapped.unmapped()).extracting(DashboardTransfer.Unmapped::ref, DashboardTransfer.Unmapped::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("l/1", "DEVICE_NOT_FOUND"));
        JsonNode widgets = mapped.dashboard().get("layout").get("widgets");
        assertThat(widgets.get(0).get("targets").get(0).get("deviceId").asString()).isEqualTo("7");
        assertThat(widgets.get(1).get("targets")).hasSize(2);
        assertThat(widgets.get(1).get("targets").get(1).get("spaceId").asString()).isEqualTo("${space}");
        assertThat(widgets.get(2).get("targets").get(0).get("spaceId").asString()).isEqualTo("70");
        // stat 위젯 대상 기기를 못 찾으면 위젯도 빠진다
        DashboardTransfer.Mapped none = DashboardTransfer.map(JSON.readTree(EXPORTED.replace("24e124136d151547", "gone")), OTHER_ORG);
        assertThat(none.unmapped()).extracting(DashboardTransfer.Unmapped::ref).contains("t/0", "t", "l/0", "l/1");
        assertThat(none.dashboard().get("layout").get("widgets")).hasSize(3);
    }

    @Test
    @DisplayName("[DSH-04.08][TC-DSH-050] 형식 버전이 다르거나 스키마에 맞지 않으면 400 DASHBOARD_LAYOUT_INVALID")
    void invalid() {
        for (String bad : new String[]{"{}", "{\"formatVersion\":2,\"dashboard\":{}}", "{\"formatVersion\":1}",
                "{\"formatVersion\":1,\"dashboard\":{\"schemaVersion\":9,\"layout\":{\"widgets\":[]}}}",
                "{\"formatVersion\":1,\"dashboard\":{\"layout\":{\"widgets\":{}}}}",
                "{\"formatVersion\":1,\"dashboard\":{\"layout\":{\"widgets\":[{\"id\":\"x\",\"type\":\"markdown\",\"x\":20,\"y\":0,\"w\":8,\"h\":2}]}}}"}) {
            assertThatThrownBy(() -> DashboardTransfer.map(JSON.readTree(bad), SAME_ORG))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode().code()).isEqualTo("DASHBOARD_LAYOUT_INVALID"));
        }
    }
}
