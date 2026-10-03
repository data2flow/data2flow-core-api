package net.java21.data2flow.core.annotation;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged.Connectivity;
import net.java21.data2flow.contracts.message.event.EventPayload;
import net.java21.data2flow.contracts.message.event.IngestAlert;
import net.java21.data2flow.core.messaging.service.CoreEventConsumer;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TSD-01.04 시계열 주석: 시스템 주석 생성(BR-TSD-23)과 API-TSD-06·07 — TC-TSD-017~020(core 몫, 문서의 pipeline·TelemetryWriter 이름은
 * core-api 주석 기능으로 옮겨 구현), AT-TSD-11.1·11.2.
 */
class AnnotationIT extends IntegrationTestSupport {

    private static final String RANGE = "from=2026-10-03T00:00:00Z&to=2026-10-04T00:00:00Z";

    @Autowired
    CoreEventConsumer consumer;
    @Autowired
    MessageCodec codec;

    private long org;
    private long operator;
    private long admin;
    private long site;
    private long roomA;
    private long roomB;
    private long deviceA;
    private long deviceA2;
    private long deviceB;

    @BeforeEach
    void setUp() {
        org = fx.organization("tsd");
        operator = fx.user(org, "kim.operator", "OPERATOR");
        admin = fx.user(org, "boss.admin", "ADMIN");
        site = data.site(org, "본관");
        long floor = data.space(org, site, "FLOOR", "3층");
        roomA = data.space(org, floor, "ROOM", "실습실 A");
        roomB = data.space(org, floor, "ROOM", "실습실 B");
        long source = jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                unknown_device_policy, created_by, updated_by)
                        VALUES (:org, 'chirpstack-s3', '소스', 'MQTT_SUBSCRIBE', 'ACTIVE', '{}'::jsonb, 'chirpstack-v4', 'REJECT', 0, 0)
                        RETURNING id""").param("org", org).query(Long.class).single();
        deviceA = data.device(org, source, "a1", "ACTIVE", roomA, null);
        deviceA2 = data.device(org, source, "a2", "ACTIVE", roomA, null);
        deviceB = data.device(org, source, "b1", "ACTIVE", roomB, null);
    }

    private <P extends EventPayload> void publish(EventType type, P payload) {
        consumer.onMessage(codec.write(DomainEvent.of(type, org, payload, null, clock)));
    }

    private String create(long user, String body) throws Exception {
        String content = mvc.perform(as(org, user, json(post("/core/annotations"), body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return content.replaceAll("(?s).*\"id\":\"(\\d+)\".*", "$1");
    }

    @Test
    @DisplayName("[TSD-01.04][AT-TSD-11.1][BR-TSD-23] 오프라인 이벤트 → OFFLINE 주석(마지막 수신부터), ONLINE이면 time_to 갱신, 중복 OFFLINE은 하나 — TC-TSD-017")
    void offlineAnnotations() throws Exception {
        Instant lastSeen = Instant.parse("2026-10-03T10:00:00Z");
        clock.set(Instant.parse("2026-10-03T10:20:00Z"));
        publish(EventType.DEVICE_CONNECTIVITY_CHANGED,
                new DeviceConnectivityChanged(deviceA, Connectivity.ONLINE, Connectivity.OFFLINE, lastSeen, 600, 2.0));
        publish(EventType.DEVICE_CONNECTIVITY_CHANGED,
                new DeviceConnectivityChanged(deviceA, Connectivity.ONLINE, Connectivity.OFFLINE, lastSeen, 600, 2.0));
        // 진행 중(끝 없음) 오프라인은 이후 구간 조회에도 보인다
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA + "&from=2026-10-03T12:00:00Z&to=2026-10-03T13:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].type").value("OFFLINE"))
                .andExpect(jsonPath("$.responses[0].timeFrom").value("2026-10-03T10:00:00Z"))
                .andExpect(jsonPath("$.responses[0].timeTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(deviceA)));
        clock.advance(Duration.ofMinutes(40));
        publish(EventType.DEVICE_CONNECTIVITY_CHANGED,
                new DeviceConnectivityChanged(deviceA, Connectivity.OFFLINE, Connectivity.ONLINE, Instant.parse("2026-10-03T11:00:00Z"), 600, 2.0));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA + "&" + RANGE + "&types=OFFLINE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].timeTo").value("2026-10-03T11:00:00Z"));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA + "&from=2026-10-03T12:00:00Z&to=2026-10-03T13:00:00Z")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        // 다른 기기 차트에는 이 기기의 오프라인이 나오지 않는다
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA2 + "&" + RANGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        // 없는 기기 이벤트는 무시(FK)
        publish(EventType.DEVICE_CONNECTIVITY_CHANGED,
                new DeviceConnectivityChanged(999_999, null, Connectivity.OFFLINE, null, 600, 2.0));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.annotations WHERE organization_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TSD-01.04][BR-TSD-23] 스크립트 오류율 알람(EVT-ING-05 SCRIPT_ERROR_RATE) → SCRIPT_ERROR 조직 주석, cleared로 닫힘, 다른 알람 코드는 무시 — TC-TSD-017")
    void scriptErrorAnnotations() throws Exception {
        long scriptId = jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by)
                        VALUES (:o, 'Milesight 디코더', 'DECODE', 0, 0) RETURNING id""").param("o", org).query(Long.class).single();
        Instant at = Instant.parse("2026-10-03T09:00:00Z");
        IngestAlert raised = new IngestAlert(IngestAlert.SCRIPT_ERROR_RATE, IngestAlert.Level.WARNING, null, 0.12, 0.1, List.of(), at,
                scriptId, 3, 0.12, "5m", false);
        publish(EventType.INGEST_ALERT_RAISED, raised);
        publish(EventType.INGEST_ALERT_RAISED, raised);
        publish(EventType.INGEST_ALERT_RAISED, IngestAlert.of(IngestAlert.INGEST_LAG, IngestAlert.Level.WARNING, null, 30, 10, List.of(), at));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceB + "&" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].type").value("SCRIPT_ERROR"))
                .andExpect(jsonPath("$.responses[0].title").value("스크립트 오류율 12.0% — Milesight 디코더"))
                .andExpect(jsonPath("$.responses[0].ref").value("script:" + scriptId));
        publish(EventType.INGEST_ALERT_CLEARED, new IngestAlert(IngestAlert.SCRIPT_ERROR_RATE, IngestAlert.Level.WARNING, null, 0.03, 0.1,
                List.of(), Instant.parse("2026-10-03T09:30:00Z"), scriptId, 3, 0.03, "5m", false));
        mvc.perform(as(org, operator, get("/core/annotations?" + RANGE + "&types=SCRIPT_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses[0].timeTo").value("2026-10-03T09:30:00Z"));
        publish(EventType.INGEST_ALERT_RAISED, new IngestAlert(IngestAlert.SCRIPT_ERROR_RATE, IngestAlert.Level.CRITICAL, null, 0.6, 0.5,
                List.of(), null, 999_999L, 1, null, "10m", true));
        mvc.perform(as(org, operator, get("/core/annotations?" + RANGE + "&types=SCRIPT_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].title").value("스크립트 오류율 60.0% — #999999 (자동 비활성)"));
    }

    @Test
    @DisplayName("[TSD-01.04][AT-TSD-11.2] 공간 범위 수동 주석 '창문 공사'가 같은 공간 다른 기기 차트에 표시, 종류(USER)·측정 항목 필터, 201 + Location — TC-TSD-018·019")
    void manualSpaceAnnotation() throws Exception {
        String id = create(operator, """
                {"timeFrom":"2026-10-03T10:00:00Z","timeTo":"2026-10-03T12:00:00Z","spaceId":"%d","title":"창문 공사"}""".formatted(roomA));
        mvc.perform(as(org, operator, json(post("/core/annotations"), """
                        {"timeFrom":"2026-10-03T08:00:00Z","deviceId":"%d","metricKey":"co2","title":"필터 교체"}""".formatted(deviceA))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/v1/core/annotations/")))
                .andExpect(jsonPath("$.response.type").value("USER"))
                .andExpect(jsonPath("$.response.createdBy").value(Long.toString(operator)));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA2 + "&" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(id))
                .andExpect(jsonPath("$.responses[0].type").value("USER"))
                .andExpect(jsonPath("$.responses[0].spaceId").value(Long.toString(roomA)))
                .andExpect(jsonPath("$.responses[0].deviceId").value(Matchers.nullValue()));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceB + "&" + RANGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        // 공간(층) 차트: 하위 공간 주석과 하위 기기 주석
        mvc.perform(as(org, operator, get("/core/annotations?spaceId=" + site + "&" + RANGE + "&types=USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA + "&" + RANGE + "&metric=temperature")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, get("/core/annotations?deviceId=" + deviceA + "&" + RANGE + "&types=OFFLINE,ALARM")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, operator, get("/core/annotations?" + RANGE + "&size=1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("[TSD-01.04] 요청 검증: from·to 필수·순서, 모르는 종류, 제목 150자, 끝이 시작보다 이름 → 400, VIEWER 작성 403 — TC-TSD-018")
    void validation() throws Exception {
        long viewer = fx.user(org, "view.user", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/annotations?" + RANGE))).andExpect(status().isOk());
        String[] badQueries = {"from=2026-10-03T00:00:00Z", "from=2026-10-04T00:00:00Z&to=2026-10-03T00:00:00Z", RANGE + "&types=PARTY",
                "from=어제&to=2026-10-03T00:00:00Z", RANGE + "&deviceId=abc"};
        for (String q : badQueries) {
            mvc.perform(as(org, viewer, get("/core/annotations?" + q))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        }
        String[] badBodies = {"{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"title\":\"\"}",
                "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"title\":\"" + "가".repeat(151) + "\"}",
                "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"timeTo\":\"2026-10-03T09:00:00Z\",\"title\":\"x\"}",
                "{\"title\":\"x\"}",
                "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"metricKey\":\"9bad\",\"title\":\"x\"}"};
        for (String b : badBodies) {
            mvc.perform(as(org, operator, json(post("/core/annotations"), b))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(org, viewer, json(post("/core/annotations"), "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"title\":\"x\"}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("[TSD-01.04][BR-TSD-23] 수동 주석은 작성자·ADMIN만 삭제(남의 것 403 ANNOTATION_FORBIDDEN), 시스템 주석은 삭제 불가, 없는 ID 404 — TC-TSD-018")
    void deleteRules() throws Exception {
        long other = fx.user(org, "lee.operator", "OPERATOR");
        String mine = create(operator, "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"spaceId\":\"" + roomA + "\",\"title\":\"내 메모\"}");
        String mine2 = create(operator, "{\"timeFrom\":\"2026-10-03T11:00:00Z\",\"title\":\"조직 메모\"}");
        mvc.perform(as(org, other, delete("/core/annotations/" + mine))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ANNOTATION_FORBIDDEN"))
                .andExpect(jsonPath("$.header.resultMessage").value("작성자나 관리자만 지울 수 있습니다"));
        mvc.perform(as(org, operator, delete("/core/annotations/" + mine))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete("/core/annotations/" + mine2))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete("/core/annotations/" + mine2))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ANNOTATION_NOT_FOUND"));
        publish(EventType.DEVICE_CONNECTIVITY_CHANGED,
                new DeviceConnectivityChanged(deviceA, null, Connectivity.OFFLINE, null, 600, 2.0));
        long system = jdbc.sql("SELECT id FROM data2flow_core.annotations WHERE organization_id = :o AND type = 'OFFLINE'")
                .param("o", org).query(Long.class).single();
        mvc.perform(as(org, admin, delete("/core/annotations/" + system))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ANNOTATION_FORBIDDEN"));
    }

    @Test
    @DisplayName("[TSD-01.04][IAM-04.06] 권한: 다른 조직 기기·공간 404, 권한 밖 공간 기기 404, 범위 제한 사용자는 범위 밖 기기 주석 제외·조직 전체 주석은 봄, 조직 전체 주석 작성 403 — TC-TSD-020")
    void scope() throws Exception {
        create(admin, "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"title\":\"정전 점검\"}");
        create(admin, "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"deviceId\":\"" + deviceB + "\",\"title\":\"B 교체\"}");
        create(admin, "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"deviceId\":\"" + deviceA + "\",\"title\":\"A 교체\"}");
        long scoped = fx.user(org, "park.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(roomA));
        mvc.perform(as(org, scoped, get("/core/annotations?" + RANGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, scoped, get("/core/annotations?deviceId=" + deviceB + "&" + RANGE))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/annotations?spaceId=" + roomB + "&" + RANGE))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, json(post("/core/annotations"), "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"deviceId\":\"" + deviceB + "\",\"title\":\"x\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, json(post("/core/annotations"), "{\"timeFrom\":\"2026-10-03T10:00:00Z\",\"title\":\"x\"}")))
                .andExpect(status().isForbidden());
        String bNote = jdbc.sql("SELECT id::text FROM data2flow_core.annotations WHERE organization_id = :o AND title = 'B 교체'")
                .param("o", org).query(String.class).single();
        mvc.perform(as(org, scoped, delete("/core/annotations/" + bNote))).andExpect(status().isNotFound());

        long otherOrg = fx.organization("other");
        long otherViewer = fx.user(otherOrg, "other.viewer", "VIEWER");
        mvc.perform(as(otherOrg, otherViewer, get("/core/annotations?deviceId=" + deviceA + "&" + RANGE))).andExpect(status().isNotFound());
        mvc.perform(as(otherOrg, otherViewer, get("/core/annotations?spaceId=" + roomA + "&" + RANGE))).andExpect(status().isNotFound());
        mvc.perform(as(otherOrg, otherViewer, get("/core/annotations?" + RANGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }
}
