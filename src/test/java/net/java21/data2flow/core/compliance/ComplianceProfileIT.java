package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-01.01~01.04 프로필·적용 API — TC-CMP-005(버전·충돌), TC-CMP-006(권한·조직), TC-CMP-010(시스템 프로필 시드), TC-CMP-018(적용·EVT-CMP-01),
 * TC-CMP-025(측정 가능성), API-CMP-09·26 유효 기준.
 */
class ComplianceProfileIT extends IntegrationTestSupport {

    private static final String PROFILE = """
            {"name":"우리 학교 기준","version":{"sourceTitle":"내부 지침 2026","sourceUrl":"https://example.org/guide","effectiveDate":"2026-09-01",
             "metrics":[{"metricKey":"co2","evalWindow":"HOUR_1","upper":1000,"unit":"ppm","requirement":"LIMIT",
                         "bands":[{"name":"허용","upper":1000,"isBase":true},{"name":"우수","upper":700}]},
                        {"metricKey":"temperature","evalWindow":"HOUR_1","lower":20,"upper":26,"unit":"℃"}]}}""";

    private long org;
    private long other;
    private long admin;
    private long operator;
    private long viewer;
    private long site;
    private long roomA;
    private long roomB;

    @BeforeEach
    void setUp() {
        org = fx.organization("cmp");
        other = fx.organization("cmp-b");
        admin = fx.user(org, "cmp.admin", "ADMIN");
        operator = fx.user(org, "cmp.operator", "OPERATOR");
        viewer = fx.user(org, "cmp.viewer", "VIEWER");
        site = data.site(org, "본관");
        long floor = data.space(org, site, "FLOOR", "2층");
        roomA = data.space(org, floor, "ROOM", "교실 A");
        roomB = data.space(org, floor, "ROOM", "교실 B");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        data.metric(org, "pm2_5", "μg/m³");
        long model = data.model(org, "AM103", List.of("co2", "temperature"));
        long source = data.source(org, "cmp-src");
        data.device(org, source, "am103-a", "ACTIVE", roomA, model);
        data.device(org, source, "am103-v", "ACTIVE", roomB, model, true);
    }

    private long systemProfile(String code) {
        return jdbc.sql("SELECT id FROM data2flow_core.compliance_profiles WHERE code = :code").param("code", code).query(Long.class).single();
    }

    private String idOf(String content, String field) {
        return content.replaceAll("(?s).*\"" + field + "\":\"(\\d+)\".*", "$1");
    }

    private long events(String change) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'EVENT'
                           AND routing_key = 'compliance.application.changed' AND payload -> 'payload' ->> 'change' = :change""")
                .param("org", org).param("change", change).query(Long.class).single();
    }

    @Test
    @DisplayName("[TC-CMP-010] 시스템 프로필 5종은 출처·기준일이 있고 RESET은 <35·<1000(경계 미포함), 국내법은 ≤1000(경계 포함), ASHRAE는 −0.5~0.5")
    void systemProfiles() throws Exception {
        mvc.perform(as(org, viewer, get("/core/compliance/profiles?kind=SYSTEM&size=20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.responses[*].code").value(containsInAnyOrder("KR_IAQ_PUBLIC_FACILITY", "RESET_AIR_CI", "WELL_V2_A08",
                        "ASHRAE_55", "ORG_TEMPLATE")));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.compliance_profile_versions
                         WHERE organization_id = 0 AND (source_title IS NULL OR source_title = '' OR effective_date IS NULL)""")
                .query(Long.class).single()).isZero();
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + systemProfile("RESET_AIR_CI"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kind").value("SYSTEM"))
                .andExpect(jsonPath("$.response.currentVersion.streak.days").value(90))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='pm2_5')].upper").value(hasItem(35)))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='pm2_5')].inclusive").value(hasItem(false)))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='pm2_5')].bands[*].upper").value(containsInAnyOrder(35, 25, 12)))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='co2')].bands[*].upper").value(containsInAnyOrder(1000, 600)));
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + systemProfile("KR_IAQ_PUBLIC_FACILITY"))))
                .andExpect(jsonPath("$.response.currentVersion.facilityTypeRequired").value(true))
                .andExpect(jsonPath("$.response.currentVersion.facilityTypes").value(containsInAnyOrder("GENERAL", "SENSITIVE", "INDOOR_PARKING")))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='co2' && @.facilityType=='GENERAL')].inclusive").value(hasItem(true)))
                .andExpect(jsonPath("$.response.currentVersion.metrics[?(@.metricKey=='pm10' && @.facilityType=='GENERAL')].upper").value(hasItem(100)));
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + systemProfile("ASHRAE_55"))))
                .andExpect(jsonPath("$.response.currentVersion.metrics[0].lower").value(-0.5))
                .andExpect(jsonPath("$.response.currentVersion.metrics[0].upper").value(0.5));
    }

    @Test
    @DisplayName("[TC-CMP-005·006] 생성 201(ADMIN), OPERATOR 403, 다른 조직 404. 새 버전 v2, 오래된 baseVersion PUT은 409 CMP_PROFILE_VERSION_CONFLICT")
    void createAndVersion() throws Exception {
        mvc.perform(as(org, operator, json(post("/core/compliance/profiles"), PROFILE))).andExpect(status().isForbidden());
        String content = mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), PROFILE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.response.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.response.currentVersion.metrics[0].bands", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(content, "profileId");
        mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), PROFILE)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_NAME_DUPLICATE"));
        long otherAdmin = fx.user(other, "b.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/compliance/profiles/" + id)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_NOT_FOUND"));

        String v2 = """
                {"baseVersion":0,"sourceTitle":"내부 지침 2026","effectiveDate":"2026-10-01","changeNote":"CO2 기준 강화",
                 "metrics":[{"metricKey":"co2","evalWindow":"HOUR_1","upper":900,"unit":"ppm"}]}""";
        mvc.perform(as(org, admin, json(post("/core/compliance/profiles/" + id + "/versions"), v2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.versionNo").value(2));
        mvc.perform(as(org, admin, json(put("/core/compliance/profiles/" + id + "/versions/2"), v2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(put("/core/compliance/profiles/" + id + "/versions/2"), v2.replace("\"baseVersion\":0", "\"baseVersion\":1")
                        .replace("900", "850"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.metrics[0].upper").value(850));
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + id + "/versions")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response[*].versionNo").value(containsInAnyOrder(2, 1)));
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + id + "/versions/1")))
                .andExpect(jsonPath("$.response.metrics[0].upper").value(1000));
        mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), PROFILE.replace("\"ppm\"", "\"%\"").replace("우리 학교 기준", "단위 오류"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_THRESHOLD_INVALID"));
        assertThat(auditCount(org, "CMP_PROFILE_VERSION_CREATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-CMP-004·014] 시스템 프로필은 복제만 된다: 버전 추가 409 READ_ONLY, 복제는 조직 v1")
    void systemCloneOnly() throws Exception {
        long reset = systemProfile("RESET_AIR_CI");
        mvc.perform(as(org, admin, post("/core/compliance/profiles/" + reset + "/archive")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_READ_ONLY"));
        mvc.perform(as(org, admin, json(post("/core/compliance/profiles/" + reset + "/clone"), "{\"name\":\"RESET 복제본\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.response.currentVersionNo").value(1))
                .andExpect(jsonPath("$.response.currentVersion.streak.days").value(90));
        assertThat(auditCount(org, "CMP_PROFILE_CLONED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-CMP-018·025] 사이트 적용: 버전 잠금·EVT-CMP-01, 측정 가능성(가상 기기 제외), 유효 기준(목표 환경보다 우선), 해제")
    void applyAndCriteria() throws Exception {
        String created = mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), PROFILE)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String profileId = idOf(created, "profileId");
        jdbc.sql("INSERT INTO data2flow_core.space_targets (organization_id, space_id, metric_key, min_value, max_value) VALUES (:org, :s, 'co2', NULL, 800)")
                .param("org", org).param("s", site).update();
        String body = "{\"profileId\":\"%s\",\"targetType\":\"SITE\",\"targetSpaceId\":\"%d\"}".formatted(profileId, site);
        mvc.perform(as(org, operator, json(post("/core/compliance/applications"), body))).andExpect(status().isForbidden());
        String applied = mvc.perform(as(org, admin, json(post("/core/compliance/applications"), body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.versionNo").value(1))
                .andExpect(jsonPath("$.response.includeDescendants").value(true))
                // co2·temperature 모두 실습실 A에만 실제 기기가 있다(B는 가상 기기) → PARTIAL 2
                .andExpect(jsonPath("$.response.measurability.partial").value(2))
                .andReturn().getResponse().getContentAsString();
        String applicationId = idOf(applied, "applicationId");
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"), body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_APPLICATION_DUPLICATE"));
        assertThat(events("CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "CMP_PROFILE_APPLIED")).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/compliance/profiles/" + profileId + "/versions/1")))
                .andExpect(jsonPath("$.response.locked").value(true));

        mvc.perform(as(org, viewer, get("/core/compliance/applications/" + applicationId + "/measurability")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$.response[0].spacesWithout[*].name").value(containsInAnyOrder("교실 B")));

        mvc.perform(as(org, viewer, get("/core/compliance/spaces/" + roomA + "/effective-criteria")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[?(@.metricKey=='co2')].upper").value(hasItem(1000)))
                .andExpect(jsonPath("$.response[?(@.metricKey=='co2')].overridesTarget").value(hasItem(true)))
                .andExpect(jsonPath("$.response[?(@.metricKey=='co2')].strictestSource.band").value(hasItem("허용")))
                .andExpect(jsonPath("$.response[?(@.metricKey=='temperature')].lower").value(hasItem(20)));
        mvc.perform(get("/internal/core/compliance/spaces/" + roomA + "/effective-criteria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.basis").value("PROFILE"))
                .andExpect(jsonPath("$.response.criteria[?(@.metricKey=='co2')].max").value(hasItem(1000)))
                .andExpect(jsonPath("$.response.criteria[?(@.metricKey=='co2')].sourceProfileId").value(hasItem(profileId)));

        // 잠긴 v1은 고칠 수 없다(BR-CMP-02)
        mvc.perform(as(org, admin, json(put("/core/compliance/profiles/" + profileId + "/versions/1"), """
                        {"baseVersion":0,"sourceTitle":"x","effectiveDate":"2026-09-01","metrics":[{"metricKey":"co2","evalWindow":"HOUR_1","upper":900,"unit":"ppm"}]}""")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_READ_ONLY"));

        mvc.perform(as(org, admin, delete("/core/compliance/applications/" + applicationId))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, delete("/core/compliance/applications/" + applicationId + "?deactivateRules=false")))
                .andExpect(status().isNoContent());
        assertThat(events("DELETED")).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/compliance/applications"))).andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("[CMP-01.03] 국내법은 시설 유형이 없으면 400 CMP_FACILITY_TYPE_REQUIRED, 보관한 프로필은 409 CMP_PROFILE_ARCHIVED")
    void facilityAndArchived() throws Exception {
        long kr = systemProfile("KR_IAQ_PUBLIC_FACILITY");
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"),
                        "{\"profileId\":\"%d\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"%d\"}".formatted(kr, roomA))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_FACILITY_TYPE_REQUIRED"));
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"),
                        "{\"profileId\":\"%d\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"%d\",\"facilityType\":\"SENSITIVE\"}".formatted(kr, roomA))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.facilityType").value("SENSITIVE"));
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"),
                        "{\"profileId\":\"%d\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"999999\"}".formatted(kr))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));

        String created = mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), PROFILE)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String profileId = idOf(created, "profileId");
        mvc.perform(as(org, admin, post("/core/compliance/profiles/" + profileId + "/archive")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.archived").value(true));
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"),
                        "{\"profileId\":\"%s\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"%d\"}".formatted(profileId, roomA))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_ARCHIVED"));
    }
}
