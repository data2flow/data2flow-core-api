package net.java21.data2flow.core.workorder;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.workorder.service.WorkOrderJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-08.02·08.05·08.06·DSH-13.04 작업 지시·정기 점검(API-DEV-90~95·129, BR-DEV-21·28, EVT-DEV-09) */
class WorkOrderIT extends IntegrationTestSupport {

    @Autowired
    WorkOrderJobs jobs;

    private FieldOpsData d;
    private long org;
    private long admin;
    private long operator;
    private long room;
    private long floor3;
    private long source;
    private long device;

    @BeforeEach
    void setUp() {
        d = new FieldOpsData(jdbc);
        org = fx.organization("wo");
        admin = fx.user(org, "wo.admin", "ADMIN");
        operator = fx.user(org, "wo.op", "OPERATOR");
        long site = data.site(org, "본관");
        floor3 = data.space(org, site, "FLOOR", "3층");
        room = data.space(org, floor3, "ROOM", "실습실");
        source = data.source(org, "cs");
        device = data.device(org, source, "dev-1", "ACTIVE", room, null);
    }

    private String create(long user, String body) throws Exception {
        String res = mvc.perform(as(org, user, json(post("/core/work-orders"), body)))
                .andExpect(status().isCreated()).andExpect(header().string("Location", startsWith("/api/v1/core/work-orders/")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.response.id");
    }

    private String transition(long user, String id, String body, int expected) throws Exception {
        return mvc.perform(as(org, user, json(post("/core/work-orders/" + id + "/transition"), body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[DEV-08.02][AT-DEV-16.1][API-DEV-90·92] 생성(담당자 있으면 ASSIGNED) → 시작 → 완료, 바뀔 때마다 EVT-DEV-09, 허용 안 된 전이 409, 감사 — TC-DEV-202·205·208·211")
    void lifecycle() throws Exception {
        String id = create(admin, """
                {"title":"배터리 교체","type":"battery","priority":"HIGH","targets":[{"deviceId":"%d"}],"assigneeId":"%d",
                 "dueAt":"2026-10-04T00:00:00Z","checklist":["배터리 확인","교체"],"origin":"ALARM","originRef":"alarm:7"}"""
                .formatted(device, operator));
        mvc.perform(as(org, admin, get("/core/work-orders/" + id)))
                .andExpect(jsonPath("$.response.status").value("ASSIGNED")).andExpect(jsonPath("$.response.type").value("BATTERY"))
                .andExpect(jsonPath("$.response.origin").value("ALARM")).andExpect(jsonPath("$.response.requesterId").value(Long.toString(admin)))
                .andExpect(jsonPath("$.response.targets[0].deviceId").value(Long.toString(device)))
                .andExpect(jsonPath("$.response.targets[0].spaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.checklist", hasSize(2)));
        transition(operator, id, "{\"action\":\"COMPLETE\"}", 409);
        transition(operator, id, "{\"action\":\"START\"}", 200);
        String done = transition(operator, id, "{\"action\":\"COMPLETE\",\"result\":{\"battery\":\"replaced\"},\"note\":\"교체 완료\"}", 200);
        assertThat((String) JsonPath.read(done, "$.response.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(done, "$.response.completedAt")).isNotNull();
        transition(operator, id, "{\"action\":\"CANCEL\"}", 409);
        transition(operator, id, "{\"action\":\"FLY\"}", 400);
        List<String> events = d.outboxPayloads(org, "workorder.changed");
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).contains("\"to\": \"ASSIGNED\"").contains("\"assigneeId\": " + operator);
        assertThat(events.get(2)).contains("\"from\": \"IN_PROGRESS\"").contains("\"to\": \"DONE\"").contains("replaced");
        mvc.perform(as(org, admin, get("/core/work-orders/" + id))).andExpect(jsonPath("$.response.comments[0].body").value("교체 완료"))
                .andExpect(jsonPath("$.response.result.battery").value("replaced"));
        assertThat(auditCount(org, "WORKORDER_CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "WORKORDER_TRANSITIONED")).isEqualTo(2);
        // 담당자 없이 만들면 OPEN, ASSIGN은 담당자 필수, 취소
        String open = create(operator, "{\"title\":\"점검\",\"type\":\"INSPECTION\",\"targets\":[{\"spaceId\":\"" + room + "\"}]}");
        transition(admin, open, "{\"action\":\"ASSIGN\"}", 400);
        transition(admin, open, "{\"action\":\"ASSIGN\",\"assigneeId\":\"" + operator + "\"}", 200);
        transition(admin, open, "{\"action\":\"CANCEL\"}", 200);
    }

    @Test
    @DisplayName("[DEV-08.02][AT-DEV-16.2][BR-DEV-21][API-DEV-129] 같은 기기·유형의 열린 작업이 있으면 자동 생성은 새로 만들지 않고 출처를 덧붙임, 같은 originRef는 그대로 — TC-DEV-202")
    void deduplicate() throws Exception {
        String first = mvc.perform(json(post("/internal/core/work-orders"), """
                        {"organizationId":%d,"title":"배터리 부족","type":"BATTERY","targets":[{"deviceId":"%d"}],"origin":"ALARM","originRef":"alarm:1"}"""
                        .formatted(org, device)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.linkedToExisting").value(false))
                .andExpect(jsonPath("$.response.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.response.id");
        mvc.perform(json(post("/internal/core/work-orders"), """
                        {"organizationId":%d,"title":"배터리 부족","type":"BATTERY","targets":[{"deviceId":"%d"}],"origin":"ALARM","originRef":"alarm:1"}"""
                        .formatted(org, device)))
                .andExpect(jsonPath("$.response.id").value(id)).andExpect(jsonPath("$.response.linkedToExisting").value(true));
        mvc.perform(json(post("/internal/core/work-orders"), """
                        {"organizationId":%d,"title":"배터리 예측","type":"BATTERY","targets":[{"deviceId":"%d"}],"origin":"ANALYSIS","originRef":"battery:9"}"""
                        .formatted(org, device)))
                .andExpect(jsonPath("$.response.id").value(id)).andExpect(jsonPath("$.response.linkedToExisting").value(true));
        mvc.perform(as(org, admin, get("/core/work-orders/" + id)))
                .andExpect(jsonPath("$.response.linkedOrigins", hasSize(1)))
                .andExpect(jsonPath("$.response.linkedOrigins[0].origin").value("ANALYSIS"));
        // 사용자 요청으로 같은 유형을 만들면 연결(200 + linkedToExisting)
        mvc.perform(as(org, admin, json(post("/core/work-orders"), """
                        {"title":"배터리","type":"BATTERY","targets":[{"deviceId":"%d"}],"origin":"FLOW","originRef":"flow:x"}""".formatted(device))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.linkedToExisting").value(true));
        // 수동은 새로 만든다
        create(admin, "{\"title\":\"배터리\",\"type\":\"BATTERY\",\"targets\":[{\"deviceId\":\"" + device + "\"}]}");
        assertThat(d.outboxPayloads(org, "workorder.changed")).hasSize(2);
        mvc.perform(json(post("/internal/core/work-orders"), """
                        {"organizationId":%d,"title":"x","type":"BATTERY","targets":[{"deviceId":"999999"}],"origin":"ALARM","originRef":"a"}"""
                        .formatted(org)))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/internal/core/work-orders"), """
                        {"organizationId":%d,"title":"x","type":"BATTERY","targets":[{"deviceId":"%d"}],"origin":"MANUAL","originRef":"a"}"""
                        .formatted(org, device)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-08.02][API-DEV-90] 검증: 제목·대상(기기 또는 공간 하나)·마감은 미래·담당자는 조직 사용자 — TC-DEV-203·209")
    void validation() throws Exception {
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"BATTERY\",\"targets\":[]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"BATTERY\",\"targets\":[{\"deviceId\":\"" + device
                        + "\",\"spaceId\":\"" + room + "\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("targets[0]"));
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"NOPE\",\"targets\":[{\"spaceId\":\"" + room + "\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("type"));
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"OTHER\",\"dueAt\":\"2026-10-01T00:00:00Z\","
                        + "\"targets\":[{\"spaceId\":\"" + room + "\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("dueAt"));
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"OTHER\",\"assigneeId\":\"999999\","
                        + "\"targets\":[{\"spaceId\":\"" + room + "\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        mvc.perform(as(org, admin, json(post("/core/work-orders"), "{\"title\":\"x\",\"type\":\"OTHER\",\"targets\":[{\"spaceId\":\"999999\"}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, admin, get("/core/work-orders/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("WORKORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DEV-13.04-mobile][DSH-13.04][AT-DSH-16.3] 첨부(이미지·PDF)·댓글·체크리스트, 같은 Idempotency-Key 완료 2회는 1회만 반영, 첨부 중복 없음 — TC-DSH-126 · TC-DEV-203")
    void attachmentsAndIdempotency() throws Exception {
        String id = create(admin, "{\"title\":\"교정\",\"type\":\"CALIBRATION\",\"targets\":[{\"deviceId\":\"" + device + "\"}],\"checklist\":[\"영점\"],"
                + "\"assigneeId\":\"" + operator + "\"}");
        String path = "/core/work-orders/" + id + "/attachments";
        String att = mvc.perform(FieldOpsData.upload(path, org, operator, "site.png", "image/png", FieldOpsData.PNG, "att-1"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.kind").value("PHOTO"))
                .andExpect(jsonPath("$.response.url").value(startsWith("/api/v1/core/work-orders/" + id + "/attachments/")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(FieldOpsData.upload(path, org, operator, "site.png", "image/png", FieldOpsData.PNG, "att-1"))
                .andExpect(status().isCreated());
        mvc.perform(FieldOpsData.upload(path, org, operator, "r.pdf", "application/pdf", FieldOpsData.PDF, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.kind").value("FILE"));
        mvc.perform(FieldOpsData.upload(path, org, operator, "x.exe", "application/octet-stream", new byte[]{1, 2, 3}, null))
                .andExpect(status().isBadRequest());
        String attId = JsonPath.read(att, "$.response.id");
        mvc.perform(as(org, admin, get("/core/work-orders/" + id + "/attachments/" + attId + "/content")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        String item = JsonPath.read(mvc.perform(as(org, admin, get("/core/work-orders/" + id))).andReturn().getResponse().getContentAsString(),
                "$.response.checklist[0].id");
        mvc.perform(as(org, operator, json(patch("/core/work-orders/" + id + "/checklist/" + item), "{\"done\":true}")))
                .andExpect(jsonPath("$.response.done").value(true)).andExpect(jsonPath("$.response.doneBy").value(Long.toString(operator)));
        mvc.perform(as(org, operator, json(post("/core/work-orders/" + id + "/comments"), "{\"text\":\"현장 도착\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.authorId").value(Long.toString(operator)));
        transition(operator, id, "{\"action\":\"START\"}", 200);
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(org, operator, json(post("/core/work-orders/" + id + "/transition"), "{\"action\":\"COMPLETE\"}")
                            .header("Idempotency-Key", "done-1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("DONE"));
        }
        mvc.perform(as(org, admin, get("/core/work-orders/" + id))).andExpect(jsonPath("$.response.attachments", hasSize(2)))
                .andExpect(jsonPath("$.response.comments", hasSize(1)));
        assertThat(d.outboxPayloads(org, "workorder.changed")).filteredOn(p -> p.contains("\"to\": \"DONE\"")).hasSize(1);
        mvc.perform(as(org, operator, json(patch("/core/work-orders/" + id + "/checklist/" + item), "{\"done\":false}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, operator, delete("/core/work-orders/" + id + "/attachments/" + attId))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/work-orders/" + id + "/attachments/" + attId + "/content"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-08.06][API-DEV-91] 내 작업(열린 것만)·마감 48시간 임박·지연·공간(하위 포함)·유형 필터, 평균 처리 시간 — TC-DEV-232·235")
    void listFilters() throws Exception {
        long other = data.device(org, source, "dev-2", "ACTIVE", floor3, null);
        String mine = create(admin, "{\"title\":\"a\",\"type\":\"REPAIR\",\"targets\":[{\"deviceId\":\"" + device + "\"}],\"assigneeId\":\""
                + operator + "\",\"dueAt\":\"2026-10-04T00:00:00Z\"}");
        String later = create(admin, "{\"title\":\"b\",\"type\":\"INSPECTION\",\"targets\":[{\"deviceId\":\"" + other + "\"}],"
                + "\"dueAt\":\"2026-10-10T00:00:00Z\"}");
        String closed = create(admin, "{\"title\":\"c\",\"type\":\"REPAIR\",\"targets\":[{\"spaceId\":\"" + room + "\"}],\"assigneeId\":\""
                + operator + "\"}");
        transition(operator, closed, "{\"action\":\"START\"}", 200);
        clock.advance(Duration.ofHours(6));
        transition(operator, closed, "{\"action\":\"COMPLETE\"}", 200);
        mvc.perform(as(org, operator, get("/core/work-orders").param("assigneeId", "me")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(mine))
                .andExpect(jsonPath("$.stats.avgLeadTimeHours").value(6.0));
        mvc.perform(as(org, admin, get("/core/work-orders").param("dueBefore", clock.instant().plus(Duration.ofHours(48)).toString())))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(mine));
        clock.advance(Duration.ofDays(2));
        mvc.perform(as(org, admin, get("/core/work-orders").param("overdue", "true")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(mine));
        mvc.perform(as(org, admin, get("/core/work-orders").param("spaceId", Long.toString(floor3))))
                .andExpect(jsonPath("$.totalCount").value(3));
        mvc.perform(as(org, admin, get("/core/work-orders").param("spaceId", Long.toString(room))))
                .andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, admin, get("/core/work-orders").param("type", "INSPECTION")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(later));
        mvc.perform(as(org, admin, get("/core/work-orders").param("status", "DONE")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.stats.avgLeadTimeHours").value(6.0));
        mvc.perform(as(org, admin, get("/core/work-orders").param("status", "BAD"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-08.05][AT-DEV-16.4][BR-DEV-28] 계획: 그룹 10대·180일·lead 7일 → 기한이 되면 10건, next_due_on +180일, 다시 돌려도 중복 없음 — TC-DEV-226·229")
    void maintenancePlan() throws Exception {
        List<Long> devices = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            devices.add(data.device(org, source, "p-" + i, "ACTIVE", room, null));
        }
        long group = d.staticGroup(org, "교정 대상", devices);
        String plan = JsonPath.read(mvc.perform(as(org, admin, json(post("/core/maintenance-plans"), """
                        {"name":"6개월 교정","targetGroupId":"%d","workType":"CALIBRATION","intervalDays":180,"leadDays":7,
                         "nextDueOn":"2026-10-12","defaultAssigneeId":"%d","checklistTemplate":["영점 조정"]}""".formatted(group, operator))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.id");
        assertThat(jobs.runOnce()).isZero(); // 10-03: 기한(10-05)이 아직
        clock.advance(Duration.ofDays(2));    // 10-05 09:00 KST
        assertThat(jobs.runOnce()).isEqualTo(10);
        mvc.perform(as(org, admin, get("/core/maintenance-plans")))
                .andExpect(jsonPath("$.responses[0].nextDueOn").value(LocalDate.parse("2026-10-12").plusDays(180).toString()));
        mvc.perform(as(org, operator, get("/core/work-orders").param("assigneeId", "me").param("type", "CALIBRATION")))
                .andExpect(jsonPath("$.totalCount").value(10)).andExpect(jsonPath("$.responses[0].origin").value("SCHEDULE"))
                .andExpect(jsonPath("$.responses[0].checklist[0].text").value("영점 조정"));
        assertThat(jobs.runOnce()).isZero();
        // 수정(baseVersion)·검증·삭제
        mvc.perform(as(org, admin, json(patch("/core/maintenance-plans/" + plan), "{\"intervalDays\":3,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/maintenance-plans/" + plan), "{\"enabled\":false,\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(patch("/core/maintenance-plans/" + plan), "{\"enabled\":false,\"name\":\"중지\",\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.enabled").value(false));
        mvc.perform(as(org, admin, json(post("/core/maintenance-plans"), """
                        {"name":"x","targetGroupId":"999999","workType":"CALIBRATION","intervalDays":30,"nextDueOn":"2026-10-12"}""")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("GROUP_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/maintenance-plans"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, delete("/core/maintenance-plans/" + plan))).andExpect(status().isNoContent());
        assertThat(auditCount(org, "MAINTENANCE_PLAN_CHANGED")).isEqualTo(3);
    }

    @Test
    @DisplayName("[DEV-08.02][BR-DEV-25] 권한: VIEWER 조회 가능·쓰기 403, 공간 범위 밖 404, 다른 조직 404 — TC-DEV-206·212")
    void permissions() throws Exception {
        String id = create(admin, "{\"title\":\"a\",\"type\":\"REPAIR\",\"targets\":[{\"deviceId\":\"" + device + "\"}]}");
        long viewer = fx.user(org, "wo.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/work-orders/" + id))).andExpect(status().isOk());
        transition(viewer, id, "{\"action\":\"START\"}", 403);
        mvc.perform(as(org, viewer, json(post("/core/work-orders"), "{\"title\":\"a\",\"type\":\"REPAIR\",\"targets\":[{\"spaceId\":\"" + room + "\"}]}")))
                .andExpect(status().isForbidden());
        long otherSpace = data.space(org, null, "SITE", "별관");
        data.spaceScope(org, viewer, List.of(otherSpace));
        mvc.perform(as(org, viewer, get("/core/work-orders/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/work-orders"))).andExpect(jsonPath("$.totalCount").value(0));
        long other = fx.organization("wo2");
        long otherAdmin = fx.user(other, "wo2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/work-orders/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(post("/core/work-orders"), "{\"title\":\"a\",\"type\":\"REPAIR\",\"targets\":[{\"deviceId\":\""
                        + device + "\"}]}")))
                .andExpect(status().isNotFound());
    }
}
