package net.java21.data2flow.core.device;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-031 수신 데이터로 기기 발견: ING-03.01~03, ING-07.02, DSC-01.07, 내부 API-DEV-120·121, API-ING-16 */
class DeviceDiscoveryIT extends IntegrationTestSupport {

    private DeviceTestData d;
    private long org;
    private long admin;
    private long site;
    private long room;
    private long model;

    @BeforeEach
    void setUp() {
        d = new DeviceTestData(jdbc);
        org = fx.organization("disc");
        admin = fx.user(org, "disc.admin", "ADMIN");
        site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        model = data.model(org, "EM300-TH", List.of("temperature"));
    }

    private String register(long sourceId, String externalId, String extra) {
        return "{\"organizationId\":%d,\"sourceId\":%d,\"externalId\":\"%s\"%s}".formatted(org, sourceId, externalId, extra);
    }

    @Test
    @DisplayName("[ING-03.02][DSC-01.07][AT-ING-03.1·03.6] 자동 등록: PENDING, 소스 기본 모델·공간, 원본 이름, 멱등(두 번째는 created=false), EVT-DEV-03 — TC-ING-049")
    void autoRegister() throws Exception {
        long source = d.source(org, "cs", "MQTT_SUBSCRIBE", "AUTO_REGISTER", model, room, 100);
        String body = mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "24E124136D151606",
                        ",\"sourceMeta\":{\"deviceName\":\"EM300-TH-151606\",\"tags\":{\"location\":\"실습실\"}},"
                                + "\"firstSeenAt\":\"2026-10-02T23:59:00Z\",\"metrics\":[\"temperature\",\"humidity\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("PENDING"))
                .andExpect(jsonPath("$.response.created").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.response.deviceId");
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "24e124136d151606", "")))
                .andExpect(jsonPath("$.response.created").value(false)).andExpect(jsonPath("$.response.deviceId").value(id));
        mvc.perform(as(org, admin, get("/core/devices").param("status", "PENDING")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].name").value("EM300-TH-151606"))
                .andExpect(jsonPath("$.responses[0].model.code").value("EM300-TH"))
                .andExpect(jsonPath("$.responses[0].space.id").value(Long.toString(room)))
                .andExpect(jsonPath("$.responses[0].suggestedSpaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.responses[0].firstSeenAt").value("2026-10-02T23:59:00Z"))
                .andExpect(jsonPath("$.responses[0].metrics[1]").value("temperature"))
                .andExpect(jsonPath("$.responses[0].autoRegistered").value(true))
                .andExpect(jsonPath("$.responses[0].sourceMeta.tags.location").value("실습실"));
        assertThat(d.outbox(org, "EVENT", "device.pending.created")).isEqualTo(1);
        mvc.perform(get("/internal/core/sources/" + source + "/devices/24E124136D151606"))
                .andExpect(jsonPath("$.response.deviceId").value(id)).andExpect(jsonPath("$.response.status").value("PENDING"))
                .andExpect(jsonPath("$.response.ignored").value(false))
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)));
        mvc.perform(get("/internal/core/sources/" + source + "/devices/unknown")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(get("/internal/core/sources/999999/devices/x")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[ING-03.02][UC-ING-03 2d-1] 같은 외부 ID 동시 등록 8건 → 기기 1대 — TC-ING-049")
    void concurrentRegistration() throws Exception {
        long source = d.source(org, "cc", "MQTT_SUBSCRIBE", "AUTO_REGISTER", null, null, 100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dup-1", "")))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnly(200);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE source_id = :s").param("s", source).query(Long.class).single())
                .isEqualTo(1);
        assertThat(d.outbox(org, "EVENT", "device.pending.created")).isEqualTo(1);
    }

    @Test
    @DisplayName("[ING-03.02][AT-ING-03.2] 정책 REJECT 소스는 409 DEVICE_REJECTED, 이미 등록된 기기는 그대로 돌려준다, 다른 조직·없는 소스 404 — TC-ING-048")
    void rejectPolicy() throws Exception {
        long source = d.source(org, "rj", "MQTT_SUBSCRIBE", "REJECT", null, null, 100);
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "x1", "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_REJECTED"));
        long existing = data.device(org, source, "x2", "ACTIVE", room, model);
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "x2", "")))
                .andExpect(jsonPath("$.response.deviceId").value(Long.toString(existing)))
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));
        mvc.perform(json(post("/internal/core/devices/auto-register"), "{\"organizationId\":%d,\"sourceId\":%d,\"externalId\":\"x\"}"
                        .formatted(org + 1000, source)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "   ", ""))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ING-07.02][BR-ING-09][AT-ING-03.3] 시간당 한도: 넘으면 429 DEVICE_AUTOREG_LIMIT, 다음 시간에도 차단 유지, ADMIN이 해제하면 즉시 허용 — TC-ING-083~085")
    void hourlyQuota() throws Exception {
        long source = d.source(org, "qt", "MQTT_SUBSCRIBE", "AUTO_REGISTER", null, null, 3);
        for (int i = 0; i < 3; i++) {
            mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dev-" + i, ""))).andExpect(status().isOk());
        }
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dev-3", "")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_AUTOREG_LIMIT"));
        // 이미 등록된 기기의 재수신은 한도와 관계없다
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dev-0", ""))).andExpect(status().isOk());
        clock.advance(Duration.ofHours(2));
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dev-4", "")))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("SELECT rejected_count FROM data2flow_core.source_autoreg_quotas WHERE source_id = :s").param("s", source)
                .query(Integer.class).single()).isEqualTo(2);

        long op = fx.user(org, "quota.op", "OPERATOR");
        mvc.perform(as(org, op, json(post("/core/ingest/sources/" + source + "/auto-register-quota/reset"), "{}")))
                .andExpect(status().isForbidden());
        long other = fx.organization("q2");
        long otherAdmin = fx.user(other, "q2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, json(post("/core/ingest/sources/" + source + "/auto-register-quota/reset"), "{}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(post("/core/ingest/sources/" + source + "/auto-register-quota/reset"), "{\"newHourlyLimit\":5}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.hourlyLimit").value(5))
                .andExpect(jsonPath("$.response.blocked").value(false));
        assertThat(auditCount(org, "AUTO_REGISTER_QUOTA_RESET")).isEqualTo(1);
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "dev-4", ""))).andExpect(status().isOk());
        mvc.perform(as(org, admin, post("/core/ingest/sources/" + source + "/auto-register-quota/release")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.hourlyLimit").value(5));
        mvc.perform(as(org, admin, json(post("/core/ingest/sources/" + source + "/auto-register-quota/reset"), "{\"newHourlyLimit\":0}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ING-03.03][AT-ING-03.5] 공간 추천: tags.location과 이름 같은 공간 1개면 추천, 2개면 없음, 공백·대소문자 무시, point로 하위 공간 — TC-ING-052")
    void spaceSuggestion() throws Exception {
        long source = d.source(org, "sg", "MQTT_SUBSCRIBE", "AUTO_REGISTER", null, null, 100);
        long lab = data.space(org, site, "ROOM", "Lab A");
        long podium = data.space(org, lab, "ZONE", "강단");
        data.space(org, site, "ROOM", "복도");
        data.space(org, lab, "ZONE", "복도");
        String one = mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "s1",
                ",\"sourceMeta\":{\"tags\":{\"location\":\"  lab a \"}}"))).andReturn().getResponse().getContentAsString();
        String two = mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "s2",
                ",\"sourceMeta\":{\"tags\":{\"location\":\"Lab A\",\"point\":\"강단\"}}"))).andReturn().getResponse().getContentAsString();
        String three = mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "s3",
                ",\"sourceMeta\":{\"tags\":{\"location\":\"복도\"}}"))).andReturn().getResponse().getContentAsString();
        String four = mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "s4",
                ",\"sourceMeta\":\"text\",\"name\":\"" + "n".repeat(150) + "\""))).andReturn().getResponse().getContentAsString();
        assertThat(suggested(one)).isEqualTo(lab);
        assertThat(suggested(two)).isEqualTo(podium);
        assertThat(suggested(three)).isNull();
        assertThat(suggested(four)).isNull();
        long fourId = Long.parseLong(JsonPath.read(four, "$.response.deviceId"));
        assertThat(jdbc.sql("SELECT length(name) FROM data2flow_core.devices WHERE id = :id").param("id", fourId).query(Integer.class).single())
                .isEqualTo(100);
        // 공간은 확정하지 않는다(추천만)
        assertThat(jdbc.sql("SELECT space_id FROM data2flow_core.devices WHERE external_id = 's1' AND source_id = :s").param("s", source)
                .query(Long.class).optional()).isEmpty();
    }

    @Test
    @DisplayName("[ING-03.02] sourceMeta는 8KB 이하로 줄여 저장(deviceName·tags·metrics만 남김)")
    void sourceMetaIsTrimmed() throws Exception {
        long source = d.source(org, "big", "MQTT_SUBSCRIBE", "AUTO_REGISTER", null, null, 100);
        String big = "x".repeat(9000);
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "b1",
                ",\"sourceMeta\":{\"deviceName\":\"dn\",\"raw\":\"" + big + "\"}"))).andExpect(status().isOk());
        String meta = jdbc.sql("SELECT source_meta::text FROM data2flow_core.devices WHERE source_id = :s").param("s", source)
                .query(String.class).single();
        assertThat(meta).contains("dn").doesNotContain("raw");
        mvc.perform(json(post("/internal/core/devices/auto-register"), register(source, "b2",
                ",\"sourceMeta\":{\"deviceName\":\"" + big + "\"}"))).andExpect(status().isOk());
    }

    private Long suggested(String registerResponse) {
        long id = Long.parseLong(JsonPath.read(registerResponse, "$.response.deviceId"));
        return jdbc.sql("SELECT suggested_space_id FROM data2flow_core.devices WHERE id = :id").param("id", id)
                .query((rs, n) -> {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }).list().get(0);
    }
}
