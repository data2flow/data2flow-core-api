package net.java21.data2flow.core.external;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.CalendarSynced;
import net.java21.data2flow.core.calendar.service.CalendarJobs;
import net.java21.data2flow.core.support.LoopItSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 외부 맥락 소스 core 쪽(DSC-06.01~06.05, UC-DSC-09): 사이트 카드·켜기, 공휴일·iCal 동기화와 조직 달력 반영(BR-DSC-18), 재시도·ERROR·운영 알람
 * (BR-DSC-17), 호출량·80% 경고·한도(AT-DSC-09.4), 측정소 찾기, iCal 업로드, EVT-DSC-07 소비, 권한. 외부 API는 가짜 구현·닫힌 포트·대역 서버만 쓴다.
 * TC-DSC-136·142·148·154·160(core 쪽 통합), TC-DSC-137·143·149·155·161(권한)
 */
class ContextSourceIT extends LoopItSupport {

    @Autowired
    CalendarJobs jobs;

    long site;

    @BeforeEach
    void site() {
        site = data.site(org, "광주캠퍼스");
    }

    private void locate() {
        jdbc.sql("UPDATE data2flow_core.spaces SET latitude = 35.1595, longitude = 126.8526 WHERE id = :id").param("id", site).update();
    }

    private MockMultipartHttpServletRequestBuilder upload(long user, MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder b = multipart("/core/sources/ical/upload");
        if (file != null) {
            b.file(file);
        }
        b.header("X-USER-ID", Long.toString(user)).header("X-ORG-ID", Long.toString(org)).header("Accept-Language", "ko");
        return b;
    }

    private MvcResult putSource(String type, String json) throws Exception {
        return mvc.perform(as(org, integrator, json(put("/core/sites/" + site + "/context-sources/" + type), json))).andReturn();
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("org", org).query(Long.class).single();
    }

    private long calendarCount() {
        return count("SELECT count(*) FROM data2flow_core.calendar_events WHERE organization_id = :org");
    }

    private long alarms(String keyPrefix) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key LIKE :k AND status <> 'CLEARED'")
                .param("org", org).param("k", keyPrefix + "%").query(Long.class).single();
    }

    @Test
    @DisplayName("[DSC-06.03][TC-DSC-148][UC-DSC-09] 카드 4개(좌표 없으면 위치 필요), 공휴일을 켜면 올해·내년 공휴일이 사이트 범위로 조직 달력에 들어가고 다시 켜도 중복 없음")
    void holidays() throws Exception {
        mvc.perform(as(org, operator, get("/core/sites/" + site + "/context-sources")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.locationRequired").value(true))
                .andExpect(jsonPath("$.response.sources.length()").value(4))
                .andExpect(jsonPath("$.response.sources[2].type").value("HOLIDAY"))
                .andExpect(jsonPath("$.response.sources[2].enabled").value(false))
                .andExpect(jsonPath("$.response.sources[2].provider.key").value("FIXED_HOLIDAY"))
                .andExpect(jsonPath("$.response.sources[2].provider.simulated").value(true));
        MvcResult r = putSource("holiday", "{\"enabled\":true}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String id = JsonPath.read(body(r), "$.response.sourceId");
        assertThat((String) JsonPath.read(body(r), "$.response.lastSync.status")).isEqualTo("SUCCEEDED");
        assertThat((String) JsonPath.read(body(r), "$.response.connectionState")).isEqualTo("CONNECTED");
        int added = JsonPath.read(body(r), "$.response.lastSync.added");
        long total = calendarCount();
        assertThat(added).isEqualTo((int) total).isGreaterThan(30);
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-10-01").param("to", "2026-10-31")))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.responses[0].title").value("개천절"))
                .andExpect(jsonPath("$.responses[0].origin").value("HOLIDAY_API"))
                .andExpect(jsonPath("$.responses[0].affectsMode").value("HOLIDAY"))
                .andExpect(jsonPath("$.responses[0].scopeSpaceIds[0]").value(Long.toString(site)))
                .andExpect(jsonPath("$.responses[0].sourceId").value(id));
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.added").value(0)).andExpect(jsonPath("$.response.removed").value(0));
        assertThat(calendarCount()).isEqualTo(total);
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"SOURCE\""));
        assertThat(auditCount(org, "SOURCE_CREATED")).isEqualTo(1);
        // 다음 달 1일 03:00(현지)에 다시
        mvc.perform(as(org, operator, get("/core/sites/" + site + "/context-sources")))
                .andExpect(jsonPath("$.response.sources[2].lastSync.nextDueAt").value("2026-10-31T18:00:00Z"));
        // 끄면 PAUSED·DISABLED, 일정은 그대로
        putSource("HOLIDAY", "{\"enabled\":false}");
        mvc.perform(as(org, operator, get("/core/sites/" + site + "/context-sources")))
                .andExpect(jsonPath("$.response.sources[2].lifecycle").value("PAUSED"))
                .andExpect(jsonPath("$.response.sources[2].connectionState").value("DISABLED"));
        assertThat(putSource("HOLIDAY", "{\"enabled\":true,\"countryCode\":\"JP\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("HOLIDAY", "{\"enabled\":true,\"apiKey\":\"x\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("UNKNOWN", "{\"enabled\":true}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("HOLIDAY", "{\"enabled\":true,\"foo\":1}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("HOLIDAY", "{}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("[DSC-06.01][AT-DSC-09.1][TC-DSC-136] 기상청: 좌표 없으면 SITE_LOCATION_REQUIRED, 키 없으면 400, 켜면 격자 자동(광주 58·74)·키 암호화 저장, 지금 갱신은 확인 호출(실패) 기록")
    void kma() throws Exception {
        MvcResult noLocation = putSource("KMA_WEATHER", "{\"enabled\":true,\"apiKey\":\"k\"}");
        assertThat(noLocation.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(noLocation), "$.header.resultCode")).isEqualTo("SITE_LOCATION_REQUIRED");
        locate();
        MvcResult noKey = putSource("KMA_WEATHER", "{\"enabled\":true}");
        assertThat((String) JsonPath.read(body(noKey), "$.header.resultCode")).isEqualTo("SOURCE_CONFIG_INVALID");
        assertThat((String) JsonPath.read(body(noKey), "$.errors[0].field")).isEqualTo("apiKey");
        MvcResult ok = putSource("KMA_WEATHER", "{\"enabled\":true,\"apiKey\":\"service-key-1\",\"items\":[\"T1H\",\"REH\"],\"dailyQuota\":1000}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String id = JsonPath.read(body(ok), "$.response.sourceId");
        assertThat((Integer) JsonPath.read(body(ok), "$.response.config.nx")).isEqualTo(58);
        assertThat((Integer) JsonPath.read(body(ok), "$.response.config.ny")).isEqualTo(74);
        assertThat((Boolean) JsonPath.read(body(ok), "$.response.config.gridAuto")).isTrue();
        assertThat((Boolean) JsonPath.read(body(ok), "$.response.apiKeyConfigured")).isTrue();
        assertThat((String) JsonPath.read(body(ok), "$.response.provider.key")).isEqualTo("KMA");
        assertThat(body(ok)).doesNotContain("service-key-1");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.source_secrets WHERE source_id = :id AND kind = 'API_KEY'")
                .param("id", Long.parseLong(id)).query(Long.class).single()).isEqualTo(1);
        // 격자 직접 지정, 범위 밖은 400
        assertThat((Boolean) JsonPath.read(body(putSource("KMA_WEATHER", "{\"enabled\":true,\"nx\":60,\"ny\":127}")), "$.response.config.gridAuto"))
                .isFalse();
        assertThat(putSource("KMA_WEATHER", "{\"enabled\":true,\"nx\":500}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("KMA_WEATHER", "{\"enabled\":true,\"items\":[\"XXX\"]}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("KMA_WEATHER", "{\"enabled\":true,\"unitCost\":-1}").getResponse().getStatus()).isEqualTo(400);
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("FAILED"))
                .andExpect(jsonPath("$.response.error").value(Matchers.startsWith("OTHER")));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/api-usage").param("days", "1")))
                .andExpect(jsonPath("$.response[0].calls").value(1)).andExpect(jsonPath("$.response[0].failures").value(1))
                .andExpect(jsonPath("$.response[0].quota").value(1000));
        // 끈 소스는 갱신할 수 없음
        putSource("KMA_WEATHER", "{\"enabled\":false}");
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSC-06.02][API-DSC-42][TC-DSC-142] 가까운 측정소 5곳(거리순, 가짜), 범위 오류 400, 대기질을 켜면 가장 가까운 측정소 자동 선택·바꿀 수 있음, 지금 갱신은 가짜로 REQUESTED")
    void airkorea() throws Exception {
        mvc.perform(as(org, integrator, get("/core/external/airkorea-stations").param("lat", "35.1527").param("lng", "126.8497")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.length()").value(5))
                .andExpect(jsonPath("$.response[0].stationName").value("치평동"))
                .andExpect(jsonPath("$.response[0].distanceKm").value(0.0));
        mvc.perform(as(org, integrator, get("/core/external/airkorea-stations").param("lat", "95").param("lng", "126")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lat"));
        mvc.perform(as(org, operator, get("/core/external/airkorea-stations").param("lat", "35").param("lng", "126")))
                .andExpect(status().isForbidden());
        locate();
        MvcResult r = putSource("AIRKOREA", "{\"enabled\":true,\"apiKey\":\"k\"}");
        assertThat((String) JsonPath.read(body(r), "$.response.config.stationName")).isEqualTo("치평동");
        assertThat((List<String>) JsonPath.read(body(r), "$.response.config.items")).containsExactly("PM10", "PM25", "O3");
        r = putSource("AIRKOREA", "{\"enabled\":true,\"stationName\":\"농성동\"}");
        assertThat((String) JsonPath.read(body(r), "$.response.config.stationName")).isEqualTo("농성동");
        assertThat((Boolean) JsonPath.read(body(r), "$.response.config.stationAuto")).isFalse();
    }

    @Test
    @DisplayName("[DSC-06.04][AT-DSC-09.3][BR-DSC-18][TC-DSC-154] iCal URL: 카테고리 → 유형 매핑, 원본에서 사라진 일정은 삭제(수동 수정한 것은 '원본 삭제됨'으로 유지), UID로 중복 없음")
    void icalUrl() throws Exception {
        String ics = new ClassPathResource("contracts/ical/academic-2026.ics").getContentAsString(StandardCharsets.UTF_8);
        AtomicReference<String> served = new AtomicReference<>(ics);
        STUB.on("GET", "/cal.ics", req -> new StubHttpServer.Reply(200, served.get(), Map.of()));
        assertThat(putSource("ICAL", "{\"enabled\":true,\"url\":\"ftp://x/cal.ics\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("ICAL", "{\"enabled\":true}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putSource("ICAL", "{\"enabled\":true,\"url\":\"" + STUB.baseUrl() + "/cal.ics\",\"typeMapping\":{\"x\":\"NOPE\"}}")
                .getResponse().getStatus()).isEqualTo(400);
        MvcResult r = putSource("ICAL", "{\"enabled\":true,\"url\":\"" + STUB.baseUrl() + "/cal.ics\",\"typeMapping\":{\"시험\":\"EXAM\",\"휴일\":\"HOLIDAY\"},"
                + "\"refreshHours\":12}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String id = JsonPath.read(body(r), "$.response.sourceId");
        assertThat((Integer) JsonPath.read(body(r), "$.response.lastSync.added")).isEqualTo(4);
        MvcResult list = mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-10-01").param("to", "2026-11-30")))
                .andExpect(jsonPath("$.totalCount").value(4)).andReturn();
        assertThat((List<String>) JsonPath.read(body(list), "$.responses[*].type")).containsExactly("EXAM", "EVENT", "HOLIDAY", "EVENT");
        assertThat((List<String>) JsonPath.read(body(list), "$.responses[*].startTime")).containsExactly(null, "18:00", null, "10:00");
        String festival = JsonPath.read(body(list), "$.responses[1].id");
        String closed = JsonPath.read(body(list), "$.responses[2].id");
        assertThat((String) JsonPath.read(body(list), "$.responses[2].affectsMode")).isEqualTo("HOLIDAY");
        // 자동 생성 일정은 affectsMode만 바꿀 수 있다 → 바꾸면 수동 수정
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + festival), "{\"title\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CALENDAR_EVENT_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("AUTO_GENERATED"));
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + festival), "{\"affectsMode\":\"UNOCCUPIED\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.locallyModified").value(true));
        mvc.perform(as(org, operator, delete("/core/calendar-events/" + closed))).andExpect(status().isBadRequest());
        // 원본에서 대동제·개교기념일이 사라지고 시험 기간이 바뀜
        served.set(ics.replace("DTEND;VALUE=DATE:20261024", "DTEND;VALUE=DATE:20261025")
                .replaceAll("(?s)BEGIN:VEVENT\r\nUID:festival-2026.*?END:VEVENT\r\n", "")
                .replaceAll("(?s)BEGIN:VEVENT\r\nUID:closed-2026-10-30.*?END:VEVENT\r\n", ""));
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now")))
                .andExpect(jsonPath("$.response.status").value("SUCCEEDED")).andExpect(jsonPath("$.response.updated").value(1))
                .andExpect(jsonPath("$.response.removed").value(2)).andExpect(jsonPath("$.response.added").value(0));
        mvc.perform(as(org, viewer, get("/core/calendar-events/" + festival)))
                .andExpect(jsonPath("$.response.originDeleted").value(true)).andExpect(jsonPath("$.response.affectsMode").value("UNOCCUPIED"));
        mvc.perform(as(org, viewer, get("/core/calendar-events/" + closed))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-10-19").param("to", "2026-10-19")))
                .andExpect(jsonPath("$.responses[0].endsOn").value("2026-10-24"));
        // 원본 삭제된 수동 수정 일정은 지울 수 있다
        mvc.perform(as(org, operator, delete("/core/calendar-events/" + festival))).andExpect(status().isNoContent());
        // 다음 갱신은 12시간 뒤
        mvc.perform(as(org, operator, get("/core/sites/" + site + "/context-sources")))
                .andExpect(jsonPath("$.response.sources[3].lastSync.nextDueAt").value(clock.instant().plus(Duration.ofHours(12)).toString()));
    }

    @Test
    @DisplayName("[DSC-06.04][API-DSC-43][BR-DSC-17][DSC-06.03] 파일 업로드(카테고리), 갱신 실패는 30초·2분·10분 재시도 후 ERROR·운영 알람, 이전 데이터 유지, 회복하면 해제")
    void uploadAndRetries() throws Exception {
        byte[] ics = new ClassPathResource("contracts/ical/academic-2026.ics").getContentAsByteArray();
        mvc.perform(upload(integrator, new MockMultipartFile("file", "학사일정.ics", "text/calendar", ics)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.eventCount").value(4))
                .andExpect(jsonPath("$.response.categories").value(Matchers.contains("시험", "행사", "휴일")))
                .andExpect(jsonPath("$.response.fileObjectKey").value(Matchers.startsWith("db:ical_files/")));
        mvc.perform(upload(integrator, new MockMultipartFile("file", "a.txt", "text/plain", ics)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("EXTENSION"));
        mvc.perform(upload(integrator, new MockMultipartFile("file", "b.ics", "text/calendar", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("ICAL_INVALID"));
        mvc.perform(upload(integrator, new MockMultipartFile("file", "c.ics", "text/calendar", new byte[2 * 1024 * 1024 + 1])))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("Size"));
        mvc.perform(upload(integrator, null)).andExpect(status().isBadRequest());
        mvc.perform(upload(operator, new MockMultipartFile("file", "a.ics", "text/calendar", ics)))
                .andExpect(status().isForbidden());
        assertThat(putSource("ICAL", "{\"enabled\":true,\"fileObjectKey\":\"db:ical_files/999999\"}").getResponse().getStatus()).isEqualTo(400);
        // URL 소스: 처음엔 성공, 그 뒤 대상이 500
        AtomicReference<Integer> code = new AtomicReference<>(200);
        String text = new String(ics, StandardCharsets.UTF_8);
        STUB.on("GET", "/fail.ics", req -> new StubHttpServer.Reply(code.get(), text, Map.of()));
        String id = JsonPath.read(body(putSource("ICAL", "{\"enabled\":true,\"url\":\"" + STUB.baseUrl() + "/fail.ics\",\"refreshHours\":1}")),
                "$.response.sourceId");
        assertThat(calendarCount()).isEqualTo(4);
        code.set(500);
        clock.advance(Duration.ofHours(1));
        assertThat(jobs.contextSyncs()).isEqualTo(1);
        assertThat(jobs.contextSyncs()).isZero();
        clock.advance(Duration.ofSeconds(30));
        assertThat(jobs.contextSyncs()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(jobs.contextSyncs()).isEqualTo(1);
        assertThat(alarms("system:CONTEXT_SYNC_FAILED")).isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(jobs.contextSyncs()).isEqualTo(1);
        assertThat(alarms("system:CONTEXT_SYNC_FAILED:" + id)).isEqualTo(1);
        assertThat(calendarCount()).isEqualTo(4);
        mvc.perform(as(org, operator, get("/core/sites/" + site + "/context-sources")))
                .andExpect(jsonPath("$.response.sources[3].connectionState").value("ERROR"))
                .andExpect(jsonPath("$.response.sources[3].lastSync.status").value("FAILED"))
                .andExpect(jsonPath("$.response.sources[3].lastSync.error").value("OTHER: HTTP 500"));
        code.set(200);
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now"))).andExpect(jsonPath("$.response.status").value("SUCCEEDED"));
        assertThat(alarms("system:CONTEXT_SYNC_FAILED")).isZero();
        // 업로드 파일 소스로 바꾸기
        String key = JsonPath.read(body(mvc.perform(upload(integrator, new MockMultipartFile("file", "a.ics", "text/calendar", ics))).andReturn()), "$.response.fileObjectKey");
        MvcResult file = putSource("ICAL", "{\"enabled\":true,\"fileObjectKey\":\"" + key + "\"}");
        assertThat((String) JsonPath.read(body(file), "$.response.config.fileObjectKey")).isEqualTo(key);
        assertThat(body(file)).doesNotContain("fail.ics");
        assertThat((String) JsonPath.read(body(file), "$.response.lastSync.status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("[DSC-06.05][AT-DSC-09.4][TC-DSC-157] 한도 1,000: 799건까지 경고 없음, 800건에 경고 알람 1건(같은 날 반복 없음), 1,000건이면 429·Retry-After, 다음 날(KST) 재개·경고 해제, 비용 표시")
    void quota() throws Exception {
        locate();
        String id = JsonPath.read(body(putSource("KMA_WEATHER", "{\"enabled\":true,\"apiKey\":\"k\",\"dailyQuota\":1000,\"unitCost\":0.5}")),
                "$.response.sourceId");
        String path = "/internal/core/sources/" + id + "/api-usage";
        mvc.perform(json(post(path), "{\"calls\":799}")).andExpect(jsonPath("$.response.warning").value(false));
        assertThat(alarms("system:API_QUOTA")).isZero();
        mvc.perform(json(post(path), "{\"calls\":1}")).andExpect(jsonPath("$.response.warning").value(true))
                .andExpect(jsonPath("$.response.exhausted").value(false));
        assertThat(alarms("system:API_QUOTA:" + id)).isEqualTo(1);
        mvc.perform(json(post(path), "{\"calls\":5,\"failures\":2}")).andExpect(jsonPath("$.response.calls").value(805));
        assertThat(jdbc.sql("SELECT occurrence_count FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key LIKE 'system:API_QUOTA%'")
                .param("org", org).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(json(post(path), "{\"calls\":195}")).andExpect(jsonPath("$.response.exhausted").value(true))
                .andExpect(jsonPath("$.response.resumeAt").value("2026-10-03T15:00:00Z"));
        mvc.perform(json(post(path), "{\"calls\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/internal/core/sources/999999/api-usage"), "{\"calls\":1}")).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/refresh-now")))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "54000"))
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_API_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("오늘 호출 한도에 도달해 내일 다시 수집합니다"));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/api-usage").param("days", "3")))
                .andExpect(jsonPath("$.response.length()").value(3))
                .andExpect(jsonPath("$.response[2].day").value("2026-10-03"))
                .andExpect(jsonPath("$.response[2].calls").value(1000)).andExpect(jsonPath("$.response[2].failures").value(2))
                .andExpect(jsonPath("$.response[2].warning").value(true)).andExpect(jsonPath("$.response[2].exhausted").value(true))
                .andExpect(jsonPath("$.response[2].cost").value(500.0))
                .andExpect(jsonPath("$.response[0].calls").value(0));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/api-usage").param("days", "91"))).andExpect(status().isBadRequest());
        // 다음 날 00:00 KST
        clock.advance(Duration.ofHours(15));
        mvc.perform(json(post(path), "{}")).andExpect(jsonPath("$.response.exhausted").value(false))
                .andExpect(jsonPath("$.response.day").value("2026-10-04"));
        mvc.perform(json(post(path), "{\"calls\":1}")).andExpect(jsonPath("$.response.calls").value(1));
        assertThat(alarms("system:API_QUOTA")).isZero();
    }

    @Test
    @DisplayName("[DSC-06.04][EVT-DSC-07] calendar.synced: 추가·변경은 사이트 범위로 반영, removedUids는 삭제, 다른 조직·모르는 소스는 버림")
    void calendarSyncedEvent() throws Exception {
        String id = JsonPath.read(body(putSource("ICAL", "{\"enabled\":false,\"url\":\"https://example.ac.kr/cal.ics\"}")), "$.response.sourceId");
        long sourceId = Long.parseLong(id);
        CalendarSynced first = new CalendarSynced(sourceId, List.of(
                new CalendarSynced.Event("a", "방학", "VACATION", LocalDate.of(2026, 12, 21), LocalDate.of(2027, 2, 28), null, null),
                new CalendarSynced.Event("b", "행사", "WHATEVER", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), null, null)), List.of());
        assertThat(deliver(EventType.CALENDAR_SYNCED, org, first, clock.instant())).isTrue();
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-11-01").param("to", "2026-12-31")))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].type").value("OTHER"))
                .andExpect(jsonPath("$.responses[1].affectsMode").value("UNOCCUPIED"))
                .andExpect(jsonPath("$.responses[1].origin").value("ICAL"));
        deliver(EventType.CALENDAR_SYNCED, org, new CalendarSynced(sourceId, List.of(), List.of("b", "zzz")), clock.instant());
        assertThat(calendarCount()).isEqualTo(1);
        long other = fx.organization("other-ctx");
        deliver(EventType.CALENDAR_SYNCED, other, first, clock.instant());
        deliver(EventType.CALENDAR_SYNCED, org, new CalendarSynced(999999, first.events(), List.of()), clock.instant());
        assertThat(calendarCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSC-06.01][TC-DSC-137·143·161] 권한: 카드·호출량 조회 SRC_READ(OPERATOR 200, VIEWER 403), 설정·갱신 SRC_ADMIN(OPERATOR 403), 다른 조직 사이트·소스 404")
    void permissions() throws Exception {
        String id = JsonPath.read(body(putSource("HOLIDAY", "{\"enabled\":false}")), "$.response.sourceId");
        mvc.perform(as(org, viewer, get("/core/sites/" + site + "/context-sources"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(put("/core/sites/" + site + "/context-sources/HOLIDAY"), "{\"enabled\":true}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, post("/core/sources/" + id + "/refresh-now"))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, get("/core/sources/" + id + "/api-usage"))).andExpect(status().isForbidden());
        long other = fx.organization("ctx-other");
        long otherAdmin = fx.user(other, "ctx.other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/sites/" + site + "/context-sources"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(other, otherAdmin, get("/core/sources/" + id + "/api-usage"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_NOT_FOUND"));
        long room = data.space(org, site, "ROOM", "실습실");
        mvc.perform(as(org, admin, get("/core/sites/" + room + "/context-sources"))).andExpect(status().isNotFound());
        data.spaceScope(org, integrator, List.of(data.site(org, "다른 캠퍼스")));
        mvc.perform(as(org, integrator, get("/core/sites/" + site + "/context-sources"))).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, get("/core/sources/" + id + "/api-usage"))).andExpect(status().isNotFound());
    }
}
