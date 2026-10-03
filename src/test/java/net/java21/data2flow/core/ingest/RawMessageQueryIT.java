package net.java21.data2flow.core.ingest;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원본 메시지 조회(ING-01.01·01.02, UC-ING-06): API-ING-05 커서 목록, API-ING-06 상세, payload 권한 — TC-ING-005·006·007.
 */
class RawMessageQueryIT extends IngestITSupport {

    static final Instant T0 = MutableClock.T0;

    long org;
    long operator;
    long integrator;
    long source;
    long labDevice;
    long otherDevice;
    long lab;
    String from;
    String to;

    @BeforeEach
    void setUp() {
        org = fx.organization("ingr");
        operator = fx.user(org, "raw.operator", "OPERATOR");
        integrator = fx.user(org, "raw.integrator", "INTEGRATOR");
        long site = data.site(org, "본관");
        lab = data.space(org, site, "ROOM", "실습실");
        long office = data.space(org, site, "ROOM", "교무실");
        source = data.source(org, "chirp");
        data.metric(org, "temperature", "℃");
        labDevice = data.device(org, source, "lab-1", "ACTIVE", lab, null);
        otherDevice = data.device(org, source, "office-1", "ACTIVE", office, null);
        from = T0.minus(Duration.ofHours(1)).toString();
        to = T0.plusSeconds(1).toString();
    }

    @Test
    @DisplayName("[ING-01.01][AT-ING-06.1][AT-ING-06.3] 상태 필터 건수 일치, 공간 범위 사용자는 다른 공간 원본이 목록·건수에서 빠짐, 커서로 누락·중복 없음 — TC-ING-005·006")
    void listWithFiltersAndScope() throws Exception {
        for (int i = 0; i < 40; i++) {
            Instant at = T0.minusSeconds(60L * i + 1);
            long device = i % 2 == 0 ? labDevice : otherDevice;
            String status = i % 5 == 0 ? "DECODE_ERROR" : "OK";
            rows.raw(org, source, device, status, at, at.plusMillis(200), "{\"i\":" + i + "}", "application/1/device/" + device + "/event/up", i == 39);
        }
        rows.raw(org, source, null, "UNKNOWN_DEVICE_REJECTED", T0.minusMillis(500), T0.minusMillis(400), "{}", "unknown/topic", false);
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("status", "DECODE_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses", hasSize(8)))
                .andExpect(jsonPath("$.countsByStatus.DECODE_ERROR").value(8))
                .andExpect(jsonPath("$.countsByStatus.OK").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        String page = mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("size", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.responses[0].status").value("UNKNOWN_DEVICE_REJECTED"))
                .andExpect(jsonPath("$.responses[0].deviceId").doesNotExist())
                .andExpect(jsonPath("$.responses[0].sourceName").value("소스 chirp"))
                .andExpect(jsonPath("$.countsByStatus.OK").value(32))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(page, "$.nextCursor")).isNotBlank();
        // 커서 넘겨 받기: 41건 모두 한 번씩
        Set<String> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/core/ingest/raw-messages").param("from", from).param("to", to).param("size", "7");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mvc.perform(as(org, operator, request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (Object id : JsonPath.<List<Object>>read(body, "$.responses[*].id")) {
                assertThat(ids.add((String) id)).isTrue();
            }
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null);
        assertThat(ids).hasSize(41);
        assertThat(pages).isEqualTo(6);
        // 소스·기기·토픽·가상 조건
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", from).param("to", to)
                        .param("deviceId", Long.toString(labDevice)).param("sourceId", Long.toString(source))))
                .andExpect(jsonPath("$.responses", hasSize(20)));
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("topicContains", "unknown/")))
                .andExpect(jsonPath("$.responses", hasSize(1)));
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("includeVirtual", "false")))
                .andExpect(jsonPath("$.responses", hasSize(40)));
        // 공간 범위 '실습실': 다른 공간·기기 모르는 원본은 목록·건수에서 빠짐(AT-ING-06.3)
        long scoped = fx.user(org, "lab.operator", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("status", "DECODE_ERROR")))
                .andExpect(jsonPath("$.responses", hasSize(4))).andExpect(jsonPath("$.countsByStatus.DECODE_ERROR").value(4));
        mvc.perform(as(org, scoped, get("/core/ingest/raw-messages").param("from", from).param("to", to).param("status", "OK")))
                .andExpect(jsonPath("$.responses", hasSize(16))).andExpect(jsonPath("$.countsByStatus.OK").value(16));
    }

    @Test
    @DisplayName("[ING-01.01] 기간 31일 초과 400 ING_QUERY_RANGE_TOO_LARGE, 보관 30일 밖 400, 형식 오류·VIEWER 403 — TC-ING-005")
    void listErrors() throws Exception {
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", "2026-08-01T00:00:00Z").param("to", to)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_QUERY_RANGE_TOO_LARGE"));
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages").param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-10T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_REPROCESS_OUT_OF_RETENTION"));
        for (String[] bad : new String[][]{{"to", to}, {"from", from}, {"status", "NOPE"}, {"format", "csv"}, {"cursor", "%%%"},
                {"topicContains", "x".repeat(201)}}) {
            var request = get("/core/ingest/raw-messages").param(bad[0], bad[1]);
            if (!"from".equals(bad[0]) && !"to".equals(bad[0])) {
                request.param("from", from).param("to", to);
            }
            mvc.perform(as(org, operator, request)).andExpect(status().isBadRequest());
        }
        long viewer = fx.user(org, "raw.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/ingest/raw-messages").param("from", from).param("to", to))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ING-01.01][AT-ING-06.2][BR-ING-15] OPERATOR 상세는 payload 가림·처리 결과는 보임, /payload 403; INTEGRATOR는 원문과 열람 감사 1건 — TC-ING-007")
    void detailAndPayload() throws Exception {
        Instant at = T0.minusSeconds(30);
        long raw = rows.raw(org, source, labDevice, "OK", at, at.plusMillis(300), "{\"temperature\":22.3}", "app/1/device/lab-1/event/up", false);
        rows.telemetry(org, labDevice, "temperature", at, 22.3, 0, 1, raw);
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages/" + raw))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(Long.toString(raw)))
                .andExpect(jsonPath("$.response.payload").doesNotExist())
                .andExpect(jsonPath("$.response.payloadMasked").value(true))
                .andExpect(jsonPath("$.response.status").value("OK"))
                .andExpect(jsonPath("$.response.payloadEncoding").value("JSON"))
                .andExpect(jsonPath("$.response.trace[0].stage").value("DECODE"))
                .andExpect(jsonPath("$.response.canonical.v").value(1))
                .andExpect(jsonPath("$.response.stored[0].metricKey").value("temperature"))
                .andExpect(jsonPath("$.response.stored[0].unit").value("℃"))
                .andExpect(jsonPath("$.response.stored[0].late").value(true));
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages/" + raw + "/payload"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ING_PAYLOAD_FORBIDDEN"))
                .andExpect(jsonPath("$.header.resultMessage").value("원본 보기 권한이 없습니다"));
        assertThat(auditCount(org, "RAW_PAYLOAD_VIEWED")).isZero();
        mvc.perform(as(org, integrator, get("/core/ingest/raw-messages/" + raw))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.payload").value("{\"temperature\":22.3}"))
                .andExpect(jsonPath("$.response.payloadMasked").value(false));
        assertThat(auditCount(org, "RAW_PAYLOAD_VIEWED")).isEqualTo(1);
        mvc.perform(as(org, integrator, get("/core/ingest/raw-messages/" + raw + "/payload"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.payload").value("{\"temperature\":22.3}"));
        assertThat(auditCount(org, "RAW_PAYLOAD_VIEWED")).isEqualTo(2);
        // BINARY는 base64
        jdbc.sql("UPDATE data2flow_pipeline.raw_messages SET payload_encoding = 'BINARY', payload = '\\x0175ff00' WHERE id = :id").param("id", raw).update();
        mvc.perform(as(org, integrator, get("/core/ingest/raw-messages/" + raw + "/payload")))
                .andExpect(jsonPath("$.response.payload").value("AXX/AA=="));
        // 없는 ID·다른 조직·공간 범위 밖 → 404
        mvc.perform(as(org, operator, get("/core/ingest/raw-messages/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ING_RAW_MESSAGE_NOT_FOUND"));
        long other = fx.organization("ings");
        long stranger = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, stranger, get("/core/ingest/raw-messages/" + raw))).andExpect(status().isNotFound());
        long scoped = fx.user(org, "office.integrator", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(jdbc.sql("SELECT space_id FROM data2flow_core.devices WHERE id = :d").param("d", otherDevice)
                .query(Long.class).single()));
        mvc.perform(as(org, scoped, get("/core/ingest/raw-messages/" + raw))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/ingest/raw-messages/" + raw + "/payload"))).andExpect(status().isNotFound());
    }
}
