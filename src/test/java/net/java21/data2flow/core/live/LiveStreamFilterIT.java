package net.java21.data2flow.core.live;

import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DevicePendingCreated;
import net.java21.data2flow.contracts.message.event.SourceConnectionChanged;
import net.java21.data2flow.contracts.message.event.SpaceChanged;
import net.java21.data2flow.contracts.connector.ConnectionErrorKind;
import net.java21.data2flow.contracts.connector.ConnectorState;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.live.service.LiveHub;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-05.01 실시간 구독(API-DSH-20·21): 토픽별 권한·범위 필터, 재검사, 세션 폐기, 홈 묶음, 수집 통계·메시지 */
class LiveStreamFilterIT extends IntegrationTestSupport {

    private static final Instant T0 = MutableClock.T0;

    @Autowired
    LiveHub hub;
    @Autowired
    MessageCodec codec;
    @Autowired
    RabbitTemplate rabbit;

    private LiveTestData live;
    private long org;
    private long admin;
    private long building;
    private long lab;
    private long classroom;
    private long source;
    private long labSensor;
    private long classSensor;

    @BeforeEach
    void setUp() {
        hub.closeAll();
        live = new LiveTestData(jdbc);
        org = fx.organization("live");
        admin = fx.user(org, "live.admin", "ADMIN");
        long site = data.site(org, "캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        lab = data.space(org, building, "ROOM", "실습실");
        classroom = data.space(org, building, "ROOM", "강의실");
        data.metric(org, "co2", "ppm");
        source = data.source(org, "campus-lns");
        labSensor = data.device(org, source, "a1", "ACTIVE", lab, null);
        classSensor = data.device(org, source, "b1", "ACTIVE", classroom, null);
        data.deviceState(org, labSensor, "ONLINE", T0, "{\"co2\":{\"v\":1150,\"t\":\"2026-10-03T00:00:00Z\",\"q\":0}}");
        live.target(org, site, "co2", null, 1000.0);
    }

    @AfterEach
    void tearDown() {
        hub.closeAll();
    }

    private static MockHttpServletRequestBuilder stream(String topics) {
        return get("/core/stream/live").param("topics", topics).accept(MediaType.TEXT_EVENT_STREAM);
    }

    private MvcResult open(long orgId, long userId, String topics) throws Exception {
        return mvc.perform(as(orgId, userId, stream(topics))).andExpect(request().asyncStarted()).andReturn();
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static int count(MvcResult r, String event) {
        Matcher m = Pattern.compile("(?m)^event:" + Pattern.quote(event) + "$").matcher(body(r));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static void awaitCount(MvcResult r, String event, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(r, event)).isEqualTo(expected));
    }

    private CanonicalTelemetry telemetry(long deviceId, Long spaceId, double co2) {
        return CanonicalTelemetry.builder().organizationId(org).sourceId(source).externalId("x" + deviceId).deviceId(deviceId)
                .spaceId(spaceId).measuredAt(T0.plusSeconds(1)).receivedAt(T0.plusSeconds(2))
                .metric(CanonicalTelemetry.Metric.of("co2", co2, "ppm")).rawMessageId(1).build();
    }

    @Test
    @DisplayName("[DSH-05.01] 연결 전 거부: 토픽 없음·형식 오류·200개 초과 400(JSON 본문), 권한 없는 토픽만이면 403")
    void rejectsBeforeStreaming() throws Exception {
        mvc.perform(as(org, admin, get("/core/stream/live").accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("topics"));
        mvc.perform(as(org, admin, stream("home,weather:1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("weather:1"));
        String many = IntStream.rangeClosed(1, 201).mapToObj(i -> "space:" + i).collect(Collectors.joining(","));
        mvc.perform(as(org, admin, stream(many))).andExpect(status().isBadRequest());
        long viewer = fx.user(org, "live.viewer", "VIEWER");
        mvc.perform(as(org, viewer, stream("ingest,ingest-messages")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        assertThat(hub.size()).isZero();
    }

    @Test
    @DisplayName("[DSH-03.03][TC-DSH-022] 수집 메시지 필터의 소스가 다른 조직이면 404 SOURCE_NOT_FOUND, 범위 밖 기기면 404")
    void messageFilterTargets() throws Exception {
        long other = fx.organization("other");
        long otherSource = data.source(other, "other-lns");
        mvc.perform(as(org, admin, stream("ingest-messages?sourceId=" + otherSource)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
        long scoped = fx.user(org, "live.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, stream("ingest-messages?deviceId=" + classSensor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("RESOURCE_NOT_FOUND"));
        MvcResult ok = open(org, scoped, "ingest-messages?sourceId=" + source + "&deviceId=" + labSensor);
        awaitCount(ok, "ready", 1);
    }

    @Test
    @DisplayName("[DSH-05.01][AT-DSH-01.3] 공간 범위 필터: 범위 밖 기기 토픽은 이벤트 0건, 범위 안은 전달, 권한이 줄면 재검사부터 중단 — TC-DSH-051")
    void scopeFilterAndRecheck() throws Exception {
        long scoped = fx.user(org, "live.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        MvcResult r = open(org, scoped, "space:" + building + ",telemetry:" + labSensor + ".co2,telemetry:" + classSensor + ".co2,"
                + "notifications");
        awaitCount(r, "ready", 1);
        assertThat(body(r)).contains("\"accepted\":[\"space:" + building + "\",\"telemetry:" + labSensor + ".co2\",\"notifications\"]")
                .contains("\"rejected\":[\"telemetry:" + classSensor + ".co2\"]");

        hub.onTelemetry(telemetry(classSensor, classroom, 900)); // 범위 밖: 아무것도 오지 않는다
        hub.onTelemetry(telemetry(labSensor, lab, 1180));
        awaitCount(r, "point", 1);
        awaitCount(r, "device-update", 1);
        assertThat(body(r)).contains("\"deviceId\":\"" + labSensor + "\",\"metricKey\":\"co2\",\"t\":\"2026-10-03T00:00:01Z\",\"v\":1180.0")
                .contains("\"metrics\":[{\"key\":\"co2\",\"value\":1180.0,\"unit\":\"ppm\",\"quality\":0,\"at\":\"2026-10-03T00:00:01Z\"}]")
                .contains("\"connection\":\"ONLINE\",\"state\":\"ACTIVE\"")
                .doesNotContain("\"deviceId\":\"" + classSensor + "\"");

        // 강의실로 범위가 바뀌면 재검사(60초) 뒤로는 실습실 값이 오지 않는다
        data.spaceScope(org, scoped, List.of(classroom));
        hub.recheckAll();
        hub.onTelemetry(telemetry(labSensor, lab, 1190));
        hub.onTelemetry(telemetry(classSensor, classroom, 950));
        awaitCount(r, "device-update", 2);
        awaitCount(r, "point", 2); // 이제 강의실 기기 토픽이 받아들여진다
        assertThat(body(r)).contains("\"deviceId\":\"" + classSensor + "\",\"metricKey\":\"co2\"").doesNotContain("1190.0");

        // 다른 조직의 값은 어떤 연결에도 가지 않는다
        long other = fx.organization("other");
        hub.onTelemetry(CanonicalTelemetry.builder().organizationId(other).sourceId(1).externalId("z").deviceId(classSensor)
                .spaceId(classroom).measuredAt(T0).receivedAt(T0).metric(CanonicalTelemetry.Metric.of("co2", 1, null)).rawMessageId(1).build());
        hub.onTelemetry(telemetry(classSensor, classroom, 960));
        awaitCount(r, "device-update", 3);
        assertThat(body(r)).doesNotContain("\"value\":1.0");
    }

    @Test
    @DisplayName("[DSH-05.01][IAM-07.06] 15초 ping, 세션이 폐기되거나 계정이 비활성이면 session-revoked를 보내고 닫는다")
    void sessionRevoked() throws Exception {
        UUID sid = live.session(org, admin, T0);
        MvcResult r = mvc.perform(as(org, admin, stream("notifications")).header("X-SESSION-ID", sid.toString())
                        .header("Last-Event-ID", "42"))
                .andExpect(request().asyncStarted()).andReturn();
        awaitCount(r, "ready", 1);
        hub.pingAll();
        awaitCount(r, "ping", 1);
        live.revokeSession(sid, T0);
        hub.pingAll();
        awaitCount(r, "session-revoked", 1);
        assertThat(body(r)).contains("SESSION_REVOKED");
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.size() == 0);

        long operator = fx.user(org, "live.operator", "OPERATOR");
        MvcResult second = open(org, operator, "home");
        awaitCount(second, "home-summary", 1);
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'DISABLED' WHERE id = :id").param("id", operator).update();
        hub.pingAll();
        awaitCount(second, "session-revoked", 1);
        assertThat(body(second)).contains("USER_INACTIVE");
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.size() == 0);
    }

    @Test
    @DisplayName("[DSH-01.01][AT-DSH-01.3] home: 연결 직후 전체 요약, 이후 5초 묶음에는 바뀐 필드만 보낸다")
    void homeBatches() throws Exception {
        MvcResult r = open(org, admin, "home");
        awaitCount(r, "home-summary", 1);
        assertThat(body(r)).contains("\"comfortTotal\":2").contains("\"state\":\"WARNING\"").contains("\"offlineDevices\":0");
        hub.homeTick(); // 바뀐 것이 없으면 보내지 않는다(조직 표시도 없음)
        data.deviceState(org, labSensor, "ONLINE", T0, "{\"co2\":{\"v\":800,\"t\":\"2026-10-03T00:00:05Z\",\"q\":0}}");
        hub.onEvent(DomainEvent.of(EventType.DEVICE_PENDING_CREATED, org,
                new DevicePendingCreated(99, source, "new", "new", T0), null, clock));
        hub.homeTick();
        awaitCount(r, "home-summary", 2);
        String last = body(r).substring(body(r).lastIndexOf("event:home-summary"));
        assertThat(last).contains("\"comfort\"").contains("\"NORMAL\"").doesNotContain("offlineDevices").doesNotContain("comfortTotal");
        hub.onTelemetry(telemetry(labSensor, lab, 800));
        hub.homeTick();
        hub.onTelemetry(telemetry(labSensor, lab, 800));
        hub.ingestTick(); // home만 구독 → ingest-stats 없음
        hub.homeTick();
        hub.pingAll();
        awaitCount(r, "ping", 1);
        assertThat(count(r, "home-summary")).isEqualTo(2);
        assertThat(count(r, "ingest-stats")).isZero();
    }

    @Test
    @DisplayName("[DSH-03.01][DSH-05.01] ingest: 5초마다 단계·소스 통계(ingest-stats), PUBLISH 단계 — TC-DSH-020")
    void ingestStats() throws Exception {
        for (int i = 1; i <= 5; i++) {
            live.stat(org, source, T0.minus(Duration.ofMinutes(i)), 60, 12);
        }
        long operator = fx.user(org, "live.operator", "OPERATOR");
        MvcResult r = open(org, operator, "ingest,home");
        awaitCount(r, "ready", 1);
        hub.ingestTick();
        awaitCount(r, "ingest-stats", 1);
        assertThat(body(r)).contains("{\"key\":\"SCRIPT\",\"inPerMin\":60.0,\"failPerMin\":12.0")
                .contains("\"key\":\"PUBLISH\"")
                .contains("\"sources\":[{\"id\":\"" + source + "\",\"name\":\"소스 campus-lns\",\"state\":\"DISCONNECTED\",\"perMin\":60.0");
    }

    @Test
    @DisplayName("[DSH-03.03][API-DSH-21] ingest-messages: 필터·범위, 원본은 INGEST_PAYLOAD_READ만, 초당 20건 상한 — TC-DSH-027")
    void ingestMessages() throws Exception {
        long operator = fx.user(org, "live.operator", "OPERATOR");
        long scoped = fx.user(org, "live.scoped", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        MvcResult withPayload = open(org, admin, "ingest-messages?sourceId=" + source);
        MvcResult noPayload = open(org, operator, "ingest-messages?result=script_error");
        MvcResult inScope = open(org, scoped, "ingest-messages");
        awaitCount(inScope, "ready", 1);
        live.raw(org, source, labSensor, "application/1/device/a1/event/up", "{\"data\":\"AQI=\"}", "SCRIPT_ERROR",
                T0.plusSeconds(1), "{\"canonical\":{\"metrics\":{\"co2\":1150}}}");
        live.raw(org, source, null, "application/1/device/zz/event/up", "{\"x\":1}", "UNKNOWN_DEVICE_REJECTED", T0.plusSeconds(1), null);
        live.raw(org, source, classSensor, "application/1/device/b1/event/up", new byte[]{1, 2, 3}, "BINARY", "OK",
                T0.plusSeconds(1), null);
        live.raw(org, source, labSensor, "late", "{}", "OK", T0.plusSeconds(9), null); // 아직 2초가 지나지 않음
        clock.set(T0.plusSeconds(10));
        hub.pollMessages();
        awaitCount(withPayload, "message", 3);
        awaitCount(noPayload, "message", 1);
        awaitCount(inScope, "message", 1);
        assertThat(body(withPayload)).contains("\"raw\":\"{\\\"data\\\":\\\"AQI=\\\"}\",\"rawEncoding\":\"TEXT\",\"rawTruncated\":false")
                .contains("\"canonical\":{\"metrics\":{\"co2\":1150}}")
                .contains("\"raw\":\"AQID\",\"rawEncoding\":\"BASE64\"")
                .contains("\"result\":\"UNKNOWN_DEVICE_REJECTED\"")
                .doesNotContain("\"topic\":\"late\"");
        assertThat(body(noPayload)).contains("\"result\":\"SCRIPT_ERROR\"").doesNotContain("\"raw\"");
        assertThat(body(inScope)).contains("\"deviceId\":\"" + labSensor + "\"").doesNotContain("UNKNOWN_DEVICE_REJECTED");

        clock.set(T0.plusSeconds(12));
        hub.pollMessages();
        awaitCount(withPayload, "message", 4);
        assertThat(body(withPayload)).contains("\"topic\":\"late\"");

        // 초당 20건 상한(서버 측 표본)
        for (int i = 0; i < 25; i++) {
            live.raw(org, source, labSensor, "burst", "{}", "OK", T0.plusSeconds(13), null);
        }
        clock.set(T0.plusSeconds(20));
        hub.pollMessages();
        awaitCount(withPayload, "message", 24);
        hub.pingAll();
        awaitCount(withPayload, "ping", 1);
        assertThat(count(withPayload, "message")).isEqualTo(24);
    }

    @Test
    @DisplayName("[DSH-05.01] 도메인 이벤트(파드별 임시 큐 data2flow.events): 연결 상태·기기 상태 변경이 space 토픽으로 간다")
    void domainEvents() throws Exception {
        MvcResult r = open(org, admin, "space:" + lab);
        awaitCount(r, "ready", 1);
        hub.onEvent(DomainEvent.of(EventType.DEVICE_CHANGED, org, new DeviceChanged(labSensor, DeviceChanged.Change.DEACTIVATED,
                null, "INACTIVE", null, lab, 2), null, clock));
        awaitCount(r, "device-update", 1);
        assertThat(body(r)).contains("{\"deviceId\":\"" + labSensor + "\",\"state\":\"INACTIVE\"}");
        hub.onEvent(DomainEvent.of(EventType.SPACE_CHANGED, org, new SpaceChanged(lab, "UPDATED", "/1/2/3"), null, clock));
        hub.onEvent(DomainEvent.of(EventType.DEVICE_CHANGED, org, new DeviceChanged(classSensor, DeviceChanged.Change.UPDATED,
                List.of("name"), "ACTIVE", null, classroom, 2), null, clock));

        // 실제 RabbitMQ 경로: data2flow.events에 발행하면 파드 임시 큐로 받는다
        DomainEvent<DeviceConnectivityChanged> offline = DomainEvent.of(EventType.DEVICE_CONNECTIVITY_CHANGED, org,
                new DeviceConnectivityChanged(labSensor, DeviceConnectivityChanged.Connectivity.ONLINE,
                        DeviceConnectivityChanged.Connectivity.OFFLINE, T0, 600, 3.0), null, clock);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            if (count(r, "device-update") < 2) {
                rabbit.convertAndSend(MessagingNames.EXCHANGE_EVENTS, offline.type(), codec.write(offline));
            }
            assertThat(body(r)).contains("\"connection\":\"OFFLINE\"");
        });
        assertThat(body(r)).doesNotContain("\"deviceId\":\"" + classSensor + "\"");
    }

    @Test
    @DisplayName("[DSC-02.01][AT-DSC-02.1] sources 토픽: 소스 연결 상태가 바뀌면 source-state가 바로 간다(SRC_READ만, 같은 조직만, VIEWER는 rejected) — 5초 이내 화면 반영")
    void sourceState() throws Exception {
        MvcResult r = open(org, admin, "sources");
        awaitCount(r, "ready", 1);
        assertThat(body(r)).contains("\"accepted\":[\"sources\"]");
        long viewer = fx.user(org, "live.viewer.src", "VIEWER");
        MvcResult v = open(org, viewer, "home,sources");
        awaitCount(v, "ready", 1);
        assertThat(body(v)).contains("\"rejected\":[\"sources\"]");
        long other = fx.organization("live-src2");
        MvcResult o = open(other, fx.user(other, "live2.admin", "ADMIN"), "sources");
        awaitCount(o, "ready", 1);

        hub.onEvent(DomainEvent.of(EventType.SOURCE_CONNECTION_CHANGED, org, new SourceConnectionChanged(source,
                ConnectorState.CONNECTED, ConnectorState.ERROR, ConnectionErrorKind.AUTH, T0.plusSeconds(5)), null, clock));
        awaitCount(r, "source-state", 1);
        assertThat(body(r)).contains("{\"sourceId\":\"" + source + "\",\"state\":\"ERROR\",\"previousState\":\"CONNECTED\","
                + "\"errorKind\":\"AUTH\",\"at\":\"2026-10-03T00:00:05Z\"}");

        // 실제 RabbitMQ 경로(data2flow.events → 파드 임시 큐): 복구 이벤트는 errorKind 없이
        DomainEvent<SourceConnectionChanged> up = DomainEvent.of(EventType.SOURCE_CONNECTION_CHANGED, org,
                new SourceConnectionChanged(source, ConnectorState.ERROR, ConnectorState.CONNECTED, null, T0.plusSeconds(9)), null, clock);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            if (count(r, "source-state") < 2) {
                rabbit.convertAndSend(MessagingNames.EXCHANGE_EVENTS, up.type(), codec.write(up));
            }
            assertThat(body(r)).contains("\"state\":\"CONNECTED\",\"previousState\":\"ERROR\",\"at\"");
        });
        assertThat(count(v, "source-state")).isZero();
        assertThat(count(o, "source-state")).isZero();
    }
}
