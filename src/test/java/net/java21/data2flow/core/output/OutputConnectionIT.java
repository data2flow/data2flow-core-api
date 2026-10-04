package net.java21.data2flow.core.output;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.output.service.OutputJobs;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출력 연결 저장·내부 API·action 중계(DSC-04.01, API-DSC-30~33·73~75) — TC-DSC-128 */
class OutputConnectionIT extends LoopItSupport {

    @Autowired
    OutputJobs jobs;

    long room;
    long device;
    long group;

    static final String MQTT = """
            {"name":"파트너 MQTT","type":"MQTT_PUBLISH",
             "target":{"url":"mqtts://broker.partner.example:8883","topicTemplate":"d2f/{spaceCode}/{deviceName}/{metric}","username":"d2f"},
             "filter":{"metrics":["co2"]},"format":"CANONICAL","secret":{"PASSWORD":"pw-1"}}""";

    @BeforeEach
    void data() {
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        jdbc.sql("UPDATE data2flow_core.spaces SET code = 'LAB-301' WHERE id = :id").param("id", room).update();
        long source = data.source(org, "cs");
        device = data.device(org, source, "24e124", "ACTIVE", room, null);
        group = jdbc.sql("""
                        INSERT INTO data2flow_core.device_groups (organization_id, name, type, created_by, updated_by)
                        VALUES (:org, '실습실 센서', 'STATIC', 0, 0) RETURNING id""").param("org", org).query(Long.class).single();
        jdbc.sql("INSERT INTO data2flow_core.device_group_members (organization_id, group_id, device_id, source) VALUES (:org, :g, :d, 'STATIC')")
                .param("org", org).param("g", group).param("d", device).update();
    }

    String create(String body) throws Exception {
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/output-connections"), body))).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(body(r), "$.response.id");
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-128] 저장: 비밀값은 암호문으로만·응답은 secretConfigured, 감사, 설정 변경 OUTPUT, 내부 API-DSC-73은 복호화·같은 버전 204")
    void createAndRuntime() throws Exception {
        String id = create(MQTT);
        byte[] enc = jdbc.sql("SELECT ciphertext FROM data2flow_core.output_secrets WHERE output_id = :id").param("id", Long.parseLong(id))
                .query(byte[].class).single();
        assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain("pw-1");
        mvc.perform(as(org, operator, get("/core/output-connections/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.secretConfigured").value(true)).andExpect(jsonPath("$.response.secretKinds[0]").value("PASSWORD"))
                .andExpect(jsonPath("$.response.target.qos").value(1)).andExpect(jsonPath("$.response.filter.metrics[0]").value("co2"))
                .andExpect(jsonPath("$.response.version").value(1)).andExpect(jsonPath("$.response.secret").doesNotExist());
        assertThat(auditCount(org, "OUTPUT_CREATED")).isEqualTo(1);
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"OUTPUT\""));

        String runtime = body(mvc.perform(get("/internal/core/output-connections/runtime")).andExpect(status().isOk()).andReturn());
        long version = ((Number) JsonPath.read(runtime, "$.response.version")).longValue();
        assertThat((List<String>) JsonPath.read(runtime, "$.response.connections[?(@.id == '" + id + "')].secrets.PASSWORD")).containsExactly("pw-1");
        assertThat((List<String>) JsonPath.read(runtime, "$.response.connections[?(@.id == '" + id + "')].organizationId"))
                .containsExactly(Long.toString(org));
        mvc.perform(get("/internal/core/output-connections/runtime").param("sinceVersion", Long.toString(version))).andExpect(status().isNoContent());

        // 수정: baseVersion, 비밀값 지우기(null), 템플릿 형식
        mvc.perform(as(org, integrator, json(patch("/core/output-connections/" + id),
                        "{\"format\":\"TEMPLATE\",\"template\":\"{{deviceName}} {{metric}}={{value}}\",\"secret\":{\"PASSWORD\":null},\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2))
                .andExpect(jsonPath("$.response.secretConfigured").value(false)).andExpect(jsonPath("$.response.format").value("TEMPLATE"));
        mvc.perform(as(org, integrator, json(patch("/core/output-connections/" + id), "{\"enabled\":false,\"baseVersion\":1}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(patch("/core/output-connections/" + id), "{\"type\":\"WEBHOOK\",\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("IMMUTABLE"));
        mvc.perform(get("/internal/core/output-connections/runtime").param("sinceVersion", Long.toString(version))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, delete("/core/output-connections/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, get("/core/output-connections/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("OUTPUT_NOT_FOUND"));
        assertThat(auditCount(org, "OUTPUT_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-128] 검증: 공용 브로커 FORBIDDEN_HOST, 허용 밖 토픽 변수, 다른 조직 기기 필터 NOT_FOUND, 같은 이름 DUPLICATE")
    void validation() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/output-connections"),
                        MQTT.replace("broker.partner.example:8883", "iot-data.java21.net:443"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("target.url")).andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN_HOST"));
        mvc.perform(as(org, integrator, json(post("/core/output-connections"), MQTT.replace("{metric}", "{site}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_VARIABLE"));
        long other = fx.organization("other");
        long otherSource = data.source(other, "other-src");
        long otherDevice = data.device(other, otherSource, "x1", "ACTIVE", null, null);
        mvc.perform(as(org, integrator, json(post("/core/output-connections"),
                        MQTT.replace("\"metrics\":[\"co2\"]", "\"deviceIds\":[\"" + otherDevice + "\"]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("filter.deviceIds"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        create(MQTT);
        mvc.perform(as(org, integrator, json(post("/core/output-connections"), MQTT))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(as(org, integrator, json(post("/core/output-connections"), """
                        {"name":"훅","type":"WEBHOOK","target":{"url":"https://hooks.example.com/x"},"format":"TEMPLATE"}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("template"));
        mvc.perform(as(org, integrator, get("/core/output-connections").param("type", "MQTT_PUBLISH"))).andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[DSC-04.01][AT-DSC-10.1][TC-DSC-128] 테스트 발송: 샘플 기기 현재값을 필터(co2만)로 거른 표준 텔레메트리·기기 맥락·복호화한 비밀값을 action에 넘기고 결과를 그대로 준다")
    void testRelay() throws Exception {
        data.deviceState(org, device, "ONLINE", clock.instant(),
                "{\"co2\":{\"v\":812,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0,\"unit\":\"ppm\"},\"temperature\":{\"v\":23.1,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0}}");
        STUB.ok("POST", "/internal/action/output-connections/test", 200,
                "{\"ok\":false,\"failureKind\":\"AUTH\",\"rendered\":\"{}\",\"response\":{\"status\":null,\"bodyPreview\":\"\"}}");
        mvc.perform(as(org, integrator, json(post("/core/output-connections/test"),
                        MQTT.replace("\"name\":\"파트너 MQTT\",", "\"sampleDeviceId\":\"" + device + "\","))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ok").value(false)).andExpect(jsonPath("$.response.failureKind").value("AUTH"));
        String sent = STUB.received("POST", "/internal/action/output-connections/test").getFirst().body();
        assertThat(sent).contains("\"PASSWORD\":\"pw-1\"").contains("\"key\":\"co2\"").doesNotContain("\"temperature\"")
                .contains("\"spaceCode\":\"LAB-301\"").contains("\"groupIds\":[\"" + group + "\"]")
                .contains("\"deviceId\":" + device);
        assertThat(STUB.received("POST", "/internal/action/output-connections/test").getFirst().header("X-CALLER-SERVICE"))
                .isEqualTo("data2flow-core-api");
        // 저장된 연결로 테스트: 저장된 비밀값을 쓴다
        String id = create(MQTT);
        mvc.perform(as(org, integrator, json(post("/core/output-connections/test"),
                        "{\"outputConnectionId\":\"" + id + "\",\"sampleDeviceId\":\"" + device + "\",\"target\":"
                                + "{\"url\":\"mqtts://broker.partner.example:8883\",\"topicTemplate\":\"d2f/{deviceId}\"}}")))
                .andExpect(status().isOk());
        assertThat(STUB.received("POST", "/internal/action/output-connections/test").get(1).body()).contains("\"PASSWORD\":\"pw-1\"");
        // 현재값 없는 기기·없는 기기
        long empty = data.device(org, data.source(org, "cs-two"), "e1", "ACTIVE", room, null);
        mvc.perform(as(org, integrator, json(post("/core/output-connections/test"),
                        MQTT.replace("\"name\":\"파트너 MQTT\",", "\"sampleDeviceId\":\"" + empty + "\","))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("OUTPUT_SAMPLE_UNAVAILABLE"));
        mvc.perform(as(org, integrator, json(post("/core/output-connections/test"), MQTT))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sampleDeviceId"));
        // action이 응답하지 않으면 503
        STUB.reset();
        STUB.on("POST", "/internal/action/output-connections/test", r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(500, "", java.util.Map.of()));
        mvc.perform(as(org, integrator, json(post("/core/output-connections/test"),
                        MQTT.replace("\"name\":\"파트너 MQTT\",", "\"sampleDeviceId\":\"" + device + "\","))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-128] 재전송 중계(API-DSC-77)·지표 보고(API-DSC-75 더하기·지연 최대값)·조회·7일 정리·기기 맥락(API-DSC-74)")
    void replayStatsContexts() throws Exception {
        String id = create(MQTT);
        STUB.ok("POST", "/internal/action/output-connections/" + id + "/replay-failed", 200, "{\"queued\":4}");
        mvc.perform(as(org, integrator, json(post("/core/output-connections/" + id + "/replay-failed"),
                        "{\"from\":\"2026-10-02T00:00:00Z\",\"to\":\"2026-10-03T00:00:00Z\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.queued").value(4));
        assertThat(STUB.received("POST", "/internal/action/output-connections/" + id + "/replay-failed").getFirst().body())
                .contains("\"from\":\"2026-10-02T00:00:00Z\"").contains("\"organizationId\":\"" + org + "\"");
        mvc.perform(as(org, integrator, json(post("/core/output-connections/" + id + "/replay-failed"), "{\"from\":\"x\"}")))
                .andExpect(status().isBadRequest());
        assertThat(auditCount(org, "OUTPUT_REPLAYED")).isEqualTo(1);

        Instant minute = clock.instant().minus(Duration.ofMinutes(5)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String item = "{\"outputId\":\"" + id + "\",\"organizationId\":\"" + org + "\",\"minute\":\"" + minute + "\",\"sent\":10,\"failed\":1,\"retried\":2,\"lagMs\":%d}";
        mvc.perform(json(post("/internal/core/output-connections/stats"), "{\"items\":[" + item.formatted(120) + "," + item.formatted(80)
                        + ",{\"outputId\":\"999999\",\"organizationId\":\"" + org + "\",\"minute\":\"" + minute + "\",\"sent\":1}]}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(org, operator, get("/core/output-connections/" + id + "/stats"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].sent").value(20)).andExpect(jsonPath("$.response[0].failed").value(2))
                .andExpect(jsonPath("$.response[0].retried").value(4)).andExpect(jsonPath("$.response[0].lagMs").value(120));
        mvc.perform(as(org, operator, get("/core/output-connections/" + id + "/stats").param("from", "2026-09-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/internal/core/output-connections/stats"), "{}")).andExpect(status().isBadRequest());
        clock.advance(Duration.ofDays(8));
        assertThat(jobs.purgeOnce()).isEqualTo(1);

        String ctx = body(mvc.perform(get("/internal/core/output-connections/device-contexts").param("deviceIds", device + ",999999"))
                .andExpect(status().isOk()).andReturn());
        assertThat((List<String>) JsonPath.read(ctx, "$.response.devices[*].deviceId")).containsExactly(Long.toString(device));
        assertThat((String) JsonPath.read(ctx, "$.response.devices[0].spaceCode")).isEqualTo("LAB-301");
        assertThat((List<String>) JsonPath.read(ctx, "$.response.devices[0].spacePathIds")).endsWith(Long.toString(room));
        assertThat((List<String>) JsonPath.read(ctx, "$.response.devices[0].groupIds")).containsExactly(Long.toString(group));
        mvc.perform(get("/internal/core/output-connections/device-contexts").param("deviceIds", "a")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/core/output-connections/device-contexts")).andExpect(jsonPath("$.response.devices").isEmpty());
    }
}
