package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-01.01·01.02 설정 API — TC-ENE-004(원천 충돌·측정 항목), TC-ENE-005(고지서 덮어쓰기), TC-ENE-007(권한·범위), TC-ENE-012(단가 기간·TOU·EVT-ENE-07),
 * TC-ENE-013(시스템 기본 배출계수 읽기 전용).
 */
class EnergySettingsIT extends IntegrationTestSupport {

    static final String TOU = """
            {"mode":"TOU","validFrom":"2026-10-01","touSchedule":{"seasons":[{"name":"ALL","months":[1,2,3,4,5,6,7,8,9,10,11,12],"bands":[
             {"dayType":"WEEKDAY","from":"00:00","to":"09:00","rate":100},{"dayType":"WEEKDAY","from":"%s","to":"24:00","rate":200},
             {"dayType":"WEEKEND_HOLIDAY","from":"00:00","to":"24:00","rate":100}]}]}}""";

    private long org;
    private long admin;
    private long operator;
    private long viewer;
    private long site;
    private long building;
    private long room;
    private long otherSite;
    private long plug;
    private long aircon;
    private long thermo;

    @BeforeEach
    void setUp() {
        org = fx.organization("ene");
        admin = fx.user(org, "ene.admin", "ADMIN");
        operator = fx.user(org, "ene.operator", "OPERATOR");
        viewer = fx.user(org, "ene.viewer", "VIEWER");
        site = data.site(org, "광주캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        room = data.space(org, building, "ROOM", "실습실");
        otherSite = data.site(org, "서울캠퍼스");
        data.metric(org, "energy", "kWh");
        data.metric(org, "power", "W");
        data.metric(org, "temperature", "°C");
        long meterModel = data.model(org, "PLUG", List.of("energy", "power"));
        long sensorModel = data.model(org, "TH", List.of("temperature"));
        long source = data.source(org, "ene-src");
        plug = data.device(org, source, "plug-1", "ACTIVE", room, meterModel);
        aircon = data.device(org, source, "ac-1", "ACTIVE", room, null);
        thermo = data.device(org, source, "th-1", "ACTIVE", room, sensorModel);
    }

    private static String id(MvcResult r, String name) throws Exception {
        return r.getResponse().getContentAsString().replaceAll("(?s).*\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    private long outbox(String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key")
                .param("org", org).param("key", routingKey).query(Long.class).single();
    }

    @Test
    @DisplayName("[TC-ENE-012] 단가: 같은 범위·시작일은 409, TOU 24시간 미커버·단가 0은 400, 바꾸면 EVT-ENE-07만(과거 재계산 없음)")
    void tariffs() throws Exception {
        String path = "/core/energy/tariffs";
        MvcResult sept = mvc.perform(as(org, admin, json(post(path), "{\"mode\":\"FLAT\",\"validFrom\":\"2026-09-01\",\"flatRateKrwPerKwh\":150}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.validTo").doesNotExist()).andReturn();
        String septId = id(sept, "tariffId");
        mvc.perform(as(org, admin, json(post(path), "{\"mode\":\"FLAT\",\"validFrom\":\"2026-10-01\",\"flatRateKrwPerKwh\":160}")))
                .andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post(path), "{\"mode\":\"FLAT\",\"validFrom\":\"2026-10-01\",\"flatRateKrwPerKwh\":170}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_PERIOD_OVERLAP"));
        mvc.perform(as(org, admin, json(post(path), "{\"mode\":\"FLAT\",\"validFrom\":\"2026-11-01\",\"flatRateKrwPerKwh\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_INVALID"));
        mvc.perform(as(org, admin, json(post(path), TOU.formatted("10:00").replace("2026-10-01", "2026-12-01"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_INVALID"));
        mvc.perform(as(org, admin, json(post(path), TOU.formatted("09:00").replace("2026-10-01", "2026-12-01"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.touSchedule.seasons[0].bands", hasSize(3)));
        // 사이트 단가는 같은 시작일이어도 범위가 달라 겹치지 않는다. 건물은 범위가 될 수 없다
        mvc.perform(as(org, admin, json(post(path), "{\"scopeSpaceId\":\"" + site + "\",\"mode\":\"FLAT\",\"validFrom\":\"2026-10-01\",\"flatRateKrwPerKwh\":155}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.scopeSpace.name").value("광주캠퍼스"));
        mvc.perform(as(org, admin, json(post(path), "{\"scopeSpaceId\":\"" + building + "\",\"mode\":\"FLAT\",\"validFrom\":\"2026-10-01\",\"flatRateKrwPerKwh\":155}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_INVALID"));

        mvc.perform(as(org, viewer, get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(4)))
                .andExpect(jsonPath("$.response[0].validFrom").value("2026-09-01")).andExpect(jsonPath("$.response[0].validTo").value("2026-09-30"))
                .andExpect(jsonPath("$.response[1].validTo").value("2026-11-30"));
        assertThat(outbox("energy.config.changed")).isEqualTo(4);

        // 고치기: baseVersion이 맞아야 한다
        String fix = "{\"mode\":\"FLAT\",\"validFrom\":\"2026-09-01\",\"flatRateKrwPerKwh\":152,\"baseVersion\":0}";
        mvc.perform(as(org, admin, json(put(path + "/" + septId), fix))).andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(put(path + "/" + septId), fix))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, json(post(path), "{\"mode\":\"FLAT\",\"validFrom\":\"2027-01-01\",\"flatRateKrwPerKwh\":150}")))
                .andExpect(status().isForbidden());
        // 범위가 제한된 관리자는 조직 단가를 바꿀 수 없고, 범위 밖 사이트 단가는 보이지 않는다
        long scoped = fx.user(org, "ene.scoped", "ADMIN");
        data.spaceScope(org, scoped, List.of(otherSite));
        mvc.perform(as(org, scoped, delete(path + "/" + septId))).andExpect(status().isForbidden());
        mvc.perform(as(org, scoped, get(path))).andExpect(jsonPath("$.response", hasSize(3)));
        mvc.perform(as(org, admin, delete(path + "/" + septId))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete(path + "/" + septId))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_TARIFF_NOT_FOUND"));
    }

    @Test
    @DisplayName("[TC-ENE-013] 배출계수: 시스템 기본값은 출처·연도가 있고 수정·삭제는 409, 조직 계수 기간 겹침은 409")
    void emissionFactors() throws Exception {
        String path = "/core/energy/emission-factors";
        MvcResult list = mvc.perform(as(org, viewer, get(path))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].system").value(true))
                .andExpect(jsonPath("$.response[0].factorTco2PerMwh").value(0.4567))
                .andExpect(jsonPath("$.response[0].sourceTitle").isNotEmpty())
                .andExpect(jsonPath("$.response[0].sourceYear").isNumber()).andReturn();
        String systemId = id(list, "emissionFactorId");
        mvc.perform(as(org, admin, json(put(path + "/" + systemId), "{\"validFrom\":\"2020-01-01\",\"factorTco2PerMwh\":0.5,\"sourceTitle\":\"x\",\"sourceYear\":2024}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_EMISSION_FACTOR_SYSTEM_READONLY"));
        mvc.perform(as(org, admin, delete(path + "/" + systemId))).andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT factor_tco2_per_mwh::text FROM data2flow_core.energy_emission_factors WHERE id = :id").param("id", Long.parseLong(systemId))
                .query(String.class).single()).isEqualTo("0.4567");

        String body = "{\"validFrom\":\"2026-01-01\",\"factorTco2PerMwh\":0.4400,\"sourceTitle\":\"한전 공시\",\"sourceYear\":2025}";
        MvcResult created = mvc.perform(as(org, admin, json(post(path), body))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.system").value(false)).andReturn();
        mvc.perform(as(org, admin, json(post(path), body))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_PERIOD_OVERLAP"));
        mvc.perform(as(org, admin, json(post(path), body.replace("0.4400", "2.5").replace("2026-01-01", "2027-01-01"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_RATE_INVALID"));
        mvc.perform(as(org, viewer, get(path))).andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].validTo").value("2025-12-31"));

        // 다른 조직은 이 조직 계수를 보지 못한다
        long other = fx.organization("ene2");
        long otherAdmin = fx.user(other, "ene2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get(path))).andExpect(jsonPath("$.response", hasSize(1)));
        mvc.perform(as(other, otherAdmin, delete(path + "/" + id(created, "emissionFactorId")))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TC-ENE-004·007] 원천: 같은 계량기 두 공간 409, 상위 계량기 겹침 409, temperature는 400, 권한·범위")
    void sources() throws Exception {
        String path = "/core/energy/sources";
        String metered = "{\"spaceId\":\"%d\",\"kind\":\"METERED\",\"deviceId\":\"%d\",\"metricKey\":\"%s\",\"coversDescendants\":%s}";
        mvc.perform(as(org, operator, json(post(path), metered.formatted(room, plug, "energy", false)))).andExpect(status().isForbidden());
        MvcResult created = mvc.perform(as(org, admin, json(post(path), metered.formatted(room, plug, "energy", false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.cumulative").value(true))
                .andExpect(jsonPath("$.response.status").value("NO_DATA")).andReturn();
        // 같은 계량기·항목을 다른 공간에
        mvc.perform(as(org, admin, json(post(path), metered.formatted(building, plug, "energy", false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_SOURCE_CONFLICT"));
        // 같은 기기의 power는 따로 연결할 수 있고 순간형이 기본이다
        mvc.perform(as(org, admin, json(post(path), metered.formatted(building, plug, "power", true))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.cumulative").value(false));
        // 건물이 하위 합계를 포함하면 사이트도 하위 합계를 포함할 수 없다(이중 계산)
        String sourceId = id(created, "sourceId");
        mvc.perform(as(org, admin, json(put(path + "/" + sourceId),
                        metered.formatted(room, plug, "energy", true).replace("}", ",\"baseVersion\":0}"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_SOURCE_CONFLICT"));
        mvc.perform(as(org, admin, json(post(path), metered.formatted(room, thermo, "temperature", false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_SOURCE_METRIC_INVALID"));
        // 모델에 없는 energy 항목도 거부
        mvc.perform(as(org, admin, json(post(path), metered.formatted(room, thermo, "energy", false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ENE_SOURCE_METRIC_INVALID"));
        String estimated = "{\"spaceId\":\"" + room + "\",\"kind\":\"ESTIMATED\",\"deviceId\":\"" + aircon + "\",\"ratedPowerW\":1200,\"standbyPowerW\":5}";
        mvc.perform(as(org, admin, json(post(path), estimated))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.status").value("OK")).andExpect(jsonPath("$.response.coversDescendants").value(false));
        mvc.perform(as(org, admin, json(post(path), estimated))).andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(post(path), estimated.replace("\"ratedPowerW\":1200,", ""))))
                .andExpect(status().isBadRequest());

        mvc.perform(as(org, viewer, get(path + "?spaceId=" + room))).andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(2)));
        mvc.perform(as(org, viewer, get(path + "/candidates?spaceId=" + site))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(1))).andExpect(jsonPath("$.response[0].metricKeys", hasSize(2)))
                .andExpect(jsonPath("$.response[0].linkedMetricKeys", hasSize(2)));
        data.spaceScope(org, viewer, List.of(otherSite));
        mvc.perform(as(org, viewer, get(path + "?spaceId=" + room))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, viewer, get(path))).andExpect(jsonPath("$.response", hasSize(0)));
        assertThat(outbox("energy.config.changed")).isEqualTo(3);
    }

    @Test
    @DisplayName("[TC-ENE-005] 고지서: 같은 달은 overwrite=true일 때만 덮어쓰고, 사이트·건물만 받는다")
    void bills() throws Exception {
        String path = "/core/energy/bills/" + building + "/2026-09";
        mvc.perform(as(org, admin, json(put(path), "{\"kwh\":3200,\"amountKrw\":480000}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.month").value("2026-09")).andExpect(jsonPath("$.response.kwh").value(3200.0))
                .andExpect(jsonPath("$.response.meteredKwh").doesNotExist());
        mvc.perform(as(org, admin, json(put(path), "{\"kwh\":3300}"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_BILL_DUPLICATE"));
        mvc.perform(as(org, admin, json(put(path), "{\"kwh\":3300,\"overwrite\":true}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kwh").value(3300.0));
        mvc.perform(as(org, admin, json(put("/core/energy/bills/" + room + "/2026-09"), "{\"kwh\":10}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put("/core/energy/bills/" + building + "/2026-13"), "{\"kwh\":10}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(put(path), "{\"kwh\":1,\"overwrite\":true}"))).andExpect(status().isForbidden());

        mvc.perform(as(org, viewer, get("/core/energy/bills?spaceId=" + building + "&year=2026"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses", hasSize(1))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, viewer, get("/core/energy/bills?year=2025"))).andExpect(jsonPath("$.responses", hasSize(0)));
        mvc.perform(as(org, admin, delete(path))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete(path))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_BILL_NOT_FOUND"));
    }

    @Test
    @DisplayName("[API-ENE-70] 내부 설정 묶음: 단가·배출계수(시스템 포함)·켜진 원천(사이트·시간대), 설정을 바꿀 때마다 configVersion이 오른다")
    void internalConfig() throws Exception {
        String bundle = "/internal/core/energy/config?orgId=" + org;
        mvc.perform(get(bundle).header("X-CALLER-SERVICE", "data2flow-analytics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.configVersion").value(0)).andExpect(jsonPath("$.response.meterSources", hasSize(0)));
        mvc.perform(as(org, admin, json(post("/core/energy/tariffs"),
                        "{\"scopeSpaceId\":\"%d\",\"mode\":\"FLAT\",\"validFrom\":\"2026-09-01\",\"flatRateKrwPerKwh\":150}".formatted(site))))
                .andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post("/core/energy/sources"),
                        "{\"spaceId\":\"%d\",\"kind\":\"METERED\",\"deviceId\":\"%d\",\"metricKey\":\"energy\"}".formatted(room, plug))))
                .andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post("/core/energy/sources"),
                        "{\"spaceId\":\"%d\",\"kind\":\"ESTIMATED\",\"deviceId\":\"%d\",\"ratedPowerW\":1200,\"standbyPowerW\":5,\"enabled\":false}"
                                .formatted(room, aircon))))
                .andExpect(status().isCreated());
        mvc.perform(get(bundle).header("X-CALLER-SERVICE", "data2flow-analytics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.configVersion").value(3))
                .andExpect(jsonPath("$.response.tariffs", hasSize(1)))
                .andExpect(jsonPath("$.response.tariffs[0].scopeSpace.id").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.emissionFactors[0].system").value(true))
                .andExpect(jsonPath("$.response.meterSources", hasSize(1)))
                .andExpect(jsonPath("$.response.meterSources[0].spaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.meterSources[0].siteId").value(Long.toString(site)))
                .andExpect(jsonPath("$.response.meterSources[0].cumulative").value(true))
                .andExpect(jsonPath("$.response.meterSources[0].timezone").isString())
                .andExpect(jsonPath("$.response.holidays." + site).isArray());
        MvcResult orgs = mvc.perform(get("/internal/core/energy/organizations").header("X-CALLER-SERVICE", "data2flow-analytics"))
                .andExpect(status().isOk()).andReturn();
        assertThat(orgs.getResponse().getContentAsString()).contains("\"" + org + "\"");
    }
}
