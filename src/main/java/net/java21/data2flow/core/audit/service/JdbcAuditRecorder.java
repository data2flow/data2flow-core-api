package net.java21.data2flow.core.audit.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.audit.AuditResult;
import net.java21.data2flow.core.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * core-api의 감사 기록 구현(IAM-06.01): {@code data2flow_core.audit_logs}에 바로 INSERT한다.
 *
 * <ul>
 *   <li>성공 기록은 업무 트랜잭션에 함께 들어간다. 업무가 롤백되면 "일어나지 않은 일"이므로 기록도 남지 않는다.</li>
 *   <li>실패·거부(FAILURE, DENIED) 기록은 별도 트랜잭션으로 바로 커밋한다. 거부 직후 예외로 업무 트랜잭션이 롤백되어도
 *       {@code ACCESS_DENIED}·{@code USER_LOGIN_FAILED}가 남아야 하기 때문이다(BR-IAM-17).</li>
 * </ul>
 * 사용자 행위자의 이름이 비어 있으면 기록 시점의 이름을 스냅샷으로 넣는다(삭제·익명화 뒤에도 의미 유지, AT-IAM-10.2).
 */
@Component
public class JdbcAuditRecorder implements AuditRecorder {

    private final AuditLogRepository repository;
    private final TransactionTemplate joinTx;
    private final TransactionTemplate newTx;

    public JdbcAuditRecorder(AuditLogRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.joinTx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(AuditEvent event) {
        AuditEvent withName = withActorName(event);
        TransactionTemplate tx = event.result() == AuditResult.SUCCESS ? joinTx : newTx;
        tx.executeWithoutResult(status -> repository.insert(withName));
    }

    private AuditEvent withActorName(AuditEvent e) {
        if (e.actorType() != AuditActorType.USER || e.actorName() != null || e.actorId() == null
                || !e.actorId().matches("\\d{1,18}")) {
            return e;
        }
        String name = repository.findActorName(Long.parseLong(e.actorId())).orElse(null);
        if (name == null) {
            return e;
        }
        return new AuditEvent(e.organizationId(), e.occurredAt(), e.actorType(), e.actorId(), name, e.action(),
                e.targetType(), e.targetId(), e.result(), e.detail(), e.cause(), e.ip(), e.userAgent(), e.requestId());
    }
}
