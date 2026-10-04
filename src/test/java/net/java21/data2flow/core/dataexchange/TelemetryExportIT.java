package net.java21.data2flow.core.dataexchange;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.dataexchange.service.ExportJobRunner;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내보내기(TSD-04.01, API-TSD-20~22, BR-TSD-14) 통합 시험 — 동기 CSV·권한 범위·넓은 형식·표시 단위·Parquet·취소·만료.
 * TC-TSD-105·106·108(동기 경로)·TC-DEV-140(표시 단위)·TC-NFR-041(Parquet 행 수)
 */
class TelemetryExportIT extends IntegrationTestSupport {

    @Autowired
    ExportJobRunner runner;

    ExchangeTestData td;
    long org;
    long admin;
    long analyst;
    long room;
    long lab;
    long sensor;
    long sensor2;
    Instant from;

    @BeforeEach
    void setUp() {
        td = new ExchangeTestData(jdbc);
        org = fx.organization("exp");
        admin = fx.user(org, "exp.admin", "ADMIN");
        analyst = fx.user(org, "exp.analyst", "ANALYST");
        long site = data.site(org, "본관");
        long floor = data.space(org, site, "FLOOR", "3층");
        room = data.space(org, floor, "ROOM", "실습실");
        lab = data.space(org, floor, "ROOM", "연구실");
        long source = data.source(org, "cs");
        data.metric(org, "temperature", "℃");
        data.metric(org, "co2", "ppm");
        sensor = data.device(org, source, "a1", "ACTIVE", room, null);
        sensor2 = data.device(org, source, "a2", "ACTIVE", lab, null);
        from = clock.instant().minus(Duration.ofHours(2));
    }

    private String body(long device, String metric, String extra) {
        return "{\"query\":{\"series\":[{\"deviceId\":%d,\"metric\":\"%s\"}],\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"raw\"}%s}"
                .formatted(device, metric, from, clock.instant(), extra);
    }

    private MvcResult export(long user, String json) throws Exception {
        return mvc.perform(as(org, user, json(post("/core/exports"), json))).andReturn();
    }

    private byte[] download(long user, String url) throws Exception {
        return mvc.perform(as(org, user, get(url.replace("/api/v1", "")))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    @DisplayName("[TSD-04.01][AT-TSD-04.1][BR-TSD-14] 작은 내보내기는 동기: UTF-8 BOM, 머리글 time,device_id,device_name,space_path,metric,value,unit,quality, 서명 링크로 내려받기·감사 — TC-TSD-102·105")
    void syncCsv() throws Exception {
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(1), 120);
        MvcResult r = export(analyst, body(sensor, "temperature", ",\"tz\":\"Asia/Seoul\""));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String json = r.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.response.mode")).isEqualTo("SYNC");
        assertThat((Integer) JsonPath.read(json, "$.response.estimatedRows")).isEqualTo(120);
        String url = JsonPath.read(json, "$.response.downloadUrl");
        byte[] file = download(analyst, url);
        String csv = new String(file, StandardCharsets.UTF_8);
        assertThat(csv.charAt(0)).isEqualTo('﻿');
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("time,device_id,device_name,space_path,metric,value,unit,quality");
        assertThat(lines).hasSize(121);
        assertThat(lines.get(1)).startsWith("2026-10-03T07:00:00+09:00," + sensor + ",기기 a1,본관/3층/실습실,temperature,20,℃,0");
        String jobId = JsonPath.read(json, "$.response.jobId");
        mvc.perform(as(org, analyst, get("/core/exports/" + jobId))).andExpect(jsonPath("$.response.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.rows").value(120)).andExpect(jsonPath("$.response.dictionaryVersion").value(1))
                .andExpect(jsonPath("$.response.query.series[0].metric").value("temperature"));
        mvc.perform(as(org, analyst, get("/core/exports"))).andExpect(jsonPath("$.totalCount").value(1));
        // 서명이 틀리면 거부, 다른 사용자(관리자 아님)에게는 없는 작업
        mvc.perform(as(org, analyst, get("/core/exports/" + jobId + "/file?expires=9999999999&signature=bad"))).andExpect(status().isForbidden());
        long other = fx.user(org, "exp.analyst2", "ANALYST");
        mvc.perform(as(org, other, get("/core/exports/" + jobId))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/exports/" + jobId))).andExpect(status().isOk());
        assertThat(td.outbox(org, "export.completed")).isEqualTo(1);
        assertThat(auditCount(org, "TELEMETRY_EXPORT_REQUESTED")).isEqualTo(1);
        assertThat(auditCount(org, "TELEMETRY_EXPORT_DOWNLOADED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TSD-04.01][AT-TSD-04.4][AT-TSD-04.5] VIEWER 403, 공간 제한 사용자는 조직 전체 조건이어도 권한 범위 기기만, 다른 조직 404 — TC-TSD-106")
    void permissions() throws Exception {
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(10), 12);
        td.raw(org, sensor2, "temperature", from, Duration.ofMinutes(10), 12);
        long viewer = fx.user(org, "exp.viewer", "VIEWER");
        assertThat(export(viewer, body(sensor, "temperature", "")).getResponse().getStatus()).isEqualTo(403);
        long limited = fx.user(org, "exp.limited", "ANALYST");
        data.spaceScope(org, limited, List.of(room));
        String two = "{\"query\":{\"series\":[{\"deviceId\":%d,\"metric\":\"temperature\"},{\"deviceId\":%d,\"metric\":\"temperature\"}],\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"raw\"}}"
                .formatted(sensor, sensor2, from, clock.instant());
        String json = export(limited, two).getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(json, "$.response.estimatedRows")).isEqualTo(12);
        String csv = new String(download(limited, JsonPath.read(json, "$.response.downloadUrl")), StandardCharsets.UTF_8);
        assertThat(csv.lines().skip(1)).allMatch(l -> l.contains(",기기 a1,")).hasSize(12);
        long other = fx.organization("exp2");
        long otherAnalyst = fx.user(other, "exp2.a", "ANALYST");
        String jobId = JsonPath.read(json, "$.response.jobId");
        mvc.perform(as(other, otherAnalyst, get("/core/exports/" + jobId))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TSD-04.01][TSD-03.03] 넓은 형식: 같은 시각 정렬, 열 이름 {기기}.{측정 항목}, 원본 품질 열 — TC-TSD-108")
    void wideLayout() throws Exception {
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(10), 3);
        td.raw(org, sensor, "co2", from, Duration.ofMinutes(10), 3);
        String q = "{\"query\":{\"series\":[{\"deviceId\":%d,\"metric\":\"temperature\"},{\"deviceId\":%d,\"metric\":\"co2\",\"label\":\"CO2\"}],\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"raw\"},\"columns\":\"WIDE\",\"tz\":\"UTC\"}"
                .formatted(sensor, sensor, from, clock.instant());
        String json = export(analyst, q).getResponse().getContentAsString();
        List<String> lines = new String(download(analyst, JsonPath.read(json, "$.response.downloadUrl")), StandardCharsets.UTF_8)
                .substring(1).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("time,기기 a1.temperature,기기 a1.temperature.quality,CO2,CO2.quality");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).isEqualTo("2026-10-02T22:00:00Z,20,0,20,0");
    }

    @Test
    @DisplayName("[DEV-04.04][TSD-04.01] 표시 단위가 ℉면 저장 단위(℃)와 표시 단위를 함께 적는다: 22.0℃ → 71.6℉ — TC-DEV-140")
    void displayUnit() throws Exception {
        data.telemetry(org, sensor, "temperature", from, 22.0, 0, false);
        jdbc.sql("INSERT INTO data2flow_core.user_dashboard_prefs (organization_id, user_id, temperature_unit) VALUES (:o, :u, 'F')")
                .param("o", org).param("u", analyst).update();
        String json = export(analyst, body(sensor, "temperature", "")).getResponse().getContentAsString();
        List<String> lines = new String(download(analyst, JsonPath.read(json, "$.response.downloadUrl")), StandardCharsets.UTF_8)
                .substring(1).lines().toList();
        assertThat(lines.getFirst()).endsWith(",unit,quality,display_value,display_unit");
        assertThat(lines.get(1)).endsWith(",22,℃,0,71.6,℉");
    }

    @Test
    @DisplayName("[NFR-04.02][TSD-04.01] Parquet 내보내기: 파일 행 수가 예상 행 수와 같고 PAR1 형식, XLSX 시트 — TC-NFR-041")
    void parquetAndXlsx() throws Exception {
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(1), 100);
        String json = export(analyst, body(sensor, "temperature", ",\"format\":\"PARQUET\"")).getResponse().getContentAsString();
        byte[] file = download(analyst, JsonPath.read(json, "$.response.downloadUrl"));
        assertThat(new String(file, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(new String(file, file.length - 4, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        String jobId = JsonPath.read(json, "$.response.jobId");
        mvc.perform(as(org, analyst, get("/core/exports/" + jobId))).andExpect(jsonPath("$.response.rows").value(100))
                .andExpect(jsonPath("$.response.format").value("PARQUET"));
        String xlsx = export(analyst, body(sensor, "temperature", ",\"format\":\"XLSX\"")).getResponse().getContentAsString();
        byte[] x = download(analyst, JsonPath.read(xlsx, "$.response.downloadUrl"));
        assertThat(x[0]).isEqualTo((byte) 'P');
        assertThat(x[1]).isEqualTo((byte) 'K');
        assertThat(export(analyst, body(sensor, "temperature", ",\"format\":\"PARQUET\",\"columns\":\"WIDE\"")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(export(analyst, body(sensor, "temperature", ",\"format\":\"PDF\"")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TSD-04.01][TSD-04.03] 7일 지나면 EXPIRED, 내려받기 410 EXPORT_EXPIRED, 파일 조각 삭제. 링크 1시간 지나면 410 — TC-TSD-115")
    void expiry() throws Exception {
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(10), 3);
        String json = export(analyst, body(sensor, "temperature", "")).getResponse().getContentAsString();
        String url = JsonPath.read(json, "$.response.downloadUrl");
        long jobId = Long.parseLong(JsonPath.read(json, "$.response.jobId"));
        clock.advance(Duration.ofMinutes(61));
        mvc.perform(as(org, analyst, get(url.replace("/api/v1", "")))).andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultCode").value("EXPORT_EXPIRED"));
        clock.advance(Duration.ofDays(7));
        assertThat(runner.housekeeping()).isEqualTo(1);
        assertThat(td.fileChunks("EXPORT", jobId)).isZero();
        mvc.perform(as(org, analyst, get("/core/exports/" + jobId))).andExpect(jsonPath("$.response.status").value("EXPIRED"))
                .andExpect(jsonPath("$.response.downloadUrl").doesNotExist());
        mvc.perform(as(org, analyst, post("/core/exports/" + jobId + "/cancel"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[TSD-04.01] 검증: 계열 없음·from 없음 400, 51개 TSD_TOO_MANY_SERIES, 잘못된 단위 400 — TC-TSD-103")
    void validation() throws Exception {
        assertThat(export(analyst, "{\"query\":{\"series\":[]}}").getResponse().getStatus()).isEqualTo(400);
        assertThat(export(analyst, "{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(export(analyst, "{\"query\":{\"series\":[{\"deviceId\":%d,\"metric\":\"temperature\"}]}}".formatted(sensor))
                .getResponse().getStatus()).isEqualTo(400);
        StringBuilder many = new StringBuilder("{\"query\":{\"from\":\"" + from + "\",\"series\":[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"deviceId\":").append(sensor).append(",\"metric\":\"temperature\"}");
        }
        MvcResult r = export(analyst, many.append("]}}").toString());
        assertThat(r.getResponse().getContentAsString()).contains("TSD_TOO_MANY_SERIES");
        assertThat(export(analyst, body(sensor, "temperature", ",\"columns\":\"DIAG\"")).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(as(org, analyst, get("/core/exports/99999999"))).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("X-Cache"));
    }
}
