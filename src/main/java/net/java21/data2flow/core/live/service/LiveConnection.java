package net.java21.data2flow.core.live.service;

import net.java21.data2flow.core.live.domain.LiveTopic;
import net.java21.data2flow.core.live.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SSE 연결 하나(API-DSH-20). 보낼 이벤트는 크기 제한 대기열에 넣고, 연결마다 가상 스레드 하나가 순서대로 써 보낸다.
 * 느린 브라우저가 텔레메트리 소비자·주기 작업을 막지 않게 하려는 것이고, 대기열이 차면 새 이벤트를 버린다(손실 허용, 다음 값이 곧 온다).
 */
public final class LiveConnection {

    private static final Logger log = LoggerFactory.getLogger(LiveConnection.class);
    private static final Object CLOSE = new Object();

    private final String id = UUID.randomUUID().toString();
    private final long organizationId;
    private final long userId;
    private final UUID sessionId;
    private final List<LiveTopic> topics;
    private final SseEmitter emitter;
    private final BlockingQueue<Object> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Consumer<LiveConnection> onClosed;
    private final AtomicBoolean homeBusy = new AtomicBoolean();

    private volatile Subscription subscription;
    private volatile JsonNode lastHome;
    private volatile Instant lastHomeAt;
    private long rateSecond = Long.MIN_VALUE;
    private int rateCount;

    LiveConnection(long organizationId, long userId, UUID sessionId, List<LiveTopic> topics, Subscription subscription,
                   SseEmitter emitter, int capacity, Consumer<LiveConnection> onClosed) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.topics = List.copyOf(topics);
        this.subscription = subscription;
        this.emitter = emitter;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onClosed = onClosed;
        emitter.onCompletion(this::markClosed);
        emitter.onTimeout(this::markClosed);
        emitter.onError(ex -> markClosed());
    }

    void start() {
        Thread.ofVirtual().name("live-sse-" + id).start(this::drain);
    }

    public String id() {
        return id;
    }

    public long organizationId() {
        return organizationId;
    }

    public long userId() {
        return userId;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public List<LiveTopic> topics() {
        return topics;
    }

    public Subscription subscription() {
        return subscription;
    }

    void subscription(Subscription next) {
        this.subscription = next;
    }

    public boolean isOpen() {
        return !closed.get() && !closing.get();
    }

    public long dropped() {
        return dropped.get();
    }

    JsonNode lastHome() {
        return lastHome;
    }

    Instant lastHomeAt() {
        return lastHomeAt;
    }

    void homeSent(JsonNode summary, Instant at) {
        this.lastHome = summary;
        this.lastHomeAt = at;
    }

    AtomicBoolean homeBusy() {
        return homeBusy;
    }

    /** 초당 상한(API-DSH-21: 초당 최대 20건). 넘으면 false */
    synchronized boolean allowMessage(long epochSecond, int perSecond) {
        if (epochSecond != rateSecond) {
            rateSecond = epochSecond;
            rateCount = 0;
        }
        if (rateCount >= perSecond) {
            return false;
        }
        rateCount++;
        return true;
    }

    /** 이벤트 하나를 대기열에 넣는다. 닫혔거나 대기열이 차면 버리고 false */
    boolean send(String event, String json) {
        if (!isOpen()) {
            return false;
        }
        if (!queue.offer(SseEmitter.event().name(event).data(json))) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** 마지막 이벤트(예: session-revoked)를 보내고 닫는다. 대기열이 차 있으면 그 이벤트 없이 닫는다 */
    void close(String lastEvent, String json) {
        if (!closing.compareAndSet(false, true) || closed.get()) {
            return;
        }
        queue.clear();
        if (lastEvent != null) {
            queue.offer(SseEmitter.event().name(lastEvent).data(json));
        }
        queue.offer(CLOSE);
    }

    private void drain() {
        try {
            while (!closed.get()) {
                Object item = queue.take();
                if (item == CLOSE) {
                    safeComplete();
                    break;
                }
                emitter.send((SseEmitter.SseEventBuilder) item);
            }
        } catch (IOException | IllegalStateException ex) {
            log.debug("실시간 연결 {} 쓰기 실패로 닫습니다: {}", id, ex.getMessage());
            emitter.completeWithError(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            safeComplete();
        } finally {
            markClosed();
        }
    }

    private void safeComplete() {
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            log.debug("실시간 연결 {} 이미 닫힘: {}", id, ex.getMessage());
        }
    }

    private void markClosed() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            queue.offer(CLOSE);
            onClosed.accept(this);
        }
    }
}
