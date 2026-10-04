package net.java21.data2flow.core.calendar.service;

import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.external.service.ContextSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 달력·외부 맥락 1분 작업(M5).
 * <ul>
 *   <li>{@link #modes()}: 공간 운영 모드 계산 → 바뀌면 EVT-DEV-06(DEV-11.02). 파드 하나만(advisory lock "d2fmode")</li>
 *   <li>{@link #contextSyncs()}: 때가 된 공휴일·iCal 동기화(DSC-06.03·06.04)와 실패 재시도(BR-DSC-17). 소스마다 다음 실행을 미뤄 두고 맡는다</li>
 * </ul>
 */
@Component
public class CalendarJobs {

    private static final Logger log = LoggerFactory.getLogger(CalendarJobs.class);
    /** advisory lock 키: "d2fmode" */
    static final long MODE_LOCK = 0x6432_666d_6f64_65L;

    private final InternalOrganizations organizations;
    private final SpaceModeService modes;
    private final ContextSyncService syncs;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public CalendarJobs(InternalOrganizations organizations, SpaceModeService modes, ContextSyncService syncs, JdbcClient jdbc,
                        PlatformTransactionManager txManager) {
        this.organizations = organizations;
        this.modes = modes;
        this.syncs = syncs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 운영 모드 한 번. 다른 파드가 돌고 있으면 false */
    public boolean modes() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", MODE_LOCK).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            for (long org : organizations.deploymentOrganizations()) {
                int changed = modes.tick(org);
                if (changed > 0) {
                    log.debug("운영 모드: 조직 {} 공간 {}개 변경", org, changed);
                }
            }
            return true;
        }));
    }

    /** 외부 맥락 동기화 한 번. 처리한 소스 수 */
    public int contextSyncs() {
        List<Long> orgs = organizations.deploymentOrganizations();
        return syncs.runDue(orgs);
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final CalendarJobs jobs;

        Schedule(CalendarJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
        void minute() {
            jobs.modes();
            jobs.contextSyncs();
        }
    }
}
