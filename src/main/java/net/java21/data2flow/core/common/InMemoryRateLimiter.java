package net.java21.data2flow.core.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 파드 안 슬라이딩 창 호출 한도. 공개 경로의 IP 한도 보조용이다(클러스터 전체 한도는 gateway Redis 토큰 버킷, design/auth.md §5).
 * 넘으면 다시 시도할 수 있을 때까지 남은 초를 돌려준다.
 */
public class InMemoryRateLimiter {

    private static final int MAX_KEYS = 50_000;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** 허용이면 빈 값, 거부면 Retry-After 초 */
    public OptionalLong tryAcquire(String key) {
        if (key == null) {
            return OptionalLong.empty();
        }
        Instant now = clock.instant();
        if (hits.size() > MAX_KEYS) {
            hits.clear();
        }
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            Instant cutoff = now.minus(window);
            while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                long retry = Math.max(1, Duration.between(now, deque.peekFirst().plus(window)).toSeconds());
                return OptionalLong.of(retry);
            }
            deque.addLast(now);
            return OptionalLong.empty();
        }
    }
}
