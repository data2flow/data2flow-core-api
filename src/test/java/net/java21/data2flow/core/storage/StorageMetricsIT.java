package net.java21.data2flow.core.storage;

import net.java21.data2flow.core.storage.service.StorageMetricsService;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OPS-01.03 저장 지표(API-OPS-03)와 디스크 여유 알람(BR-OPS-03) */
class StorageMetricsIT extends IntegrationTestSupport {

    @Autowired
    StorageMetricsService service;

    private long org;
    private long admin;

    @BeforeEach
    void setUp() {
        org = fx.organization("stor");
        admin = fx.user(org, "stor.admin", "ADMIN");
    }

    private String alarm(String column) {
        return jdbc.sql("SELECT " + column + " FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = 'system:DISK_FREE'"
                        + " ORDER BY id DESC LIMIT 1")
                .param("org", org).query(String.class).optional().orElse(null);
    }

    @Test
    @DisplayName("[OPS-01.03] DB 용량·data2flow_* 표 크기(큰 순)·일별 증가 추세(하루 1회 기록), 디스크 용량 설정이 없으면 null, OPERATOR 403 — TC-OPS-010 계열")
    void metrics() throws Exception {
        service.snapshot();
        clock.advance(Duration.ofDays(1));
        service.snapshot();
        mvc.perform(as(org, admin, get("/core/ops/metrics/storage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.dbSizeBytes").value(greaterThan(0)))
                .andExpect(jsonPath("$.response.tables[0].schema").value(startsWith("data2flow_")))
                .andExpect(jsonPath("$.response.tables[?(@.table == 'dashboards')]", hasSize(1)))
                .andExpect(jsonPath("$.response.tables[?(@.table == 'telemetry' && @.schema == 'data2flow_pipeline')]", hasSize(1)))
                .andExpect(jsonPath("$.response.dailyGrowthBytes", hasSize(2)))
                .andExpect(jsonPath("$.response.dailyGrowthBytes[1].growthBytes").exists())
                .andExpect(jsonPath("$.response.diskFreePercent").doesNotExist());
        long operator = fx.user(org, "stor.op", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/ops/metrics/storage"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[OPS-01.03][AT-OPS-02.3][BR-OPS-03] 여유 19% → MAJOR, 9% → 같은 알람을 CRITICAL로 승격, 기준 이상으로 돌아오면 자동 해제 — TC-OPS-013")
    void diskAlarm() {
        service.evaluate(org, 25.0);
        assertThat(alarm("status")).isNull();
        service.evaluate(org, 19.0);
        assertThat(alarm("severity")).isEqualTo("MAJOR");
        String id = alarm("id");
        service.evaluate(org, 9.0);
        assertThat(alarm("severity")).isEqualTo("CRITICAL");
        assertThat(alarm("id")).isEqualTo(id);
        service.evaluate(org, 30.0);
        assertThat(alarm("status")).isEqualTo("CLEARED");
        jdbc.sql("""
                        INSERT INTO data2flow_core.ops_thresholds (organization_id, key, value, enabled, severity, version, updated_by)
                        VALUES (:org, 'DISK_FREE_PERCENT', 20, false, 'MAJOR', 1, 0)""").param("org", org).update();
        service.evaluate(org, 5.0);
        assertThat(alarm("status")).isEqualTo("CLEARED");
    }
}
