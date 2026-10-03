package net.java21.data2flow.core.live.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.event.CommandStatusChanged;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.contracts.message.event.DeviceStateChanged;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DevicePendingCreated;
import net.java21.data2flow.contracts.message.event.SourceConnectionChanged;
import net.java21.data2flow.contracts.message.event.SpaceChanged;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.IngestMonitorResponse;
import net.java21.data2flow.core.dashboard.service.HomeSummaryService;
import net.java21.data2flow.core.dashboard.service.IngestFlowService;
import net.java21.data2flow.core.live.domain.LiveTopic;
import net.java21.data2flow.core.live.domain.Subscription;
import net.java21.data2flow.core.live.dto.LiveDtos;
import net.java21.data2flow.core.live.dto.LiveDtos.DeviceUpdate;
import net.java21.data2flow.core.live.dto.LiveDtos.IngestMessage;
import net.java21.data2flow.core.live.dto.LiveDtos.MetricUpdate;
import net.java21.data2flow.core.live.dto.LiveDtos.Ping;
import net.java21.data2flow.core.live.dto.LiveDtos.Point;
import net.java21.data2flow.core.live.dto.LiveDtos.SessionRevoked;
import net.java21.data2flow.core.live.repository.LiveRepository;
import net.java21.data2flow.core.live.repository.LiveRepository.DeviceRef;
import net.java21.data2flow.core.live.repository.LiveRepository.RawRow;
import net.java21.data2flow.core.live.repository.LiveRepository.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 파드 안의 실시간 연결 모음과 전달(API-DSH-20·21, DSH-05.01). 텔레메트리 스트림·도메인 이벤트를 받아 조직과 권한으로 거른 뒤
 * 연결마다 보낸다. 주기 작업(설정 {@code data2flow.core.live.*}):
 * <ul>
 *   <li>{@link #pingAll()} 15초: {@code ping}, 그리고 세션 폐기·계정 비활성이면 {@code session-revoked} 후 닫음(IAM-07.06)</li>
 *   <li>{@link #recheckAll()} 60초: 권한·공간 범위 재판정. 줄어든 토픽은 그때부터 이벤트가 없다(TC-DSH-051)</li>
 *   <li>{@link #homeTick()} 5초: 바뀐 조직만 홈 요약을 다시 계산해 바뀐 필드만 {@code home-summary}로(최대 60초마다는 다시 비교)</li>
 *   <li>{@link #ingestTick()} 5초: {@code ingest-stats}</li>
 *   <li>{@link #pollMessages()} 1초: 원본 메시지 폴링 → {@code message}(초당 20건 상한)</li>
 * </ul>
 * {@code Last-Event-ID}로 다시 보내 주는(재생) 이벤트는 M2에 없다: 알림(notifications)은 M5이고, 홈은 다시 연결하면 처음에 전체 요약을
 * 보내며, 텔레메트리는 최신값부터다(API-DSH-20). 그래서 이벤트에 {@code id:}를 달지 않는다.
 */
@Component
public class LiveHub implements SmartLifecycle {

    /** API-DSH-21 서버 측 표본: 연결당 초당 최대 건수 */
    public static final int MESSAGES_PER_SECOND = 20;
    /** 원본 payload 표시 상한(API-DSH-21 "raw 최대 4KB") */
    public static final int RAW_LIMIT_BYTES = 4096;
    /** 처리 결과가 기록될 시간을 주려고 이만큼 지난 원본만 보낸다 */
    static final Duration MESSAGE_SETTLE = Duration.ofSeconds(2);
    static final Duration HOME_REFRESH = Duration.ofSeconds(60);
    static final int MESSAGE_BATCH = 500;

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    private final Map<String, LiveConnection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Set<LiveConnection>> byOrganization = new ConcurrentHashMap<>();
    private final Set<Long> homeDirty = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> messageCursors = new ConcurrentHashMap<>();

    private final LiveRepository repository;
    private final LiveSubscriptions subscriptions;
    private final PermissionLookup permissions;
    private final HomeSummaryService home;
    private final IngestFlowService ingest;
    private final JsonMapper json;
    private final Clock clock;
    private final CoreProperties.Live settings;

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public LiveHub(LiveRepository repository, LiveSubscriptions subscriptions, PermissionLookup permissions, HomeSummaryService home,
                   IngestFlowService ingest, JsonMapper json, Clock clock, CoreProperties properties) {
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.permissions = permissions;
        this.home = home;
        this.ingest = ingest;
        this.json = json;
        this.clock = clock;
        this.settings = properties.live();
    }

    // ------------------------------------------------------------------ 연결

    void register(LiveConnection connection) {
        connections.put(connection.id(), connection);
        byOrganization.computeIfAbsent(connection.organizationId(), k -> ConcurrentHashMap.newKeySet()).add(connection);
        if (!connection.subscription().messages().isEmpty()) {
            messageCursors.computeIfAbsent(connection.organizationId(),
                    org -> repository.findMaxRawMessageId(org, clock.instant().minus(Duration.ofMinutes(1))));
        }
        connection.start();
    }

    void remove(LiveConnection connection) {
        connections.remove(connection.id());
        Set<LiveConnection> set = byOrganization.get(connection.organizationId());
        if (set != null) {
            set.remove(connection);
        }
    }

    /** 열린 연결 수(지표·테스트) */
    public int size() {
        return connections.size();
    }

    /** 모든 연결을 닫는다(종료·테스트). 브라우저는 다른 파드로 다시 연결한다 */
    public void closeAll() {
        for (LiveConnection c : List.copyOf(connections.values())) {
            c.close(null, null);
        }
        connections.clear();
        byOrganization.clear();
        messageCursors.clear();
        homeDirty.clear();
    }

    Collection<LiveConnection> connections() {
        return connections.values();
    }

    private Collection<LiveConnection> of(long organizationId) {
        Set<LiveConnection> set = byOrganization.get(organizationId);
        return set == null ? List.of() : set;
    }

    // ------------------------------------------------------------------ 입력: 텔레메트리·도메인 이벤트

    /** {@code data2flow.telemetry} 한 건(그룹 core-live). 조직의 연결에만, 공간 범위 안일 때만 */
    public void onTelemetry(CanonicalTelemetry t) {
        Collection<LiveConnection> targets = of(t.organizationId());
        if (targets.isEmpty()) {
            return;
        }
        homeDirty.add(t.organizationId());
        String deviceId = Long.toString(t.deviceId());
        String update = null;
        for (LiveConnection c : targets) {
            Subscription s = c.subscription();
            if (!c.isOpen() || !s.grant().spaceScope().includes(t.spaceId())) {
                continue;
            }
            if (!s.spaceTopicsFor(t.spaceId()).isEmpty()) {
                if (update == null) {
                    List<MetricUpdate> metrics = t.metrics().stream()
                            .map(m -> new MetricUpdate(m.key(), m.value(), m.unit(), m.quality(), t.measuredAt())).toList();
                    update = write(new DeviceUpdate(deviceId, metrics, "ONLINE", t.deviceStatus().name()));
                }
                c.send("device-update", update);
            }
            for (CanonicalTelemetry.Metric m : t.metrics()) {
                if (s.wantsPoint(t.deviceId(), m.key())) {
                    c.send("point", write(new Point(deviceId, m.key(), t.measuredAt(), m.value(), m.quality(), t.virtual())));
                }
            }
        }
    }

    /** {@code data2flow.events} 한 건(파드별 임시 큐). 홈을 다시 계산하게 표시하고, 기기 변경은 space 토픽으로 보낸다 */
    public void onEvent(DomainEvent<?> event) {
        long org = event.organizationId();
        Collection<LiveConnection> targets = of(org);
        if (targets.isEmpty()) {
            return;
        }
        homeDirty.add(org);
        switch (event.payload()) {
            case DeviceConnectivityChanged p -> {
                DeviceRef device = repository.findDevice(org, p.deviceId()).orElse(null);
                if (device != null) {
                    deviceUpdate(targets, device.spaceId(), new DeviceUpdate(Long.toString(p.deviceId()), null, p.to().name(),
                            device.status()));
                }
            }
            case DeviceChanged p -> {
                deviceUpdate(targets, p.spaceId(), new DeviceUpdate(Long.toString(p.deviceId()), null, null, p.status()));
                if (p.change() == DeviceChanged.Change.UPDATED || p.change() == DeviceChanged.Change.DELETED
                        || p.change() == DeviceChanged.Change.APPROVED || p.change() == DeviceChanged.Change.REPLACED) {
                    refreshSubscriptions(targets); // 기기 이동·삭제: telemetry 토픽 판정을 바로 다시
                }
            }
            case SpaceChanged p -> refreshSubscriptions(targets);
            case DevicePendingCreated p -> log.trace("승인 대기 기기 {}: 홈 다시 계산", p.deviceId());
            case SourceConnectionChanged p -> sourceState(targets, p);
            case CommandStatusChanged p -> commandStatus(org, targets, p);
            case DeviceStateChanged p -> actuatorState(org, targets, p);
            default -> log.trace("실시간 화면이 쓰지 않는 이벤트 {}", event.type());
        }
    }

    /** {@code commands:{deviceId}} 토픽에 명령 상태를 보낸다(ACT-04.02, 같은 조직·구독한 기기만). 출처 표시 이름을 붙인다 */
    private void commandStatus(long org, Collection<LiveConnection> targets, CommandStatusChanged p) {
        if (targets.stream().noneMatch(c -> c.isOpen() && c.subscription().commandDevices().contains(p.deviceId()))) {
            return;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        if (p.source() != null) {
            Map<String, Object> raw = json.convertValue(p.source(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
            raw.forEach((k, v) -> {
                if (v != null) {
                    source.put(k, v);
                }
            });
            repository.findSourceNames(org, p.source().flowId(), p.source().userId()).forEach(source::put);
        }
        String body = write(new LiveDtos.CommandStatus(p.commandId() == null ? null : p.commandId().toString(),
                Long.toString(p.deviceId()), p.capability(), p.command(), p.status() == null ? null : p.status().name(), p.reason(),
                p.message(), source.isEmpty() ? null : source, p.at() == null ? clock.instant() : p.at()));
        for (LiveConnection c : targets) {
            if (c.isOpen() && c.subscription().commandDevices().contains(p.deviceId())) {
                c.send("command-status", body);
            }
        }
    }

    /** 액추에이터 보고 상태(EVT-ACT-02)를 그 기기 공간을 보는 {@code space:{id}} 토픽에 {@code device-update}로 보낸다 */
    private void actuatorState(long org, Collection<LiveConnection> targets, DeviceStateChanged p) {
        Long spaceId = p.spaceId();
        if (spaceId == null) {
            spaceId = repository.findDevice(org, p.deviceId()).map(DeviceRef::spaceId).orElse(null);
        }
        if (spaceId == null) {
            return;
        }
        String body = write(new LiveDtos.ActuatorUpdate(Long.toString(p.deviceId()),
                p.connectivity() == null ? null : p.connectivity().name(),
                new LiveDtos.ActuatorState(p.reported(), p.delta(), p.reportedVersion(), p.origin() == null ? null : p.origin().name(),
                        p.at() == null ? clock.instant() : p.at())));
        for (LiveConnection c : targets) {
            Subscription s = c.subscription();
            if (c.isOpen() && s.grant().spaceScope().includes(spaceId) && !s.spaceTopicsFor(spaceId).isEmpty()) {
                c.send("device-update", body);
            }
        }
    }

    /** {@code sources} 토픽에 소스 연결 상태 변경을 보낸다(DSC-02.01, 같은 조직 연결만) */
    private void sourceState(Collection<LiveConnection> targets, SourceConnectionChanged p) {
        String body = write(new LiveDtos.SourceState(Long.toString(p.sourceId()), p.to() == null ? null : p.to().name(),
                p.from() == null ? null : p.from().name(), p.errorKind() == null ? null : p.errorKind().name(),
                p.at() == null ? clock.instant() : p.at()));
        for (LiveConnection c : targets) {
            if (c.isOpen() && c.subscription().sources()) {
                c.send("source-state", body);
            }
        }
    }

    private void deviceUpdate(Collection<LiveConnection> targets, Long spaceId, DeviceUpdate update) {
        if (spaceId == null) {
            return;
        }
        String body = write(update);
        for (LiveConnection c : targets) {
            Subscription s = c.subscription();
            if (c.isOpen() && s.grant().spaceScope().includes(spaceId) && !s.spaceTopicsFor(spaceId).isEmpty()) {
                c.send("device-update", body);
            }
        }
    }

    private void refreshSubscriptions(Collection<LiveConnection> targets) {
        for (LiveConnection c : targets) {
            if (c.isOpen()) {
                c.subscription(subscriptions.resolve(c.organizationId(), c.subscription().grant(), c.topics()));
            }
        }
    }

    // ------------------------------------------------------------------ 주기 작업

    /** ping과 세션·계정 확인(IAM-07.06) */
    public void pingAll() {
        String ping = write(new Ping(clock.instant()));
        for (LiveConnection c : List.copyOf(connections.values())) {
            if (!c.isOpen()) {
                continue;
            }
            SessionState state = repository.findSessionState(c.organizationId(), c.userId(), c.sessionId());
            if (!state.userActive()) {
                c.close("session-revoked", write(new SessionRevoked("USER_INACTIVE")));
            } else if (state.sessionRevoked()) {
                c.close("session-revoked", write(new SessionRevoked("SESSION_REVOKED")));
            } else {
                c.send("ping", ping);
            }
        }
    }

    /** 권한·공간 범위 재판정(TC-DSH-051 "구독 중 권한이 줄면 60초 안에 해당 토픽 중단") */
    public void recheckAll() {
        for (LiveConnection c : List.copyOf(connections.values())) {
            if (c.isOpen()) {
                AccessGrant grant = permissions.find(c.organizationId(), c.userId());
                c.subscription(subscriptions.resolve(c.organizationId(), grant, c.topics()));
            }
        }
    }

    /** 홈 요약 묶음(5초). 바뀐 필드만 보낸다 */
    public void homeTick() {
        Instant now = clock.instant();
        Set<Long> dirty = Set.copyOf(homeDirty);
        homeDirty.removeAll(dirty);
        Map<GrantKey, JsonNode> cache = new HashMap<>();
        for (LiveConnection c : List.copyOf(connections.values())) {
            Subscription s = c.subscription();
            if (!c.isOpen() || !s.home()) {
                continue;
            }
            boolean stale = c.lastHomeAt() == null || !c.lastHomeAt().plus(HOME_REFRESH).isAfter(now);
            if (!dirty.contains(c.organizationId()) && !stale) {
                continue;
            }
            JsonNode summary = cache.computeIfAbsent(new GrantKey(c.organizationId(), s.grant()),
                    k -> json.valueToTree(home.compute(k.organizationId(), k.grant())));
            sendHome(c, summary, now);
        }
    }

    /** 연결 직후 전체 홈 요약(재연결 때 놓친 변경을 메운다) */
    void sendInitialHome(LiveConnection c) {
        Subscription s = c.subscription();
        if (s.home()) {
            sendHome(c, json.valueToTree(home.compute(c.organizationId(), s.grant())), clock.instant());
        }
    }

    private void sendHome(LiveConnection c, JsonNode summary, Instant now) {
        if (!c.homeBusy().compareAndSet(false, true)) {
            return;
        }
        try {
            ObjectNode diff = json.createObjectNode();
            JsonNode previous = c.lastHome();
            for (Map.Entry<String, JsonNode> e : summary.properties()) {
                if (previous == null || !e.getValue().equals(previous.get(e.getKey()))) {
                    diff.set(e.getKey(), e.getValue());
                }
            }
            if (previous != null) {
                for (Map.Entry<String, JsonNode> e : previous.properties()) {
                    if (!summary.has(e.getKey())) {
                        diff.putNull(e.getKey()); // 권한이 줄어 빠진 카드(pendingDevices·sources)
                    }
                }
            }
            if (!diff.isEmpty()) {
                c.send("home-summary", json.writeValueAsString(diff));
            }
            c.homeSent(summary, now);
        } finally {
            c.homeBusy().set(false);
        }
    }

    /** 수집 흐름 통계(5초) */
    public void ingestTick() {
        Map<ScopeKey, String> cache = new HashMap<>();
        for (LiveConnection c : List.copyOf(connections.values())) {
            Subscription s = c.subscription();
            if (!c.isOpen() || !s.ingest()) {
                continue;
            }
            String body = cache.computeIfAbsent(new ScopeKey(c.organizationId(), s.grant().spaceScope()), k -> {
                IngestMonitorResponse r = ingest.live(k.organizationId(), k.scope());
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("stages", r.stages());
                stats.put("sources", r.sources());
                return write(stats);
            });
            c.send("ingest-stats", body);
        }
    }

    /** 원본 메시지 폴링(1초). 처리 결과가 기록될 2초가 지난 것만, 연결 필터·범위·초당 상한에 맞춰 보낸다 */
    public void pollMessages() {
        Instant now = clock.instant();
        Map<Long, List<LiveConnection>> byOrg = new HashMap<>();
        for (LiveConnection c : connections.values()) {
            if (c.isOpen() && !c.subscription().messages().isEmpty()) {
                byOrg.computeIfAbsent(c.organizationId(), k -> new ArrayList<>()).add(c);
            }
        }
        messageCursors.keySet().retainAll(byOrg.keySet());
        for (Map.Entry<Long, List<LiveConnection>> e : byOrg.entrySet()) {
            long org = e.getKey();
            long cursor = messageCursors.computeIfAbsent(org, o -> repository.findMaxRawMessageId(o, now.minus(Duration.ofMinutes(1))));
            List<RawRow> rows = repository.findRawMessagesAfter(org, cursor, now.minus(Duration.ofHours(1)), MESSAGE_BATCH);
            Instant settled = now.minus(MESSAGE_SETTLE);
            List<RawRow> ready = new ArrayList<>();
            for (RawRow row : rows) {
                if (row.receivedAt().isAfter(settled)) {
                    break;
                }
                ready.add(row);
            }
            if (ready.isEmpty()) {
                continue;
            }
            messageCursors.put(org, ready.getLast().id());
            Map<Long, DeviceRef> devices = repository.findDevices(org,
                    ready.stream().map(RawRow::deviceId).filter(java.util.Objects::nonNull).distinct().toList());
            for (RawRow row : ready) {
                DeviceRef device = row.deviceId() == null ? null : devices.get(row.deviceId());
                String withRaw = null;
                String withoutRaw = null;
                for (LiveConnection c : e.getValue()) {
                    Subscription s = c.subscription();
                    if (!visible(s.grant().spaceScope(), row, device) || !matches(s.messages(), row)
                            || !c.allowMessage(now.getEpochSecond(), MESSAGES_PER_SECOND)) {
                        continue;
                    }
                    if (s.payload()) {
                        withRaw = withRaw != null ? withRaw : write(message(row, true));
                        c.send("message", withRaw);
                    } else {
                        withoutRaw = withoutRaw != null ? withoutRaw : write(message(row, false));
                        c.send("message", withoutRaw);
                    }
                }
            }
        }
    }

    /** 범위가 제한된 사용자는 범위 안 공간의 기기 메시지만(기기를 모르는 메시지는 보이지 않는다) */
    static boolean visible(SpaceScope scope, RawRow row, DeviceRef device) {
        if (scope.unrestricted()) {
            return true;
        }
        return device != null && device.spaceId() != null && scope.includes(device.spaceId());
    }

    static boolean matches(List<LiveTopic.IngestMessages> filters, RawRow row) {
        for (LiveTopic.IngestMessages f : filters) {
            if ((f.sourceId() == null || f.sourceId() == row.sourceId())
                    && (f.deviceId() == null || f.deviceId().equals(row.deviceId()))
                    && (f.result() == null || f.result().equals(row.status()))) {
                return true;
            }
        }
        return false;
    }

    IngestMessage message(RawRow row, boolean withRaw) {
        String raw = null;
        String encoding = null;
        Boolean truncated = null;
        if (withRaw && row.payload() != null) {
            byte[] payload = row.payload();
            truncated = payload.length > RAW_LIMIT_BYTES;
            byte[] head = truncated ? java.util.Arrays.copyOf(payload, RAW_LIMIT_BYTES) : payload;
            if ("BINARY".equals(row.encoding())) {
                encoding = "BASE64";
                raw = Base64.getEncoder().encodeToString(head);
            } else {
                encoding = "TEXT";
                raw = decodeUtf8(head);
            }
        }
        Object canonical = null;
        if (row.trace() != null) {
            JsonNode trace = json.readTree(row.trace());
            if (trace.hasNonNull("canonical")) {
                canonical = trace.get("canonical");
            }
        }
        return new IngestMessage(Long.toString(row.id()), row.receivedAt(), Long.toString(row.sourceId()), row.topic(),
                row.deviceId() == null ? null : Long.toString(row.deviceId()), row.externalId(), row.status(), row.errorCode(),
                raw, encoding, truncated, canonical);
    }

    /** 잘린 UTF-8 끝 글자는 대체 문자로 */
    static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    String write(Object value) {
        return json.writeValueAsString(value);
    }

    // ------------------------------------------------------------------ 수명

    @Override
    public void start() {
        running = true;
        if (!settings.schedulerEnabled()) {
            return;
        }
        ScheduledExecutorService s = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("live-tick-", 0).factory());
        every(s, settings.pingInterval(), this::pingAll);
        every(s, settings.permissionRecheck(), this::recheckAll);
        every(s, settings.homeInterval(), this::homeTick);
        every(s, settings.ingestInterval(), this::ingestTick);
        every(s, settings.messagePoll(), this::pollMessages);
        scheduler = s;
    }

    private static void every(ScheduledExecutorService s, Duration interval, Runnable task) {
        long ms = Math.max(100, interval.toMillis());
        s.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.warn("실시간 화면 주기 작업 실패(다음 주기에 다시): {}", ex.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** 종료: 연결을 먼저 닫아 웹 서버의 graceful shutdown이 SSE를 기다리지 않게 한다 */
    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
        closeAll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record GrantKey(long organizationId, AccessGrant grant) {
    }

    private record ScopeKey(long organizationId, SpaceScope scope) {
    }
}
