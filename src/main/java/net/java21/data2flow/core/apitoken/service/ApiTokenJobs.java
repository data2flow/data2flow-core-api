package net.java21.data2flow.core.apitoken.service;

import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장기 토큰 상태 정리(1분): 만료 → EXPIRED, 교체 유예 끝 → REVOKED(BR-IAM-34). 검증은 시각으로 바로 판정하므로(TokenUsability) 이 작업은
 * 목록 상태를 맞추고 gateway 캐시 삭제를 알리는 일만 한다. 파드 여러 개 중 하나만 돌도록 advisory lock을 잡는다.
 */
@Component
public class ApiTokenJobs {

    /** advisory lock 키: "d2ftok" */
    static final long LOCK_KEY = 0x6432_6674_6f6bL;

    private final ApiTokenRepository tokens;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApiTokenJobs(ApiTokenRepository tokens, OutboxWriter outbox, JdbcClient jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.tokens = tokens;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 한 번 실행. 상태를 바꾼 토큰 수(다른 파드가 돌고 있으면 -1) */
    public int runOnce() {
        Integer n = tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return -1;
            }
            List<long[]> changed = tokens.expireDue(clock.instant());
            Map<Long, List<String>> byOrg = new LinkedHashMap<>();
            for (long[] c : changed) {
                byOrg.computeIfAbsent(c[0], k -> new ArrayList<>()).add(Long.toString(c[1]));
            }
            byOrg.forEach((org, ids) -> outbox.authBlacklist(org, List.of(), List.of(), ids, "API_TOKEN_EXPIRED"));
            return changed.size();
        });
        return n == null ? 0 : n;
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 {@link #runOnce()}를 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ApiTokenJobs jobs;

        Schedule(ApiTokenJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT50S", fixedDelayString = "PT1M")
        void run() {
            jobs.runOnce();
        }
    }
}
