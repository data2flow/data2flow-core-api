package net.java21.data2flow.core.space;

import net.java21.data2flow.core.space.service.SemanticService;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시맨틱 계층 Location→Equipment→Point(DEV-13.01, API-DEV-131, BR-DEV-32·33) — TC-DEV-303~306 */
class SemanticIT extends IntegrationTestSupport {

    private static final String TEMPLATE = """
            {"equipClass":"Zone_Air_Sensor","points":[
              {"metricKey":"temperature","pointType":"Measurement","quantity":"Temperature","tags":["sensor","temp"]},
              {"metricKey":"humidity","pointType":"Measurement","quantity":"Humidity","tags":["sensor"]},
              {"metricKey":"unknown_key","pointType":"Measurement","quantity":"Noise"}]}""";

    @Autowired
    SemanticService semantic;

    private long org;
    private long integrator;
    private long model;
    private long device;
    private long room;

    private void setUp() {
        org = fx.organization("sem");
        integrator = fx.user(org, "sem.int", "INTEGRATOR");
        data.metric(org, "temperature", "°C");
        data.metric(org, "humidity", "%");
        long site = data.site(org, "사이트");
        room = data.space(org, site, "ROOM", "실습실");
        model = data.model(org, "EM300-TH", List.of("temperature", "humidity"));
        jdbc.sql("UPDATE data2flow_core.device_models SET semantic_template = CAST(:t AS jsonb) WHERE id = :id")
                .param("t", TEMPLATE).param("id", model).update();
        device = data.device(org, data.source(org, "src-s"), "em300-1", "ACTIVE", room, model);
    }

    @Test
    @DisplayName("[DEV-13.01][AT-DEV-23.1·23.2] 모델 템플릿으로 장비 1개·점 2개, 물리량 Temperature → Zone_Air_Temperature 수정은 저장되고 감사에 이전·이후 값 — TC-DEV-303·305")
    void templateThenEdit() throws Exception {
        setUp();
        semantic.applyModelTemplate(org, device);
        mvc.perform(as(org, integrator, get("/core/devices/" + device + "/semantic")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.deviceId").value(Long.toString(device)))
                .andExpect(jsonPath("$.response.equipment.length()").value(1))
                .andExpect(jsonPath("$.response.equipment[0].equipClass").value("Zone_Air_Sensor"))
                .andExpect(jsonPath("$.response.equipment[0].name").value("기기 em300-1"))
                .andExpect(jsonPath("$.response.equipment[0].spaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.equipment[0].points.length()").value(2))
                .andExpect(jsonPath("$.response.equipment[0].points[0].pointType").value("MEASUREMENT"))
                .andExpect(jsonPath("$.response.equipment[0].points[0].source").value("MODEL"));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"), """
                        {"equipment":[{"equipClass":"zone_air_sensor","name":"온습도","points":[
                          {"metricKey":"temperature","pointType":"measurement","quantity":"Zone_Air_Temperature","tags":["sensor","sensor","temp"]},
                          {"metricKey":"humidity","pointType":"MEASUREMENT","quantity":"custom:Comfort_RH","tags":[]}]}]}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.equipment[0].equipClass").value("Zone_Air_Sensor"))
                .andExpect(jsonPath("$.response.equipment[0].points[0].quantity").value("Zone_Air_Temperature"))
                .andExpect(jsonPath("$.response.equipment[0].points[0].tags.length()").value(2))
                .andExpect(jsonPath("$.response.equipment[0].points[0].source").value("USER"))
                .andExpect(jsonPath("$.response.equipment[0].points[1].quantity").value("custom:Comfort_RH"));
        String detail = jdbc.sql("SELECT CAST(detail AS text) FROM data2flow_core.audit_logs WHERE organization_id = :org AND action = 'SEMANTIC_CHANGED'")
                .param("org", org).query(String.class).single();
        assertThat(detail).contains("temperature|MEASUREMENT|Temperature").contains("temperature|MEASUREMENT|Zone_Air_Temperature");
    }

    @Test
    @DisplayName("[DEV-13.01][AT-DEV-23.3] 물리량 Tempurature → 400 SEMANTIC_TAG_UNKNOWN + 후보 Temperature, 그 밖 입력 검증 — TC-DEV-304")
    void unknownTag() throws Exception {
        setUp();
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"),
                        "{\"equipment\":[{\"equipClass\":\"Zone_Air_Sensor\",\"name\":\"센서\",\"points\":[{\"metricKey\":\"temperature\",\"pointType\":\"MEASUREMENT\",\"quantity\":\"Tempurature\"}]}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SEMANTIC_TAG_UNKNOWN"))
                .andExpect(jsonPath("$.header.resultMessage").value(containsString("Temperature")))
                .andExpect(jsonPath("$.errors[0].field").value("equipment[0].points[0].quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("Temperature"));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"),
                        "{\"equipment\":[{\"equipClass\":\"Zone_Air_Snsor\",\"name\":\"센서\",\"points\":[]}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Zone_Air_Sensor"));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"),
                        "{\"equipment\":[{\"equipClass\":\"Sensor\",\"name\":\"\",\"points\":[{\"metricKey\":\"temperature\",\"pointType\":\"ALARM\",\"quantity\":\"Temperature\",\"tags\":[\"bad tag!\"]},{\"metricKey\":\"temperature\",\"pointType\":\"STATUS\",\"quantity\":\"Temperature\"},{}]}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(5));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"),
                        "{\"equipment\":[{\"equipClass\":\"Sensor\",\"name\":\"x\",\"points\":[{\"metricKey\":\"radon\",\"pointType\":\"MEASUREMENT\",\"quantity\":\"Radon\"}]}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + device + "/semantic"),
                        "{\"equipment\":[{\"equipClass\":\"Sensor\",\"name\":\"x\",\"spaceId\":\"999999\",\"points\":[]}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DEV-13.01][AT-DEV-23.4] 모델 템플릿을 바꿔도 기존 기기는 그대로, [모델 태그 다시 적용]으로만 반영 — TC-DEV-303")
    void reapplyOnlyOnRequest() throws Exception {
        setUp();
        semantic.applyModelTemplate(org, device);
        jdbc.sql("UPDATE data2flow_core.device_models SET semantic_template = CAST(:t AS jsonb) WHERE id = :id")
                .param("t", "{\"equipment\":[{\"equipClass\":\"Environment_Sensor\",\"name\":\"환경\",\"points\":[{\"metricKey\":\"temperature\",\"pointType\":\"WEIRD\",\"quantity\":\"air_temperature\"}]}]}")
                .param("id", model).update();
        mvc.perform(as(org, integrator, get("/core/devices/" + device + "/semantic")))
                .andExpect(jsonPath("$.response.equipment[0].equipClass").value("Zone_Air_Sensor"));
        mvc.perform(as(org, integrator, post("/core/devices/" + device + "/semantic/reapply-model")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.equipment[0].equipClass").value("Environment_Sensor"))
                .andExpect(jsonPath("$.response.equipment[0].name").value("환경"))
                .andExpect(jsonPath("$.response.equipment[0].points[0].quantity").value("Air_Temperature"))
                .andExpect(jsonPath("$.response.equipment[0].points[0].pointType").value("MEASUREMENT"));
        assertThat(auditCount(org, "SEMANTIC_CHANGED")).isEqualTo(1);
        // 모델 없는 기기는 다시 적용할 수 없고, 템플릿이 없으면 비운다
        long loose = data.device(org, data.source(org, "src-t"), "x-1", "ACTIVE", room, null);
        mvc.perform(as(org, integrator, post("/core/devices/" + loose + "/semantic/reapply-model")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("modelId"));
        jdbc.sql("UPDATE data2flow_core.device_models SET semantic_template = CAST('[]' AS jsonb) WHERE id = :id").param("id", model).update();
        mvc.perform(as(org, integrator, post("/core/devices/" + device + "/semantic/reapply-model")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.equipment.length()").value(0));
        jdbc.sql("UPDATE data2flow_core.device_models SET semantic_template = NULL WHERE id = :id").param("id", model).update();
        semantic.applyModelTemplate(org, device);
        semantic.applyModelTemplate(org, loose);
        semantic.applyModelTemplate(org, 999_999);
        mvc.perform(as(org, fx.user(org, "sem.op", "OPERATOR"), post("/core/devices/" + device + "/semantic/reapply-model")))
                .andExpect(status().isForbidden());
    }
}
