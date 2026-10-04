package net.java21.data2flow.core.external.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.message.event.CalendarSynced;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.domain.ICalParser;
import net.java21.data2flow.core.calendar.domain.ICalParser.ICalEvent;
import net.java21.data2flow.core.calendar.service.CalendarSyncService;
import net.java21.data2flow.core.external.provider.HolidayProvider;
import net.java21.data2flow.core.external.provider.HolidayProvider.Holiday;
import net.java21.data2flow.core.external.provider.ProviderException;
import net.java21.data2flow.core.external.repository.ContextSourceRepository;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.ContextSource;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.SyncState;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * core가 직접 갱신하는 외부 맥락 소스(DSC-06.03 공휴일, DSC-06.04 iCal)의 동기화.
 * <ul>
 *   <li>공휴일: 올해·내년(사이트 시간대 기준)을 받아 조직 달력에 반영, 매월 1일 03:00(현지)에 다시(연 1회 + 월 1회 갱신을 겸함)</li>
 *   <li>iCal: URL(https·webcal) 또는 업로드 파일을 읽어 카테고리 → 일정 유형 매핑 후 반영, {@code refreshHours}(기본 6)마다</li>
 *   <li>실패하면 이전 데이터를 그대로 두고 30초·2분·10분 뒤 다시(BR-DSC-17). 그래도 실패하면 소스 상태 ERROR와 MAJOR 운영 알람
 *       {@code system:CONTEXT_SYNC_FAILED:{sourceId}}(DSC-06.03 "갱신 실패 시 이전 데이터를 유지하고 운영 알람"), 다음 정기 시각에 다시</li>
 *   <li>성공하면 상태 CONNECTED, 알람 해제, 마지막 결과(+추가 ~갱신 −삭제)를 남긴다</li>
 * </ul>
 * 외부 호출은 트랜잭션 밖에서 하고(호출량 기록은 실패해도 남김), 달력 반영과 결과 기록은 소스마다 별도 트랜잭션이다.
 */
@Service
public class ContextSyncService {

    private static final Logger log = LoggerFactory.getLogger(ContextSyncService.class);
    public static final String SYNC_ALARM = "CONTEXT_SYNC_FAILED";
    static final Duration[] BACKOFF = {Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)};
    static final int MAX_ICAL_BYTES = 2 * 1024 * 1024;

    private final ContextSourceRepository repository;
    private final CalendarSyncService calendar;
    private final ExternalProviders providers;
    private final ApiUsageService usage;
    private final SourceHealthRepository health;
    private final SpaceRepository spaces;
    private final AlarmService alarms;
    private final ExternalProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HttpClient http;

    public ContextSyncService(ContextSourceRepository repository, CalendarSyncService calendar, ExternalProviders providers,
                              ApiUsageService usage, SourceHealthRepository health, SpaceRepository spaces, AlarmService alarms,
                              ExternalProperties properties, PlatformTransactionManager txManager, Clock clock) {
        this.repository = repository;
        this.calendar = calendar;
        this.providers = providers;
        this.usage = usage;
        this.health = health;
        this.spaces = spaces;
        this.alarms = alarms;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(properties.httpTimeout()).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** 한 번 실행 결과 */
    public record Outcome(boolean ok, int added, int updated, int removed, String error) {
    }

    /** 때가 된 소스 모두(1분 작업). 처리한 소스 수 */
    public int runDue(Collection<Long> organizations) {
        Instant now = clock.instant();
        int n = 0;
        for (ContextSource s : repository.listDue(organizations, now, 50)) {
            // 여러 파드가 같은 소스를 동시에 맡지 않게 다음 실행을 15분 미뤄 두고 시작한다(끝나면 결과대로 다시 정함)
            Boolean claimed = tx.execute(status -> repository.claim(s.organizationId(), s.id(), now, now.plus(Duration.ofMinutes(15))));
            if (Boolean.TRUE.equals(claimed)) {
                sync(s);
                n++;
            }
        }
        return n;
    }

    /** 소스 하나를 지금 동기화(별도 트랜잭션) */
    public Outcome sync(ContextSource s) {
        Instant now = clock.instant();
        SyncState before = repository.findSync(s.organizationId(), s.id()).orElse(null);
        Outcome outcome;
        try {
            outcome = run(s);
        } catch (RuntimeException ex) {
            String message = message(ex);
            log.warn("외부 맥락 동기화 실패: 소스 {} ({}) — {}", s.id(), s.type(), message);
            outcome = new Outcome(false, 0, 0, 0, message);
        }
        Outcome result = outcome;
        tx.executeWithoutResult(status -> record(s, before, result, now));
        return result;
    }

    private Outcome run(ContextSource s) {
        List<Long> scope = s.siteId() == null ? List.of() : List.of(s.siteId());
        ZoneId zone = zone(s);
        return switch (s.type()) {
            case "HOLIDAY" -> holidays(s, scope, zone);
            case "ICAL" -> ical(s, scope, zone);
            default -> throw new IllegalArgumentException("core가 동기화하지 않는 유형: " + s.type());
        };
    }

    private Outcome holidays(ContextSource s, List<Long> scope, ZoneId zone) {
        HolidayProvider provider = providers.holidays();
        int year = LocalDate.ofInstant(clock.instant(), zone).getYear();
        List<Integer> years = List.of(year, year + 1);
        boolean real = !provider.descriptor().simulated();
        if (real) {
            usage.requireAllowed(s);
        }
        List<Holiday> all = new ArrayList<>();
        int failures = 0;
        try {
            for (int y : years) {
                all.addAll(provider.holidays(y));
            }
        } catch (ProviderException ex) {
            failures = 1;
            throw ex;
        } finally {
            if (real) {
                usage.record(s, years.size(), failures);
            }
        }
        // 같은 날 두 공휴일(예: 어린이날·부처님오신날)은 한 일정으로 합친다
        Map<LocalDate, String> byDate = new LinkedHashMap<>();
        for (Holiday h : all) {
            byDate.merge(h.date(), h.name(), (a, b) -> a.contains(b) ? a : a + "·" + b);
        }
        List<CalendarSynced.Event> events = byDate.entrySet().stream()
                .map(e -> new CalendarSynced.Event("holiday:" + e.getKey(), e.getValue(), "HOLIDAY", e.getKey(), e.getKey(), null, null))
                .toList();
        return tx.execute(status -> {
            CalendarSyncService.Result r = calendar.apply(s.organizationId(), s.id(), CalendarModels.HOLIDAY_API, scope, events, List.of());
            int removed = calendar.removeMissing(s.organizationId(), s.id(), events.stream().map(CalendarSynced.Event::uid).toList(),
                    row -> years.contains(row.startsOn().getYear()));
            return new Outcome(true, r.added(), r.updated(), r.removed() + removed, null);
        });
    }

    private Outcome ical(ContextSource s, List<Long> scope, ZoneId zone) {
        String text = icalText(s);
        List<ICalEvent> parsed = ICalParser.parse(text, zone);
        Map<String, String> mapping = typeMapping(s.connection().path("typeMapping"));
        List<CalendarSynced.Event> events = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ICalEvent e : parsed) {
            if (!seen.add(e.uid())) {
                continue;
            }
            events.add(new CalendarSynced.Event(e.uid(), e.title(), mapType(e.categories(), mapping), e.startsOn(), e.endsOn(), e.startTime(),
                    e.endTime()));
        }
        return tx.execute(status -> {
            CalendarSyncService.Result r = calendar.apply(s.organizationId(), s.id(), CalendarModels.ICAL, scope, events, List.of());
            int removed = calendar.removeMissing(s.organizationId(), s.id(), events.stream().map(CalendarSynced.Event::uid).toList(),
                    row -> true);
            return new Outcome(true, r.added(), r.updated(), r.removed() + removed, null);
        });
    }

    /** 카테고리 → 일정 유형(대소문자 무시). 맞는 매핑이 없으면 EVENT */
    static String mapType(List<String> categories, Map<String, String> mapping) {
        for (String c : categories) {
            String t = mapping.get(c.toLowerCase(Locale.ROOT));
            if (t != null) {
                return t;
            }
        }
        return "EVENT";
    }

    static Map<String, String> typeMapping(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asString("EVENT").toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** iCal 본문: 업로드 파일(db:ical_files/&lt;id&gt;) 또는 URL(webcal → https) */
    String icalText(ContextSource s) {
        String key = s.connection().path("fileObjectKey").asString("");
        if (!key.isBlank()) {
            long id = ContextSourceService.icalFileId(key);
            byte[] data = repository.findIcalFile(s.organizationId(), id)
                    .orElseThrow(() -> new IllegalStateException("iCal 파일을 찾을 수 없습니다: " + key));
            return new String(data, StandardCharsets.UTF_8);
        }
        String url = s.connection().path("url").asString("");
        return fetch(ContextSourceService.httpUrl(url));
    }

    String fetch(String url) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(properties.httpTimeout()).GET()
                    .header("Accept", "text/calendar").build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException ex) {
            throw new ProviderException(ProviderException.Kind.TIMEOUT, "시간 초과", ex);
        } catch (IOException ex) {
            throw new ProviderException(ProviderException.Kind.OTHER, "연결 실패: " + ex.getClass().getSimpleName(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.OTHER, "중단됨", ex);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new ProviderException(response.statusCode() == 401 || response.statusCode() == 403 ? ProviderException.Kind.AUTH
                        : ProviderException.Kind.OTHER, "HTTP " + response.statusCode());
            }
            byte[] data = in.readNBytes(MAX_ICAL_BYTES + 1);
            if (data.length > MAX_ICAL_BYTES) {
                throw new ProviderException(ProviderException.Kind.PROTOCOL, "2MB를 넘습니다");
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ProviderException(ProviderException.Kind.OTHER, "읽기 실패", ex);
        }
    }

    /** 결과 기록·재시도 예약·상태·알람 */
    private void record(ContextSource s, SyncState before, Outcome o, Instant now) {
        String key = AlarmKeys.system(SYNC_ALARM, Long.toString(s.id()));
        health.lockState(s.organizationId(), s.id());
        if (o.ok()) {
            repository.saveSync(s.organizationId(), new SyncState(s.id(), nextRegular(s, now), 0, now, now, "SUCCEEDED", null, o.added(),
                    o.updated(), o.removed()), now);
            health.updateConnectionState(s.organizationId(), s.id(), "CONNECTED", null, now, now);
            alarms.clearByKey(s.organizationId(), key, null, now, AlarmClearReason.AUTO, "SYSTEM", null);
            return;
        }
        int retry = before == null ? 0 : before.retryCount();
        Instant lastSuccess = before == null ? null : before.lastSuccessAt();
        if (retry < BACKOFF.length) {
            repository.saveSync(s.organizationId(), new SyncState(s.id(), now.plus(BACKOFF[retry]), retry + 1, now, lastSuccess, "FAILED",
                    truncate(o.error()), 0, 0, 0), now);
            return;
        }
        repository.saveSync(s.organizationId(), new SyncState(s.id(), nextRegular(s, now), 0, now, lastSuccess, "FAILED",
                truncate(o.error()), 0, 0, 0), now);
        health.updateConnectionState(s.organizationId(), s.id(), "ERROR", errorKind(o.error()), now, now);
        alarms.raise(new AlarmService.Raise(s.organizationId(), key, AlarmSourceType.SYSTEM, null, null, null, AlarmSeverity.MAJOR,
                "외부 맥락 갱신 실패: " + s.name(), null, s.siteId(), null, null, null, null, now, "SYSTEM", false, false));
    }

    /** 다음 정기 실행: 공휴일은 다음 달 1일 03:00(현지), iCal은 refreshHours 뒤 */
    Instant nextRegular(ContextSource s, Instant now) {
        if ("HOLIDAY".equals(s.type())) {
            ZoneId zone = zone(s);
            LocalDate first = LocalDate.ofInstant(now, zone).withDayOfMonth(1).plusMonths(1);
            return first.atTime(3, 0).atZone(zone).toInstant();
        }
        int hours = s.connection().path("refreshHours").asInt(6);
        return now.plus(Duration.ofHours(Math.max(1, hours)));
    }

    private ZoneId zone(ContextSource s) {
        if (s.siteId() == null) {
            return SpaceSupport.DEFAULT_ZONE;
        }
        return spaces.findById(s.organizationId(), s.siteId()).map(Space::timezone).filter(t -> t != null && !t.isBlank())
                .map(ZoneId::of).orElse(SpaceSupport.DEFAULT_ZONE);
    }

    static String errorKind(String error) {
        if (error == null) {
            return "OTHER";
        }
        for (ProviderException.Kind k : ProviderException.Kind.values()) {
            if (error.startsWith(k.name() + ":")) {
                return k == ProviderException.Kind.OTHER ? "OTHER" : k.name();
            }
        }
        return "OTHER";
    }

    static String message(RuntimeException ex) {
        if (ex instanceof ProviderException p) {
            return p.kind().name() + ": " + p.getMessage();
        }
        if (ex instanceof ICalParser.ICalException) {
            return "PROTOCOL: " + ex.getMessage();
        }
        if (ex instanceof net.java21.data2flow.contracts.error.BusinessException b) {
            return "QUOTA: " + b.getErrorCode().code();
        }
        return "OTHER: " + ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : " " + ex.getMessage());
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
