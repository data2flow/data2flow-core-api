package net.java21.data2flow.core.commissioning;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.commissioning.service.CommissioningJobs;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.workorder.FieldOpsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-09.04 QR 라벨·딥링크, DEV-13.05 현장 설치(BR-DEV-37·38, EVT-DEV-14), DEV-13.06 설치 현황판(API-DEV-24·26·137·138) */
class CommissioningIT extends IntegrationTestSupport {

    @Autowired
    CommissioningJobs jobs;

    private FieldOpsData d;
    private long org;
    private long admin;
    private long operator;
    private long site;
    private long floor3;
    private long room;
    private long source;
    private long model;

    @BeforeEach
    void setUp() {
        d = new FieldOpsData(jdbc);
        org = fx.organization("cm");
        admin = fx.user(org, "cm.admin", "ADMIN");
        operator = fx.user(org, "cm.op", "OPERATOR");
        site = data.site(org, "본관");
        floor3 = data.space(org, site, "FLOOR", "3층");
        room = data.space(org, floor3, "ROOM", "실습실");
        source = data.source(org, "cs");
        data.metric(org, "temperature", "°C");
        model = data.model(org, "EM300-TH", List.of("temperature"));
    }

    private MockMultipartHttpServletRequestBuilder commission(long user, long deviceId, String clientOpId, long spaceId, String installedAt,
                                                              boolean photo) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart("/core/devices/" + deviceId + "/commission");
        if (photo) {
            b.file(new MockMultipartFile("photos", "p.png", "image/png", FieldOpsData.PNG));
        }
        b.param("clientOpId", clientOpId).param("spaceId", Long.toString(spaceId)).param("x", "0.25").param("y", "0.5");
        if (installedAt != null) {
            b.param("installedAt", installedAt);
        }
        b.header("X-USER-ID", Long.toString(user)).header("X-ORG-ID", Long.toString(org)).header("Accept-Language", "ko");
        return b;
    }

    @Test
    @DisplayName("[DEV-09.04][AT-DEV-21.2][API-DEV-24·26] QR 토큰 24자·내용 URL, 해석, 재발급하면 이전 QR 404, 권한 밖 404 DEVICE_NOT_FOUND, 라벨 PDF — TC-DEV-255·258·259")
    void qr() throws Exception {
        long device = data.device(org, source, "dev-1", "ACTIVE", room, model);
        String res = mvc.perform(as(org, admin, get("/core/devices/" + device + "/qr"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(res, "$.response.qrToken");
        assertThat(token).hasSize(24);
        assertThat((String) JsonPath.read(res, "$.response.url")).isEqualTo("https://web.test/d/" + token);
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/qr"))).andExpect(jsonPath("$.response.qrToken").value(token));
        mvc.perform(as(org, operator, get("/core/qr/" + token))).andExpect(jsonPath("$.response.deviceId").value(Long.toString(device)));
        String reissued = JsonPath.read(mvc.perform(as(org, operator, post("/core/devices/" + device + "/reissue-qr")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.response.qrToken");
        assertThat(reissued).isNotEqualTo(token);
        mvc.perform(as(org, operator, get("/core/qr/" + token))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/qr/" + reissued))).andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/qr/short"))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "DEVICE_QR_REISSUED")).isEqualTo(1);
        long analyst = fx.user(org, "cm.analyst", "ANALYST");
        mvc.perform(as(org, analyst, post("/core/devices/" + device + "/reissue-qr"))).andExpect(status().isForbidden());
        data.spaceScope(org, analyst, List.of(data.site(org, "별관")));
        mvc.perform(as(org, analyst, get("/core/qr/" + reissued))).andExpect(status().isNotFound());
        long other = fx.organization("cm2");
        mvc.perform(as(other, fx.user(other, "cm2.admin", "ADMIN"), get("/core/qr/" + reissued))).andExpect(status().isNotFound());
        // 라벨 PDF: 토큰이 없던 기기도 만든다
        long second = data.device(org, source, "dev-2", "ACTIVE", room, model);
        byte[] pdf = mvc.perform(as(org, operator, json(post("/core/devices/qr-labels"),
                        "{\"deviceIds\":[\"" + device + "\",\"" + second + "\"],\"layout\":\"A4_3x8\"}")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-1.4").contains("(dev-1) Tj").contains("(dev-2) Tj").endsWith("%%EOF\n");
        assertThat(jdbc.sql("SELECT qr_token FROM data2flow_core.devices WHERE id = :id").param("id", second).query(String.class).single())
                .hasSize(24);
        mvc.perform(as(org, operator, json(post("/core/devices/qr-labels"), "{\"deviceIds\":[\"" + device + "\"],\"layout\":\"LETTER\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(post("/core/devices/qr-labels"), "{\"deviceIds\":[\"999999\"]}"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-13.05][AT-DEV-27.1·27.2][BR-DEV-37] 승인 대기 기기 QR 설치 → ACTIVE·공간·좌표·사진·작업자, 같은 clientOpId 재전송은 1회, 첫 수신 VERIFIED + 값, EVT-DEV-14 — TC-DEV-323·325")
    void commissionAndVerify() throws Exception {
        long device = data.device(org, source, "dev-p", "PENDING", null, model);
        String op = UUID.randomUUID().toString();
        mvc.perform(commission(operator, device, op, room, null, true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("INSTALLED"))
                .andExpect(jsonPath("$.response.waitUntil").value("2026-10-03T00:10:00Z"));
        mvc.perform(commission(operator, device, op, room, null, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("INSTALLED"));
        mvc.perform(as(org, admin, get("/core/devices/" + device)))
                .andExpect(jsonPath("$.response.status").value("ACTIVE")).andExpect(jsonPath("$.response.space.id").value(Long.toString(room)));
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/commission")))
                .andExpect(jsonPath("$.response.installedBy").value(Long.toString(operator)))
                .andExpect(jsonPath("$.response.installedByName").exists())
                .andExpect(jsonPath("$.response.photoUrls", hasSize(1))).andExpect(jsonPath("$.response.x").value(0.25));
        assertThat(auditCount(org, "DEVICE_COMMISSIONED")).isEqualTo(1);
        // 3분 뒤 첫 데이터 → VERIFIED(1분 작업 또는 온라인 이벤트)
        clock.advance(Duration.ofMinutes(3));
        data.deviceState(org, device, "ONLINE", clock.instant(), "{\"temperature\":{\"v\":23.4,\"t\":\"2026-10-03T00:03:00Z\",\"q\":0}}");
        assertThat(jobs.runOnce()).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/commission")))
                .andExpect(jsonPath("$.response.status").value("VERIFIED"))
                .andExpect(jsonPath("$.response.firstSeenAt").value("2026-10-03T00:03:00Z"))
                .andExpect(jsonPath("$.response.latest.temperature.v").value(23.4))
                .andExpect(jsonPath("$.response.checklist.firstData").value(true));
        List<String> events = d.outboxPayloads(org, "device.commissioning.changed");
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).contains("\"status\": \"VERIFIED\"");
        assertThat(jobs.runOnce()).isZero();
    }

    @Test
    @DisplayName("[DEV-13.05][AT-DEV-27.3·27.5][BR-DEV-37·38] 10분 무수신 → PROBLEM + 점검 체크리스트, 다른 담당자가 더 나중에 설치했으면 409 COMMISSION_CONFLICT + 서버 값 — TC-DEV-323·324")
    void problemAndConflict() throws Exception {
        long device = data.device(org, source, "dev-a", "ACTIVE", floor3, model);
        mvc.perform(commission(admin, device, UUID.randomUUID().toString(), room, "2026-10-03T00:00:00Z", false))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(9));
        assertThat(jobs.runOnce()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(jobs.runOnce()).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/commission")))
                .andExpect(jsonPath("$.response.status").value("PROBLEM"))
                .andExpect(jsonPath("$.response.checklist.firstData").value(false))
                .andExpect(jsonPath("$.response.checklist.position").value(true))
                .andExpect(jsonPath("$.response.checklist.photo").value(false))
                .andExpect(jsonPath("$.response.checklist.battery").value(false));
        // 오프라인으로 그 전에 찍은 다른 담당자 입력 → 충돌
        mvc.perform(commission(operator, device, UUID.randomUUID().toString(), room, "2026-10-02T23:50:00Z", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("COMMISSION_CONFLICT"))
                .andExpect(jsonPath("$.response.installedBy").value(Long.toString(admin)))
                .andExpect(jsonPath("$.response.installedAt").value("2026-10-03T00:00:00Z"));
        // 나중 시각이면 다시 설치(재설치)
        mvc.perform(commission(operator, device, UUID.randomUUID().toString(), room, "2026-10-03T00:09:00Z", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("INSTALLED"));
        // 검증
        mvc.perform(commission(operator, device, "not-uuid", room, null, false)).andExpect(status().isBadRequest());
        long pendingNoModel = data.device(org, source, "dev-n", "PENDING", null, null);
        mvc.perform(commission(operator, pendingNoModel, UUID.randomUUID().toString(), room, null, false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_MODEL_REQUIRED"));
        long analyst = fx.user(org, "cm.an", "ANALYST");
        mvc.perform(commission(analyst, device, UUID.randomUUID().toString(), room, null, false)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[DEV-13.06][AT-DEV-28.1·28.3][API-DEV-138] 3층 예정 10·완료 6·확인 5·문제 1, 문제 칸 기기 목록과 체크리스트, 권한 범위 — TC-DEV-328·330·331")
    void board() throws Exception {
        long[] devs = new long[10];
        for (int i = 0; i < 10; i++) {
            devs[i] = data.device(org, source, "b-" + i, "ACTIVE", room, model);
        }
        Instant t0 = clock.instant();
        for (int i = 0; i < 6; i++) {
            mvc.perform(commission(admin, devs[i], UUID.randomUUID().toString(), room, t0.toString(), false)).andExpect(status().isOk());
        }
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 5; i++) {
            data.deviceState(org, devs[i], "ONLINE", clock.instant(), "{}");
        }
        clock.advance(Duration.ofMinutes(9));
        jobs.runOnce();
        mvc.perform(as(org, operator, get("/core/installation-board").param("siteId", Long.toString(site))))
                .andExpect(jsonPath("$.response.floors", hasSize(1)))
                .andExpect(jsonPath("$.response.floors[0].spaceId").value(Long.toString(floor3)))
                .andExpect(jsonPath("$.response.floors[0].planned").value(10))
                .andExpect(jsonPath("$.response.floors[0].installed").value(6))
                .andExpect(jsonPath("$.response.floors[0].verified").value(5))
                .andExpect(jsonPath("$.response.floors[0].problem").value(1));
        mvc.perform(as(org, operator, get("/core/installation-board/devices").param("spaceId", Long.toString(floor3)).param("status", "PROBLEM")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(devs[5])))
                .andExpect(jsonPath("$.responses[0].checklist.firstData").value(false));
        mvc.perform(as(org, operator, get("/core/installation-board/devices").param("spaceId", Long.toString(floor3)).param("status", "PLANNED")))
                .andExpect(jsonPath("$.totalCount").value(4));
        mvc.perform(as(org, operator, get("/core/installation-board/devices").param("spaceId", Long.toString(floor3)).param("status", "X")))
                .andExpect(status().isBadRequest());
        long viewer = fx.user(org, "cm.v", "VIEWER");
        data.spaceScope(org, viewer, List.of(data.site(org, "별관")));
        mvc.perform(as(org, viewer, get("/core/installation-board"))).andExpect(jsonPath("$.response.floors", hasSize(0)));
        mvc.perform(as(org, viewer, get("/core/installation-board").param("siteId", Long.toString(site)))).andExpect(status().isNotFound());
    }
}
