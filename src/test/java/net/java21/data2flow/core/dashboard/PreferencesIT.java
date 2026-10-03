package net.java21.data2flow.core.dashboard;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.hamcrest.Matchers;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-07.02 테마·DSH-07.04 시간대·DSH-07.05 즐겨찾기·최근 본 항목(API-DSH-12) */
class PreferencesIT extends IntegrationTestSupport {

    private long org;
    private long user;
    private long lab;
    private long classroom;
    private long device;

    @BeforeEach
    void setUp() {
        org = fx.organization("pref");
        user = fx.user(org, "pref.user", "OPERATOR");
        long site = data.site(org, "캠퍼스");
        lab = data.space(org, site, "ROOM", "실습실");
        classroom = data.space(org, site, "ROOM", "강의실");
        long source = data.source(org, "campus-lns");
        device = data.device(org, source, "a1", "ACTIVE", lab, null);
    }

    @Test
    @DisplayName("[DSH-07.04][AT-DSH-11.1] 설정이 없으면 기본값, 시간대는 조직 기본(Asia/Seoul) — TC-DSH-077")
    void defaults() throws Exception {
        mvc.perform(as(org, user, get("/core/accounts/me/preferences")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.response.timeZone").value(nullValue()))
                .andExpect(jsonPath("$.response.effectiveTimeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.response.organizationTimeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.response.effectiveLocale").value("ko"))
                .andExpect(jsonPath("$.response.home").value("HOME"))
                .andExpect(jsonPath("$.response.effectiveTemperatureUnit").value("C"))
                .andExpect(jsonPath("$.response.favorites", hasSize(0)))
                .andExpect(jsonPath("$.response.recent", hasSize(0)))
                .andExpect(jsonPath("$.response.version").value(0));
        jdbc.sql("UPDATE data2flow_core.org_settings SET timezone = 'UTC', unit_system = 'IMPERIAL' WHERE organization_id = :org")
                .param("org", org).update();
        mvc.perform(as(org, user, get("/core/accounts/me/preferences")))
                .andExpect(jsonPath("$.response.effectiveTimeZone").value("UTC"))
                .andExpect(jsonPath("$.response.effectiveTemperatureUnit").value("F"));
    }

    @Test
    @DisplayName("[DSH-07.05][DSH-07.04] 온 키만 저장(baseVersion), 즐겨찾기 이름, 사용자 시간대가 우선, 낡은 버전 409 — TC-DSH-079")
    void saveAndConflict() throws Exception {
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), """
                        {"theme":"dark","timeZone":"Asia/Tokyo","temperatureUnit":"F","locale":"JA",
                         "favorites":[{"type":"SPACE","id":"%d"},{"type":"DEVICE","id":%d},{"type":"DASHBOARD","id":"5"},{"type":"SPACE","id":"%d"}],
                         "toursDismissed":["home"],"baseVersion":0}""".formatted(lab, device, lab))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.theme").value("DARK"))
                .andExpect(jsonPath("$.response.timeZone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.response.effectiveTimeZone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.response.locale").value("ja"))
                .andExpect(jsonPath("$.response.temperatureUnit").value("F"))
                .andExpect(jsonPath("$.response.favorites[*].type").value(contains("SPACE", "DEVICE", "DASHBOARD")))
                .andExpect(jsonPath("$.response.favorites[0].name").value("실습실"))
                .andExpect(jsonPath("$.response.favorites[1].name").value("기기 a1"))
                .andExpect(jsonPath("$.response.toursDismissed[0]").value("home"))
                .andExpect(jsonPath("$.response.version").value(1));
        // 온 키만: 테마만 바꾸면 즐겨찾기·시간대는 그대로
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"theme\":\"LIGHT\",\"baseVersion\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.theme").value("LIGHT"))
                .andExpect(jsonPath("$.response.timeZone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.response.favorites", hasSize(3)))
                .andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"theme\":\"DARK\",\"baseVersion\":1}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        // 시간대를 비우면 조직 기본값으로
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"timeZone\":null,\"temperatureUnit\":null,\"baseVersion\":2}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.timeZone").value(nullValue()))
                .andExpect(jsonPath("$.response.effectiveTimeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.response.effectiveTemperatureUnit").value("C"));
    }

    @Test
    @DisplayName("[DSH-07.05] 잘못된 값은 400 INVALID_REQUEST와 필드 목록, baseVersion이 없으면 400")
    void validation() throws Exception {
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), """
                        {"theme":"NEON","timeZone":"Mars/Base","home":null,"locale":"fr","temperatureUnit":"K","defaultDashboardId":"x",
                         "favorites":[{"type":"FLOW","id":"1"}],"toursDismissed":[""],"baseVersion":0}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("theme", "locale", "home",
                        "temperatureUnit", "timeZone", "defaultDashboardId", "favorites[0]", "toursDismissed")));
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"favorites\":{},\"toursDismissed\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("favorites", "toursDismissed")));
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"theme\":\"DARK\"}")))
                .andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder("[");
        for (int i = 1; i <= 51; i++) {
            many.append(i > 1 ? "," : "").append("{\"type\":\"DASHBOARD\",\"id\":\"").append(i).append("\"}");
        }
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"), "{\"favorites\":" + many + "],\"baseVersion\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("favorites"));
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"),
                        "{\"defaultDashboardId\":\"12\",\"home\":\"dashboard\",\"favorites\":null,\"toursDismissed\":null,\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.defaultDashboardId").value("12"))
                .andExpect(jsonPath("$.response.home").value("DASHBOARD"));
    }

    @Test
    @DisplayName("[DSH-07.05] 최근 본 항목: 20건 유지, 다시 보면 맨 앞, 권한이 사라진 항목은 조회에서 제외 — TC-DSH-079")
    void recentAndScope() throws Exception {
        for (int i = 1; i <= 21; i++) {
            mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"DASHBOARD\",\"id\":\"" + i + "\"}")))
                    .andExpect(status().isOk());
        }
        mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"space\",\"id\":\"" + lab + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.recent", hasSize(20)))
                .andExpect(jsonPath("$.response.recent[0].type").value("SPACE"))
                .andExpect(jsonPath("$.response.recent[0].name").value("실습실"))
                .andExpect(jsonPath("$.response.recent[0].at", endsWith("Z")))
                .andExpect(jsonPath("$.response.recent[1].id").value("21"))
                .andExpect(jsonPath("$.response.recent[19].id").value("3"));
        mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"DASHBOARD\",\"id\":\"10\"}")))
                .andExpect(jsonPath("$.response.recent[0].id").value("10"))
                .andExpect(jsonPath("$.response.recent", hasSize(20)))
                .andExpect(jsonPath("$.response.recent[?(@.id == '10')]", hasSize(1)));
        mvc.perform(as(org, user, json(put("/core/accounts/me/preferences"),
                        "{\"favorites\":[{\"type\":\"SPACE\",\"id\":\"" + lab + "\"},{\"type\":\"SPACE\",\"id\":\"" + classroom + "\"},"
                                + "{\"type\":\"DEVICE\",\"id\":\"" + device + "\"}],\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.favorites", hasSize(3)))
                .andExpect(jsonPath("$.response.recent", hasSize(20)));
        // 강의실만 권한 → 실습실과 그 기기는 목록에서 빠진다
        data.spaceScope(org, user, List.of(classroom));
        mvc.perform(as(org, user, get("/core/accounts/me/preferences")))
                .andExpect(jsonPath("$.response.favorites", hasSize(1)))
                .andExpect(jsonPath("$.response.favorites[0].name").value("강의실"))
                .andExpect(jsonPath("$.response.recent[?(@.type == 'SPACE')]", hasSize(0)));
        mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"SPACE\",\"id\":\"" + lab + "\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"DEVICE\",\"id\":\"" + device + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, user, json(post("/core/accounts/me/recent"), "{\"type\":\"WIDGET\",\"id\":\"x\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("type", "id")));
    }
}
