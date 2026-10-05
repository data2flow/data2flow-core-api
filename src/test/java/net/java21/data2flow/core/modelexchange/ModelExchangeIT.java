package net.java21.data2flow.core.modelexchange;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.modelexchange.domain.Dtdl;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.workorder.FieldOpsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-03.04 모델 정의 JSON 가져오기·내보내기와 DTDL 호환(API-DEV-44·45, AT-DEV-09.4·09.5) */
class ModelExchangeIT extends IntegrationTestSupport {

    static final String DTDL = """
            {"@context":["dtmi:dtdl:context;3","dtmi:dtdl:extension:quantitativeTypes;1"],
             "@id":"dtmi:com:example:thermo_switch;1","@type":"Interface","displayName":{"en":"Thermo switch"},
             "contents":[
               {"@type":["Telemetry","Temperature"],"name":"temperature","schema":"double","unit":"degreeCelsius"},
               {"@type":["Telemetry","RelativeHumidity"],"name":"humidity","schema":"double","unit":"percent"},
               {"@type":"Telemetry","name":"co2","schema":"double"},
               {"@type":"Command","name":"switch"}]}""";

    @Autowired
    JsonMapper json;

    private long org;
    private long integrator;

    @BeforeEach
    void setUp() {
        org = fx.organization("mx");
        integrator = fx.user(org, "mx.int", "INTEGRATOR");
    }

    private long modelWithScript(long orgId) {
        long t = data.metric(orgId, "temperature", "℃");
        jdbc.sql("UPDATE data2flow_core.metrics SET display_name = '온도', valid_min = -40, valid_max = 85 WHERE id = :id").param("id", t).update();
        data.metric(orgId, "battery", "%");
        long model = data.model(orgId, "EM300-TH", List.of("temperature", "battery"));
        long script = jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, config, created_by, updated_by)
                        VALUES (:o, 'em300 디코더', 'DECODE', '{}'::jsonb, 0, 0) RETURNING id""").param("o", orgId).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO data2flow_core.script_versions (organization_id, script_id, version_no, code, code_sha256, status, static_check, created_by)
                        VALUES (:o, :s, 1, 'function decode(b){return {}}', repeat('a', 64), 'DRAFT', '{"ok":true,"problems":[]}'::jsonb, 0)""")
                .param("o", orgId).param("s", script).update();
        jdbc.sql("UPDATE data2flow_core.device_models SET decode_script_id = :s, capabilities = '[{\"capability\":\"Switch\",\"constraints\":null}]'::jsonb,"
                        + " attribute_schema = '{\"type\":\"object\",\"properties\":{\"floor\":{\"type\":\"integer\"}}}'::jsonb WHERE id = :m")
                .param("s", script).param("m", model).update();
        return model;
    }

    @Test
    @DisplayName("[DEV-03.04][AT-DEV-09.4] 모델 JSON 내보내기 → 다른 조직에서 가져오기: 측정 항목·기능·속성 스키마 같음, 스크립트는 코드 포함 DRAFT — TC-DEV-107·110")
    void roundTrip() throws Exception {
        long model = modelWithScript(org);
        String exported = mvc.perform(as(org, integrator, get("/core/device-models/" + model + "/export")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.format").value("data2flow"))
                .andExpect(jsonPath("$.response.metrics", hasSize(2))).andExpect(jsonPath("$.response.scripts[0].kind").value("DECODE"))
                .andReturn().getResponse().getContentAsString();
        String doc = json.writeValueAsString(json.readTree(exported).get("response"));
        long other = fx.organization("mx2");
        long otherInt = fx.user(other, "mx2.int", "INTEGRATOR");
        // 드라이런: 만들지 않고 계획만
        mvc.perform(FieldOpsData.upload("/core/device-models/import?dryRun=true", other, otherInt, "m.json", "application/json",
                        doc.getBytes(StandardCharsets.UTF_8), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.dryRun").value(true))
                .andExpect(jsonPath("$.response.createdMetrics", hasSize(2)));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE organization_id = :o").param("o", other)
                .query(Long.class).single()).isZero();
        String res = mvc.perform(FieldOpsData.upload("/core/device-models/import", other, otherInt, "m.json", "application/json",
                        doc.getBytes(StandardCharsets.UTF_8), null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.model.code").value("EM300-TH"))
                .andExpect(jsonPath("$.response.model.metrics", hasSize(2)))
                .andExpect(jsonPath("$.response.model.capabilities[0].capability").value("Switch"))
                .andExpect(jsonPath("$.response.model.attributeSchema.properties.floor.type").value("integer"))
                .andExpect(jsonPath("$.response.scripts[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.response.unmapped", hasSize(0))).andReturn().getResponse().getContentAsString();
        String scriptId = JsonPath.read(res, "$.response.scripts[0].id");
        assertThat(jdbc.sql("SELECT code FROM data2flow_core.script_versions WHERE script_id = :s").param("s", Long.parseLong(scriptId))
                .query(String.class).single()).isEqualTo("function decode(b){return {}}");
        assertThat(jdbc.sql("SELECT display_name || '|' || unit FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'temperature'")
                .param("o", other).query(String.class).single()).isEqualTo("온도|℃");
        // 같은 코드 다시 → 409
        mvc.perform(FieldOpsData.upload("/core/device-models/import", other, otherInt, "m.json", "application/json",
                        doc.getBytes(StandardCharsets.UTF_8), null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("MODEL_CODE_DUPLICATE"));
        assertThat(auditCount(other, "DEVICE_MODEL_IMPORTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-03.04][AT-DEV-09.5] DTDL v3 인터페이스(Telemetry 3, Command 1) 가져오기 → 측정 항목 3, 기능 매핑 1, 매핑 실패 0 — TC-DEV-107·110")
    void importDtdl() throws Exception {
        mvc.perform(FieldOpsData.upload("/core/device-models/import", org, integrator, "i.json", "application/json",
                        DTDL.getBytes(StandardCharsets.UTF_8), null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.format").value("dtdl"))
                .andExpect(jsonPath("$.response.model.code").value("THERMO_SWITCH"))
                .andExpect(jsonPath("$.response.model.metrics", hasSize(3)))
                .andExpect(jsonPath("$.response.model.capabilities", hasSize(1)))
                .andExpect(jsonPath("$.response.model.capabilities[0].capability").value("Switch"))
                .andExpect(jsonPath("$.response.unmapped", hasSize(0)))
                .andExpect(jsonPath("$.response.createdMetrics", hasSize(3)));
        assertThat(jdbc.sql("SELECT unit FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'temperature'").param("o", org)
                .query(String.class).single()).isEqualTo("℃");
        // 잘못된 DTDL은 400(DTDL 규칙 위반)
        mvc.perform(FieldOpsData.upload("/core/device-models/import", org, integrator, "i.json", "application/json",
                        "{\"@context\":\"dtmi:dtdl:context;3\",\"@id\":\"bad\",\"@type\":\"Interface\",\"contents\":[]}".getBytes(StandardCharsets.UTF_8), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DTDL"));
        // OPERATOR는 가져올 수 없다
        long operator = fx.user(org, "mx.op", "OPERATOR");
        mvc.perform(FieldOpsData.upload("/core/device-models/import", org, operator, "i.json", "application/json",
                DTDL.getBytes(StandardCharsets.UTF_8), null)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[DEV-03.04·13.04][AT-DEV-26.3] DTDL 내보내기는 DTDL v3 규칙 검사를 통과(DTMI·이름·의미 형식·단위) — TC-DEV-109·318")
    void exportDtdl() throws Exception {
        long model = modelWithScript(org);
        String res = mvc.perform(as(org, integrator, get("/core/device-models/" + model + "/export").param("format", "dtdl")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.@id").value("dtmi:data2flow:model:em300_th;1"))
                .andExpect(jsonPath("$.response.contents[0].unit").value("degreeCelsius"))
                .andExpect(jsonPath("$.response.contents[2].@type").value("Command"))
                .andReturn().getResponse().getContentAsString();
        assertThat(Dtdl.validate(json.readTree(res).get("response"))).isEmpty();
        mvc.perform(as(org, integrator, get("/core/device-models/" + model + "/export").param("format", "xml"))).andExpect(status().isBadRequest());
        long other = fx.organization("mx3");
        mvc.perform(as(other, fx.user(other, "mx3.a", "ADMIN"), get("/core/device-models/" + model + "/export"))).andExpect(status().isNotFound());
    }
}
