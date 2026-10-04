package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.GatewayConnectivityChanged;
import net.java21.data2flow.core.alarm.repository.GatewayHealthRepository;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.maintenance.service.MaintenanceService;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.rule.service.RuleHealthService;
import net.java21.data2flow.core.rule.service.RuleTuningService;
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
 * 자동화 주기 작업(M4). 파드 여러 개 중 하나만 돌도록 advisory lock을 잡는다.
 * <ul>
 *   <li>{@link #minute()} 1분: 유지보수 예약 시작·끝(OPS-05.01), 플래핑 끄기(BR-RUL-10), 게이트웨이 연결 판정 → EVT-DEV-08(DEV-05.03),
 *       예약 제어 실행(ACT-02.07)</li>
 *   <li>{@link #ruleHealth()} 5분: 규칙 상태 점검(RUL-06.03, BR-RUL-06·20)</li>
 *   <li>{@link #tuning()} 하루: 규칙 튜닝 제안(RUL-06.02)</li>
 * </ul>
 */
@Component
public class AutomationJobs {

    private static final Logger log = LoggerFactory.getLogger(AutomationJobs.class);
    /** advisory lock 키: "d2falm1"(1분), "d2frule"(규칙), "d2ftune"(튜닝) */
    static final long MINUTE_LOCK = 0x6432_6661_6c6d_31L;
    static final long RULE_LOCK = 0x6432_6672_756c_65L;
    static final long TUNING_LOCK = 0x6432_6674_756e_65L;

    private final InternalOrganizations organizations;
    private final MaintenanceService maintenance;
    private final AlarmService alarms;
    private final GatewayHealthRepository gateways;
    private final CoreEventPublisher publisher;
    private final RuleHealthService ruleHealth;
    private final RuleTuningService tuning;
    private final net.java21.data2flow.core.control.service.ScheduleService schedules;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AutomationJobs(InternalOrganizations organizations, MaintenanceService maintenance, AlarmService alarms, GatewayHealthRepository gateways,
                          CoreEventPublisher publisher, RuleHealthService ruleHealth, RuleTuningService tuning,
                          net.java21.data2flow.core.control.service.ScheduleService schedules, JdbcClient jdbc,
                          PlatformTransactionManager txManager, Clock clock) {
        this.schedules = schedules;
        this.organizations = organizations;
        this.maintenance = maintenance;
        this.alarms = alarms;
        this.gateways = gateways;
        this.publisher = publisher;
        this.ruleHealth = ruleHealth;
        this.tuning = tuning;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 1분 작업 한 번. 다른 파드가 돌고 있으면 false */
    public boolean minute() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lock(MINUTE_LOCK)) {
                return false;
            }
            Instant now = clock.instant();
            for (long org : organizations.deploymentOrganizations()) {
                maintenance.tick(org);
                alarms.settleFlapping(org, now);
                gatewayCheck(org, now);
            }
            schedules.runDue(organizations.deploymentOrganizations());
            return true;
        }));
    }

    /** 게이트웨이 ONLINE ↔ OFFLINE 전환을 EVT-DEV-08로 낸다(알람은 이 이벤트를 받은 처리기가 만든다) */
    int gatewayCheck(long org, Instant now) {
        int n = 0;
        for (GatewayHealthRepository.GatewayState g : gateways.listNewlyOffline(org, now)) {
            gateways.updateStatus(org, g.id(), "OFFLINE");
            publisher.event(EventType.GATEWAY_CONNECTIVITY_CHANGED, org, new GatewayConnectivityChanged(g.id(), g.gatewayEui(),
                    DeviceConnectivityChanged.Connectivity.ONLINE, DeviceConnectivityChanged.Connectivity.OFFLINE, g.lastSeenAt()));
            n++;
        }
        for (GatewayHealthRepository.GatewayState g : gateways.listRecovered(org, now)) {
            gateways.updateStatus(org, g.id(), "ONLINE");
            publisher.event(EventType.GATEWAY_CONNECTIVITY_CHANGED, org, new GatewayConnectivityChanged(g.id(), g.gatewayEui(),
                    DeviceConnectivityChanged.Connectivity.OFFLINE, DeviceConnectivityChanged.Connectivity.ONLINE, g.lastSeenAt()));
            n++;
        }
        return n;
    }

    public boolean ruleHealth() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lock(RULE_LOCK)) {
                return false;
            }
            for (long org : organizations.deploymentOrganizations()) {
                int changed = ruleHealth.check(org);
                if (changed > 0) {
                    log.info("규칙 상태 점검: 조직 {} 규칙 {}개 상태 변경", org, changed);
                }
            }
            return true;
        }));
    }

    public boolean tuning() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lock(TUNING_LOCK)) {
                return false;
            }
            for (long org : organizations.deploymentOrganizations()) {
                tuning.generate(org);
            }
            return true;
        }));
    }

    private boolean lock(long key) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", key).query(Boolean.class).single());
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final AutomationJobs jobs;

        Schedule(AutomationJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT1M")
        void minute() {
            jobs.minute();
        }

        @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT5M")
        void ruleHealth() {
            jobs.ruleHealth();
        }

        @Scheduled(initialDelayString = "PT30M", fixedDelayString = "PT24H")
        void tuning() {
            jobs.tuning();
        }
    }
}
