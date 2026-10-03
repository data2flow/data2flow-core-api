package net.java21.data2flow.core.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-04.01~04.03 권한 매트릭스(IAM-04.05): 조회 DEV_READ(VIEWER+), 쓰기 DEV_ADMIN(INTEGRATOR+) — TC-DEV-125·132·138 */
class MetricPermissionIT extends CatalogItSupport {

    @Test
    @DisplayName("[DEV-04.01][IAM-04.05] ADMIN·INTEGRATOR 생성 201, OPERATOR·ANALYST·VIEWER 쓰기 403·조회 200 — TC-DEV-125·132·138")
    void roleMatrix() throws Exception {
        long org = fx.organization("perm-metric");
        seeder.seed(org);
        jdbc.sql("INSERT INTO data2flow_core.metrics (organization_id, key, display_name, status) VALUES (:o, 'pm1', 'pm1', 'UNVERIFIED')")
                .param("o", org).update();
        long pm1 = metricId(org, "pm1");
        long temperature = metricId(org, "temperature");
        int n = 0;
        for (String role : List.of("ADMIN", "INTEGRATOR")) {
            long user = fx.user(org, "writer.u" + n, role);
            mvc.perform(as(org, user, json(post("/core/metrics"), "{\"key\":\"k" + n++ + "\",\"displayName\":\"x\"}")))
                    .andExpect(status().isCreated());
        }
        for (String role : List.of("OPERATOR", "ANALYST", "VIEWER")) {
            long user = fx.user(org, "reader.u" + n++, role);
            mvc.perform(as(org, user, get("/core/metrics"))).andExpect(status().isOk());
            mvc.perform(as(org, user, get("/core/metric-aliases"))).andExpect(status().isOk());
            mvc.perform(as(org, user, json(post("/core/metrics"), "{\"key\":\"zz\",\"displayName\":\"x\"}")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
            mvc.perform(as(org, user, json(patch("/core/metrics/" + temperature), "{\"unit\":\"K\",\"baseVersion\":0}")))
                    .andExpect(status().isForbidden());
            mvc.perform(as(org, user, post("/core/metrics/" + pm1 + "/verify"))).andExpect(status().isForbidden());
            mvc.perform(as(org, user, json(post("/core/metrics/" + pm1 + "/alias-to"), "{\"targetKey\":\"temperature\"}")))
                    .andExpect(status().isForbidden());
            mvc.perform(as(org, user, post("/core/metrics/" + pm1 + "/ignore"))).andExpect(status().isForbidden());
            mvc.perform(as(org, user, json(post("/core/metric-aliases"), "{\"alias\":\"t\",\"metricKey\":\"temperature\"}")))
                    .andExpect(status().isForbidden());
            mvc.perform(as(org, user, delete("/core/metric-aliases/1"))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("[DEV-04.01][BR-IAM-01] 다른 조직의 측정 항목·별칭·재매핑 작업 ID는 404 — TC-DEV-125")
    void otherOrganization() throws Exception {
        long org = fx.organization("perm-ma");
        long other = fx.organization("perm-mb");
        seeder.seed(org);
        seeder.seed(other);
        long admin = fx.user(org, "a.admin", "ADMIN");
        long otherAdmin = fx.user(other, "b.admin", "ADMIN");
        long otherMetric = metricId(other, "co2");
        mvc.perform(as(org, admin, get("/core/metrics/" + otherMetric)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + otherMetric), "{\"unit\":\"K\",\"baseVersion\":0}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, admin, post("/core/metrics/" + otherMetric + "/ignore"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(post("/core/metric-aliases"), "{\"alias\":\"carbon\",\"metricKey\":\"co2\"}")))
                .andExpect(status().isCreated());
        long alias = jdbc.sql("SELECT id FROM data2flow_core.metric_aliases WHERE organization_id = :o").param("o", other)
                .query(Long.class).single();
        mvc.perform(as(org, admin, delete("/core/metric-aliases/" + alias))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/metric-aliases"))).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, admin, get("/core/metrics?key=carbon"))).andExpect(jsonPath("$.totalCount").value(0));
    }
}
