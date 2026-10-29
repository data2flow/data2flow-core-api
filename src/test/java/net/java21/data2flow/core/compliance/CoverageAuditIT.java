package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-03 커버리지 점검 API-CMP-25 — TC-CMP-073(설치 밀도·권한·다른 조직 404), AT-CMP-11.2(공개 표시), AT-CMP-11.4(교정 초과),
 * AT-CMP-06.3(품질). 시계 T0는 2026-10-03T00:00Z(서울 09:00)다.
 */
class CoverageAuditIT extends IntegrationTestSupport {

    private long org;
    private long admin;
    private long viewer;
    private long site;
    private long office;
    private long lab;
    private long sensorA;
    private long sensorB;

    @BeforeEach
    void setUp() {
        org = fx.organization("cmpc");
        admin = fx.user(org, "cmpc.admin", "ADMIN");
        viewer = fx.user(org, "cmpc.viewer", "VIEWER");
        site = data.site(org, "광주캠퍼스");
        long building = data.space(org, site, "BUILDING", "본관");
        office = data.space(org, building, "ROOM", "개방 사무실");
        lab = data.space(org, building, "ROOM", "실습실");
        long corridor = data.space(org, building, "ROOM", "복도");
        area(office, "OFFICE", 1200);
        area(lab, "LAB", null);
        area(corridor, "CORRIDOR", 300);
        for (String key : List.of("co2", "pm2_5", "tvoc", "temperature")) {
            data.metric(org, key, "x");
        }
        long iaq = data.model(org, "AM319", List.of("co2", "pm2_5", "tvoc"));
        long thermo = data.model(org, "EM300-TH", List.of("temperature"));
        long source = data.source(org, "cmpc-src");
        sensorA = data.device(org, source, "am319-a", "ACTIVE", office, iaq);
        sensorB = data.device(org, source, "am319-b", "ACTIVE", office, iaq);
        data.device(org, source, "em300-a", "ACTIVE", office, thermo);
        data.device(org, source, "am319-v", "ACTIVE", office, iaq, true);
        data.device(org, source, "am319-off", "INACTIVE", office, iaq);
        data.device(org, source, "am319-lab", "ACTIVE", lab, iaq);
        jdbc.sql("UPDATE data2flow_core.device_models SET default_interval_sec = 60 WHERE id = :id").param("id", iaq).update();
        jdbc.sql("UPDATE data2flow_core.devices SET created_at = :at WHERE organization_id = :org")
                .param("at", Pg.ts(MutableClock.T0.minus(Duration.ofDays(60)))).param("org", org).update();
    }

    private void area(long space, String usage, Integer m2) {
        jdbc.sql("UPDATE data2flow_core.spaces SET usage = :usage, area_m2 = :area WHERE id = :id").param("usage", usage).param("area", m2)
                .param("id", space).update();
    }

    private String profile(String code) {
        return jdbc.sql("SELECT id FROM data2flow_core.compliance_profiles WHERE organization_id = 0 AND code = :code").param("code", code)
                .query(Long.class).single().toString();
    }

    /** 서울 10/2 하루(15:00Z~) 1분마다 count개 정상 */
    private void day(long device, int good) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1m (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max,
                               sum, first, last, twa, is_virtual)
                        SELECT :device, 'co2', CAST(:from AS timestamptz) + make_interval(mins => g), :org, CASE WHEN g < :good THEN 1 ELSE 0 END, 1,
                               600, 600, 600, 600, 600, 600, 600, false
                          FROM generate_series(0, 1439) g""")
                .param("device", device).param("from", Pg.ts(Instant.parse("2026-10-01T15:00:00Z"))).param("org", org).param("good", good).update();
    }

    private void calibrated(long device, Instant at) {
        long order = jdbc.sql("""
                        INSERT INTO data2flow_core.work_orders (organization_id, title, type, status, completed_at, created_by, updated_by)
                        VALUES (:org, '교정', 'CALIBRATION', 'DONE', :at, :by, :by) RETURNING id""")
                .param("org", org).param("at", Pg.ts(at)).param("by", admin).query(Long.class).single();
        jdbc.sql("INSERT INTO data2flow_core.work_order_targets (organization_id, work_order_id, device_id) VALUES (:org, :order, :device)")
                .param("org", org).param("order", order).param("device", device).update();
    }

    @Test
    @DisplayName("[TC-CMP-073] RESET: 개방 사무실 1,200㎡ 필요 3·실제 2(가상·INACTIVE·온습도 전용 제외)·부족 1, 면적 없는 실습실은 판단 불가")
    void densityAndAccess() throws Exception {
        String path = "/core/compliance/coverage?siteId=" + site + "&profileId=" + profile("RESET_AIR_CI");
        mvc.perform(as(org, viewer, get(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.profile.name").value("RESET Air(Commercial Interiors)"))
                .andExpect(jsonPath("$.response.density", hasSize(2)))
                .andExpect(jsonPath("$.response.density[0].space.name").value("개방 사무실"))
                .andExpect(jsonPath("$.response.density[0].areaM2").value(1200))
                .andExpect(jsonPath("$.response.density[0].required").value(3))
                .andExpect(jsonPath("$.response.density[0].actual").value(2))
                .andExpect(jsonPath("$.response.density[0].shortage").value(1))
                .andExpect(jsonPath("$.response.density[1].space.name").value("실습실"))
                .andExpect(jsonPath("$.response.density[1].undeterminedReason").value("NO_AREA"))
                .andExpect(jsonPath("$.response.summary.density").value("RED"))
                .andExpect(jsonPath("$.response.publicDisplay").doesNotExist())
                .andExpect(jsonPath("$.response.summary.publicDisplay").value("NA"));

        long other = fx.organization("cmpc2");
        long otherAdmin = fx.user(other, "cmpc2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get(path))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/compliance/coverage?siteId=" + site + "&profileId=999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_NOT_FOUND"));
        data.spaceScope(org, viewer, List.of(lab));
        mvc.perform(as(org, viewer, get(path))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.density", hasSize(1)))
                .andExpect(jsonPath("$.response.density[0].space.name").value("실습실"));
    }

    @Test
    @DisplayName("[AT-CMP-11.2·11.4·06.3] WELL A08 공개 표시 필요 수와 추천, 교정 400일 전은 초과, 결측 15%면 품질 미달")
    void displayCalibrationQuality() throws Exception {
        area(lab, "LAB", 300);
        calibrated(sensorA, MutableClock.T0.minus(Duration.ofDays(400)));
        calibrated(sensorB, MutableClock.T0.minus(Duration.ofDays(100)));
        day(sensorA, 1224);
        mvc.perform(as(org, viewer, get("/core/compliance/coverage?siteId=" + site + "&profileId=" + profile("WELL_V2_A08"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.density", hasSize(0)))
                .andExpect(jsonPath("$.response.summary.density").value("NA"))
                .andExpect(jsonPath("$.response.publicDisplay.scopeAreaM2").value(1500))
                .andExpect(jsonPath("$.response.publicDisplay.required").value(3))
                .andExpect(jsonPath("$.response.publicDisplay.shortage").value(3))
                .andExpect(jsonPath("$.response.publicDisplay.actualAvailable").value(false))
                .andExpect(jsonPath("$.response.publicDisplay.recommendations[0].name").value("개방 사무실"))
                .andExpect(jsonPath("$.response.publicDisplay.recommendations", hasSize(2)))
                .andExpect(jsonPath("$.response.calibration[0].device.id").value(Long.toString(sensorA)))
                .andExpect(jsonPath("$.response.calibration[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.response.calibration[0].intervalDays").value(365))
                .andExpect(jsonPath("$.response.calibration[0].elapsedDays").value(400))
                .andExpect(jsonPath("$.response.calibration[0].space.name").value("개방 사무실"))
                .andExpect(jsonPath("$.response.summary.calibration").value("RED"))
                .andExpect(jsonPath("$.response.period.from").value("2026-09-03"))
                .andExpect(jsonPath("$.response.period.to").value("2026-10-02"));

        // 품질: 기간을 하루로 보기 위해 기기 등록을 하루 전으로(1,440개 기대, 정상 1,224개 → 15%)
        jdbc.sql("UPDATE data2flow_core.devices SET created_at = :at WHERE id = :id")
                .param("at", Pg.ts(Instant.parse("2026-10-01T15:00:00Z"))).param("id", sensorA).update();
        mvc.perform(as(org, admin, get("/core/compliance/coverage?siteId=" + office)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.profile").doesNotExist())
                .andExpect(jsonPath("$.response.quality", hasSize(1)))
                .andExpect(jsonPath("$.response.quality[0].devices").value(3))
                .andExpect(jsonPath("$.response.quality[0].belowStandard").value(true))
                .andExpect(jsonPath("$.response.summary.quality").value("RED"));
    }
}
