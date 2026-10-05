package net.java21.data2flow.core.live.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.service.AnalyticsAccess;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.analytics.service.AnalysisService;
import net.java21.data2flow.core.config.AnalyticsProperties;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.live.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 분석 실행 상태 스트림(API-ANA-16 {@code GET /core/stream/analytics/runs/{run-id}}, SSE, ANA-04.02). V 이상이고 그 실행의 분석이 보여야 한다
 * (아니면 연결 전 404 ANALYSIS_RUN_NOT_FOUND). 연결 직후 {@code run-status}를 한 번 보내고, 상태가 바뀔 때마다(실행 상태 이벤트 EVT-ANA-01을
 * 받거나 주기적으로 analytics 실행 상태를 다시 읽어) {@code run-status {runId, status, progress, stage, queuePosition}}를 보낸다.
 * 끝 상태(SUCCEEDED·FAILED·TIMEOUT·CANCELLED)면 같은 모양의 {@code run-done}을 보내고 연결을 닫는다. 연결은 파드에만 있다(손실 허용, 다시 연결하면 처음부터).
 */
@Component
public class AnalysisRunStreams implements SmartLifecycle {

    static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED");
    private static final Logger log = LoggerFactory.getLogger(AnalysisRunStreams.class);

    private final Map<Key, State> runs = new ConcurrentHashMap<>();
    private final AnalyticsAccess access;
    private final AnalyticsClient analytics;
    private final JsonMapper json;
    private final CoreProperties.Live live;
    private final AnalyticsProperties properties;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public AnalysisRunStreams(AnalyticsAccess access, AnalyticsClient analytics, JsonMapper json, CoreProperties core,
                              AnalyticsProperties properties) {
        this.access = access;
        this.analytics = analytics;
        this.json = json;
        this.live = core.live();
        this.properties = properties;
    }

    record Key(long organizationId, long runId) {
    }

    static final class State {
        final Set<LiveConnection> connections = ConcurrentHashMap.newKeySet();
        volatile String last;
        volatile long userId;
    }

    /** 연결 열기 */
    public SseEmitter open(long runId) {
        AccessGrant grant = access.view();
        CurrentUser user = access.user();
        JsonNode run = fetch(runId);
        Long analysisId = AnalysisService.longOrNull(run.get("analysisId"));
        if (analysisId == null) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        }
        try {
            access.visible(analysisId, grant);
        } catch (BusinessException ex) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        }
        Key key = new Key(user.organizationId(), runId);
        State state = runs.computeIfAbsent(key, k -> new State());
        state.userId = user.userId();
        SseEmitter emitter = new SseEmitter(live.emitterTimeout().toMillis());
        LiveConnection connection = new LiveConnection(user.organizationId(), user.userId(), null, List.of(), Subscription.empty(grant), emitter,
                live.queueCapacity(), c -> remove(key, c));
        state.connections.add(connection);
        connection.start();
        String status = status(run);
        state.last = signature(run);
        if (TERMINAL.contains(status)) {
            connection.close("run-done", write(view(runId, run)));
        } else {
            connection.send("run-status", write(view(runId, run)));
        }
        return emitter;
    }

    /** 실행 상태 이벤트(EVT-ANA-01, 파드별 임시 큐) 한 건 */
    /** EVT-ANA-01(계약 타입). analytics가 보낸 페이로드 모양 그대로 화면에 넘긴다 */
    public void onEvent(long organizationId, net.java21.data2flow.contracts.message.event.AnalyticsRunStatusChanged event) {
        onEvent(organizationId, (JsonNode) json.valueToTree(event));
    }

    public void onEvent(long organizationId, JsonNode payload) {
        Long runId = AnalysisService.longOrNull(payload.get("runId"));
        if (runId == null) {
            return;
        }
        State state = runs.get(new Key(organizationId, runId));
        if (state != null) {
            push(runId, state, payload);
        }
    }

    /** 열린 실행마다 analytics 상태를 다시 읽어 바뀌었으면 보낸다(이벤트를 놓쳐도 진행이 보이게) */
    public void poll() {
        for (Map.Entry<Key, State> e : runs.entrySet()) {
            Key key = e.getKey();
            State state = e.getValue();
            try {
                JsonNode run = CurrentUserHolder.callAs(new CurrentUser(state.userId, key.organizationId()), () -> fetch(key.runId()));
                push(key.runId(), state, run);
            } catch (RuntimeException ex) {
                log.debug("실행 {} 상태 읽기 실패(다음 주기에 다시): {}", key.runId(), ex.toString());
            }
        }
    }

    private void push(long runId, State state, JsonNode run) {
        String sig = signature(run);
        if (sig.equals(state.last)) {
            return;
        }
        state.last = sig;
        String body = write(view(runId, run));
        boolean done = TERMINAL.contains(status(run));
        for (LiveConnection c : List.copyOf(state.connections)) {
            if (done) {
                c.close("run-done", body);
            } else {
                c.send("run-status", body);
            }
        }
    }

    private JsonNode fetch(long runId) {
        JsonNode r = analytics.call(HttpMethod.GET, "/internal/analytics/runs/" + runId, null, null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        JsonNode run = r == null ? null : r.has("run") ? r.get("run") : r;
        if (run == null || !run.isObject()) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        }
        return run;
    }

    static String status(JsonNode run) {
        return run.path("status").asString("").toUpperCase(java.util.Locale.ROOT);
    }

    static String signature(JsonNode run) {
        return status(run) + "|" + run.path("progress").asString("") + "|" + run.path("stage").asString("") + "|"
                + run.path("queuePosition").asString("");
    }

    static Map<String, Object> view(long runId, JsonNode run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", Long.toString(runId));
        m.put("status", status(run));
        m.put("progress", run.hasNonNull("progress") ? run.get("progress").asInt() : null);
        m.put("stage", run.hasNonNull("stage") ? run.get("stage").asString() : null);
        m.put("queuePosition", run.hasNonNull("queuePosition") ? run.get("queuePosition").asInt() : null);
        return m;
    }

    private void remove(Key key, LiveConnection connection) {
        State state = runs.get(key);
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

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    @Override
    public void start() {
        running = true;
        if (!properties.schedulerEnabled()) {
            return;
        }
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1, Thread.ofPlatform().daemon().name("analysis-run-", 0).factory());
        long ms = Math.max(500, properties.runStreamPoll().toMillis());
        s.scheduleWithFixedDelay(() -> {
            try {
                poll();
            } catch (RuntimeException ex) {
                log.warn("분석 실행 스트림 주기 작업 실패: {}", ex.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
        scheduler = s;
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
