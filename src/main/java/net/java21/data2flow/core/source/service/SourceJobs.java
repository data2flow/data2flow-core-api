package net.java21.data2flow.core.source.service;

import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.source.repository.SourceHealthRepository.ActiveSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * 소스 주기 작업. 파드 여러 개 중 하나만 돌도록 PostgreSQL advisory lock을 잡고, 배포 조직(ADR-030)의 소스만 본다.
 * <ul>
 *   <li>{@link #checkOnce()} 1분마다: ACTIVE 소스의 대표 상태를 다시 계산(보고가 90초 넘게 끊긴 인스턴스를 빼면 상태가 바뀔 수 있다,
 *       EVT-DSC-04)하고 무수신을 판정(EVT-DSC-05 {@code source.no-data})한다</li>
 *   <li>{@link #purgeOnce()} 1시간마다: 7일 지난 1분 지표(domain-model §2.6)와 24시간 넘게 보고 없는 인스턴스 행을 지운다</li>
 * </ul>
 */
@Component
public class SourceJobs {

    private static final Logger log = LoggerFactory.getLogger(SourceJobs.class);
    /** advisory lock 키: "d2fsrcc"(점검), "d2fsrcp"(정리) */
    static final long CHECK_LOCK = 0x6432_6673_7263_63L;
    static final long PURGE_LOCK = 0x6432_6673_7263_70L;
    static final Duration STATS_RETENTION = Duration.ofDays(7);
    static final Duration RUNTIME_RETENTION = Duration.ofHours(24);

    private final SourceHealthRepository health;
    private final SourceStateService states;
    private final DeploymentOrganization deployment;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SourceJobs(SourceHealthRepository health, SourceStateService states, DeploymentOrganization deployment, JdbcClient jdbc,
                      PlatformTransactionManager txManager, Clock clock) {
        this.health = health;
        this.states = states;
        this.deployment = deployment;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 1분 점검 한 번. 다른 파드가 돌고 있으면 -1, 아니면 새로 무수신이 된 소스 수 */
    public int checkOnce() {
        Integer result = tx.execute(status -> {
            if (!lock(CHECK_LOCK)) {
                return -1;
            }
            OptionalLong restriction = deployment.restriction();
            int noData = 0;
            for (ActiveSource s : health.listActiveSources(restriction)) {
                states.recompute(s.organizationId(), s.sourceId(), SourceModels.ACTIVE);
                if (states.checkNoData(s.organizationId(), s.sourceId(), s.noDataAlarmAfterSec())) {
                    noData++;
                }
            }
            return noData;
        });
        return result == null ? -1 : result;
    }

    /** 정리 한 번. 지운 지표 행 수(다른 파드가 돌고 있으면 -1) */
    public int purgeOnce() {
        Integer result = tx.execute(status -> {
            if (!lock(PURGE_LOCK)) {
                return -1;
            }
            Instant now = clock.instant();
            OptionalLong restriction = deployment.restriction();
            int stats = health.deleteStatsBefore(now.minus(STATS_RETENTION), restriction);
            int runtimes = health.deleteRuntimesBefore(now.minus(RUNTIME_RETENTION), restriction);
            log.info("소스 지표 정리: 1분 지표 {}행, 인스턴스 상태 {}행", stats, runtimes);
            return stats;
        });
        return result == null ? -1 : result;
    }

    private boolean lock(long key) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", key).query(Boolean.class).single());
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final SourceJobs jobs;

        Schedule(SourceJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
        void check() {
            jobs.checkOnce();
        }

        @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
        void purge() {
            jobs.purgeOnce();
        }
    }
}
