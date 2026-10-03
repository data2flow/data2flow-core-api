package net.java21.data2flow.core.space;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공간 트리·속성·목표·시간표·모드(DEV-01.01·01.02·01.04·11.01) — 실제 PostgreSQL 18 + 아웃박스(EVT-DEV-05·설정 변경 SPACE)·감사·조직 조건.
 * TC-DEV-004·008·012·023·280~283
 */
class SpaceIT extends IntegrationTestSupport {

    private long org;
    private long integrator;

    private void setUp() {
        org = fx.organization("space");
        integrator = fx.user(org, "int.user", "INTEGRATOR");
    }

    private String create(String body) throws Exception {
        String json = mvc.perform(as(org, integrator, json(post("/core/spaces"), body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.response.id");
    }

    private long outbox(String kind, String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = :kind AND routing_key = :rk")
                .param("org", org).param("kind", kind).param("rk", routingKey).query(Long.class).single();
    }

    @Test
    @DisplayName("[DEV-01.01][AT-DEV-01.1] 빈 조직에 사이트(Asia/Seoul) 생성 → 트리에 1개, 시간대 저장, EVT-DEV-05·설정 변경·감사·설정 버전 — TC-DEV-004")
    void createSite() throws Exception {
        setUp();
        mvc.perform(as(org, integrator, json(post("/core/spaces"),
                        "{\"type\":\"SITE\",\"name\":\"광주캠퍼스\",\"timezone\":\"Asia/Seoul\",\"address\":\"광주\",\"latitude\":35.1595,\"longitude\":126.8526}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/spaces/")))
                .andExpect(jsonPath("$.response.type").value("SITE"))
                .andExpect(jsonPath("$.response.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.response.depth").value(1))
                .andExpect(jsonPath("$.response.kmaNx").value(58))
                .andExpect(jsonPath("$.response.kmaNy").value(74))
                .andExpect(jsonPath("$.response.version").value(0));
        mvc.perform(as(org, integrator, get("/core/spaces")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].name").value("광주캠퍼스"))
                .andExpect(jsonPath("$.response[0].children.length()").value(0));
        assertThat(outbox("EVENT", "space.changed")).isEqualTo(1);
        assertThat(outbox("CONFIG", "")).isEqualTo(1);
        assertThat(auditCount(org, "SPACE_CREATED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM data2flow_core.config_versions WHERE organization_id = :org AND scope = 'SPACES'")
                .param("org", org).query(Long.class).single()).isEqualTo(1);
        String payload = jdbc.sql("SELECT CAST(payload AS text) FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = 'space.changed'")
                .param("org", org).query(String.class).single();
        assertThat(payload.replace(" ", "")).contains("\"change\":\"CREATED\"").containsPattern("\"path\":\"/\\d+\"");
    }

    @Test
    @DisplayName("[DEV-01.01][AT-DEV-01.2~01.5] 깊이 6 성공·7 SPACE_DEPTH_EXCEEDED, 이름 대소문자·공백 중복 409, 비지 않은 공간 삭제 409 blockers, 하위로 이동 400 — TC-DEV-001·002")
    void treeRules() throws Exception {
        setUp();
        String site = create("{\"type\":\"SITE\",\"name\":\"캠퍼스\",\"timezone\":\"Asia/Seoul\"}");
        String building = create("{\"parentId\":\"" + site + "\",\"type\":\"BUILDING\",\"name\":\"본관\"}");
        String floor = create("{\"parentId\":\"" + building + "\",\"type\":\"FLOOR\",\"name\":\"3층\"}");
        String room = create("{\"parentId\":\"" + floor + "\",\"type\":\"ROOM\",\"name\":\"Lab\"}");
        String zone = create("{\"parentId\":\"" + room + "\",\"type\":\"ZONE\",\"name\":\"창가\"}");
        String zone6 = create("{\"parentId\":\"" + zone + "\",\"type\":\"ZONE\",\"name\":\"창가 1\"}");
        mvc.perform(as(org, integrator, json(post("/core/spaces"), "{\"parentId\":\"" + zone6 + "\",\"type\":\"ZONE\",\"name\":\"7단계\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_DEPTH_EXCEEDED"));
        // 상위보다 큰 단위, 최상위가 SITE가 아님
        mvc.perform(as(org, integrator, json(post("/core/spaces"), "{\"parentId\":\"" + floor + "\",\"type\":\"BUILDING\",\"name\":\"별관\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_TYPE_INVALID"));
        mvc.perform(as(org, integrator, json(post("/core/spaces"), "{\"type\":\"BUILDING\",\"name\":\"떠 있는 건물\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_TYPE_INVALID"));
        // 중간 생략 허용(사이트 바로 아래 실)
        create("{\"parentId\":\"" + site + "\",\"type\":\"ROOM\",\"name\":\"경비실\"}");
        mvc.perform(as(org, integrator, json(post("/core/spaces"), "{\"parentId\":\"" + floor + "\",\"type\":\"ROOM\",\"name\":\"lab \"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NAME_DUPLICATE"));
        // 비지 않은 공간: 하위 + 기기 3대
        long source = data.source(org, "src-1");
        for (int i = 0; i < 3; i++) {
            data.device(org, source, "dev-" + i, "ACTIVE", Long.parseLong(room), null);
        }
        mvc.perform(as(org, integrator, delete("/core/spaces/" + room)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_EMPTY"))
                .andExpect(jsonPath("$.header.resultMessage").value("하위 공간이나 기기가 있어 삭제할 수 없습니다"))
                .andExpect(jsonPath("$.response.blockers.devices").value(3))
                .andExpect(jsonPath("$.response.blockers.children").value(1))
                .andExpect(jsonPath("$.response.blockers.markers").value(0))
                .andExpect(jsonPath("$.response.blockers.workOrders").value(0));
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + building + "/move"), "{\"newParentId\":\"" + floor + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_MOVE_CYCLE"));
        // 빈 공간 삭제는 204, 다시 조회하면 404, 같은 이름으로 다시 만들 수 있다
        mvc.perform(as(org, integrator, delete("/core/spaces/" + zone6))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, get("/core/spaces/" + zone6)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        create("{\"parentId\":\"" + zone + "\",\"type\":\"ZONE\",\"name\":\"창가 1\"}");
        assertThat(auditCount(org, "SPACE_DELETED")).isEqualTo(1);
        String deleted = jdbc.sql("SELECT CAST(payload AS text) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'CONFIG' ORDER BY id DESC OFFSET 1 LIMIT 1")
                .param("org", org).query(String.class).single();
        assertThat(deleted.replace(" ", "")).contains("\"op\":\"DELETE\"");
    }

    @Test
    @DisplayName("[DEV-01.01] 이동하면 하위 전체의 path·depth가 바뀌고, 깊이 한도·종류 순서·이름 중복을 지킨다 — TC-DEV-008")
    void moveUpdatesSubtree() throws Exception {
        setUp();
        long site = data.site(org, "사이트");
        long a = data.space(org, site, "BUILDING", "A동");
        long b = data.space(org, site, "BUILDING", "B동");
        long floor = data.space(org, a, "FLOOR", "1층");
        long room = data.space(org, floor, "ROOM", "101호");
        long zone = data.space(org, room, "ZONE", "구역");
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + floor + "/move"), "{\"newParentId\":\"" + b + "\",\"sortOrder\":3}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.parentId").value(Long.toString(b)))
                .andExpect(jsonPath("$.response.path").value("/" + site + "/" + b + "/" + floor))
                .andExpect(jsonPath("$.response.sortOrder").value(3))
                .andExpect(jsonPath("$.response.version").value(1));
        assertThat(jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE id = :id").param("id", zone).query(String.class).single())
                .isEqualTo("/" + site + "/" + b + "/" + floor + "/" + room + "/" + zone + "/");
        assertThat(auditCount(org, "SPACE_MOVED")).isEqualTo(1);
        // 층을 구역 아래로: 종류 위반
        long zoneB = data.space(org, b, "ZONE", "B구역");
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + floor + "/move"), "{\"newParentId\":\"" + zoneB + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_TYPE_INVALID"));
        // 깊이: 구역 사슬 아래로 옮기면 6 초과
        long z2 = data.space(org, zoneB, "ZONE", "z2");
        long z3 = data.space(org, z2, "ZONE", "z3");
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + room + "/move"), "{\"newParentId\":\"" + z3 + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_TYPE_INVALID"));
        long zoneTwin = data.space(org, z3, "ZONE", "구역");
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + zone + "/move"), "{\"newParentId\":\"" + z3 + "\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NAME_DUPLICATE"));
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + zone + "/move"), "{\"newParentId\":\"" + zoneTwin + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_DEPTH_EXCEEDED"));
        // 사이트는 옮길 수 없다, 낡은 baseVersion은 409, 새 부모 필수
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + site + "/move"), "{\"newParentId\":\"" + b + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_MOVE_CYCLE"));
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + room + "/move"), "{\"newParentId\":\"" + a + "\",\"baseVersion\":7}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + room + "/move"), "{}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("newParentId"));
        // 다른 조직 공간은 404
        long other = fx.organization("other");
        long otherSite = data.site(other, "남의 사이트");
        mvc.perform(as(org, integrator, json(post("/core/spaces/" + room + "/move"), "{\"newParentId\":\"" + otherSite + "\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DEV-01.02][DEV-10.01] PATCH 온 키만·baseVersion, SITE 전용 필드·용도·면적 검증, 코드 조직 안 고유, 좌표 바꾸면 격자 다시 계산 — TC-DEV-009·010·261")
    void patchAttributes() throws Exception {
        setUp();
        long site = data.site(org, "사이트");
        long room = data.space(org, site, "ROOM", "강의실");
        long room2 = data.space(org, site, "ROOM", "회의실");
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room),
                        "{\"usage\":\"classroom\",\"areaM2\":66.555,\"capacity\":30,\"code\":\"R-101\",\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.usage").value("CLASSROOM"))
                .andExpect(jsonPath("$.response.areaM2").value(66.56))
                .andExpect(jsonPath("$.response.capacity").value(30))
                .andExpect(jsonPath("$.response.name").value("강의실"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room), "{\"capacity\":10,\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room), "{\"capacity\":10}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room),
                        "{\"type\":\"ZONE\",\"timezone\":\"Asia/Seoul\",\"usage\":\"KITCHEN\",\"areaM2\":-1,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("type")));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room),
                        "{\"timezone\":\"Asia/Seoul\",\"usage\":\"KITCHEN\",\"areaM2\":-1,\"capacity\":-2,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("usage", "areaM2", "capacity", "timezone")));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room2), "{\"code\":\"R-101\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SPACE_CODE_DUPLICATE"));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + room2), "{\"name\":\"강의실\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NAME_DUPLICATE"));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + site), "{\"latitude\":37.5665,\"longitude\":126.978,\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kmaNx").value(60)).andExpect(jsonPath("$.response.kmaNy").value(127));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + site), "{\"timezone\":null,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("timezone"));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + site), "{\"timezone\":\"Mars/Base\",\"latitude\":91,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("timezone", "latitude")));
        mvc.perform(as(org, integrator, json(patch("/core/spaces/" + site), "{\"name\":3,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        assertThat(auditCount(org, "SPACE_UPDATED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[DEV-01.04][AT-DEV-02.1] 건물 목표 22~26, 실은 상속 → 실 목표 22~26 inherited=true, 실이 정하면 그 값, 없는 측정 항목 404 — TC-DEV-020·023")
    void targetsInherit() throws Exception {
        setUp();
        data.metric(org, "temperature", "°C");
        data.metric(org, "co2", "ppm");
        long site = data.site(org, "사이트");
        long building = data.space(org, site, "BUILDING", "본관");
        long room = data.space(org, building, "ROOM", "실습실");
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + building + "/targets"),
                        "{\"inherit\":false,\"items\":[{\"metricKey\":\"temperature\",\"min\":22,\"max\":26}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(false))
                .andExpect(jsonPath("$.response.items[0].metricKey").value("temperature"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + room + "/targets")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(true))
                .andExpect(jsonPath("$.response.inheritedFromSpaceId").value(Long.toString(building)))
                .andExpect(jsonPath("$.response.effective[0].min").value(22.0))
                .andExpect(jsonPath("$.response.effective[0].max").value(26.0))
                .andExpect(jsonPath("$.response.effective[0].inherited").value(true))
                .andExpect(jsonPath("$.response.effective[0].inheritedFromSpaceName").value("본관"));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/targets"),
                        "{\"inherit\":false,\"items\":[{\"metricKey\":\"co2\",\"max\":1000}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.effective.length()").value(2))
                .andExpect(jsonPath("$.response.effective[0].metricKey").value("co2"))
                .andExpect(jsonPath("$.response.effective[0].inherited").value(false))
                .andExpect(jsonPath("$.response.effective[1].metricKey").value("temperature"))
                .andExpect(jsonPath("$.response.effective[1].inherited").value(true));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/targets"),
                        "{\"inherit\":false,\"items\":[{\"metricKey\":\"radon\",\"max\":1}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/targets"),
                        "{\"inherit\":false,\"items\":[{\"metricKey\":\"co2\",\"min\":30,\"max\":20},{\"metricKey\":\"co2\"},{\"metricKey\":\"\"}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("items[0].min", "items[1].metricKey", "items[1]",
                        "items[2].metricKey")));
        // 상속으로 돌리면 이 공간 값이 지워진다
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/targets"), "{\"inherit\":true,\"items\":[{\"metricKey\":\"co2\",\"max\":1}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(true))
                .andExpect(jsonPath("$.response.effective.length()").value(1));
        // 내부 API-DEV-126(조직 범위)
        mvc.perform(get("/internal/core/spaces/" + room + "/targets").header("X-CALLER-SERVICE", "data2flow-flow-engine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.effective[0].inheritedFromSpaceId").value(Long.toString(building)));
        mvc.perform(get("/internal/core/spaces/999999/targets")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-11.01][AT-DEV-02.2·02.3] 실 시간표 월 09:00~18:00: 08:59 UNOCCUPIED·09:00 OCCUPIED·18:00 UNOCCUPIED, 겹치면 SCHEDULE_OVERLAP, 하위 상속 — TC-DEV-280·283")
    void scheduleAndMode() throws Exception {
        setUp();
        long site = data.site(org, "사이트");
        long room = data.space(org, site, "ROOM", "실습실");
        long zone = data.space(org, room, "ZONE", "구역");
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/schedule"),
                        "{\"inherit\":false,\"slots\":[{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"12:00\"},{\"dayOfWeek\":1,\"start\":\"11:00\",\"end\":\"13:00\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCHEDULE_OVERLAP"))
                .andExpect(jsonPath("$.header.resultMessage").value("같은 요일의 시간 구간이 겹칩니다"));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/schedule"),
                        "{\"inherit\":false,\"slots\":[{\"dayOfWeek\":1,\"start\":\"9:00\",\"end\":\"12:00\"},{\"dayOfWeek\":8,\"start\":\"09:00\",\"end\":\"12:00\"},{\"dayOfWeek\":2,\"start\":\"13:00\",\"end\":\"12:00\"}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("slots[0]", "slots[1].dayOfWeek", "slots[2].end")));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/schedule"),
                        "{\"inherit\":false,\"slots\":[{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"12:00\"},{\"dayOfWeek\":1,\"start\":\"12:00\",\"end\":\"18:00\"},{\"dayOfWeek\":7,\"start\":\"22:00\",\"end\":\"24:00\"}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(false))
                .andExpect(jsonPath("$.response.slots.length()").value(3))
                .andExpect(jsonPath("$.response.slots[2].end").value("24:00"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + zone + "/schedule")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(true))
                .andExpect(jsonPath("$.response.inheritedFromSpaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.slots.length()").value(3));
        // 2026-10-05(월) Asia/Seoul
        clock.set(Instant.parse("2026-10-04T23:59:00Z"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + zone + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("UNOCCUPIED"))
                .andExpect(jsonPath("$.response.source").value("SCHEDULE"))
                .andExpect(jsonPath("$.response.nextChangeAt").value("2026-10-05T00:00:00Z"));
        clock.set(Instant.parse("2026-10-05T00:00:00Z"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + room + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("OCCUPIED"))
                .andExpect(jsonPath("$.response.nextChangeAt").value("2026-10-05T09:00:00Z"));
        clock.set(Instant.parse("2026-10-05T09:00:00Z"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + room + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("UNOCCUPIED"));
        // 수동 지정(DEV-11.02 저장은 M5) — 저장된 값이 있으면 시간표보다 앞선다
        jdbc.sql("UPDATE data2flow_core.spaces SET mode_override = 'MAINTENANCE', mode_override_until = :until WHERE id = :id")
                .param("until", java.time.OffsetDateTime.parse("2026-10-05T11:00:00Z")).param("id", site).update();
        mvc.perform(get("/internal/core/spaces/" + zone + "/mode"))
                .andExpect(jsonPath("$.response.mode").value("MAINTENANCE"))
                .andExpect(jsonPath("$.response.source").value("OVERRIDE"))
                .andExpect(jsonPath("$.response.until").value("2026-10-05T11:00:00Z"));
        clock.set(Instant.parse("2026-10-05T11:00:00Z"));
        mvc.perform(get("/internal/core/spaces/" + zone + "/mode"))
                .andExpect(jsonPath("$.response.source").value("SCHEDULE"));
        // 상속으로 돌리면 구간이 지워지고, 루트까지 정한 곳이 없으면 운영 시간 없음
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + room + "/schedule"), "{\"inherit\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.inherit").value(true))
                .andExpect(jsonPath("$.response.slots.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.space_schedules WHERE space_id = :id").param("id", room)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("[DEV-01.01] 공간 상세·트리 include=counts,mode,targets, rootId·depth — 사전 작업 GET /core/spaces/{space-id}")
    void detailAndTreeIncludes() throws Exception {
        setUp();
        data.metric(org, "temperature", "°C");
        long site = data.site(org, "사이트");
        long building = data.space(org, site, "BUILDING", "본관");
        long room = data.space(org, building, "ROOM", "실습실");
        long source = data.source(org, "src-a");
        long d1 = data.device(org, source, "a1", "ACTIVE", room, null);
        data.device(org, source, "a2", "ACTIVE", building, null);
        data.device(org, source, "a3", "DELETED", room, null);
        data.deviceState(org, d1, "OFFLINE", MutableClock.T0, null);
        jdbc.sql("INSERT INTO data2flow_core.space_targets (organization_id, space_id, metric_key, min_value, max_value) VALUES (:org, :s, 'temperature', 20, 25)")
                .param("org", org).param("s", site).update();
        mvc.perform(as(org, integrator, get("/core/spaces/" + room)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ancestors.length()").value(2))
                .andExpect(jsonPath("$.response.ancestors[1].name").value("본관"))
                .andExpect(jsonPath("$.response.effectiveTimezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.response.deviceCount").value(1))
                .andExpect(jsonPath("$.response.childCount").value(0))
                .andExpect(jsonPath("$.response.hasFloorplan").value(false))
                .andExpect(jsonPath("$.response.targets.effective[0].inheritedFromSpaceId").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.schedule.inherit").value(true))
                .andExpect(jsonPath("$.response.mode.mode").value("UNOCCUPIED"));
        mvc.perform(as(org, integrator, get("/core/spaces").param("include", "counts,mode,targets")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].counts.devices").value(2))
                .andExpect(jsonPath("$.response[0].counts.offline").value(1))
                .andExpect(jsonPath("$.response[0].mode").value("UNOCCUPIED"))
                .andExpect(jsonPath("$.response[0].targets[0].metricKey").value("temperature"))
                .andExpect(jsonPath("$.response[0].children[0].children[0].counts.devices").value(1));
        mvc.perform(as(org, integrator, get("/core/spaces").param("rootId", Long.toString(building)).param("depth", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].id").value(Long.toString(building)))
                .andExpect(jsonPath("$.response[0].counts").doesNotExist())
                .andExpect(jsonPath("$.response[0].children.length()").value(0));
        mvc.perform(as(org, integrator, get("/core/spaces").param("depth", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, get("/core/spaces").param("rootId", "abc")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("rootId"));
    }

    @Test
    @DisplayName("[DEV-01.05][AT-DEV-12.1·12.2] 에어컨을 실습실에 설치 → 자동 CONTROLS, 추가 관계 저장, '실습실을 제어하는 Thermostat' 질의 1대 — TC-DEV-027·029")
    void relations() throws Exception {
        setUp();
        long site = data.site(org, "사이트");
        long lab = data.space(org, site, "ROOM", "실습실");
        long hall = data.space(org, site, "ROOM", "복도");
        long source = data.source(org, "src-a");
        long model = data.model(org, "AC-1", List.of());
        long sensorModel = data.model(org, "EM-1", List.of());
        jdbc.sql("UPDATE data2flow_core.device_models SET capabilities = CAST(:caps AS jsonb) WHERE id = :m")
                .param("caps", "[{\"capability\":\"Thermostat\"},{\"capability\":\"OnOff\"}]").param("m", model).update();
        long ac = data.device(org, source, "ac-1", "ACTIVE", lab, model);
        jdbc.sql("UPDATE data2flow_core.devices SET kind = 'ACTUATOR' WHERE id = :id").param("id", ac).update();
        long sensor = data.device(org, source, "em-1", "ACTIVE", hall, sensorModel);
        mvc.perform(as(org, integrator, get("/core/devices/" + ac + "/relations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.items.length()").value(1))
                .andExpect(jsonPath("$.response.items[0].relation").value("CONTROLS"))
                .andExpect(jsonPath("$.response.items[0].spaceName").value("실습실"))
                .andExpect(jsonPath("$.response.items[0].auto").value(true));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + sensor + "/relations"),
                        "{\"items\":[{\"spaceId\":\"" + lab + "\",\"relation\":\"measures\"},{\"spaceId\":\"" + hall + "\",\"relation\":\"MEASURES\"}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.items.length()").value(2))
                .andExpect(jsonPath("$.response.items[0].auto").value(true))
                .andExpect(jsonPath("$.response.items[1].spaceId").value(Long.toString(lab)))
                .andExpect(jsonPath("$.response.items[1].auto").value(false));
        assertThat(auditCount(org, "DEVICE_RELATIONS_CHANGED")).isEqualTo(1);
        mvc.perform(as(org, integrator, json(put("/core/devices/" + sensor + "/relations"), "{\"items\":[{\"spaceId\":\"999999\",\"relation\":\"MEASURES\"}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(put("/core/devices/" + sensor + "/relations"), "{\"items\":[{\"spaceId\":\"" + lab + "\",\"relation\":\"OWNS\"},{}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("items[0].relation", "items[1].spaceId")));
        mvc.perform(as(org, integrator, get("/core/spaces/" + lab + "/devices").param("relation", "CONTROLS").param("capability", "thermostat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(ac)))
                .andExpect(jsonPath("$.responses[0].capabilities.length()").value(2))
                .andExpect(jsonPath("$.responses[0].connectivity").value("UNKNOWN"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + lab + "/devices")))
                .andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, integrator, get("/core/spaces/" + site + "/devices").param("includeDescendants", "true").param("relation", "MEASURES")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(sensor)));
        mvc.perform(as(org, integrator, get("/core/spaces/" + site + "/devices").param("relation", "OWNS")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/core/spaces/" + lab + "/devices").param("relation", "CONTROLS").param("capability", "Thermostat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].deviceId").value(Long.toString(ac)))
                .andExpect(jsonPath("$.response[0].connection").value("UNKNOWN"));
        mvc.perform(as(org, integrator, get("/core/devices/999999/relations")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
    }
}
