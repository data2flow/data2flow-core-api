package net.java21.data2flow.core.asset;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.workorder.FieldOpsData;
import net.java21.data2flow.core.workorder.service.WorkOrderJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-08.01 자산 정보(API-DEV-96)와 보증 만료 30일 전 알림(AT-DEV-16.5) */
class AssetIT extends IntegrationTestSupport {

    @Autowired
    WorkOrderJobs jobs;

    private long org;
    private long admin;
    private long device;
    private long room;

    @BeforeEach
    void setUp() {
        org = fx.organization("as");
        admin = fx.user(org, "as.admin", "ADMIN");
        room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        device = data.device(org, data.source(org, "cs"), "dev-1", "ACTIVE", room, null);
    }

    private long warrantyAlarms() {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key LIKE 'system:WARRANTY_EXPIRING:%'")
                .param("org", org).query(Long.class).single();
    }

    @Test
    @DisplayName("[DEV-08.01][API-DEV-96] 자산 정보 저장·조회, 사진 추가·내려받기·삭제, 날짜 순서 검사, 감사 — TC-DEV-202·205")
    void assetInfo() throws Exception {
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/asset-info")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.serialNo").doesNotExist());
        mvc.perform(as(org, admin, json(put("/core/devices/" + device + "/asset-info"), """
                        {"serialNo":"SN-1","purchasedOn":"2026-01-02","installedOn":"2026-01-10","warrantyUntil":"2027-01-01",
                         "supplier":"밀레사이트","installer":"김설치"}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.serialNo").value("SN-1"))
                .andExpect(jsonPath("$.response.warrantyUntil").value("2027-01-01"));
        mvc.perform(as(org, admin, json(put("/core/devices/" + device + "/asset-info"), "{\"purchasedOn\":\"2026-02-01\",\"installedOn\":\"2026-01-01\"}")))
                .andExpect(status().isBadRequest());
        String res = mvc.perform(FieldOpsData.upload("/core/devices/" + device + "/asset-info/photos", org, admin, "a.png", "image/png",
                        FieldOpsData.PNG, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.photoUrls", hasSize(1)))
                .andExpect(jsonPath("$.response.serialNo").value("SN-1")).andReturn().getResponse().getContentAsString();
        mvc.perform(FieldOpsData.upload("/core/devices/" + device + "/asset-info/photos", org, admin, "a.pdf", "application/pdf",
                FieldOpsData.PDF, null)).andExpect(status().isBadRequest());
        String url = JsonPath.read(res, "$.response.photoUrls[0]");
        String path = url.replace("/api/v1", "");
        mvc.perform(as(org, admin, get(path))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(as(org, admin, delete(path))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get(path))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "ASSET_INFO_UPDATED")).isEqualTo(1);
        long operator = fx.user(org, "as.op", "OPERATOR");
        mvc.perform(as(org, operator, json(put("/core/devices/" + device + "/asset-info"), "{}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/devices/" + device + "/asset-info"))).andExpect(status().isOk());
        long other = fx.organization("as2");
        mvc.perform(as(other, fx.user(other, "as2.admin", "ADMIN"), get("/core/devices/" + device + "/asset-info")))
                .andExpect(status().isNotFound());
        data.spaceScope(org, operator, List.of(data.site(org, "별관")));
        mvc.perform(as(org, operator, get("/core/devices/" + device + "/asset-info"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-08.01][AT-DEV-16.5] 보증 만료 30일 전 기기는 매일 점검에서 알림(시스템 알람) 1회, 다시 돌려도 1회, 만료일을 바꾸면 다시 — TC-DEV-202")
    void warrantyWarning() throws Exception {
        mvc.perform(as(org, admin, json(put("/core/devices/" + device + "/asset-info"), "{\"warrantyUntil\":\"2026-11-30\"}")))
                .andExpect(status().isOk());
        jobs.runOnce();
        assertThat(warrantyAlarms()).isZero(); // 10-03 기준 58일 남음
        clock.advance(Duration.ofDays(30));    // 11-02: 28일 남음
        jobs.runOnce();
        jobs.runOnce();
        assertThat(warrantyAlarms()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT severity FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key LIKE 'system:WARRANTY%'")
                .param("org", org).query(String.class).single()).isEqualTo("INFO");
        // 만료일을 바꾸면(PUT과 같은 효과) 다시 알린다. 시계를 다음 달로 옮긴 뒤라 감사 기록을 남기지 않도록 DB로 바꾼다
        // (감사 로그 DEFAULT 파티션에 다음 달 행이 생기면 AuditLogIT의 월 파티션 생성이 실패한다)
        jdbc.sql("UPDATE data2flow_core.asset_info SET warranty_until = '2026-11-20', warranty_notified_for = NULL WHERE device_id = :d")
                .param("d", device).update();
        jobs.runOnce();
        assertThat(jdbc.sql("SELECT occurrence_count FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key LIKE 'system:WARRANTY%'")
                .param("org", org).query(Integer.class).single()).isEqualTo(2);
    }
}
