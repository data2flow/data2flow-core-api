package net.java21.data2flow.core.device;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-02.01·02.02·02.04·02.08·02.10 기기 API(실제 PostgreSQL 18 + RabbitMQ, 아웃박스·감사·조직 조건) */
class DeviceIT extends IntegrationTestSupport {

    private DeviceTestData d;
    private long org;
    private long admin;
    private long site;
    private long building;
    private long floor;
    private long room;
    private long firstFloor;
    private long model;
    private long source;

    @BeforeEach
    void setUp() {
        d = new DeviceTestData(jdbc);
        org = fx.organization("dev");
        admin = fx.user(org, "dev.admin", "ADMIN");
        site = data.site(org, "본관");
        building = data.space(org, site, "BUILDING", "A동");
        floor = data.space(org, building, "FLOOR", "3층");
        room = data.space(org, floor, "ROOM", "실습실");
        firstFloor = data.space(org, building, "FLOOR", "1층");
        data.metric(org, "temperature", "℃");
        data.metric(org, "humidity", "%");
        model = data.model(org, "EM300-TH", List.of("temperature", "humidity"));
        source = data.source(org, "cs");
    }

    private String createBody(String externalId, String tags) {
        return """
                {"sourceId":"%d","externalId":"%s","name":"온습도 1","kind":"SENSOR","modelId":"%d","spaceId":"%d","tags":%s}"""
                .formatted(source, externalId, model, room, tags);
    }

    @Test
    @DisplayName("[DEV-02.04][AT-DEV-04.1] 수동 등록 201 + Location, ACTIVE, 외부 ID 소문자 정규화, 대문자로 다시 등록하면 409 DEVICE_DUPLICATE — TC-DEV-032·050·051")
    void createAndDuplicate() throws Exception {
        String body = mvc.perform(as(org, admin, json(post("/core/devices"), createBody("24E124136D151606", "[\"pilot\",\"Pilot\",\"3층\"]"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/devices/")))
                .andExpect(jsonPath("$.response.externalId").value("24e124136d151606"))
                .andExpect(jsonPath("$.response.status").value("ACTIVE"))
                .andExpect(jsonPath("$.response.model.code").value("EM300-TH"))
                .andExpect(jsonPath("$.response.space.path").value(containsInAnyOrder("본관", "A동", "3층", "실습실")))
                .andExpect(jsonPath("$.response.tags", hasSize(2)))
                .andExpect(jsonPath("$.response.effective.inheritedFrom").value("MODEL"))
                .andExpect(jsonPath("$.response.effective.expectedIntervalSec").value(600))
                .andExpect(jsonPath("$.response.relations[0].relation").value("MEASURES"))
                .andExpect(jsonPath("$.response.state.connectivity").value("UNKNOWN"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.response.id");
        assertThat(auditCount(org, "DEVICE_CREATED")).isEqualTo(1);
        assertThat(d.outbox(org, "EVENT", "device.changed")).isEqualTo(1);
        assertThat(d.outbox(org, "CONFIG", "")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM data2flow_core.config_versions WHERE organization_id = :org AND scope = 'DEVICES'")
                .param("org", org).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT logical_device_id FROM data2flow_core.devices WHERE id = :id").param("id", Long.parseLong(id))
                .query(Long.class).single()).isEqualTo(Long.parseLong(id));

        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("24e1 2413 6d15 1606", "[]"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_DUPLICATE"));
    }

    @Test
    @DisplayName("[DEV-02.04] 수동 등록 검증: 필수 400, 없는 모델·공간·소스 404, 사용 중지 모델 400, 태그 21개 400 DEVICE_TAG_LIMIT — TC-DEV-032·051")
    void createValidation() throws Exception {
        mvc.perform(as(org, admin, json(post("/core/devices"), "{\"externalId\":\"a\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("x1", "[]").replace("\"modelId\":\"" + model, "\"modelId\":\"999999"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("x1", "[]").replace("\"spaceId\":\"" + room, "\"spaceId\":\"999999"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("x1", "[]").replace("\"sourceId\":\"" + source, "\"sourceId\":\"999999"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
        StringBuilder tags = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            tags.append(i == 0 ? "" : ",").append("\"t").append(i).append('"');
        }
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("x1", tags + "]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_TAG_LIMIT"));
        d.model(model, "SENSOR", null, "DEPRECATED");
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("x1", "[]"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MODEL_DEPRECATED"));
    }

    @Test
    @DisplayName("[DEV-02.01][API-DEV-11] 목록 필터: 공간+하위, 태그(any, 대소문자 무시), 그룹, 가상 기본 제외, 상태·연결·검색·정렬 — TC-DEV-032·034·081")
    void listFilters() throws Exception {
        long a = data.device(org, source, "aa01", "ACTIVE", room, model);
        long b = data.device(org, source, "bb02", "ACTIVE", firstFloor, model, true);
        long p = data.device(org, source, "cc03", "PENDING", null, null);
        d.tag(org, a, "pilot");
        data.deviceState(org, a, "ONLINE", clock.instant(), "{\"temperature\":{\"v\":23.4,\"t\":\"2026-10-03T00:00:00Z\",\"q\":0}}");
        data.deviceState(org, p, "OFFLINE", clock.instant().minus(Duration.ofHours(1)), "{}");
        long group = jdbc.sql("INSERT INTO data2flow_core.device_groups (organization_id, name, type) VALUES (:org, 'g', 'STATIC') RETURNING id")
                .param("org", org).query(Long.class).single();
        jdbc.sql("INSERT INTO data2flow_core.device_group_members (organization_id, group_id, device_id, source) VALUES (:org, :g, :d, 'STATIC')")
                .param("org", org).param("g", group).param("d", p).update();

        mvc.perform(as(org, admin, get("/core/devices")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].externalId").value("aa01"))
                .andExpect(jsonPath("$.responses[0].metrics[0]").value("temperature"))
                .andExpect(jsonPath("$.responses[0].connectivity").value("ONLINE"))
                .andExpect(jsonPath("$.responses[0].space.path[3]").value("실습실"))
                .andExpect(jsonPath("$.responses[0].model.code").value("EM300-TH"))
                .andExpect(jsonPath("$.responses[1].connectivity").value("OFFLINE"));
        mvc.perform(as(org, admin, get("/core/devices").param("virtual", "true"))).andExpect(jsonPath("$.totalCount").value(3));
        mvc.perform(as(org, admin, get("/core/devices").param("spaceId", Long.toString(floor))))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(a)));
        mvc.perform(as(org, admin, get("/core/devices").param("spaceId", Long.toString(floor)).param("includeDescendants", "false")))
                .andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, admin, get("/core/devices").param("tag", "PILOT").param("tag", "nothing")))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices").param("status", "PENDING")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(p)));
        mvc.perform(as(org, admin, get("/core/devices").param("connectivity", "ONLINE"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices").param("groupId", Long.toString(group)))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices").param("q", "CC0"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices").param("modelId", Long.toString(model)).param("sourceId", Long.toString(source))
                        .param("kind", "SENSOR").param("onboarding", "INCOMPLETE")))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices").param("sort", "lastSeenAt,desc").param("virtual", "true")))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(a)))
                .andExpect(jsonPath("$.responses[2].id").value(Long.toString(b)));
        mvc.perform(as(org, admin, get("/core/devices").param("status", "GONE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(as(org, admin, get("/core/devices").param("sort", "secret,asc"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/devices").param("spaceId", "abc"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/devices").param("onboarding", "MAYBE"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-02.01][IAM-04.06][BR-DEV-25] 공간 범위가 제한된 사용자: 범위 밖·공간 없는 기기는 목록에 없고 ID로 요청하면 404 — TC-DEV-035")
    void spaceScope() throws Exception {
        long inside = data.device(org, source, "in01", "ACTIVE", room, model);
        long outside = data.device(org, source, "out1", "ACTIVE", firstFloor, model);
        long pending = data.device(org, source, "pen1", "PENDING", null, null);
        long op = fx.user(org, "scoped.op", "OPERATOR");
        data.spaceScope(org, op, List.of(floor));
        mvc.perform(as(org, op, get("/core/devices"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(inside)));
        mvc.perform(as(org, op, get("/core/devices/" + inside))).andExpect(status().isOk());
        mvc.perform(as(org, op, get("/core/devices/" + outside))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, op, get("/core/devices/" + pending))).andExpect(status().isNotFound());
        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/devices/" + inside))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-02.01][API-DEV-23][DEV-02.08] 상세: device_state 연결·최근값(표시 이름·단위), 기기 주기 지정이면 DEVICE 상속, 그룹·LoRaWAN — TC-DEV-034·072")
    void detail() throws Exception {
        long id = data.device(org, source, "24e124136d151606", "ACTIVE", room, model);
        jdbc.sql("UPDATE data2flow_core.devices SET expected_interval_sec = 120, source_meta = CAST(:m AS jsonb) WHERE id = :id")
                .param("m", "{\"deviceName\":\"EM300\",\"joinEui\":\"24e124c0002a0001\",\"tags\":{\"location\":\"실습실\"}}").param("id", id).update();
        data.deviceState(org, id, "ONLINE", clock.instant(),
                "{\"temperature\":{\"v\":23.4,\"t\":\"2026-10-03T00:00:00Z\",\"q\":0},\"zzz\":{\"v\":\"x\",\"q\":1}}");
        mvc.perform(as(org, admin, get("/core/devices/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.state.connectivity").value("ONLINE"))
                .andExpect(jsonPath("$.response.latest[0].metricKey").value("temperature"))
                .andExpect(jsonPath("$.response.latest[0].unit").value("℃"))
                .andExpect(jsonPath("$.response.latest[0].value").value(23.4))
                .andExpect(jsonPath("$.response.latest[1].value").doesNotExist())
                .andExpect(jsonPath("$.response.effective.expectedIntervalSec").value(120))
                .andExpect(jsonPath("$.response.effective.inheritedFrom").value("DEVICE"))
                .andExpect(jsonPath("$.response.effective.offlineMultiplier").value(3.0))
                .andExpect(jsonPath("$.response.lorawan.devEui").value("24e124136d151606"))
                .andExpect(jsonPath("$.response.lorawan.joinEui").value("24e124c0002a0001"))
                .andExpect(jsonPath("$.response.sourceMeta.deviceName").value("EM300"))
                .andExpect(jsonPath("$.response.onboarding.firstData").value(true))
                .andExpect(jsonPath("$.response.source.id").value(Long.toString(source)));
        mvc.perform(as(org, admin, get("/core/devices/999999"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-02.01][API-DEV-13] 부분 수정: 온 키만, baseVersion 필수·불일치 409, 이름은 DEV_ADMIN·공간은 DEV_PLACE, ACTIVE에서 모델 비우기 400 — TC-DEV-032·040")
    void patchDevice() throws Exception {
        long id = data.device(org, source, "p001", "ACTIVE", room, model);
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id),
                        "{\"name\":\"새 이름\",\"expectedIntervalSec\":60,\"offlineMultiplier\":2.5,\"tags\":[\"a\"],\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("새 이름"))
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.effective.offlineMultiplier").value(2.5))
                .andExpect(jsonPath("$.response.tags[0]").value("a"));
        assertThat(auditCount(org, "DEVICE_UPDATED")).isEqualTo(1);
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"name\":\"x\"}"))).andExpect(status().isBadRequest());
        long op = fx.user(org, "patch.op", "OPERATOR");
        mvc.perform(as(org, op, json(patch("/core/devices/" + id), "{\"name\":\"x\",\"baseVersion\":1}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, op, json(patch("/core/devices/" + id), "{\"spaceId\":\"" + firstFloor + "\",\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.space.id").value(Long.toString(firstFloor)));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"modelId\":null,\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_MODEL_REQUIRED"));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"spaceId\":null,\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_SPACE_REQUIRED"));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id),
                        "{\"expectedIntervalSec\":5,\"offlineMultiplier\":11,\"kind\":\"ROBOT\",\"name\":\"\",\"modelId\":\"x\",\"tags\":\"a\",\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(6)));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"modelId\":\"999999\",\"baseVersion\":2}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
        mvc.perform(as(org, admin, json(patch("/core/devices/" + id), "{\"expectedIntervalSec\":null,\"kind\":\"HYBRID\",\"baseVersion\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.effective.inheritedFrom").value("MODEL"))
                .andExpect(jsonPath("$.response.relations", hasSize(2)));
        assertThat(d.outboxPayloads(org, "device.changed")).anyMatch(p -> p.contains("\"UPDATED\"") && p.contains("spaceId"));
    }

    @Test
    @DisplayName("[DEV-02.02][AT-DEV-06.1] ACTIVE ↔ INACTIVE, 같은 상태·PENDING은 409 DEVICE_STATE_CONFLICT, 연결 상태와 따로 — TC-DEV-039·041")
    void activateDeactivate() throws Exception {
        long id = data.device(org, source, "s001", "ACTIVE", room, model);
        data.deviceState(org, id, "ONLINE", clock.instant(), "{}");
        mvc.perform(as(org, admin, json(post("/core/devices/" + id + "/deactivate"), "{\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("INACTIVE"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, get("/core/devices/" + id))).andExpect(jsonPath("$.response.state.connectivity").value("ONLINE"));
        mvc.perform(as(org, admin, json(post("/core/devices/" + id + "/deactivate"), "{\"baseVersion\":1}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_STATE_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/devices/" + id + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/devices/" + id + "/activate"), "{\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("ACTIVE"));
        assertThat(d.outboxPayloads(org, "device.changed")).anyMatch(p -> p.contains("DEACTIVATED")).anyMatch(p -> p.contains("ACTIVATED"));
        assertThat(auditCount(org, "DEVICE_DEACTIVATED")).isEqualTo(1);

        long pending = data.device(org, source, "s002", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/" + pending + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_STATE_CONFLICT"));
        long noModel = data.device(org, source, "s003", "INACTIVE", room, null);
        mvc.perform(as(org, admin, json(post("/core/devices/" + noModel + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_MODEL_REQUIRED"));
        long noSpace = data.device(org, source, "s004", "INACTIVE", null, model);
        mvc.perform(as(org, admin, json(post("/core/devices/" + noSpace + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_SPACE_REQUIRED"));
        mvc.perform(as(org, admin, json(post("/core/devices/" + id + "/activate"), "{}"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-02.02][API-DEV-17] 삭제는 소프트 삭제(204), 이후 404·목록 제외, baseVersion 필수, 기기 자격 폐기 — TC-DEV-033·040")
    void deleteDevice() throws Exception {
        long id = data.device(org, source, "del1", "ACTIVE", room, model);
        jdbc.sql("INSERT INTO data2flow_core.device_credentials (organization_id, device_id, username, created_by) VALUES (:org, :d, 'del1', 0)")
                .param("org", org).param("d", id).update();
        mvc.perform(as(org, admin, delete("/core/devices/" + id))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, delete("/core/devices/" + id).param("baseVersion", "0"))).andExpect(status().isNoContent());
        assertThat(d.status(id)).isEqualTo("DELETED");
        mvc.perform(as(org, admin, get("/core/devices/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/devices"))).andExpect(jsonPath("$.totalCount").value(0));
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.device_credentials WHERE device_id = :d").param("d", id)
                .query(String.class).single()).isEqualTo("REVOKED");
        assertThat(d.outboxPayloads(org, "device.changed")).anyMatch(p -> p.contains("DELETED"));
        // 삭제한 키로 다시 수동 등록하면 같은 행을 다시 쓴다
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("del1", "[]"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.id").value(Long.toString(id)));
    }

    @Test
    @DisplayName("[DEV-02.10][AT-DEV-08.2] 태그 일괄: 'Pilot' 추가는 'pilot'과 같은 태그로 1개 유지, 20개 넘으면 그 기기만 DEVICE_TAG_LIMIT, 태그 검색 — TC-DEV-080·082")
    void bulkTags() throws Exception {
        long a = data.device(org, source, "t001", "ACTIVE", room, model);
        long b = data.device(org, source, "t002", "ACTIVE", room, model);
        d.tag(org, a, "pilot");
        for (int i = 0; i < 20; i++) {
            d.tag(org, b, "x" + i);
        }
        mvc.perform(as(org, admin, json(post("/core/devices/tag"),
                        "{\"deviceIds\":[\"%d\",\"%d\",\"999999\"],\"add\":[\"Pilot\",\"new\"],\"remove\":[]}".formatted(a, b))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].ok").value(true))
                .andExpect(jsonPath("$.response.results[0].tags").value(containsInAnyOrder("pilot", "new")))
                .andExpect(jsonPath("$.response.results[1].errorCode").value("DEVICE_TAG_LIMIT"))
                .andExpect(jsonPath("$.response.results[2].errorCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, admin, json(post("/core/devices/tag"), "{\"deviceIds\":[\"%d\"],\"remove\":[\"PILOT\"]}".formatted(a))))
                .andExpect(jsonPath("$.response.results[0].tags[0]").value("new"));
        mvc.perform(as(org, admin, get("/core/devices").param("tag", "NEW"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, json(post("/core/devices/tag"), "{\"deviceIds\":[\"%d\"],\"add\":[\"a b\"]}".formatted(a))))
                .andExpect(status().isBadRequest());
        assertThat(auditCount(org, "DEVICE_TAGS_CHANGED")).isEqualTo(2);
        assertThat(d.version(a)).isEqualTo(2);
    }

    @Test
    @DisplayName("[DEV-02.01] 다른 조직의 소스로 수동 등록하면 404, 응답 ID·시각 형식(문자열 ID, UTC)")
    void crossOrganization() throws Exception {
        long other = fx.organization("o2");
        long otherSource = data.source(other, "o2s");
        mvc.perform(as(org, admin, json(post("/core/devices"), createBody("z1", "[]").replace("\"sourceId\":\"" + source,
                        "\"sourceId\":\"" + otherSource))))
                .andExpect(status().isNotFound());
        long id = data.device(org, source, "fmt1", "ACTIVE", room, model);
        mvc.perform(as(org, admin, get("/core/devices/" + id)))
                .andExpect(jsonPath("$.response.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.response.createdAt").exists());
        assertThat(d.count("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :o", Map.of("o", other))).isZero();
    }
}
