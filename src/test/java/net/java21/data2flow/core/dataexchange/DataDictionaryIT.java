package net.java21.data2flow.core.dataexchange;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 데이터 사전(TSD-07.04, API-TSD-59, BR-TSD-27) — TC-TSD-160·162·163 */
class DataDictionaryIT extends IntegrationTestSupport {

    long org;
    long viewer;

    @BeforeEach
    void setUp() {
        org = fx.organization("dict");
        viewer = fx.user(org, "dict.viewer", "VIEWER");
        long site = data.site(org, "본관");
        data.space(org, site, "ROOM", "실습실");
        for (int i = 0; i < 20; i++) {
            data.metric(org, "m" + i, i % 2 == 0 ? "℃" : "ppm");
        }
    }

    @Test
    @DisplayName("[TSD-07.04][AT-TSD-19.1][AT-TSD-19.2] JSON: 측정 항목 20개(key·이름·단위·집계), 품질 코드 표, 공간 트리, version. 항목 추가 → 판 +1, 같은 구조면 그대로 — TC-TSD-160·162")
    void jsonAndVersion() throws Exception {
        mvc.perform(as(org, viewer, get("/core/data-dictionary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.metrics", hasSize(20)))
                .andExpect(jsonPath("$.response.metrics[0].key").value("m0"))
                .andExpect(jsonPath("$.response.metrics[0].unit").value("℃"))
                .andExpect(jsonPath("$.response.metrics[0].aggDefault").value("avg"))
                .andExpect(jsonPath("$.response.qualityCodes", hasSize(6)))
                .andExpect(jsonPath("$.response.qualityCodes[0].meaning").value("정상"))
                .andExpect(jsonPath("$.response.spaces", hasSize(2)))
                .andExpect(jsonPath("$.response.tables[0].columns[0].name").value("time"));
        mvc.perform(as(org, viewer, get("/core/data-dictionary"))).andExpect(jsonPath("$.response.version").value(1));
        data.metric(org, "noise", "dB");
        mvc.perform(as(org, viewer, get("/core/data-dictionary"))).andExpect(jsonPath("$.response.version").value(2))
                .andExpect(jsonPath("$.response.metrics", hasSize(21)));
        mvc.perform(as(org, viewer, get("/core/data-dictionary").param("version", "1")))
                .andExpect(jsonPath("$.response.metrics", hasSize(20)));
        mvc.perform(as(org, viewer, get("/core/data-dictionary").param("version", "9"))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/data-dictionary").header(HttpHeaders.ACCEPT_LANGUAGE, "en")))
                .andExpect(jsonPath("$.response.qualityCodes[0].meaning").value("Normal"));
    }

    @Test
    @DisplayName("[TSD-07.04] HTML(사람용) 문서, 잘못된 format 400, 다른 조직은 자기 사전만 — TC-TSD-161·163")
    void htmlAndValidation() throws Exception {
        String html = mvc.perform(as(org, viewer, get("/core/data-dictionary").param("format", "html"))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data2flow data dictionary v1").contains("<td>m19</td>").contains("실습실");
        mvc.perform(as(org, viewer, get("/core/data-dictionary").param("format", "xml"))).andExpect(status().isBadRequest());
        long other = fx.organization("dict2");
        long otherViewer = fx.user(other, "dict2.viewer", "VIEWER");
        mvc.perform(as(other, otherViewer, get("/core/data-dictionary"))).andExpect(jsonPath("$.response.metrics", hasSize(0)));
    }
}
