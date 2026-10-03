package net.java21.data2flow.core.live.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SimFaultLabel;
import net.java21.data2flow.contracts.message.event.SimRunChanged;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.config.LoopProperties;
import net.java21.data2flow.core.live.domain.Subscription;
import net.java21.data2flow.core.sim.domain.SimErrorCode;
import net.java21.data2flow.core.sim.service.SimulatorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 가상 환경 실행 실시간 스트림(API-SIM-31 {@code GET /core/stream/sim/runs/{run-id}}, SIM-04.02). simulator는 틱 이벤트를 내지 않으므로
 * core가 1초마다 실행 상태({@code GET /internal/sim/runs/{id}})와 공간 현재값({@code GET /internal/sim/spaces})을 읽어 {@code sim.tick}을
 * 만들고, 실행 상태 이벤트(EVT-SIM-01)·장애 라벨(EVT-SIM-02)은 받는 즉시 보낸다.
 *
 * <ul>
 *   <li>{@code sim.tick}: {@code {runId, simClock, progressPct, spaces:{spaceId:{temperature, humidity, co2, pm2_5, occupancy}}, actuators:{}}}</li>
 *   <li>{@code sim.event}: {@code {simAt, type, message}} — 실행 기록(lastEvents)의 새 항목, 장애 시작·끝(FAULT_STARTED·FAULT_ENDED)</li>
 *   <li>{@code sim.status}: {@code {runId, status, simClock, accelerationEffective, partial?, failureReason?, at}} — 연결 직후 한 번, 상태가 바뀔 때.
 *       끝 상태(COMPLETED·STOPPED·FAILED·PURGED)면 보낸 뒤 연결을 닫는다</li>
 *   <li>{@code sim.throttle}: {@code {from, to, reason}} — 실제 가속이 줄거나 돌아올 때(SIM-11.02 처리량 한도)</li>
 * </ul>
 * 연결은 파드에만 있고(손실 허용), 다시 연결하면 처음 상태부터 다시 받는다.
 */
@Component
public class SimRunStreams implements SmartLifecycle {

    static final Set<String> TERMINAL = Set.of("COMPLETED", "STOPPED", "FAILED", "PURGED");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(SimRunStreams.class);

    private final Map<RunKey, RunState> runs = new ConcurrentHashMap<>();
    private final RoleChecker roleChecker;
    private final SimulatorClient simulator;
    private final JsonMapper json;
    private final Clock clock;
    private final CoreProperties.Live live;
    private final LoopProperties loop;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public SimRunStreams(RoleChecker roleChecker, SimulatorClient simulator, JsonMapper json, Clock clock, CoreProperties core,
                         LoopProperties loop) {
        this.roleChecker = roleChecker;
        this.simulator = simulator;
        this.json = json;
        this.clock = clock;
        this.live = core.live();
        this.loop = loop;
    }

    record RunKey(long organizationId, String runId) {
    }

    /** 실행 하나를 보는 연결들과 마지막으로 본 값 */
    static final class RunState {
        final Set<LiveConnection> connections = ConcurrentHashMap.newKeySet();
        final Set<String> seenEvents = ConcurrentHashMap.newKeySet();
        volatile String status;
        volatile Integer acceleration;
        volatile long userId;
    }

    /** 연결 열기 — SIM_READ. 실행이 없거나 다른 조직이면 404 SIM_NOT_FOUND(simulator 판정) */
    public SseEmitter open(String runId) {
        AccessGrant grant = roleChecker.require(Permission.SIM_READ);
        CurrentUser user = roleChecker.currentUser();
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        JsonNode run = simulator.call(HttpMethod.GET, "/internal/sim/runs/" + runId, null, null);
        RunKey key = new RunKey(user.organizationId(), runId);
        RunState state = runs.computeIfAbsent(key, k -> new RunState());
        state.userId = user.userId();
        SseEmitter emitter = new SseEmitter(live.emitterTimeout().toMillis());
        LiveConnection connection = new LiveConnection(user.organizationId(), user.userId(), null, List.of(),
                Subscription.empty(grant), emitter, live.queueCapacity(), c -> remove(key, c));
        state.connections.add(connection);
        connection.start();
        if (run != null) {
            for (JsonNode e : run.path("lastEvents").values()) {
                state.seenEvents.add(eventKey(e));
            }
            state.status = run.path("status").asString(null);
            state.acceleration = run.hasNonNull("accelerationEffective") ? run.get("accelerationEffective").asInt() : null;
            connection.send("sim.status", write(statusOf(runId, run)));
            if (TERMINAL.contains(state.status)) {
                connection.close(null, null);
            }
        }
        return emitter;
    }

    private void remove(RunKey key, LiveConnection connection) {
        RunState state = runs.get(key);
        if (state != null) {
            state.connections.remove(connection);
            if (state.connections.isEmpty()) {
                runs.remove(key, state);
            }
        }
    }

    public int size() {
        return runs.values().stream().mapToInt(s -> s.connections.size()).sum();
    }

    /** 도메인 이벤트(파드별 임시 큐): 실행 상태 → sim.status(+sim.throttle), 장애 라벨 → sim.event */
    public void onEvent(DomainEvent<?> event) {
        switch (event.payload()) {
            case SimRunChanged p -> {
                RunState state = runs.get(new RunKey(event.organizationId(), Long.toString(p.runId())));
                if (state == null) {
                    return;
                }
                if (event.eventType() == EventType.SIM_RUN_THROTTLED || (state.acceleration != null
                        && state.acceleration != p.accelerationEffective())) {
                    broadcast(state, "sim.throttle", write(throttle(state.acceleration, p.accelerationEffective())));
                }
                state.acceleration = p.accelerationEffective();
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("runId", Long.toString(p.runId()));
                status.put("status", p.status());
                status.put("simClock", p.simClock());
                status.put("accelerationEffective", p.accelerationEffective());
                if (p.partial() != null) {
                    status.put("partial", p.partial());
                }
                if (p.failureReason() != null) {
                    status.put("failureReason", p.failureReason());
                }
                status.put("at", p.at() == null ? clock.instant() : p.at());
                state.status = p.status();
                broadcast(state, "sim.status", write(status));
                if (TERMINAL.contains(p.status())) {
                    state.connections.forEach(c -> c.close(null, null));
                }
            }
            case SimFaultLabel p -> {
                if (p.runId() == null) {
                    return;
                }
                RunState state = runs.get(new RunKey(event.organizationId(), Long.toString(p.runId())));
                if (state == null) {
                    return;
                }
                boolean started = event.eventType() == EventType.SIM_FAULT_STARTED;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("simAt", started || p.simTo() == null ? p.simFrom() : p.simTo());
                e.put("type", started ? "FAULT_STARTED" : "FAULT_ENDED");
                e.put("message", p.kind() + " " + p.targetType() + ":" + p.targetId());
                e.put("faultId", Long.toString(p.faultId()));
                broadcast(state, "sim.event", write(e));
            }
            default -> log.trace("실행 스트림이 쓰지 않는 이벤트 {}", event.type());
        }
    }

    /** 1초 틱: 열린 실행마다 상태·공간 현재값을 읽어 sim.tick, 새 실행 기록은 sim.event, 상태 변화는 sim.status */
    public void tick() {
        for (Map.Entry<RunKey, RunState> entry : List.copyOf(runs.entrySet())) {
            RunKey key = entry.getKey();
            RunState state = entry.getValue();
            if (state.connections.isEmpty()) {
                continue;
            }
            try {
                CurrentUserHolder.callAs(new CurrentUser(state.userId, key.organizationId(), null, Set.of()), () -> {
                    tickOne(key, state);
                    return null;
                });
            } catch (RuntimeException ex) {
                log.debug("실행 {} 틱 실패(다음 틱에 다시): {}", key.runId(), ex.toString());
            }
        }
    }

    private void tickOne(RunKey key, RunState state) {
        JsonNode run = simulator.call(HttpMethod.GET, "/internal/sim/runs/" + key.runId(), null, null);
        if (run == null) {
            return;
        }
        JsonNode spaces = simulator.call(HttpMethod.GET, "/internal/sim/spaces", null, null);
        Map<String, Object> currents = new LinkedHashMap<>();
        if (spaces != null && spaces.isArray()) {
            for (JsonNode s : spaces.values()) {
                JsonNode current = s.get("current");
                if (current != null && current.isObject()) {
                    currents.put(s.path("spaceId").asString(""), current);
                }
            }
        }
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("runId", key.runId());
        tick.put("simClock", run.get("simClock"));
        tick.put("progressPct", run.get("progressPct"));
        tick.put("spaces", currents);
        tick.put("actuators", Map.of());
        broadcast(state, "sim.tick", write(tick));
        for (JsonNode e : run.path("lastEvents").values()) {
            if (state.seenEvents.add(eventKey(e))) {
                broadcast(state, "sim.event", write(e));
            }
        }
        Integer accel = run.hasNonNull("accelerationEffective") ? run.get("accelerationEffective").asInt() : null;
        if (accel != null && state.acceleration != null && !accel.equals(state.acceleration)) {
            broadcast(state, "sim.throttle", write(throttle(state.acceleration, accel)));
        }
        if (accel != null) {
            state.acceleration = accel;
        }
        String status = run.path("status").asString(null);
        if (status != null && !status.equals(state.status)) {
            state.status = status;
            broadcast(state, "sim.status", write(statusOf(key.runId(), run)));
            if (TERMINAL.contains(status)) {
                state.connections.forEach(c -> c.close(null, null));
            }
        }
    }

    /** 15초 ping(연결 유지) */
    public void pingAll() {
        String ping = write(Map.of("at", clock.instant()));
        runs.values().forEach(s -> broadcast(s, "ping", ping));
    }

    private Map<String, Object> statusOf(String runId, JsonNode run) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("runId", runId);
        status.put("status", run.path("status").asString(null));
        status.put("simClock", run.get("simClock"));
        status.put("accelerationEffective", run.get("accelerationEffective"));
        status.put("progressPct", run.get("progressPct"));
        if (run.hasNonNull("failureReason")) {
            status.put("failureReason", run.get("failureReason"));
        }
        status.put("at", clock.instant());
        return status;
    }

    private static Map<String, Object> throttle(Integer from, int to) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("from", from);
        t.put("to", to);
        t.put("reason", from != null && to < from ? "THROUGHPUT_LIMIT" : "RECOVERED");
        return t;
    }

    private static String eventKey(JsonNode e) {
        return e.path("simAt").asString("") + "|" + e.path("type").asString("") + "|" + e.path("message").asString("");
    }

    private static void broadcast(RunState state, String event, String body) {
        for (LiveConnection c : List.copyOf(state.connections)) {
            if (c.isOpen()) {
                c.send(event, body);
            }
        }
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    @Override
    public void start() {
        running = true;
        if (!loop.simTickEnabled()) {
            return;
        }
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1, Thread.ofPlatform().daemon().name("sim-tick-", 0).factory());
        every(s, loop.simTick(), this::tick);
        every(s, live.pingInterval(), this::pingAll);
        scheduler = s;
    }

    private static void every(ScheduledExecutorService s, Duration interval, Runnable task) {
        long ms = Math.max(200, interval.toMillis());
        s.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.warn("실행 스트림 주기 작업 실패(다음 주기에 다시): {}", ex.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
        List<LiveConnection> all = new ArrayList<>();
        runs.values().forEach(r -> all.addAll(r.connections));
        all.forEach(c -> c.close(null, null));
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
