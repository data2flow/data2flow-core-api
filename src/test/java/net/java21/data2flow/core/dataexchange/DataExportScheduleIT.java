package net.java21.data2flow.core.dataexchange;

import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.MimeMessage;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AggregatesRecomputed;
import net.java21.data2flow.core.dataexchange.event.ExchangeEventHandler;
import net.java21.data2flow.core.dataexchange.service.ExportScheduleRunner;
import net.java21.data2flow.core.support.StubHttpServer;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정기 내보내기(TSD-04.03·07.02, API-TSD-23·56, BR-TSD-27·28) 통합 시험. S3는 대역 HTTP 서버, SFTP는 내장 Apache MINA SSHD 서버,
 * 메일은 GreenMail. 실제 저장소·s3·s4에는 붙지 않는다. TC-TSD-118·157·158·162·163, AT-TSD-12.1·17.1~17.4·19.3
 */
class DataExportScheduleIT extends IntegrationTestSupport {

    static final StubHttpServer S3 = new StubHttpServer();
    static SshServer sftp;
    static Path sftpRoot;

    @Autowired
    ExportScheduleRunner runner;
    @Autowired
    ExchangeEventHandler events;

    ExchangeTestData td;
    long org;
    long analyst;
    long sensor;
    /** 10-03 00:00 KST */
    final Instant dayStart = Instant.parse("2026-10-02T15:00:00Z");

    @BeforeAll
    static void startSftp() throws Exception {
        sftpRoot = Files.createTempDirectory("d2f-sftp");
        sftp = SshServer.setUpDefaultServer();
        sftp.setHost("127.0.0.1");
        sftp.setPort(0);
        sftp.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(sftpRoot.resolve("hostkey.ser")));
        sftp.setPasswordAuthenticator((user, password, session) -> "d2f".equals(user) && "secret".equals(password));
        sftp.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        Path home = Files.createDirectories(sftpRoot.resolve("home"));
        sftp.setFileSystemFactory(new VirtualFileSystemFactory(home));
        sftp.start();
    }

    @AfterAll
    static void stopSftp() throws Exception {
        sftp.stop();
    }

    @BeforeEach
    void setUp() {
        S3.reset();
        td = new ExchangeTestData(jdbc);
        org = fx.organization("sch");
        analyst = fx.user(org, "sch.analyst", "ANALYST");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "co2", "ppm");
        sensor = data.device(org, data.source(org, "cs"), "a1", "ACTIVE", room, null);
    }

    private String create(String body) throws Exception {
        String json = mvc.perform(as(org, analyst, json(post("/core/export-schedules"), body))).andExpect(status().isCreated())
                .andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.response.id");
    }

    private String query(String resolution) {
        return "{\"series\":[{\"deviceId\":%d,\"metric\":\"co2\"}],\"resolution\":\"%s\"}".formatted(sensor, resolution);
    }

    @Test
    @DisplayName("[TSD-04.03][AT-TSD-12.1] 매일 07:00 KST·전날 → 07:00에 전날 00:00~24:00(KST) 파일, 수신자에게 내려받기 링크 메일, EVT-TSD-01·07 — TC-TSD-115·118")
    void dailyEmail() throws Exception {
        fx.mail(org, smtpPort());
        td.raw(org, sensor, "co2", dayStart.minus(Duration.ofHours(3)), Duration.ofHours(1), 30);
        String id = create("{\"name\":\"daily-co2\",\"query\":%s,\"format\":\"CSV\",\"cron\":\"0 7 * * *\",\"relativePeriod\":\"PREVIOUS_DAY\",\"delivery\":\"EMAIL\",\"recipients\":[\"ops@example.com\"]}"
                .formatted(query("raw")));
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + id)))
                .andExpect(jsonPath("$.response.nextRunAt").value("2026-10-03T22:00:00Z"))
                .andExpect(jsonPath("$.response.relativePeriod").value("PREVIOUS_DAY"));
        assertThat(runner.runOnce()).isZero();
        clock.set(Instant.parse("2026-10-03T22:00:00Z"));
        assertThat(runner.runOnce()).isEqualTo(1);
        List<MimeMessage> mails = mails();
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().getSubject()).contains("daily-co2");
        assertThat((String) mails.getFirst().getContent()).contains("/data/exports/").contains("2026-10-03 00:00 (Asia/Seoul)");
        String jobs = mvc.perform(as(org, analyst, get("/core/exports"))).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(jobs, "$.responses[0].rows")).isEqualTo(24);
        assertThat((String) JsonPath.read(jobs, "$.responses[0].scheduleId")).isEqualTo(id);
        assertThat(td.outbox(org, "export.completed")).isEqualTo(1);
        assertThat(td.outbox(org, "bi.export.completed")).isEqualTo(1);
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + id))).andExpect(jsonPath("$.response.lastStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.nextRunAt").value("2026-10-04T22:00:00Z"));
    }

    @Test
    @DisplayName("[TSD-07.02][AT-TSD-17.1][AT-TSD-17.2][AT-TSD-19.3] 1시간 집계 Parquet → S3에 {조직}_{범위}_{시작}_{끝}_v1.parquet와 data-dictionary_v{n}.json, 재계산되면 다음 실행에 같은 기간 _v2 — TC-TSD-155·157·160")
    void s3ParquetAndNewVersion() throws Exception {
        S3.on("PUT", "/exports/.*", r -> new StubHttpServer.Reply(200, "", Map.of()));
        td.hourly(org, sensor, "co2", dayStart, 24);
        String id = create(("{\"name\":\"bi-co2\",\"query\":%s,\"format\":\"PARQUET\",\"cron\":\"0 1 * * *\",\"relativePeriod\":\"전날\",\"delivery\":\"STORAGE\","
                + "\"targetType\":\"S3\",\"target\":{\"endpoint\":\"%s\",\"bucket\":\"exports\",\"prefix\":\"d2f/\"},"
                + "\"credential\":{\"accessKey\":\"AK\",\"secretKey\":\"SK\"}}").formatted(query("1h"), S3.baseUrl()));
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + id)))
                .andExpect(jsonPath("$.response.credentialConfigured").value(true))
                .andExpect(jsonPath("$.response.credentialRef").value("db:export_schedules.credential_enc"))
                .andExpect(jsonPath("$.response.credential").doesNotExist());
        clock.set(Instant.parse("2026-10-03T16:00:00Z")); // 10-04 01:00 KST
        assertThat(runner.runOnce()).isEqualTo(1);
        List<StubHttpServer.Received> puts = S3.received("PUT", "/exports/.*");
        assertThat(puts).extracting(StubHttpServer.Received::path)
                .containsExactly("/exports/d2f/org-sch_bi-co2_20261003_20261004_v1.parquet", "/exports/d2f/data-dictionary_v1.json");
        assertThat(puts.getFirst().header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AK/20261003/us-east-1/s3/aws4_request");
        assertThat(puts.getFirst().body()).startsWith("PAR1");
        assertThat(puts.get(1).body()).contains("\"version\":1");
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + id))).andExpect(jsonPath("$.response.lastFileVersion").value(1));

        // 늦은 데이터로 10-03 집계 재계산 → 다음 실행에 같은 기간 v2(이전 판 유지) + 새 기간 v1
        events.handle(DomainEvent.of(EventType.AGGREGATES_RECOMPUTED, org, new AggregatesRecomputed("1h",
                List.of(new AggregatesRecomputed.Item(sensor, "co2", dayStart.plus(Duration.ofHours(3)), dayStart.plus(Duration.ofHours(4))))),
                null, clock));
        S3.reset();
        S3.on("PUT", "/exports/.*", r -> new StubHttpServer.Reply(200, "", Map.of()));
        clock.set(Instant.parse("2026-10-04T16:00:00Z"));
        assertThat(runner.runOnce()).isEqualTo(2);
        assertThat(S3.received("PUT", "/exports/.*")).extracting(StubHttpServer.Received::path)
                .contains("/exports/d2f/org-sch_bi-co2_20261004_20261005_v1.parquet", "/exports/d2f/org-sch_bi-co2_20261003_20261004_v2.parquet");
        assertThat(td.outbox(org, "bi.export.completed")).isEqualTo(3);
    }

    @Test
    @DisplayName("[TSD-07.02][AT-TSD-17.3] SFTP 인증 실패 → 1분·2분 뒤 다시, 3회 실패면 FAILED와 bi.export.failed(EXPORT_TARGET_UNWRITABLE). 맞는 자격이면 SFTP에 파일 — TC-TSD-155·157")
    void sftpRetriesThenFails() throws Exception {
        td.raw(org, sensor, "co2", dayStart, Duration.ofHours(1), 24);
        String target = "{\"host\":\"127.0.0.1\",\"port\":%d,\"username\":\"d2f\"}".formatted(sftp.getPort());
        String bad = create(("{\"name\":\"sftp-bad\",\"query\":%s,\"format\":\"CSV\",\"cron\":\"0 1 * * *\",\"relativePeriod\":\"PREVIOUS_DAY\","
                + "\"delivery\":\"STORAGE\",\"targetType\":\"SFTP\",\"target\":%s,\"credential\":{\"password\":\"wrong\"}}").formatted(query("raw"), target));
        clock.set(Instant.parse("2026-10-03T16:00:00Z"));
        assertThat(runner.runOnce()).isEqualTo(1);
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + bad))).andExpect(jsonPath("$.response.lastStatus").value("RETRYING"));
        assertThat(runner.runOnce()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(runner.runOnce()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(runner.runOnce()).isEqualTo(1);
        mvc.perform(as(org, analyst, get("/core/export-schedules/" + bad))).andExpect(jsonPath("$.response.lastStatus").value("FAILED"))
                .andExpect(jsonPath("$.response.lastError").value(startsWith("EXPORT_TARGET_UNWRITABLE")));
        assertThat(td.outbox(org, "bi.export.failed")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(td.outboxPayload(org, "bi.export.failed"), "$.payload.attempts")).isEqualTo(3);

        create(("{\"name\":\"sftp-ok\",\"query\":%s,\"format\":\"CSV\",\"cron\":\"0 1 * * *\",\"relativePeriod\":\"PREVIOUS_DAY\","
                + "\"delivery\":\"STORAGE\",\"targetType\":\"SFTP\",\"target\":%s,\"credential\":{\"password\":\"secret\"}}").formatted(query("raw"), target));
        clock.set(Instant.parse("2026-10-04T16:00:00Z"));
        runner.runOnce();
        assertThat(Files.exists(sftpRoot.resolve("home/org-sch_sftp-ok_20261004_20261005_v1.csv"))).isTrue();
        assertThat(Files.exists(sftpRoot.resolve("home/data-dictionary_v1.json"))).isTrue();
    }

    @Test
    @DisplayName("[TSD-07.02][AT-TSD-17.4] 연결 테스트: SFTP는 CONNECT·WRITE·DELETE 성공, S3 쓰기 권한 없음은 502 EXPORT_TARGET_UNWRITABLE + 단계 결과 — TC-TSD-156·158")
    void testTarget() throws Exception {
        mvc.perform(as(org, analyst, json(post("/core/export-schedules/test-target"),
                        "{\"targetType\":\"SFTP\",\"target\":{\"host\":\"127.0.0.1\",\"port\":%d,\"username\":\"d2f\"},\"credential\":{\"password\":\"secret\"}}"
                                .formatted(sftp.getPort()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ok").value(true))
                .andExpect(jsonPath("$.response.steps[0].name").value("CONNECT"))
                .andExpect(jsonPath("$.response.steps[2].name").value("DELETE"));
        S3.fail("PUT", "/readonly/.*", 403, "AccessDenied", null);
        mvc.perform(as(org, analyst, json(post("/core/export-schedules/test-target"),
                        "{\"targetType\":\"S3\",\"target\":{\"endpoint\":\"%s\",\"bucket\":\"readonly\"},\"credential\":{\"accessKey\":\"a\",\"secretKey\":\"b\"}}"
                                .formatted(S3.baseUrl()))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultCode").value("EXPORT_TARGET_UNWRITABLE"))
                .andExpect(jsonPath("$.response.ok").value(false))
                .andExpect(jsonPath("$.response.steps[1].name").value("WRITE"))
                .andExpect(jsonPath("$.response.steps[1].ok").value(false));
        mvc.perform(as(org, analyst, json(post("/core/export-schedules/test-target"), "{\"targetType\":\"FTP\",\"target\":{}}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[TSD-04.03][TSD-07.02] 권한·검증·수정: VIEWER 403, 남의 일정 404·ADMIN은 전체, 다른 조직 404, cron·수신자·이름 중복 400, baseVersion 409, 삭제 204·감사 — TC-TSD-117·119·158·163")
    void crudAndPermissions() throws Exception {
        String body = "{\"name\":\"weekly\",\"query\":%s,\"format\":\"XLSX\",\"cron\":\"0 7 * * MON\",\"relativePeriod\":\"PREVIOUS_WEEK\",\"delivery\":\"EMAIL\",\"recipients\":[\"a@example.com\"]}"
                .formatted(query("1h"));
        String id = create(body);
        long viewer = fx.user(org, "sch.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/export-schedules"))).andExpect(status().isForbidden());
        long other = fx.user(org, "sch.other", "ANALYST");
        mvc.perform(as(org, other, get("/core/export-schedules/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, other, get("/core/export-schedules"))).andExpect(jsonPath("$.totalCount").value(0));
        long admin = fx.user(org, "sch.admin", "ADMIN");
        mvc.perform(as(org, admin, get("/core/export-schedules"))).andExpect(jsonPath("$.totalCount").value(1));
        long otherOrg = fx.organization("sch2");
        long otherAdmin = fx.user(otherOrg, "sch2.admin", "ADMIN");
        mvc.perform(as(otherOrg, otherAdmin, get("/core/export-schedules/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, json(post("/core/export-schedules"), body))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(as(org, analyst, json(post("/core/export-schedules"), body.replace("weekly", "w2").replace("0 7 * * MON", "x"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("cron"));
        mvc.perform(as(org, analyst, json(post("/core/export-schedules"), body.replace("weekly", "w3").replace("a@example.com", "nope"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("recipients[0]"));
        mvc.perform(as(org, analyst, json(post("/core/export-schedules"), body.replace("weekly", "w4").replace("\"EMAIL\"", "\"STORAGE\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("targetType"));
        mvc.perform(as(org, analyst, json(patch("/core/export-schedules/" + id), "{\"enabled\":false,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.enabled").value(false))
                .andExpect(jsonPath("$.response.nextRunAt").doesNotExist()).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, analyst, json(patch("/core/export-schedules/" + id), "{\"enabled\":true,\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, analyst, delete("/core/export-schedules/" + id))).andExpect(status().isNoContent());
        assertThat(auditCount(org, "EXPORT_SCHEDULE_CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "EXPORT_SCHEDULE_DELETED")).isEqualTo(1);
    }
}
