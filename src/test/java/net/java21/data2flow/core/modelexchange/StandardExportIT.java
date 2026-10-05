package net.java21.data2flow.core.modelexchange;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.modelexchange.domain.Dtdl;
import net.java21.data2flow.core.modelexchange.domain.StandardFormats;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.workorder.FieldOpsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-13.04 표준 형식 내보내기(API-DEV-135·136, BR-DEV-36, EVT-DEV-13, AT-DEV-26.1~26.3) */
class StandardExportIT extends IntegrationTestSupport {

    @Autowired
    JsonMapper json;

    private FieldOpsData d;
    private long org;
    private long admin;
    private long site;
    private long floor;
    private long room;
    private long co2Sensor;
    private long thSensor;

    @BeforeEach
    void setUp() {
        d = new FieldOpsData(jdbc);
        org = fx.organization("sx");
        admin = fx.user(org, "sx.admin", "ADMIN");
        site = data.site(org, "본관");
        floor = data.space(org, site, "FLOOR", "3층");
        room = data.space(org, floor, "ROOM", "실습실");
        long source = data.source(org, "cs");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        data.metric(org, "humidity", "%");
        long am = data.model(org, "AM107", List.of("co2", "temperature", "humidity"));
        long em = data.model(org, "EM300-TH", List.of("temperature", "humidity"));
        co2Sensor = data.device(org, source, "am-1", "ACTIVE", room, am);
        thSensor = data.device(org, source, "em-1", "ACTIVE", floor, em);
        data.deviceState(org, co2Sensor, "ONLINE", clock.instant(),
                "{\"co2\":{\"v\":812,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0},\"temperature\":{\"v\":22.5,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0},"
                        + "\"humidity\":{\"v\":45,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0}}");
        long eq = jdbc.sql("INSERT INTO data2flow_core.equipment (organization_id, device_id, space_id, equip_class, name) VALUES (:o, :d, :s,"
                + " 'Air_Quality_Sensor', 'AM107') RETURNING id").param("o", org).param("d", co2Sensor).param("s", room).query(Long.class).single();
        point(eq, "co2", "MEASUREMENT", "CO2_Concentration");
        point(eq, "temperature", "MEASUREMENT", "Temperature");
        point(eq, "humidity", "MEASUREMENT", null); // 태그 없음 → 빠짐
    }

    private void point(long equipment, String key, String type, String quantity) {
        jdbc.sql("INSERT INTO data2flow_core.points (organization_id, equipment_id, metric_key, point_type, quantity) VALUES (:o, :e, :k, :t, :q)")
                .param("o", org).param("e", equipment).param("k", key).param("t", type).param("q", quantity).update();
    }

    private JsonNode export(String format, String scope, boolean values) throws Exception {
        String res = mvc.perform(as(org, admin, json(post("/core/devices/export-standard"),
                        "{\"format\":\"" + format + "\",\"scope\":" + scope + ",\"includeValues\":" + values + "}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.status").value("DONE")).andReturn().getResponse().getContentAsString();
        String jobId = JsonPath.read(res, "$.response.jobId");
        String job = mvc.perform(as(org, admin, get("/core/export-jobs/" + jobId))).andExpect(jsonPath("$.response.status").value("DONE"))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(job, "$.response.downloadUrl");
        byte[] file = mvc.perform(as(org, admin, get(url.replace("/api/v1", "")))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree("{\"job\":" + json.writeValueAsString(json.readTree(job).get("response")) + ",\"file\":"
                + json.writeValueAsString(new String(file, StandardCharsets.UTF_8)) + "}");
    }

    @Test
    @DisplayName("[DEV-13.04][AT-DEV-26.2] CO2 센서 NGSI-LD 현재값 스냅샷 → AirQualityObserved(co2 ppm·refDevice·dateObserved), Smart Data Models 필수 항목 통과, EVT-DEV-13 — TC-DEV-318·320")
    void ngsiLd() throws Exception {
        JsonNode r = export("NGSI_LD", "{\"spaceIds\":[\"" + site + "\"]}", true);
        JsonNode entities = json.readTree(r.get("file").asString());
        assertThat(entities.size()).isEqualTo(3);
        JsonNode air = null;
        for (JsonNode e : entities) {
            assertThat(StandardFormats.validateNgsi(e)).isEmpty();
            if ("AirQualityObserved".equals(e.get("type").asString())) {
                air = e;
            }
        }
        assertThat(air).isNotNull();
        assertThat(air.get("co2").get("value").asDouble()).isEqualTo(812.0);
        assertThat(air.get("co2").get("unitCode").asString()).isEqualTo("59");
        assertThat(air.get("relativeHumidity").get("value").asDouble()).isEqualTo(0.45);
        assertThat(air.get("refDevice").get("object").asString()).isEqualTo("urn:ngsi-ld:Device:d2f-" + org + "-" + co2Sensor);
        assertThat(r.get("job").get("report").get("airQualityObserved").asInt()).isEqualTo(1);
        assertThat(d.outboxPayloads(org, "device.export.completed")).hasSize(1).first().asString().contains("COMPLETED");
        assertThat(auditCount(org, "DEVICE_STANDARD_EXPORTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-13.04][AT-DEV-26.1] Brick Turtle·JSON-LD: 공간 계층·장비·점(Brick 1.3 클래스), 태그 없는 점은 보고서에 '태그 없음'으로 빠짐 — TC-DEV-320")
    void brick() throws Exception {
        JsonNode r = export("BRICK_TTL", "{\"deviceIds\":[\"" + co2Sensor + "\"]}", false);
        String ttl = r.get("file").asString();
        assertThat(ttl).contains("@prefix brick: <https://brickschema.org/schema/Brick#> .")
                .contains("d2f:space_" + site + " a brick:Site .").contains("d2f:space_" + floor + " brick:isPartOf d2f:space_" + site + " .")
                .contains("d2f:device_" + co2Sensor + " a brick:Air_Quality_Sensor .")
                .contains("a brick:CO2_Sensor .").contains("a brick:Temperature_Sensor .")
                .contains("d2f:device_" + co2Sensor + " brick:hasLocation d2f:space_" + room + " .");
        // 모든 문장은 "주어 술어 목적어 ." 한 줄(Turtle 문법)
        for (String line : ttl.split("\n")) {
            if (!line.isBlank() && !line.startsWith("@prefix")) {
                assertThat(line).matches("^d2f:[a-z_0-9]+ (a|rdfs:label|brick:[A-Za-z]+) (brick:[A-Za-z0-9_.]+|d2f:[a-z_0-9]+|\".*\") \\.$");
            }
        }
        JsonNode skipped = r.get("job").get("report").get("skipped");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).get("reason").asString()).isEqualTo("태그 없음");
        JsonNode jsonld = json.readTree(export("BRICK_JSONLD", "{\"deviceIds\":[\"" + co2Sensor + "\"]}", false).get("file").asString());
        assertThat(jsonld.get("@context").get("brick").asString()).isEqualTo(StandardFormats.BRICK);
        assertThat(jsonld.get("@graph").size()).isEqualTo(3 + 1 + 2);
    }

    @Test
    @DisplayName("[DEV-13.04][AT-DEV-26.3][BR-DEV-36] DTDL 내보내기: 공간 Interface + 모델 Interface, 모두 DTDL v3 규칙 통과, 내보낼 것이 없으면 400 EXPORT_INVALID_REQUEST — TC-DEV-319·320")
    void dtdlAndEmpty() throws Exception {
        JsonNode r = export("DTDL", "{\"spaceIds\":[\"" + site + "\"]}", false);
        JsonNode doc = json.readTree(r.get("file").asString());
        assertThat(doc.size()).isEqualTo(3);
        for (JsonNode iface : doc) {
            assertThat(Dtdl.validate(iface)).isEmpty();
        }
        long empty = data.site(org, "빈 사이트");
        mvc.perform(as(org, admin, json(post("/core/devices/export-standard"), "{\"format\":\"NGSI_LD\",\"scope\":{\"spaceIds\":[\"" + empty + "\"]}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("EXPORT_INVALID_REQUEST"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.device_export_jobs WHERE organization_id = :o AND status = 'FAILED'")
                .param("o", org).query(Long.class).single()).isEqualTo(1);
        mvc.perform(as(org, admin, json(post("/core/devices/export-standard"), "{\"format\":\"CSV\"}"))).andExpect(status().isBadRequest());
        long operator = fx.user(org, "sx.op", "OPERATOR");
        mvc.perform(as(org, operator, json(post("/core/devices/export-standard"), "{\"format\":\"DTDL\"}"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[DEV-13.04][API-DEV-136] NGSI-LD 주기 전송 설정 저장·목록·삭제(주기 60~86400초) — TC-DEV-319")
    void pushes() throws Exception {
        String id = JsonPath.read(mvc.perform(as(org, admin, json(post("/core/ngsi-pushes"),
                        "{\"outputConnectionId\":\"7\",\"scope\":{\"spaceIds\":[\"" + room + "\"]},\"intervalSec\":300}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.intervalSec").value(300))
                .andReturn().getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, admin, get("/core/ngsi-pushes"))).andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].scope.spaceIds[0]").value(Long.toString(room)));
        mvc.perform(as(org, admin, json(post("/core/ngsi-pushes"), "{\"outputConnectionId\":\"7\",\"intervalSec\":5}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, delete("/core/ngsi-pushes/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete("/core/ngsi-pushes/" + id))).andExpect(status().isNotFound());
    }
}
