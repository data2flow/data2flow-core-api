package net.java21.data2flow.core.notify;

import net.java21.data2flow.contracts.message.ActionRequest;
import net.java21.data2flow.contracts.notification.NotificationRequest;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationIT extends AlarmItSupport {

    String telegramChannel() throws Exception {
        MvcResult r = mvc.perform(as(org, admin, json(post("/core/notification-channels"), """
                        {"name":"시설팀 텔레그램","type":"TELEGRAM","config":{"chatIds":["-100123"],"botUsername":"d2f_bot"},
                         "secret":{"botToken":"123:ABC","webhookSecret":"s3cr3t"}}""")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.secret").value("***"))
                .andExpect(jsonPath("$.response.secretConfigured").value(true)).andReturn();
        return read(r, "$.response.id");
    }

    String policy(String channels, String extra) throws Exception {
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/notification-policies"), """
                        {"name":"MAJOR 이상","minSeverity":"MAJOR","spaceId":"%d","includeChildren":true,
                         "recipients":[{"type":"USER","id":"%d"},{"type":"ON_CALL"},{"type":"ROLE","id":"admin"}],
                         "channels":%s,"renotifyMinutes":30,"aggregateWindowSec":60%s}""".formatted(building, operator, channels, extra))))
                .andExpect(status().isCreated()).andReturn();
        return read(r, "$.response.notificationPolicyId");
    }

    @Test
    @DisplayName("[OPS-06.01][TC-OPS-058][TC-OPS-060][TC-OPS-059] 채널 등록: 비밀값 ***, 등록 안 된 유형 400, 정책이 쓰는 마지막 채널 삭제 409 CHANNEL_IN_USE, 내부 API-OPS-35는 복호화")
    void channels() throws Exception {
        String id = telegramChannel();
        mvc.perform(as(org, admin, get("/core/notification-channels"))).andExpect(jsonPath("$.responses[0].secret").value("***"))
                .andExpect(jsonPath("$.responses[0].config.chatIds[0]").value("-100123"));
        mvc.perform(as(org, admin, json(post("/core/notification-channels"), "{\"name\":\"sms\",\"type\":\"SMS\",\"config\":{}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, operator, get("/core/notification-channels"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, get("/core/notification-channel-types"))).andExpect(jsonPath("$.responses[0].key").value("TELEGRAM"))
                .andExpect(jsonPath("$.responses[0].available").value(true)).andExpect(jsonPath("$.responses[1].available").value(false));
        mvc.perform(get("/internal/core/notification-channels").param("organizationId", Long.toString(org)).param("type", "TELEGRAM"))
                .andExpect(jsonPath("$.responses[0].channelId").value(id))
                .andExpect(jsonPath("$.responses[0].secrets.botToken").value("123:ABC"));
        assertThat(STUB.received("POST", "/internal/action/notifications/channels/" + id + "/webhook")).hasSize(1);
        policy("[\"WEB\",\"TELEGRAM\"]", "");
        mvc.perform(as(org, admin, delete("/core/notification-channels/" + id))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("CHANNEL_IN_USE"));
        mvc.perform(as(org, admin, get("/core/notification-channels/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CHANNEL_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("알림 채널을 찾을 수 없습니다"));
        mvc.perform(as(org, admin, json(put("/core/notification-channels/" + id), """
                        {"name":"시설팀","config":{"chatIds":["-100999"]},"rateLimitPerMin":30,"baseVersion":1}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2));
    }

    @Test
    @DisplayName("[OPS-06.01][TC-OPS-057] 테스트 발송 실패(401) → 502 CHANNEL_TEST_FAILED, 성공이면 지연 시간")
    void channelTest() throws Exception {
        String id = telegramChannel();
        STUB.ok("POST", "/internal/action/notifications/channels/test", 200, "{\"ok\":false,\"providerResponse\":\"401 Unauthorized\"}");
        mvc.perform(as(org, admin, post("/core/notification-channels/" + id + "/test"))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.resultCode").value("CHANNEL_TEST_FAILED"))
                .andExpect(jsonPath("$.header.resultMessage").value("테스트 발송에 실패했습니다: 401 Unauthorized"));
        assertThat(STUB.received("POST", "/internal/action/notifications/channels/test").getFirst().body()).contains("\"botToken\":\"123:ABC\"");
        STUB.reset();
        STUB.ok("POST", "/internal/action/notifications/channels/test", 200, "{\"ok\":true,\"latencyMs\":800}");
        mvc.perform(as(org, admin, json(post("/core/notification-channels/test-draft"), """
                        {"name":"x","type":"TELEGRAM","config":{"chatIds":["1"]},"secret":{"botToken":"t"}}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.latencyMs").value(800));
    }

    @Test
    @DisplayName("[RUL-03.02][TC-RUL-073][TC-RUL-101] 정책: 설정 안 된 채널 409 CHANNEL_NOT_CONFIGURED, MAJOR 알람 → 알림 요청(수신자 합침·중복 제거, policyId, 묶기 창), MINOR는 요청 없음")
    void policiesAndRequests() throws Exception {
        mvc.perform(as(org, operator, json(post("/core/notification-policies"), """
                        {"name":"p","minSeverity":"MAJOR","recipients":[{"type":"USER","id":"%d"}],"channels":["TELEGRAM"]}""".formatted(operator))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CHANNEL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.header.resultMessage").value("TELEGRAM이 설정되지 않았습니다"));
        telegramChannel();
        String policyId = policy("[\"WEB\",\"TELEGRAM\"]", ",\"steps\":[{\"stepNo\":1,\"waitMinutes\":10,\"recipients\":[{\"type\":\"USER\",\"id\":\""
                + admin + "\"}]}]");
        mvc.perform(as(org, operator, json(post("/core/notification-policies"), """
                        {"name":"전체 정보","minSeverity":"INFO","recipients":[{"type":"USER","id":"%d"}],"channels":["WEB"]}""".formatted(operator))))
                .andExpect(status().isCreated());
        raise(sensor1, 1100, clock.instant());
        List<String> requests = notifyRequests();
        assertThat(requests).hasSize(1);
        ActionRequest req = codec.read(requests.getFirst().getBytes(StandardCharsets.UTF_8), ActionRequest.class);
        NotificationRequest n = req.notificationRequest();
        assertThat(n.event()).isEqualTo("alarm.raised");
        assertThat(n.policyId()).isEqualTo(Long.parseLong(policyId));
        assertThat(n.aggregateWindowSec()).isEqualTo(60);
        assertThat(n.recipients()).extracting(r -> r.recipientKey() + "|" + r.channel())
                .containsExactlyInAnyOrder("USER:" + operator + "|WEB", "USER:" + operator + "|TELEGRAM", "ON_CALL|WEB", "ON_CALL|TELEGRAM",
                        "ROLE:ADMIN|WEB", "ROLE:ADMIN|TELEGRAM");
        assertThat(n.link()).endsWith("/alarms/" + alarmId(sensor1));
        assertThat(n.variables()).containsEntry("device.name", "기기 co2-1").containsEntry("space.path", "캠퍼스 / 본관 / 실습실");
        // 재발생은 재알림 간격(30분) 안이면 요청하지 않는다
        raise(sensor1, 1200, clock.instant().plusSeconds(60));
        assertThat(notifyRequests()).hasSize(1);
        clock.advance(Duration.ofMinutes(31));
        raise(sensor1, 1300, clock.instant());
        assertThat(notifyRequests()).hasSize(2);
        // 해제 알림(notify_on_clear)
        clear(sensor1, 800, clock.instant());
        assertThat(notifyRequests()).hasSize(3).last().asString().contains("alarm.cleared");
        // MINOR 알람은 MAJOR 정책에 맞지 않고 INFO 정책(WEB)만
        raise(net.java21.data2flow.contracts.alarm.AlarmKeys.system("X", "1"), net.java21.data2flow.contracts.alarm.AlarmSourceType.SYSTEM, null,
                net.java21.data2flow.contracts.alarm.AlarmSeverity.MINOR, null, lab, 1, clock.instant());
        assertThat(notifyRequests().getLast()).doesNotContain("TELEGRAM");
        mvc.perform(get("/internal/core/notification-policies/" + policyId)).andExpect(jsonPath("$.response.organizationId").value(org))
                .andExpect(jsonPath("$.response.steps[0].waitMinutes").value(10));
        mvc.perform(as(org, analyst, get("/core/notification-policies"))).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].stepCount").exists());
        mvc.perform(as(org, operator, json(put("/core/notification-policies/" + policyId), """
                        {"name":"MAJOR 이상","minSeverity":"CRITICAL","recipients":[{"type":"ON_CALL"}],"channels":["WEB"],"baseVersion":0}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.minSeverity").value("CRITICAL"));
        mvc.perform(as(org, operator, delete("/core/notification-policies/" + policyId))).andExpect(status().isNoContent());
        mvc.perform(as(org, operator, get("/core/notification-policies/" + policyId))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POLICY_NOT_FOUND"));
    }

    @Test
    @DisplayName("[RUL-02.05][TC-RUL-055] 억제된 알람(유지보수)은 알림을 요청하지 않는다(BR-RUL-08)")
    void suppressedNotNotified() throws Exception {
        policy("[\"WEB\"]", "");
        mvc.perform(as(org, operator, json(post("/core/maintenance-windows"),
                "{\"targetType\":\"SPACE\",\"targetId\":\"" + lab + "\",\"reason\":\"점검\"}"))).andExpect(status().isCreated());
        raise(sensor1, 1100, clock.instant());
        assertThat(alarmStatus(alarmId(sensor1))).isEqualTo("SUPPRESSED");
        assertThat(notifyRequests()).isEmpty();
    }

    @Test
    @DisplayName("[RUL-05.01][TC-RUL-091][TC-RUL-092] 템플릿: 기본 4개 언어, 알 수 없는 변수 {{foo}}는 200 + TEMPLATE_VARIABLE_UNKNOWN 경고, 미리 보기·되돌리기, 내부 resolve")
    void templates() throws Exception {
        MvcResult list = mvc.perform(as(org, analyst, get("/core/notification-templates").param("channel", "TELEGRAM").param("locale", "ko")))
                .andExpect(jsonPath("$.totalCount").value(4)).andReturn();
        String id = read(list, "$.responses[?(@.key == 'alarm.raised.default')].notificationTemplateId").toString().replaceAll("[\\[\\]\"]", "");
        MvcResult saved = mvc.perform(as(org, operator, json(put("/core/notification-templates/" + id), "{\"body\":\"{{device.name}} {{foo}}\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("TEMPLATE_VARIABLE_UNKNOWN"))
                .andExpect(jsonPath("$.header.resultMessage").value("알 수 없는 변수: foo"))
                .andExpect(jsonPath("$.response.template.customized").value(true)).andReturn();
        id = read(saved, "$.response.template.notificationTemplateId");
        raise(sensor1, 1100, clock.instant());
        mvc.perform(as(org, operator, json(post("/core/notification-templates/" + id + "/preview"), "{\"alarmId\":\"" + alarmId(sensor1) + "\"}")))
                .andExpect(jsonPath("$.response.body").value("기기 co2-1 "));
        mvc.perform(get("/internal/core/notification-templates/resolve").param("organizationId", Long.toString(org))
                        .param("key", "alarm.raised.default").param("channel", "TELEGRAM").param("locale", "ja"))
                .andExpect(jsonPath("$.response.body").value(containsString("値")));
        mvc.perform(get("/internal/core/notification-templates/resolve").param("organizationId", Long.toString(org))
                        .param("key", "alarm.raised.default").param("channel", "TELEGRAM").param("locale", "ko"))
                .andExpect(jsonPath("$.response.body").value("{{device.name}} {{foo}}"));
        mvc.perform(as(org, operator, post("/core/notification-templates/" + id + "/reset"))).andExpect(jsonPath("$.response.builtin").value(true));
        mvc.perform(as(org, analyst, get("/core/notification-templates/variables"))).andExpect(jsonPath("$.totalCount").value(14));
        mvc.perform(get("/internal/core/notification-templates/resolve").param("organizationId", Long.toString(org)).param("key", "nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[RUL-02.07][TC-RUL-064] 무음: 일회·반복 생성, 끝이 시작보다 이르면 400 SILENCE_RANGE_INVALID, 내부 API-RUL-43(반복은 daysOfWeek·timezone)")
    void silences() throws Exception {
        String start = clock.instant().toString();
        mvc.perform(as(org, operator, json(post("/core/silences"), """
                        {"kind":"ONE_TIME","target":{"type":"SPACE","id":"%d"},"startsAt":"%s","endsAt":"%s","reason":"공사"}"""
                        .formatted(lab, start, clock.instant().plusSeconds(3600)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.target.name").value("실습실"));
        mvc.perform(as(org, operator, json(post("/core/silences"), """
                        {"kind":"RECURRING","target":{"type":"RULE","id":"1"},"recurrence":{"days":[7]}}"""))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, json(post("/core/silences"), """
                        {"kind":"RECURRING","target":{"type":"DEVICE","id":"%d"},"recurrence":{"days":[7],"from":"00:00","to":"23:59"}}"""
                        .formatted(sensor1)))).andExpect(status().isCreated());
        mvc.perform(as(org, operator, json(post("/core/silences"), """
                        {"kind":"RECURRING","target":{"type":"DEVICE","id":"%d"},"recurrence":{"dateFrom":"2026-12-20","dateTo":"2027-02-28"}}"""
                        .formatted(sensor1)))).andExpect(status().isCreated());
        mvc.perform(as(org, operator, json(post("/core/silences"), """
                        {"kind":"ONE_TIME","target":{"type":"SPACE","id":"%d"},"startsAt":"%s","endsAt":"%s"}"""
                        .formatted(lab, start, clock.instant().minusSeconds(60)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SILENCE_RANGE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("무음 기간이 올바르지 않습니다"));
        mvc.perform(get("/internal/core/silences").param("organizationId", Long.toString(org)))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.responses[?(@.recurrence.daysOfWeek)].recurrence.daysOfWeek[0]").value(7))
                .andExpect(jsonPath("$.responses[?(@.recurrence.daysOfWeek)].recurrence.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.responses[?(@.kind == 'ONE_TIME')].target.id").value(Long.toString(lab)));
        MvcResult r = mvc.perform(as(org, operator, get("/core/silences"))).andExpect(jsonPath("$.totalCount").value(3)).andReturn();
        mvc.perform(as(org, operator, delete("/core/silences/" + read(r, "$.responses[0].silenceId")))).andExpect(status().isNoContent());
        mvc.perform(as(org, viewer, get("/core/silences"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[RUL-05.03][TC-RUL-098] 당직표 저장·대리 근무·지금 당직자, 내부 API-RUL-44")
    void onCall() throws Exception {
        mvc.perform(as(org, operator, json(put("/core/on-call"), """
                        {"name":"시설팀 당직","timezone":"Asia/Seoul","shifts":[{"dayOfWeek":6,"from":"00:00","to":"23:59","userId":"%d"}],
                         "baseVersion":0}""".formatted(operator)))).andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1));
        // T0 = 2026-10-03(토) 09:00 서울
        mvc.perform(as(org, viewer, get("/core/on-call/current"))).andExpect(jsonPath("$.response.userId").value(Long.toString(operator)));
        mvc.perform(as(org, operator, json(post("/core/on-call/overrides"), """
                        {"startsAt":"%s","endsAt":"%s","originalUserId":"%d","substituteUserId":"%d"}"""
                        .formatted(clock.instant().minusSeconds(60), clock.instant().plusSeconds(3600), operator, admin))))
                .andExpect(status().isCreated());
        mvc.perform(as(org, viewer, get("/core/on-call/current"))).andExpect(jsonPath("$.response.userId").value(Long.toString(admin)))
                .andExpect(jsonPath("$.response.substitute").value(true));
        mvc.perform(get("/internal/core/on-call").param("organizationId", Long.toString(org)))
                .andExpect(jsonPath("$.response.shifts[0].dayOfWeek").value(6))
                .andExpect(jsonPath("$.response.overrides[0].substituteUserId").value(Long.toString(admin)));
        mvc.perform(as(org, operator, json(put("/core/on-call"), "{\"shifts\":[],\"baseVersion\":0}"))).andExpect(status().isConflict());
        mvc.perform(as(org, viewer, json(put("/core/on-call"), "{\"shifts\":[],\"baseVersion\":1}"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[OPS-06.05][TC-OPS-068][RUL-05.02][TC-RUL-096][TC-RUL-097] 수신 설정 저장·내부 수신자 펼치기, 메신저 연결 코드 확정 → 확인·30분 무음, 연결 안 된 계정 404")
    void preferencesAndMessenger() throws Exception {
        telegramChannel();
        mvc.perform(as(org, operator, get("/core/accounts/me/notify-preferences"))).andExpect(jsonPath("$.response.minSeverity").value("INFO"));
        mvc.perform(as(org, operator, json(put("/core/accounts/me/notify-preferences"),
                        "{\"channels\":[\"TELEGRAM\"],\"minSeverity\":\"MAJOR\",\"dndFrom\":\"22:00\",\"dndTo\":\"07:00\",\"dndAllowCritical\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.dndFrom").value("22:00")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, operator, json(put("/core/accounts/me/notify-preferences"), "{\"dndFrom\":\"22:00\",\"dndTo\":null}")))
                .andExpect(status().isBadRequest());
        STUB.ok("POST", "/internal/action/notifications/links", 200, "{\"deepLink\":\"https://t.me/d2f_bot?start=X\"}");
        MvcResult start = mvc.perform(as(org, operator, json(post("/core/accounts/me/messenger-links/start"), "{\"channel\":\"TELEGRAM\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.deepLink").value("https://t.me/d2f_bot?start=X")).andReturn();
        String code = read(start, "$.response.code");
        mvc.perform(json(post("/internal/core/messenger-links/confirm"), "{\"channel\":\"TELEGRAM\",\"code\":\"" + code + "\",\"externalUserId\":\"777000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.userId").value(Long.toString(operator)));
        mvc.perform(json(post("/internal/core/messenger-links/confirm"), "{\"channel\":\"TELEGRAM\",\"code\":\"" + code + "\",\"externalUserId\":\"777000\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/internal/core/messenger-links/confirm"), "{\"channel\":\"TELEGRAM\",\"code\":\"NOPE\",\"externalUserId\":\"1\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/core/messenger-links").param("channel", "TELEGRAM").param("externalUserId", "777000"))
                .andExpect(jsonPath("$.response.organizationId").value(org));
        mvc.perform(get("/internal/core/messenger-links").param("channel", "TELEGRAM").param("externalUserId", "1")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/core/organizations/" + org + "/notify-recipients").param("userIds", Long.toString(operator)).param("roles", "ADMIN"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[?(@.userId == '" + operator + "')].links.TELEGRAM").value("777000"))
                .andExpect(jsonPath("$.responses[?(@.userId == '" + operator + "')].dnd.from").value("22:00"))
                .andExpect(jsonPath("$.responses[?(@.userId == '" + operator + "')].minSeverity").value("MAJOR"));
        mvc.perform(as(org, operator, get("/core/accounts/me/notify-preferences"))).andExpect(jsonPath("$.response.links[0].externalUserId").value("****7000"));
        raise(sensor1, 1100, clock.instant());
        long alarm = alarmId(sensor1);
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/ack"), "{\"via\":\"MESSENGER\"}").header("X-USER-ID", Long.toString(operator))
                .header("X-ORG-ID", Long.toString(org))).andExpect(status().isOk()).andExpect(jsonPath("$.response.alreadyAcked").value(false));
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/ack"), "{}").header("X-USER-ID", Long.toString(operator))
                .header("X-ORG-ID", Long.toString(org))).andExpect(jsonPath("$.response.alreadyAcked").value(true));
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/mute"), "{\"minutes\":30,\"via\":\"MESSENGER\"}").header("X-USER-ID",
                        Long.toString(operator)).header("X-ORG-ID", Long.toString(org)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.silenceId").exists());
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/ack"), "{}").header("X-USER-ID", Long.toString(viewer))
                .header("X-ORG-ID", Long.toString(org))).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.alarm_events WHERE alarm_id = :id AND type = 'SILENCED' AND actor_type = 'MESSENGER'")
                .param("id", alarm).query(Long.class).single()).isEqualTo(1);
        mvc.perform(as(org, operator, delete("/core/accounts/me/messenger-links/TELEGRAM"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[RUL-03.03][TC-RUL-074] 내부 API-RUL-41 알람(space.pathIds)·API-RUL-50 에스컬레이션 기록 202, 발송 결과 EVT-RUL-04는 타임라인 NOTIFIED 한 번")
    void internalAlarm() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long alarm = alarmId(sensor1);
        mvc.perform(get("/internal/core/alarms/" + alarm)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.space.pathIds.length()").value(3))
                .andExpect(jsonPath("$.response.space.pathIds[2]").value(lab))
                .andExpect(jsonPath("$.response.organizationId").value(org));
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/events"), "{\"type\":\"ESCALATED\",\"data\":{\"stepNo\":1}}"))
                .andExpect(status().isAccepted());
        mvc.perform(json(post("/internal/core/alarms/" + alarm + "/events"), "{\"type\":\"RAISED\"}")).andExpect(status().isBadRequest());
        var result = new net.java21.data2flow.contracts.message.event.NotificationDeliveryResult(java.util.UUID.randomUUID(), alarm, null,
                "TELEGRAM", "USER:" + operator, net.java21.data2flow.contracts.notification.DeliveryStatus.SENT, 1, null, clock.instant());
        deliver(net.java21.data2flow.contracts.message.EventType.NOTIFICATION_DELIVERED, org, result, clock.instant());
        deliver(net.java21.data2flow.contracts.message.EventType.NOTIFICATION_DELIVERED, org, result, clock.instant());
        mvc.perform(as(org, operator, get("/core/alarms/" + alarm))).andExpect(jsonPath("$.response.events[1].type").value("ESCALATED"))
                .andExpect(jsonPath("$.response.events[2].type").value("NOTIFIED")).andExpect(jsonPath("$.response.events.length()").value(3));
        mvc.perform(get("/internal/core/alarms/999999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[RUL-03.05][TC-RUL-079] 발송 이력·재발송은 action 내부 API 중계(커서 목록 그대로)")
    void deliveries() throws Exception {
        STUB.on("GET", "/internal/action/notifications/deliveries", r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(200,
                "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"size\":20,\"responses\":[],"
                        + "\"nextCursor\":null}", java.util.Map.of()));
        mvc.perform(as(org, admin, get("/core/notification-deliveries").param("channelId", "3"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
        STUB.ok("POST", "/internal/action/notifications/deliveries/abc/resend", 200, "{\"newDeliveryId\":\"x\"}");
        mvc.perform(as(org, admin, post("/core/notification-deliveries/abc/resend"))).andExpect(jsonPath("$.response.newDeliveryId").value("x"));
    }
}
