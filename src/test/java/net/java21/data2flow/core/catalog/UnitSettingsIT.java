package net.java21.data2flow.core.catalog;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-04.04 조직 기본 단위(API-DEV-57) */
class UnitSettingsIT extends IntegrationTestSupport {

    @Test
    @DisplayName("[DEV-04.04][API-DEV-57] 조직 단위를 ℉로(baseVersion), 내 화면 설정 effective 단위가 F, 형식·버전 오류, ADMIN만 — TC-DEV-143·144")
    void units() throws Exception {
        long org = fx.organization("un");
        long admin = fx.user(org, "un.admin", "ADMIN");
        int version = jdbc.sql("SELECT version FROM data2flow_core.org_settings WHERE organization_id = :o").param("o", org).query(Integer.class).single();
        mvc.perform(as(org, admin, get("/core/settings/units"))).andExpect(jsonPath("$.response.temperatureUnit").value("C"));
        mvc.perform(as(org, admin, json(put("/core/settings/units"), "{\"temperatureUnit\":\"F\",\"baseVersion\":" + version + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.temperatureUnit").value("F"))
                .andExpect(jsonPath("$.response.version").value(version + 1));
        mvc.perform(as(org, admin, json(put("/core/settings/units"), "{\"temperatureUnit\":\"F\",\"baseVersion\":" + version + "}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(put("/core/settings/units"), "{\"temperatureUnit\":\"K\",\"baseVersion\":" + (version + 1) + "}")))
                .andExpect(status().isBadRequest());
        long operator = fx.user(org, "un.op", "OPERATOR");
        mvc.perform(as(org, operator, json(put("/core/settings/units"), "{\"temperatureUnit\":\"C\",\"baseVersion\":" + (version + 1) + "}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/settings/units"))).andExpect(jsonPath("$.response.temperatureUnit").value("F"));
        mvc.perform(as(org, operator, get("/core/accounts/me/preferences"))).andExpect(jsonPath("$.response.effectiveTemperatureUnit").value("F"));
        assertThat(auditCount(org, "UNIT_SETTINGS_UPDATED")).isEqualTo(1);
    }
}
