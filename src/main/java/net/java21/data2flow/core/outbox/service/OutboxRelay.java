package net.java21.data2flow.core.outbox.service;

import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.outbox.repository.OutboxRepository;
import net.java21.data2flow.core.outbox.repository.OutboxRepository.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.OptionalLong;

/**
 * 아웃박스 릴레이(ERD README §11.1). 보내지 않은 행을 {@code FOR UPDATE SKIP LOCKED}로 잠가 파드 여러 개가 나눠 보내고,
 * 대상의 확인(RabbitMQ confirm, auth 2xx)을 받은 뒤 sent_at을 쓴다. 실패한 행은 다음 주기에 다시 보낸다(최소 1회).
 * 배포 조직이 정해져 있으면({@link DeploymentOrganization}) 그 조직의 행만 보낸다(ADR-030).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final List<OutboxDispatcher> dispatchers;
    private final TransactionTemplate tx;
    private final CoreProperties properties;
    private final DeploymentOrganization deployment;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, List<OutboxDispatcher> dispatchers, PlatformTransactionManager txManager,
                       CoreProperties properties, DeploymentOrganization deployment, Clock clock) {
        this.repository = repository;
        this.dispatchers = dispatchers;
        this.tx = new TransactionTemplate(txManager);
        this.properties = properties;
        this.deployment = deployment;
        this.clock = clock;
    }

    /** 한 묶음을 보낸다. 보낸 행 수를 돌려준다 */
    public int relayOnce() {
        Integer sent = tx.execute(status -> {
            int count = 0;
            OptionalLong only = deployment.restriction();
            Long org = only.isPresent() ? only.getAsLong() : null;
            for (OutboxMessage message : repository.lockUnsent(properties.outbox().batchSize(), org)) {
                try {
                    dispatcherFor(message).dispatch(message);
                    repository.markSent(message.id(), clock.instant());
                    count++;
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("아웃박스 발송 실패 id={} exchange={} attempts={}", message.id(), message.exchange(),
                            message.attempts() + 1, ex);
                    repository.markFailed(message.id(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /** 보낸 지 7일 지난 행 정리 */
    public int purgeSent() {
        return repository.deleteSentBefore(clock.instant().minus(properties.outbox().retention()));
    }

    private OutboxDispatcher dispatcherFor(OutboxMessage message) {
        return dispatchers.stream().filter(d -> d.supports(message)).findFirst()
                .orElseThrow(() -> new IllegalStateException("보낼 곳을 모르는 아웃박스: " + message.exchange()));
    }

    /** 주기 실행. 테스트는 {@code data2flow.core.outbox.relay-enabled=false}로 끄고 {@link #relayOnce()}를 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final OutboxRelay relay;

        Schedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${data2flow.core.outbox.relay-interval:1s}")
        void run() {
            relay.relayOnce();
        }
    }
}
