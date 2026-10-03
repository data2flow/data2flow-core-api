package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.idempotency.IdempotencyStore;
import net.java21.data2flow.core.audit.repository.AuditLogRepository;
import net.java21.data2flow.core.invitation.repository.InvitationRepository;
import net.java21.data2flow.core.messaging.repository.ProcessedMessageRepository;
import net.java21.data2flow.core.outbox.service.OutboxRelay;
import net.java21.data2flow.core.signup.service.SignupService;
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
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 매시간 정리 작업. 파드 여러 개 중 하나만 돌도록 PostgreSQL advisory lock을 잡는다(ShedLock 테이블 없이).
 * <ul>
 *   <li>감사 로그 월 파티션을 이번 달부터 3개월 앞까지 만든다(ERD README §10)</li>
 *   <li>72시간 지난 초대 → EXPIRED(domain-model §3.2), 기한 지난 가입 신청 → EXPIRED(§3.4)</li>
 *   <li>멱등 키 24시간 지난 것 삭제(BR-OPS-20), 보낸 아웃박스 7일 지난 것 삭제(ERD README §14), 소비 기록 7일 지난 것 삭제</li>
 * </ul>
 */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);
    /** advisory lock 키: "d2fcore" */
    static final long LOCK_KEY = 0x6432_6663_6f72_65L;
    static final int MONTHS_AHEAD = 3;

    private final AuditLogRepository auditLogs;
    private final InvitationRepository invitations;
    private final SignupService signups;
    private final IdempotencyStore idempotency;
    private final OutboxRelay outbox;
    private final ProcessedMessageRepository processed;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MaintenanceJobs(AuditLogRepository auditLogs, InvitationRepository invitations, SignupService signups,
                           IdempotencyStore idempotency, OutboxRelay outbox, ProcessedMessageRepository processed, JdbcClient jdbc,
                           PlatformTransactionManager txManager, Clock clock) {
        this.auditLogs = auditLogs;
        this.invitations = invitations;
        this.signups = signups;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.processed = processed;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 한 번 실행. 다른 파드가 돌고 있으면 false */
    public boolean runOnce() {
        Boolean ran = tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            Instant now = clock.instant();
            int invitationsExpired = invitations.expirePending(now);
            int signupsExpired = signups.expire();
            int keys = idempotency.deleteExpired(now);
            int sent = outbox.purgeSent();
            int consumed = processed.deleteBefore(now.minus(java.time.Duration.ofDays(7)));
            log.info("정리 작업: 초대 만료 {}, 가입 신청 만료 {}, 멱등 키 삭제 {}, 아웃박스 삭제 {}, 소비 기록 삭제 {}", invitationsExpired,
                    signupsExpired, keys, sent, consumed);
            return true;
        });
        if (Boolean.TRUE.equals(ran)) {
            ensureAuditPartitions(clock.instant());
        }
        return Boolean.TRUE.equals(ran);
    }

    /** 파티션마다 별도 트랜잭션(하나가 실패해도 나머지는 만든다) */
    void ensureAuditPartitions(Instant now) {
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth m = month.plusMonths(i);
            String name = String.format("audit_logs_y%04dm%02d", m.getYear(), m.getMonthValue());
            Instant from = m.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = m.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            try {
                tx.executeWithoutResult(status -> auditLogs.createMonthlyPartition(name, from, to));
            } catch (RuntimeException ex) {
                // DEFAULT 파티션에 그 달 행이 이미 있으면 만들 수 없다. 운영 경고로 남긴다(ERD README §10)
                log.warn("감사 로그 파티션 {}을 만들지 못했습니다", name, ex);
            }
        }
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 {@link #runOnce()}를 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final MaintenanceJobs jobs;

        Schedule(MaintenanceJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
        void run() {
            jobs.runOnce();
        }
    }
}
