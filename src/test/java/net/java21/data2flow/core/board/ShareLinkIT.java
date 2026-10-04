package net.java21.data2flow.core.board;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-06.03 읽기 전용 공유 링크(API-DSH-10·15, BR-DSH-10·12) */
class ShareLinkIT extends IntegrationTestSupport {

    private long org;
    private long analyst;
    private long viewer;
    private long device;
    private String dashboard;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("share");
        analyst = fx.user(org, "share.analyst", "ANALYST");
        viewer = fx.user(org, "share.viewer", "VIEWER");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        long source = data.source(org, "cs");
        data.metric(org, "co2", "ppm");
        device = data.device(org, source, "d1", "ACTIVE", room, null);
        data.deviceState(org, device, "ONLINE", clock.instant(), "{\"co2\":{\"v\":812,\"t\":\"" + clock.instant() + "\",\"q\":0}}");
        String layout = """
                {"widgets":[{"id":"co2","type":"stat","x":0,"y":0,"w":6,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%d","metricKey":"co2"}]}]}"""
                .formatted(device);
        dashboard = JsonPath.read(mvc.perform(as(org, analyst, json(post("/core/dashboards"),
                        "{\"name\":\"공개 현황\",\"visibility\":\"ORG\",\"layout\":" + layout + "}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.id");
        // 저장된 정의에 제어 위젯이 섞여 있어도(나중 마일스톤) 공개 응답에서 빠지는지 본다
        jdbc.sql("""
                        UPDATE data2flow_core.dashboards SET layout = jsonb_set(layout, '{widgets}', layout->'widgets' ||
                               '[{"id":"ctl","type":"control","x":6,"y":0,"w":6,"h":4}]'::jsonb) WHERE id = :id""")
                .param("id", Long.parseLong(dashboard)).update();
    }

    private String create(long user, int days) throws Exception {
        return mvc.perform(as(org, user, json(post("/core/dashboards/" + dashboard + "/share-links"), "{\"expiresInDays\":" + days + "}")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.response.url", startsWith("https://web.test/share/")))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[DSH-06.03][AT-DSH-07.1][AT-DSH-07.2] 로그인 없이 200(제어 위젯·편집 정보 없음), 위젯 데이터, 폐기 즉시 404 SHARE_LINK_INVALID, 만료·없는 토큰도 같은 코드 — TC-DSH-067")
    void publicView() throws Exception {
        String res = create(analyst, 7);
        String token = JsonPath.<String>read(res, "$.response.url").substring("https://web.test/share/".length());
        String linkId = JsonPath.read(res, "$.response.id");
        mvc.perform(get("/core/public/share/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(jsonPath("$.response.dashboard.name").value("공개 현황"))
                .andExpect(jsonPath("$.response.dashboard.layout.widgets", hasSize(1)))
                .andExpect(jsonPath("$.response.dashboard.layout.widgets[?(@.type == 'control')]", hasSize(0)))
                .andExpect(jsonPath("$.response.dashboard.version").doesNotExist())
                .andExpect(jsonPath("$.response.dashboard.editable").doesNotExist())
                .andExpect(jsonPath("$.response.branding.publicTheme").value("AUTO"))
                .andExpect(jsonPath("$.response.expiresAt").value(clock.instant().plus(Duration.ofDays(7)).toString()));
        mvc.perform(json(post("/core/public/share/" + token + "/widgets/co2/data"), "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.data.value").value(812.0));
        // 제어·편집 API는 신원 헤더가 없으면 401(공개 경로가 아님)
        mvc.perform(json(put("/core/dashboards/" + dashboard), "{\"name\":\"x\",\"baseVersion\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(as(org, analyst, get("/core/dashboards/" + dashboard + "/share-links")))
                .andExpect(jsonPath("$.response", hasSize(1)))
                .andExpect(jsonPath("$.response[0].lastUsedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.response[0].url").doesNotExist());
        assertThat(jdbc.sql("SELECT token_hash FROM data2flow_core.share_links WHERE id = :id").param("id", Long.parseLong(linkId))
                .query(String.class).single()).hasSize(64).doesNotContain(token);
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + dashboard + "/share-links/" + linkId))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + dashboard + "/share-links/" + linkId))).andExpect(status().isNoContent());
        mvc.perform(get("/core/public/share/" + token)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_LINK_INVALID"));
        mvc.perform(json(post("/core/public/share/" + token + "/widgets/co2/data"), "{}")).andExpect(status().isNotFound());
        // 만료
        String expiring = JsonPath.<String>read(create(analyst, 1), "$.response.url").substring("https://web.test/share/".length());
        mvc.perform(get("/core/public/share/" + expiring)).andExpect(status().isOk());
        clock.advance(Duration.ofDays(1));
        mvc.perform(get("/core/public/share/" + expiring)).andExpect(status().isNotFound());
        mvc.perform(get("/core/public/share/not-a-token")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_LINK_INVALID"));
        // 대시보드를 PRIVATE로 바꾸거나 지우면 다른 사람이 만든 링크는 막힌다
        String third = JsonPath.<String>read(create(analyst, 3), "$.response.url").substring("https://web.test/share/".length());
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + dashboard))).andExpect(status().isNoContent());
        mvc.perform(get("/core/public/share/" + third)).andExpect(status().isNotFound());
        assertThat(auditCount(org, "SHARE_LINK_CREATED")).isEqualTo(3);
        assertThat(auditCount(org, "SHARE_LINK_REVOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSH-06.03] 권한: ANALYST 생성·폐기 201·204, VIEWER 403, 보이지 않는 대시보드·다른 조직 404 DASHBOARD_NOT_FOUND, 만료 0·91일 400 — TC-DSH-070")
    void permissions() throws Exception {
        mvc.perform(as(org, viewer, json(post("/core/dashboards/" + dashboard + "/share-links"), "{\"expiresInDays\":7}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, get("/core/dashboards/" + dashboard + "/share-links"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, json(post("/core/dashboards/" + dashboard + "/share-links"), "{\"expiresInDays\":91}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, json(post("/core/dashboards/" + dashboard + "/share-links"), "{\"expiresInDays\":0}")))
                .andExpect(status().isBadRequest());
        long other = fx.organization("share2");
        long otherAdmin = fx.user(other, "share2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, json(post("/core/dashboards/" + dashboard + "/share-links"), "{\"expiresInDays\":7}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_NOT_FOUND"));
        String priv = JsonPath.read(mvc.perform(as(org, analyst, json(post("/core/dashboards"), "{\"name\":\"비공개\"}")))
                .andReturn().getResponse().getContentAsString(), "$.response.id");
        long analyst2 = fx.user(org, "share.analyst2", "ANALYST");
        mvc.perform(as(org, analyst2, json(post("/core/dashboards/" + priv + "/share-links"), "{\"expiresInDays\":7}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + dashboard + "/share-links/999999"))).andExpect(status().isNotFound());
    }
}
