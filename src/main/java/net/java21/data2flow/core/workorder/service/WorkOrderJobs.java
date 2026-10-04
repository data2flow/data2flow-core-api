package net.java21.data2flow.core.workorder.service;

import net.java21.data2flow.core.asset.service.AssetService;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.workorder.repository.MaintenancePlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.OptionalLong;

/**
 * 작업 지시 정기 작업(15분마다, advisory lock으로 파드 하나만): 정기 점검 계획 실행(BR-DEV-28, 계획마다 사이트 시간대 03:00 뒤 첫 실행)과
 * 보증 만료 30일 전 알림(DEV-08.01, AT-DEV-16.5). 이 배포의 조직만 돈다(ADR-030: staging·prod가 DB를 함께 씀).
 */
@Component
public class WorkOrderJobs {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderJobs.class);
    /** advisory lock 키: "d2fwork" */
    static final long LOCK_KEY = 0x6432_6677_6f72_6bL;

    private final MaintenancePlanRepository plans;
    private final MaintenancePlanService planService;
    private final AssetService assets;
    private final DeploymentOrganization deployment;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public WorkOrderJobs(MaintenancePlanRepository plans, MaintenancePlanService planService, AssetService assets,
                         DeploymentOrganization deployment, JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.plans = plans;
        this.planService = planService;
        this.assets = assets;
        this.deployment = deployment;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 한 번 실행. 다른 파드가 돌고 있으면 -1, 아니면 만든 작업 지시 수 */
    public int runOnce() {
        Integer created = tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return -1;
            }
            OptionalLong only = deployment.restriction();
            int n = 0;
            List<Long> orgs = plans.listOrganizationsWithPlans();
            for (long org : orgs) {
                if (only.isEmpty() || only.getAsLong() == org) {
                    n += planService.runDue(org);
                }
            }
            int warned = assets.warnWarrantyExpiring(only);
            if (n > 0 || warned > 0) {
                log.info("작업 지시 정기 작업: 정기 점검 작업 지시 {}건, 보증 만료 알림 {}건", n, warned);
            }
            return n;
        });
        return created == null ? -1 : created;
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 {@link #runOnce()}를 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final WorkOrderJobs jobs;

        Schedule(WorkOrderJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT15M")
        void run() {
            jobs.runOnce();
        }
    }
}
