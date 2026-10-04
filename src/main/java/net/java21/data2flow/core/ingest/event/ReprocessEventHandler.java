package net.java21.data2flow.core.ingest.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ReprocessJobFinished;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * EVT-ING-09 {@code ingest.reprocess.finished}(ING-01.04): 기간 재처리 작업이 끝나면 감사 로그 {@code REPROCESS_COMPLETED}를 남긴다
 * (design/api/ING-api.md EVT-ING-09 소비). 작업 상태 자체는 pipeline {@code reprocess_jobs}를 API-ING-14가 읽는다.
 */
@Component
public class ReprocessEventHandler implements CoreEventHandler {

    static final String AUDIT_COMPLETED = "REPROCESS_COMPLETED";

    private final Audits audits;

    public ReprocessEventHandler(Audits audits) {
        this.audits = audits;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.INGEST_REPROCESS_FINISHED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof ReprocessJobFinished f) {
            audits.record(audits.event(event.organizationId(), AUDIT_COMPLETED).target("REPROCESS_JOB", f.jobId())
                    .detail("status", f.status().name()).detail("sourceId", f.sourceId()).detail("total", f.total())
                    .detail("processed", f.processed()).detail("failed", f.failed()).detail("skipped", f.skipped())
                    .detail("error", f.error()).detail("finishedAt", f.finishedAt().toString()));
        }
    }
}
