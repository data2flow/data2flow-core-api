package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.core.common.InternalOrganizations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * 환경 기준 주기 작업(M7). 파드 여러 개 중 하나만 돌도록 advisory lock을 잡는다. 조직마다 트랜잭션을 따로 써서 한 조직의 실패가 다른 조직을
 * 막지 않는다(락은 조직 트랜잭션마다 다시 잡는다).
 * <ul>
 *   <li>{@link #score()} 5분: 종합 점수 저장·EVT-CMP-02(CMP-01.05)</li>
 *   <li>{@link #track()} 5분: 어제·오늘 일 집계, 이탈 구간(EVT-CMP-04), 연속 달성(EVT-CMP-05)(CMP-02.01)</li>
 *   <li>{@link #outcomes()} 10분: 조치 후 24시간이 지난 개선 조치의 결과 자동 확인(CMP-02.05, BR-CMP-18)</li>
 * </ul>
 */
@Component
public class ComplianceJobs {

    private static final Logger log = LoggerFactory.getLogger(ComplianceJobs.class);
    /** advisory lock 키: "d2fcmps"(점수), "d2fcmpt"(추적), "d2fcmpa"(조치 결과) */
    static final long SCORE_LOCK = 0x6432_6663_6d70_73L;
    static final long TRACK_LOCK = 0x6432_6663_6d70_74L;
    static final long OUTCOME_LOCK = 0x6432_6663_6d70_61L;

    private final InternalOrganizations organizations;
    private final ComplianceScoreService scores;
    private final ComplianceTracker tracker;
    private final CorrectiveActionService actions;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ComplianceJobs(InternalOrganizations organizations, ComplianceScoreService scores, ComplianceTracker tracker,
                          CorrectiveActionService actions, JdbcClient jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.organizations = organizations;
        this.scores = scores;
        this.tracker = tracker;
        this.actions = actions;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 점수 작업 한 번. 다른 파드가 돌고 있으면 false */
    public boolean score() {
        Instant now = clock.instant();
        return each(SCORE_LOCK, org -> scores.scoreAll(org, now), "점수");
    }

    /** 추적 작업 한 번. 다른 파드가 돌고 있으면 false */
    public boolean track() {
        Instant now = clock.instant();
        return each(TRACK_LOCK, org -> tracker.track(org, now), "준수 추적");
    }

    /** 조치 결과 작업 한 번. 다른 파드가 돌고 있으면 false */
    public boolean outcomes() {
        Instant now = clock.instant();
        return each(OUTCOME_LOCK, org -> actions.evaluateDue(org, now), "조치 결과 확인");
    }

    private boolean each(long key, java.util.function.LongConsumer work, String name) {
        boolean ran = false;
        for (long org : organizations.deploymentOrganizations()) {
            try {
                Boolean done = tx.execute(status -> {
                    if (!lock(key)) {
                        return false;
                    }
                    work.accept(org);
                    return true;
                });
                if (!Boolean.TRUE.equals(done)) {
                    return ran;
                }
                ran = true;
            } catch (RuntimeException ex) {
                log.warn("{} 작업 실패: 조직 {}", name, org, ex);
            }
        }
        return true;
    }

    private boolean lock(long key) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", key).query(Boolean.class).single());
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ComplianceJobs jobs;

        Schedule(ComplianceJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(cron = "30 */5 * * * *")
        void score() {
            jobs.score();
        }

        @Scheduled(cron = "45 */5 * * * *")
        void track() {
            jobs.track();
        }

        @Scheduled(cron = "15 */10 * * * *")
        void outcomes() {
            jobs.outcomes();
        }
    }
}
