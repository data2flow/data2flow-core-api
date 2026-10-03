package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.source.domain.SourceConfigValidator;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 실시간 원본 메시지 보기(DSC-02.06, API-DSC-10): {@code GET /core/stream/sources/{source-id}/live}(SSE)로 ingress API-DSC-52 샘플 스트림을
 * 중계한다. 디버깅용이라 원본 값을 그대로 보여 준다(SRC_ADMIN, INTEGRATOR 이상).
 *
 * <ul>
 *   <li>이벤트 {@code message}: {@code {receivedAt, topic, sizeBytes, payload, truncated, decoded?}} — ingress의
 *       {@code size}·{@code rawExcerpt}(≤4KB)를 API-DSC-10 이름으로 바꾼다. truncated = 원본 크기 &gt; 보낸 payload 크기</li>
 *   <li>{@code maxRate}(기본 {@value #DEFAULT_MAX_RATE}건/초, 1~{@value #MAX_RATE}): 넘친 메시지는 버리고 1초마다
 *       {@code dropped{count}}로 알린다</li>
 *   <li>{@code topicFilter}(MQTT 와일드카드): ingress에도 힌트로 넘기고 core가 다시 거른다</li>
 *   <li>15초마다 {@code ping}. 화면을 닫으면 ingress 연결도 닫는다. 30분 뒤 끝나면 브라우저가 다시 연결한다</li>
 * </ul>
 */
@Service
public class SourceLiveRelay implements DisposableBean {

    static final int DEFAULT_MAX_RATE = 10;
    static final int MAX_RATE = 100;
    static final Duration PING_INTERVAL = Duration.ofSeconds(15);
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(1);
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(SourceLiveRelay.class);

    private final IngressClient ingress;
    private final SourceQueryService queries;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Duration pingInterval;

    @Autowired
    public SourceLiveRelay(IngressClient ingress, SourceQueryService queries, RoleChecker roleChecker, JsonMapper json, Clock clock) {
        this(ingress, queries, roleChecker, json, clock, PING_INTERVAL);
    }

    SourceLiveRelay(IngressClient ingress, SourceQueryService queries, RoleChecker roleChecker, JsonMapper json, Clock clock,
                    Duration pingInterval) {
        this.ingress = ingress;
        this.queries = queries;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
        this.pingInterval = pingInterval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("source-live-tick").factory());
    }

    /** 권한·소스 확인 뒤 스트림을 연다 */
    public SseEmitter open(long sourceId, String topicFilter, Integer maxRate) {
        roleChecker.require(Permission.SRC_ADMIN);
        long orgId = roleChecker.currentUser().organizationId();
        queries.find(orgId, sourceId);
        if (topicFilter != null && !topicFilter.isBlank() && !SourceConfigValidator.checkTopic(topicFilter.strip())) {
            throw new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID,
                    List.of(new FieldErrorDetail("topicFilter", "Pattern", null)), "topicFilter");
        }
        int rate = maxRate == null ? DEFAULT_MAX_RATE : Math.max(1, Math.min(MAX_RATE, maxRate));
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Relay relay = new Relay(emitter, topicFilter == null ? null : topicFilter.strip(), rate);
        emitter.onCompletion(relay::stop);
        emitter.onTimeout(relay::stop);
        emitter.onError(e -> relay.stop());
        relay.ticks();
        ingress.openLive(sourceId, relay.filter).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200) {
                if (response != null) {
                    IngressClient.closeQuietly(response.body());
                }
                log.debug("ingress 원본 스트림을 열지 못했습니다: source={}", sourceId);
                relay.finish();
                return;
            }
            relay.body.set(response.body());
            Thread.ofVirtual().name("source-live-" + sourceId).start(() -> relay.read(response));
        });
        return emitter;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    /** 스트림 하나의 상태 */
    final class Relay {

        private final SseEmitter emitter;
        private final String filter;
        private final int maxRate;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<InputStream> body = new AtomicReference<>();
        private ScheduledFuture<?> flush;
        private ScheduledFuture<?> ping;
        private Instant window;
        private int sentInWindow;
        private long dropped;

        Relay(SseEmitter emitter, String filter, int maxRate) {
            this.emitter = emitter;
            this.filter = filter == null || filter.isBlank() ? null : filter;
            this.maxRate = maxRate;
        }

        void ticks() {
            flush = scheduler.scheduleWithFixedDelay(this::flushDropped, FLUSH_INTERVAL.toMillis(), FLUSH_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
            ping = scheduler.scheduleWithFixedDelay(() -> send("ping", Map.of()), pingInterval.toMillis(), pingInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        void read(HttpResponse<InputStream> response) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!stopped.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatch(event, data.toString());
                        event = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).stripLeading());
                    }
                }
                if (!data.isEmpty()) {
                    dispatch(event, data.toString());
                }
            } catch (IOException ex) {
                log.debug("ingress 원본 스트림이 끊겼습니다: {}", ex.getClass().getSimpleName());
            } finally {
                finish();
            }
        }

        void dispatch(String event, String data) {
            if (data.isEmpty() || (event != null && !"message".equals(event))) {
                return;
            }
            JsonNode in;
            try {
                in = json.readTree(data);
            } catch (RuntimeException ex) {
                return;
            }
            String topic = in.path("topic").asString(null);
            if (filter != null && !SourceConfigValidator.topicMatches(filter, topic)) {
                return;
            }
            if (!allow()) {
                return;
            }
            send("message", toMessage(in));
        }

        synchronized boolean allow() {
            Instant second = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (!second.equals(window)) {
                window = second;
                sentInWindow = 0;
            }
            if (sentInWindow >= maxRate) {
                dropped++;
                return false;
            }
            sentInWindow++;
            return true;
        }

        void flushDropped() {
            long n;
            synchronized (this) {
                n = dropped;
                dropped = 0;
            }
            if (n > 0) {
                send("dropped", Map.of("count", n));
            }
        }

        void send(String name, Object data) {
            if (stopped.get()) {
                return;
            }
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException ex) {
                stop();
            }
        }

        /** 위쪽(ingress) 스트림이 끝났다: 남은 생략 수를 보내고 닫는다 */
        void finish() {
            if (stopped.get()) {
                return;
            }
            flushDropped();
            stop();
            try {
                emitter.complete();
            } catch (IllegalStateException ex) {
                // 이미 끝남
            }
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            if (flush != null) {
                flush.cancel(false);
            }
            if (ping != null) {
                ping.cancel(false);
            }
            IngressClient.closeQuietly(body.getAndSet(null));
        }
    }

    /** ingress 샘플({@code size, rawExcerpt}) → API-DSC-10 메시지({@code sizeBytes, payload, truncated}) */
    static ObjectNode toMessage(JsonNode in) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("receivedAt", in.has("receivedAt") ? in.get("receivedAt") : in.path("at"));
        out.set("topic", in.path("topic"));
        String payload = in.has("payload") ? in.get("payload").asString("") : in.path("rawExcerpt").asString("");
        long size = in.has("sizeBytes") ? in.get("sizeBytes").asLong() : in.has("size") ? in.get("size").asLong()
                : payload.getBytes(StandardCharsets.UTF_8).length;
        out.put("sizeBytes", size);
        out.put("payload", payload);
        out.put("truncated", in.path("truncated").asBoolean(size > payload.getBytes(StandardCharsets.UTF_8).length));
        if (in.has("decoded") && !in.get("decoded").isNull()) {
            out.set("decoded", in.get("decoded"));
        }
        return out;
    }
}
