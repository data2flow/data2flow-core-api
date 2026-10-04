package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** DSH-04.01 배치 검사(BR-DSH-18) — TC-DSH-029 */
class DashboardLayoutValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode layout(String widgets) {
        return JSON.readTree("{\"widgets\":[" + widgets + "]}");
    }

    static String stat(String id, int x, int y, int w, int h) {
        return "{\"id\":\"%s\",\"type\":\"stat\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"targets\":[{\"kind\":\"DEVICE_METRIC\",\"deviceId\":\"1\",\"metricKey\":\"co2\"}]}"
                .formatted(id, x, y, w, h);
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return "OK";
        } catch (BusinessException ex) {
            return ex.getErrorCode().code() + ":" + ex.getErrors().getFirst().code();
        }
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("[DSH-04.01][TC-DSH-029] 겹침·격자 밖·크기 0·음수 위치 → DASHBOARD_LAYOUT_INVALID {reason}, 정상 배치 통과")
    @CsvSource(delimiter = '|', value = {
            "정상 두 개 나란히|0,0,12,4;12,0,12,4|OK",
            "정확히 맞닿음(세로)|0,0,24,4;0,4,24,4|OK",
            "겹침|0,0,12,4;6,2,12,4|DASHBOARD_LAYOUT_INVALID:OVERLAP",
            "격자 밖 x+w>24|20,0,6,4|DASHBOARD_LAYOUT_INVALID:OUT_OF_GRID",
            "폭 0|0,0,0,4|DASHBOARD_LAYOUT_INVALID:OUT_OF_GRID",
            "음수 y|0,-1,4,4|DASHBOARD_LAYOUT_INVALID:OUT_OF_GRID",
            "높이 49|0,0,4,49|DASHBOARD_LAYOUT_INVALID:OUT_OF_GRID",
            "같은 자리 셋|0,0,4,4;0,0,4,4;0,0,4,4|DASHBOARD_LAYOUT_INVALID:OVERLAP"})
    void gridRules(String name, String boxes, String expected) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String b : boxes.split(";")) {
            String[] p = b.split(",");
            sb.append(i > 0 ? "," : "").append(stat("w" + i, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3])));
            i++;
        }
        assertThat(code(() -> DashboardLayoutValidator.validate(layout(sb.toString()), Set.of()))).isEqualTo(expected);
    }

    @Test
    @DisplayName("[DSH-04.01][TC-DSH-029][BR-DSH-18] 위젯 40개는 통과, 41개째 → TOO_MANY_WIDGETS")
    void widgetLimit() {
        StringBuilder forty = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            forty.append(i > 0 ? "," : "").append(stat("w" + i, (i % 6) * 4, (i / 6) * 2, 4, 2));
        }
        assertThatNoException().isThrownBy(() -> DashboardLayoutValidator.validate(layout(forty.toString()), Set.of()));
        String fortyOne = forty + "," + stat("w40", 0, 100, 4, 2);
        assertThat(code(() -> DashboardLayoutValidator.validate(layout(fortyOne), Set.of()))).isEqualTo("DASHBOARD_LAYOUT_INVALID:TOO_MANY_WIDGETS");
    }

    @Test
    @DisplayName("[DSH-04.01] 위젯 id 중복·종류 미지원·대상 수·변수 참조·옵션 형식")
    void widgetRules() {
        assertThat(code(() -> DashboardLayoutValidator.validate(layout(stat("a", 0, 0, 4, 4) + "," + stat("a", 4, 0, 4, 4)), Set.of())))
                .isEqualTo("DASHBOARD_LAYOUT_INVALID:DUPLICATE_ID");
        assertThat(code(() -> DashboardLayoutValidator.validate(layout("{\"id\":\"c\",\"type\":\"control\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}"),
                Set.of()))).isEqualTo("WIDGET_TYPE_UNSUPPORTED:UNSUPPORTED");
        assertThat(code(() -> DashboardLayoutValidator.validate(layout("{\"id\":\"s\",\"type\":\"stat\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"targets\":[]}"),
                Set.of()))).isEqualTo("WIDGET_QUERY_INVALID:COUNT");
        StringBuilder targets = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            targets.append(i > 0 ? "," : "").append("{\"kind\":\"DEVICE_METRIC\",\"deviceId\":\"1\",\"metricKey\":\"co2\"}");
        }
        assertThat(code(() -> DashboardLayoutValidator.validate(layout("{\"id\":\"l\",\"type\":\"line\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"targets\":["
                + targets + "]}"), Set.of()))).isEqualTo("WIDGET_QUERY_INVALID:COUNT");
        String withVar = "{\"id\":\"v\",\"type\":\"line\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"targets\":[{\"kind\":\"SPACE_AGGREGATE\",\"spaceId\":\"${space}\",\"metricKey\":\"temperature\"}]}";
        assertThatNoException().isThrownBy(() -> DashboardLayoutValidator.validate(layout(withVar), Set.of("space")));
        assertThat(code(() -> DashboardLayoutValidator.validate(layout(withVar), Set.of()))).isEqualTo("WIDGET_QUERY_INVALID:INVALID");
        assertThat(code(() -> DashboardLayoutValidator.validate(layout("{\"id\":\"g\",\"type\":\"gauge\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,"
                + "\"targets\":[{\"kind\":\"DEVICE_METRIC\",\"deviceId\":1,\"metricKey\":\"co2\"}],\"options\":{\"min\":100,\"max\":10}}"), Set.of())))
                .isEqualTo("DASHBOARD_LAYOUT_INVALID:ORDER");
        assertThat(code(() -> DashboardLayoutValidator.validate(layout("{\"id\":\"m\",\"type\":\"markdown\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,"
                + "\"options\":{\"content\":\"" + "가".repeat(10_001) + "\"}}"), Set.of()))).isEqualTo("DASHBOARD_LAYOUT_INVALID:INVALID");
        assertThat(code(() -> DashboardLayoutValidator.validate(JSON.readTree("{\"widgets\":{}}"), Set.of())))
                .isEqualTo("DASHBOARD_LAYOUT_INVALID:TYPE");
    }

    @Test
    @DisplayName("[DSH-04.05] 변수: 이름 형식·중복·종류, 최대 10개")
    void variables() {
        assertThat(DashboardLayoutValidator.validateVariables(JSON.readTree("[{\"name\":\"space\",\"type\":\"SPACE\"}]"))).containsExactly("space");
        assertThatThrownBy(() -> DashboardLayoutValidator.validateVariables(JSON.readTree(
                "[{\"name\":\"space\",\"type\":\"SPACE\"},{\"name\":\"space\",\"type\":\"ZONE\"}]")))
                .isInstanceOf(BusinessException.class);
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            many.append(i > 0 ? "," : "").append("{\"name\":\"v").append(i).append("\",\"type\":\"DEVICE\"}");
        }
        assertThatThrownBy(() -> DashboardLayoutValidator.validateVariables(JSON.readTree(many + "]"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DashboardLayoutValidator.validateVariables(JSON.readTree("{}"))).isInstanceOf(BusinessException.class);
    }
}
