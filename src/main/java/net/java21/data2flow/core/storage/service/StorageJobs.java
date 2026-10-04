package net.java21.data2flow.core.storage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 저장 지표 하루 작업(OPS-01.03): 표 크기 기록과 디스크 여유 알람 판정. 파드 여럿 중 하나만 돌도록 advisory lock을 잡는다.
 * 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 {@link StorageMetricsService#snapshot()}을 직접 부른다.
 */
@Component
@ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StorageJobs {

    /** advisory lock 키: "d2fstor" */
    static final long LOCK_KEY = 0x6432_6673_746f_72L;

    private final StorageMetricsService service;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public StorageJobs(StorageMetricsService service, JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.service = service;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT6H")
    void run() {
        tx.executeWithoutResult(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean.class).single();
            if (Boolean.TRUE.equals(locked)) {
                service.snapshot();
            }
        });
    }
}
