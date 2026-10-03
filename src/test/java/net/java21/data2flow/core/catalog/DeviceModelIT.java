package net.java21.data2flow.core.catalog;

import net.java21.data2flow.core.account.service.BootstrapService;
import net.java21.data2flow.core.catalog.service.BuiltinCatalogStartupRunner;
import net.java21.data2flow.core.config.CoreProperties.Bootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-03.01 기기 모델, DEV-03.02 기본 모델 6종, DEV-07.05 속성 스키마 저장 — TC-DEV-088·091·094·097 */
class DeviceModelIT extends CatalogItSupport {

    @Autowired
    BootstrapService bootstrap;
    @Autowired
    BuiltinCatalogStartupRunner startupRunner;

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("model");
        admin = fx.user(org, "model.admin", "ADMIN");
        seeder.seed(org);
    }

    private String createBody(String code, String metricsJson) {
        return """
                {"code":"%s","vendor":"자체 제작","name":"ESP32 온습도","protocol":"MQTT","kind":"SENSOR","defaultIntervalSec":120,
                 "description":"실습용","metrics":%s,"capabilities":[{"capability":"Thermostat","constraints":{"min":18,"max":30}},
                 {"capability":"Thermostat","constraints":null}]}""".formatted(code, metricsJson);
    }

    @Test
    @DisplayName("[DEV-03.02][AT-DEV-09.1] 새 조직(최초 설치) → 기본 모델 6종, 측정 항목·단위가 채워져 있고 다시 실행해도 그대로(멱등) — TC-DEV-088·094")
    void newOrganizationHasBuiltinModels() throws Exception {
        bootstrap.bootstrap(new Bootstrap(true, false, "new-school", "새 학교", "first.admin", "first@school.ac.kr", null,
                "Vivid-Orbit-73!Lamp", null));
        long newOrg = jdbc.sql("SELECT id FROM data2flow_core.organizations WHERE code = 'new-school'").query(Long.class).single();
        long viewer = fx.user(newOrg, "new.viewer", "VIEWER");
        mvc.perform(as(newOrg, viewer, get("/core/device-models"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(6))
                .andExpect(jsonPath("$.responses[*].code", containsInAnyOrder("EM300-TH", "EM320-TH", "EM500-CO2", "AM103", "AM107", "WS302")))
                .andExpect(jsonPath("$.responses[?(@.code=='AM107')].metricCount").value(8))
                .andExpect(jsonPath("$.responses[0].builtin").value(true))
                .andExpect(jsonPath("$.responses[0].vendor").value("Milesight"));
        long ws302 = modelId(newOrg, "WS302");
        mvc.perform(as(newOrg, viewer, get("/core/device-models/" + ws302))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.metrics[*].key", containsInAnyOrder("LAeq", "LAI", "LAImax", "battery")))
                .andExpect(jsonPath("$.response.metrics[?(@.key=='battery')].required").value(false))
                .andExpect(jsonPath("$.response.protocol").value("LORAWAN"))
                .andExpect(jsonPath("$.response.imageUrl").value(nullValue()));
        mvc.perform(as(newOrg, viewer, get("/core/metrics?status=VERIFIED&key=LAeq"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].unit").value("dB")).andExpect(jsonPath("$.responses[0].builtin").value(true));
        mvc.perform(as(newOrg, viewer, get("/core/metrics?status=VERIFIED"))).andExpect(jsonPath("$.totalCount").value(12));
        long outbox = jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :o").param("o", newOrg)
                .query(Long.class).single();
        assertThat(configMessages(newOrg, "MODEL", "UPSERT")).isEqualTo(6);
        assertThat(configMessages(newOrg, "METRIC", "UPSERT")).isEqualTo(12);

        assertThat(seeder.seed(newOrg)).isEqualTo(new net.java21.data2flow.core.catalog.service.BuiltinCatalogSeeder.SeedResult(0, 0));
        bootstrap.bootstrap(new Bootstrap(true, false, "new-school", "새 학교", "first.admin", "first@school.ac.kr", null, null, null));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.model_metrics WHERE organization_id = :o").param("o", newOrg)
                .query(Long.class).single()).isEqualTo(2 + 3 + 4 + 4 + 8 + 4);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :o").param("o", newOrg)
                .query(Long.class).single()).isEqualTo(outbox);
    }

    @Test
    @DisplayName("[DEV-03.02] 시작할 때 ACTIVE 조직마다 기본 카탈로그를 채운다. 사용자가 고친 정의와 같은 코드의 사용자 모델은 건드리지 않는다")
    void startupSeedsActiveOrganizations() throws Exception {
        long a = fx.organization("seed-a");
        long b = fx.organization("seed-b");
        jdbc.sql("UPDATE data2flow_core.organizations SET status = 'SUSPENDED' WHERE id = :b").param("b", b).update();
        long c = fx.organization("seed-c");
        data.metric(c, "temperature", "°F");
        data.model(c, "AM103", List.of("temperature"));
        startupRunner.run(null);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE organization_id = :o AND builtin").param("o", a)
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE organization_id = :o").param("o", b)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT unit FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'temperature'").param("o", c)
                .query(String.class).single()).isEqualTo("°F");
        long am103 = modelId(c, "AM103");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.model_metrics WHERE model_id = :m").param("m", am103)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_models WHERE organization_id = :o AND builtin").param("o", c)
                .query(Long.class).single()).isEqualTo(5);
    }

    @Test
    @DisplayName("[DEV-03.01] 모델 생성 201+Location, 측정 항목·기능 이름 저장, 중복 코드 409, 없는·미검증 측정 항목 404, 형식 400 — TC-DEV-091")
    void createModel() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/device-models"),
                        createBody("ESP32-TH", "[{\"key\":\"temperature\",\"required\":true},{\"key\":\"humidity\",\"required\":false}]"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/device-models/")))
                .andExpect(jsonPath("$.response.code").value("ESP32-TH"))
                .andExpect(jsonPath("$.response.builtin").value(false))
                .andExpect(jsonPath("$.response.status").value("ACTIVE"))
                .andExpect(jsonPath("$.response.defaultIntervalSec").value(120))
                .andExpect(jsonPath("$.response.defaultOfflineMultiplier").value(3.0))
                .andExpect(jsonPath("$.response.metrics", hasSize(2)))
                .andExpect(jsonPath("$.response.capabilities", hasSize(1)))
                .andExpect(jsonPath("$.response.capabilities[0].constraints.max").value(30))
                .andExpect(jsonPath("$.response.package.defaultRuleTemplateIds", hasSize(0)))
                .andExpect(jsonPath("$.response.deviceCount").value(0))
                .andExpect(jsonPath("$.response.version").value(0));
        assertThat(auditCount(org, "DEVICE_MODEL_CREATED")).isEqualTo(1);
        assertThat(configVersion(org, "MODELS")).isEqualTo(7);

        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-TH", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("MODEL_CODE_DUPLICATE"))
                .andExpect(jsonPath("$.header.resultMessage").value("같은 코드의 모델이 이미 있습니다"));
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-X", "[{\"key\":\"pm25\"}]"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        jdbc.sql("INSERT INTO data2flow_core.metrics (organization_id, key, display_name, status) VALUES (:o, 'lux_raw', 'lux_raw', 'UNVERIFIED')")
                .param("o", org).update();
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-X", "[{\"key\":\"lux_raw\"}]"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("esp32-lower", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"));
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-Y", "[]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("metrics"));
        mvc.perform(as(org, admin, json(post("/core/device-models"), """
                        {"code":"RELAY-1","vendor":"v","name":"릴레이","protocol":"ZIGBEE","kind":"ROBOT","defaultIntervalSec":5,
                         "defaultOfflineMultiplier":20}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(4)));
        mvc.perform(as(org, admin, json(post("/core/device-models"), """
                        {"code":"RELAY-1","vendor":"v","name":"릴레이","protocol":"mqtt","kind":"actuator","capabilities":[{"capability":" "}]}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(startsWith("capabilities")));
        mvc.perform(as(org, admin, json(post("/core/device-models"), """
                        {"code":"RELAY-1","vendor":"v","name":"릴레이","protocol":"mqtt","kind":"actuator"}""")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.protocol").value("MQTT"))
                .andExpect(jsonPath("$.response.metrics", hasSize(0)));
    }

    @Test
    @DisplayName("[DEV-03.01] 목록 필터(통신 방식·종류·기본 제공·검색어·코드), DEPRECATED는 includeDeprecated나 code로만, 페이지 형식")
    void listFilters() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-TH", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isCreated());
        long esp = modelId(org, "ESP32-TH");
        mvc.perform(as(org, admin, get("/core/device-models?protocol=mqtt"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/device-models?builtin=true&kind=SENSOR"))).andExpect(jsonPath("$.totalCount").value(6));
        mvc.perform(as(org, admin, get("/core/device-models?q=em3"))).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, admin, get("/core/device-models?q=100%25"))).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, admin, get("/core/device-models?size=2&page=2"))).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.responses", hasSize(2)));
        mvc.perform(as(org, admin, post("/core/device-models/" + esp + "/deprecate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DEPRECATED")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, post("/core/device-models/" + esp + "/deprecate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, get("/core/device-models"))).andExpect(jsonPath("$.totalCount").value(6));
        mvc.perform(as(org, admin, get("/core/device-models?includeDeprecated=true"))).andExpect(jsonPath("$.totalCount").value(7));
        mvc.perform(as(org, admin, get("/core/device-models?code=ESP32-TH"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].status").value("DEPRECATED"));
        assertThat(auditCount(org, "DEVICE_MODEL_DEPRECATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-03.01][DEV-07.05] 부분 수정(온 키만·baseVersion), 코드 변경 400, 버전 충돌 409, 패키지·속성 스키마 저장과 검사 — TC-DEV-091")
    void updateAndPackage() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-TH", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isCreated());
        long id = modelId(org, "ESP32-TH");
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id),
                        "{\"name\":\"ESP32 온습도 v2\",\"description\":null,\"metrics\":[{\"key\":\"humidity\",\"required\":true},{\"key\":\"temperature\",\"required\":true}],\"capabilities\":[],\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("ESP32 온습도 v2"))
                .andExpect(jsonPath("$.response.vendor").value("자체 제작"))
                .andExpect(jsonPath("$.response.description").value(nullValue()))
                .andExpect(jsonPath("$.response.metrics[0].key").value("humidity"))
                .andExpect(jsonPath("$.response.capabilities", hasSize(0)))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id), "{\"code\":\"OTHER\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id),
                        "{\"name\":\"\",\"kind\":\"BAD\",\"defaultIntervalSec\":1,\"defaultOfflineMultiplier\":\"x\",\"metrics\":[],\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(5)));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id), "{\"metrics\":[{\"key\":\"nope\"}],\"baseVersion\":1}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id), "{\"metrics\":{},\"baseVersion\":1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + id), "{\"name\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));

        long decode = script("decoder-esp32", "DECODE");
        long transform = script("offset-fix", "TRANSFORM");
        String schema = "{\"type\":\"object\",\"properties\":{\"tempOffset\":{\"type\":\"number\",\"unit\":\"℃\",\"default\":0}},\"required\":[\"tempOffset\"]}";
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"), """
                        {"transformScriptId":"%d","decodeScriptId":"%d","driverKey":"mqtt-json","defaultDashboardId":"55",
                         "defaultRuleTemplateIds":["3","3","4"],"attributeSchema":%s,"baseVersion":1}""".formatted(transform, decode, schema))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.modelId").value(Long.toString(id)))
                .andExpect(jsonPath("$.response.transformScriptId").value(Long.toString(transform)))
                .andExpect(jsonPath("$.response.defaultRuleTemplateIds", hasSize(2)))
                .andExpect(jsonPath("$.response.attributeSchema.properties.tempOffset.unit").value("℃"))
                .andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(as(org, admin, get("/core/device-models/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.package.driverKey").value("mqtt-json"))
                .andExpect(jsonPath("$.response.attributeSchema.required[0]").value("tempOffset"));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"),
                        "{\"transformScriptId\":\"%d\"}".formatted(decode))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("transformScriptId"));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"), "{\"decodeScriptId\":\"99999\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"), "{\"decodeScriptId\":\"abc\",\"defaultRuleTemplateIds\":[\"-1\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(2)));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"),
                        "{\"attributeSchema\":{\"properties\":{\"tempOffset\":{\"type\":\"number\",\"default\":\"zero\"}}}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("attributeSchema"));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"), "{\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(put("/core/device-models/" + id + "/package"), "{\"attributeSchema\":null}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.attributeSchema").value(nullValue()))
                .andExpect(jsonPath("$.response.transformScriptId").value(nullValue()));
        assertThat(auditCount(org, "DEVICE_MODEL_UPDATED")).isEqualTo(1);
        assertThat(auditCount(org, "DEVICE_MODEL_PACKAGE_UPDATED")).isEqualTo(2);
        assertThat(configMessages(org, "MODEL", "UPSERT")).isEqualTo(6 + 1 + 1 + 2);
    }

    @Test
    @DisplayName("[DEV-03.01][BR-DEV-14][AT-DEV-09.2] 기본 모델 수정·패키지·사용 중지·삭제는 403 MODEL_BUILTIN_READONLY, 복제는 새 코드로 측정 항목 동일 — TC-DEV-088·094")
    void builtinReadOnlyAndClone() throws Exception {
        setUp();
        long em300 = modelId(org, "EM300-TH");
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + em300), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("MODEL_BUILTIN_READONLY"))
                .andExpect(jsonPath("$.header.resultMessage").value("기본 제공 모델은 수정할 수 없습니다. 복제해서 사용하세요"));
        mvc.perform(as(org, admin, json(put("/core/device-models/" + em300 + "/package"), "{}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, post("/core/device-models/" + em300 + "/deprecate"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, delete("/core/device-models/" + em300))).andExpect(status().isForbidden());

        mvc.perform(as(org, admin, json(post("/core/device-models/" + em300 + "/clone"), "{\"newCode\":\"EM300-TH-LAB\",\"name\":\"실습실 온습도\"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/device-models/")))
                .andExpect(jsonPath("$.response.code").value("EM300-TH-LAB"))
                .andExpect(jsonPath("$.response.name").value("실습실 온습도"))
                .andExpect(jsonPath("$.response.builtin").value(false))
                .andExpect(jsonPath("$.response.vendor").value("Milesight"))
                .andExpect(jsonPath("$.response.metrics[*].key", containsInAnyOrder("temperature", "humidity")));
        long copy = modelId(org, "EM300-TH-LAB");
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + copy), "{\"defaultIntervalSec\":60,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.defaultIntervalSec").value(60));
        mvc.perform(as(org, admin, json(post("/core/device-models/" + em300 + "/clone"), "{\"newCode\":\"EM300-TH-LAB\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("MODEL_CODE_DUPLICATE"));
        mvc.perform(as(org, admin, json(post("/core/device-models/" + em300 + "/clone"), "{\"newCode\":\"bad code\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("newCode"));
        mvc.perform(as(org, admin, json(post("/core/device-models/" + copy + "/clone"), "{\"newCode\":\"EM300-TH-LAB2\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.name").value("실습실 온습도"));
    }

    @Test
    @DisplayName("[DEV-03.01][BR-DEV-15][AT-DEV-09.3] 기기 5대가 쓰는 모델 삭제는 409 MODEL_IN_USE, 사용 중지는 됨, 안 쓰는 모델 삭제 204 + MODEL DELETE — TC-DEV-091")
    void deleteInUse() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-TH", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isCreated());
        long id = modelId(org, "ESP32-TH");
        long source = source(org, "lab-mqtt");
        long site = data.site(org, "본관");
        for (int i = 0; i < 5; i++) {
            data.device(org, source, "esp-" + i, "ACTIVE", site, id);
        }
        mvc.perform(as(org, admin, get("/core/device-models/" + id))).andExpect(jsonPath("$.response.deviceCount").value(5));
        mvc.perform(as(org, admin, delete("/core/device-models/" + id)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("MODEL_IN_USE"))
                .andExpect(jsonPath("$.header.resultMessage").value("이 모델을 쓰는 기기가 있습니다"));
        mvc.perform(as(org, admin, post("/core/device-models/" + id + "/deprecate"))).andExpect(status().isOk());

        mvc.perform(as(org, admin, json(post("/core/device-models"), createBody("ESP32-UNUSED", "[{\"key\":\"temperature\"}]"))))
                .andExpect(status().isCreated());
        long unused = modelId(org, "ESP32-UNUSED");
        jdbc.sql("UPDATE data2flow_core.data_sources SET default_model_id = :m WHERE id = :s").param("m", unused).param("s", source).update();
        mvc.perform(as(org, admin, delete("/core/device-models/" + unused))).andExpect(status().isConflict());
        jdbc.sql("UPDATE data2flow_core.data_sources SET default_model_id = NULL WHERE id = :s").param("s", source).update();
        mvc.perform(as(org, admin, delete("/core/device-models/" + unused))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/device-models/" + unused)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
        assertThat(configMessages(org, "MODEL", "DELETE")).isEqualTo(1);
        assertThat(auditCount(org, "DEVICE_MODEL_DELETED")).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/device-models/" + id))).andExpect(jsonPath("$.response.status").value(endsWith("DEPRECATED")));
    }

    private long script(String name, String kind) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by)
                        VALUES (:o, :name, :kind, 0, 0) RETURNING id""")
                .param("o", org).param("name", name).param("kind", kind).query(Long.class).single();
    }
}
