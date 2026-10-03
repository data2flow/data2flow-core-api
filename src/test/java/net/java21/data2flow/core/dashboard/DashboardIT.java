package net.java21.data2flow.core.dashboard;

import net.java21.data2flow.core.live.LiveTestData;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.hamcrest.Matchers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-01.02 홈 쾌적도·DSH-01.01 요약(API-DSH-01), DSH-02.01 공간 요약(API-DSH-02), DSH-03.01·03.02 수집 흐름(API-DSH-05) */
class DashboardIT extends IntegrationTestSupport {

    private static final Instant T0 = MutableClock.T0;

    private LiveTestData live;
    private long org;
    private long admin;
    private long site;
    private long building;
    private long lab;
    private long classroom;
    private long source;
    private long labSensor;

    @BeforeEach
    void setUp() {
        live = new LiveTestData(jdbc);
        org = fx.organization("dsh");
        admin = fx.user(org, "dsh.admin", "ADMIN");
        site = data.site(org, "캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        lab = data.space(org, building, "ROOM", "실습실");
        classroom = data.space(org, building, "ROOM", "강의실");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "°C");
        long model = data.model(org, "AM319", List.of("co2", "temperature"));
        source = data.source(org, "campus-lns");
        labSensor = data.device(org, source, "a1", "ACTIVE", lab, model);
        long classSensor = data.device(org, source, "b1", "ACTIVE", classroom, model);
        long classOffline = data.device(org, source, "b2", "ACTIVE", classroom, model);
        data.device(org, source, "p1", "PENDING", null, model);
        data.deviceState(org, labSensor, "ONLINE", T0.minusSeconds(30),
                "{\"co2\":{\"v\":1150,\"t\":\"2026-10-02T23:59:30Z\",\"q\":0},\"temperature\":{\"v\":24.5,\"t\":\"2026-10-02T23:59:30Z\",\"q\":0}}");
        data.deviceState(org, classSensor, "ONLINE", T0.minusSeconds(20),
                "{\"co2\":{\"v\":1400,\"t\":\"2026-10-02T23:59:40Z\",\"q\":0},\"temperature\":{\"v\":23,\"t\":\"2026-10-02T23:59:40Z\",\"q\":0}}");
        data.deviceState(org, classOffline, "OFFLINE", T0.minusSeconds(3600), "{}");
        live.target(org, site, "co2", null, 1000.0);
        // 강의실은 온도만 직접 정했다 → 상위 CO2 목표를 쓰지 않는다(묶음 상속, BR-DEV-04)
        live.target(org, classroom, "temperature", 20.0, 26.0);
        live.runtime(org, source, "ingress-0", "CONNECTED", T0);
        live.stat(org, source, T0.minus(Duration.ofMinutes(1)), 30, 0);
    }

    @Test
    @DisplayName("[DSH-01.02][AT-DSH-01.2] 홈: 실습실 CO2 1,150ppm(목표 ≤1,000) → 경고·원인, 카드 수치 — TC-DSH-006·TC-DSH-078")
    void homeSummary() throws Exception {
        mvc.perform(as(org, admin, get("/core/home/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alarms.critical").value(0))
                .andExpect(jsonPath("$.response.offlineDevices").value(1))
                .andExpect(jsonPath("$.response.pendingDevices").value(1))
                .andExpect(jsonPath("$.response.ingestPerMinute").value(30.0))
                .andExpect(jsonPath("$.response.sources.connected").value(1))
                .andExpect(jsonPath("$.response.sources.total").value(1))
                .andExpect(jsonPath("$.response.comfortTotal").value(2))
                .andExpect(jsonPath("$.response.comfort[0].spaceId").value(Long.toString(lab)))
                .andExpect(jsonPath("$.response.comfort[0].state").value("WARNING"))
                .andExpect(jsonPath("$.response.comfort[0].causes[0].metricKey").value("co2"))
                .andExpect(jsonPath("$.response.comfort[0].causes[0].value").value(1150.0))
                .andExpect(jsonPath("$.response.comfort[0].causes[0].unit").value("ppm"))
                .andExpect(jsonPath("$.response.comfort[0].causes[0].target.max").value(1000.0))
                .andExpect(jsonPath("$.response.comfort[0].updatedAt").value("2026-10-02T23:59:30Z"))
                .andExpect(jsonPath("$.response.comfort[1].spaceName").value("강의실"))
                .andExpect(jsonPath("$.response.comfort[1].state").value("NORMAL"))
                .andExpect(jsonPath("$.response.timeline", hasSize(0)));
    }

    @Test
    @DisplayName("[DSH-01.01][AT-DSH-01.5] 권한에 따라 카드가 빠지고, 공간 B만 권한이면 모든 수치가 B 범위로 계산된다 — BR-DSH-01")
    void homeSummaryScope() throws Exception {
        long viewer = fx.user(org, "dsh.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/home/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.pendingDevices").doesNotExist())
                .andExpect(jsonPath("$.response.sources").doesNotExist())
                .andExpect(jsonPath("$.response.offlineDevices").value(1));
        long scoped = fx.user(org, "dsh.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, get("/core/home/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.offlineDevices").value(0))
                .andExpect(jsonPath("$.response.comfortTotal").value(1))
                .andExpect(jsonPath("$.response.comfort[0].spaceName").value("실습실"))
                .andExpect(jsonPath("$.response.sources.total").value(0))
                .andExpect(jsonPath("$.response.ingestPerMinute").value(0.0));
        long other = fx.organization("other");
        long stranger = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, stranger, get("/core/home/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.comfortTotal").value(0))
                .andExpect(jsonPath("$.response.offlineDevices").value(0));
    }

    @Test
    @DisplayName("[DSH-02.01][AT-DSH-02.1] 공간 요약: 경로·상속 목표·쾌적도·기기 현재값·하위 공간, 시각은 Z 접미 UTC — TC-DSH-078")
    void spaceOverview() throws Exception {
        mvc.perform(as(org, admin, get("/core/spaces/{id}/overview", lab)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.space.name").value("실습실"))
                .andExpect(jsonPath("$.response.space.type").value("ROOM"))
                .andExpect(jsonPath("$.response.space.path[*].name").value(Matchers.contains("캠퍼스", "본관", "실습실")))
                .andExpect(jsonPath("$.response.space.targetEnv[0].metricKey").value("co2"))
                .andExpect(jsonPath("$.response.space.targetEnv[0].max").value(1000.0))
                .andExpect(jsonPath("$.response.space.targetEnv[0].inheritedFromSpaceId").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.comfort.state").value("WARNING"))
                .andExpect(jsonPath("$.response.devices", hasSize(1)))
                .andExpect(jsonPath("$.response.devices[0].id").value(Long.toString(labSensor)))
                .andExpect(jsonPath("$.response.devices[0].connection").value("ONLINE"))
                .andExpect(jsonPath("$.response.devices[0].modelName").value("AM319"))
                .andExpect(jsonPath("$.response.devices[0].lastSeenAt", endsWith("Z")))
                .andExpect(jsonPath("$.response.devices[0].metrics[0].key").value("co2"))
                .andExpect(jsonPath("$.response.devices[0].metrics[0].unit").value("ppm"))
                .andExpect(jsonPath("$.response.devices[0].metrics[0].at").value("2026-10-02T23:59:30Z"))
                .andExpect(jsonPath("$.response.openAlarms", hasSize(0)))
                .andExpect(jsonPath("$.response.hasFloorplan").value(false));
        mvc.perform(as(org, admin, get("/core/spaces/{id}/overview", building)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.comfort.state").value("WARNING"))
                .andExpect(jsonPath("$.response.space.targetEnv[0].inheritedFromSpaceId").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.devices", hasSize(0)))
                .andExpect(jsonPath("$.response.children[*].name").value(Matchers.contains("강의실", "실습실")))
                .andExpect(jsonPath("$.response.children[0].comfortState").value("NORMAL"));
        mvc.perform(as(org, admin, get("/core/spaces/{id}/overview", site)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.space.targetEnv[0].inheritedFromSpaceId").value(nullValue()));
    }

    @Test
    @DisplayName("[DSH-02.01][IAM-04.06] 공간 요약: 범위 밖·다른 조직·없는 공간은 404 SPACE_NOT_FOUND")
    void spaceOverviewScope() throws Exception {
        long scoped = fx.user(org, "dsh.scoped", "VIEWER");
        data.spaceScope(org, scoped, List.of(classroom));
        mvc.perform(as(org, scoped, get("/core/spaces/{id}/overview", lab)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, scoped, get("/core/spaces/{id}/overview", classroom))).andExpect(status().isOk());
        long other = fx.organization("other");
        long stranger = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, stranger, get("/core/spaces/{id}/overview", lab)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, admin, get("/core/spaces/{id}/overview", 999999)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DSH-03.01][AT-DSH-03.1] 수집 흐름: 스크립트 오류 분당 12건 → SCRIPT failPerMin 12와 실패 링크, 소스 상태 — TC-DSH-020")
    void ingestMonitor() throws Exception {
        jdbc.sql("DELETE FROM data2flow_core.source_stat_1m").update();
        for (int i = 1; i <= 5; i++) {
            live.stat(org, source, T0.minus(Duration.ofMinutes(i)), 100, 12);
        }
        live.stat(org, source, T0.minus(Duration.ofMinutes(30)), 50, 0);
        live.dlq(org, "STORE", T0.minusSeconds(10));
        mvc.perform(as(org, admin, get("/core/monitoring/ingest").param("window", "1h")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.window").value("1h"))
                .andExpect(jsonPath("$.response.stages[*].key").value(
                        Matchers.contains("SOURCE", "DECODE", "SCRIPT", "VALIDATE", "STORE", "PUBLISH")))
                .andExpect(jsonPath("$.response.stages[0].inPerMin").value(100.0))
                .andExpect(jsonPath("$.response.stages[2].failPerMin").value(12.0))
                .andExpect(jsonPath("$.response.stages[2].failureLink").value("/ingest/failures?stage=SCRIPT&code=SCRIPT_ERROR"))
                .andExpect(jsonPath("$.response.stages[4].failPerMin").value(0.2))
                .andExpect(jsonPath("$.response.sources[0].id").value(Long.toString(source)))
                .andExpect(jsonPath("$.response.sources[0].state").value("CONNECTED"))
                .andExpect(jsonPath("$.response.sources[0].perMin").value(100.0))
                .andExpect(jsonPath("$.response.sources[0].lastMessageAt").value("2026-10-02T23:59:00Z"))
                .andExpect(jsonPath("$.response.throughput[0].sourceId").value(Long.toString(source)))
                .andExpect(jsonPath("$.response.throughput[0].points", hasSize(60)))
                .andExpect(jsonPath("$.response.throughput[0].points[59][0]").value("2026-10-02T23:59:00Z"))
                .andExpect(jsonPath("$.response.throughput[0].points[59][1]").value(100.0))
                .andExpect(jsonPath("$.response.throughput[0].points[30][1]").value(50.0));
        mvc.perform(as(org, admin, get("/core/monitoring/ingest").param("window", "24h")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.throughput[0].points", hasSize(288)))
                .andExpect(jsonPath("$.response.throughput[0].points[287][1]").value(100.0));
        mvc.perform(as(org, admin, get("/core/monitoring/ingest")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.window").value("1h"));
        mvc.perform(as(org, admin, get("/core/monitoring/ingest").param("window", "7d")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("window"));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"ADMIN, 200", "INTEGRATOR, 200", "OPERATOR, 200", "ANALYST, 403", "VIEWER, 403"})
    @DisplayName("[DSH-03.01][AT-DSH-03.3] 수집 흐름 권한: OPERATOR 이상 200, ANALYST·VIEWER 403 — TC-DSH-022")
    void ingestMonitorPermission(String role, int expected) throws Exception {
        long user = fx.user(org, "perm." + role.toLowerCase(), role);
        mvc.perform(as(org, user, get("/core/monitoring/ingest"))).andExpect(status().is(expected));
    }

    @Test
    @DisplayName("[DSH-03.02][IAM-04.06] 범위가 제한된 사용자는 사이트가 범위 안인 소스만, 실패 보관함 수치는 0")
    void ingestMonitorScope() throws Exception {
        long other = data.source(org, "other-lns");
        live.siteOf(org, source, site);
        live.stat(org, other, T0.minus(Duration.ofMinutes(2)), 77, 0);
        live.dlq(org, "STORE", T0.minusSeconds(10));
        long scoped = fx.user(org, "dsh.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(site));
        mvc.perform(as(org, scoped, get("/core/monitoring/ingest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sources", hasSize(1)))
                .andExpect(jsonPath("$.response.sources[0].id").value(Long.toString(source)))
                .andExpect(jsonPath("$.response.stages[4].failPerMin").value(0.0));
        jdbc.sql("UPDATE data2flow_core.data_sources SET lifecycle = 'PAUSED' WHERE id = :id").param("id", source).update();
        mvc.perform(as(org, admin, get("/core/monitoring/ingest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sources[?(@.id == '" + source + "')].state").value("DISABLED"))
                .andExpect(jsonPath("$.response.sources[?(@.id == '" + other + "')].state").value("DISCONNECTED"));
    }
}
