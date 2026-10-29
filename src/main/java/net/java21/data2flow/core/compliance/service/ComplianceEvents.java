package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ComplianceApplicationChanged;
import net.java21.data2flow.contracts.message.event.ComplianceApplicationChanged.Change;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EVT-CMP-01 {@code compliance.application.changed}. flow-engine이 기준 캐시를 지우도록 적용이 덮는 공간 ID를 모두 싣는다
 * (하위 포함이면 하위 공간, 용도 적용이면 그 용도의 공간들). 업무 트랜잭션 안에서 아웃박스에 쓴다
 */
@Component
public class ComplianceEvents {

    static final Change CREATED = Change.CREATED;
    static final Change DELETED = Change.DELETED;
    static final Change VERSION_ADVANCED = Change.VERSION_ADVANCED;

    private final ComplianceApplicationRepository applications;
    private final CoreEventPublisher publisher;

    public ComplianceEvents(ComplianceApplicationRepository applications, CoreEventPublisher publisher) {
        this.applications = applications;
        this.publisher = publisher;
    }

    public void applicationChanged(long orgId, ApplicationRow a, int versionNo, Change change) {
        applicationChanged(orgId, a, versionNo, change, applications.coveredSpaces(orgId, a));
    }

    /** 덮는 공간을 이미 읽었으면(삭제 전 등) 그 목록을 쓴다 */
    public void applicationChanged(long orgId, ApplicationRow a, int versionNo, Change change, List<SpaceRow> covered) {
        List<Long> spaceIds = covered.stream().map(SpaceRow::id).toList();
        publisher.event(EventType.COMPLIANCE_APPLICATION_CHANGED, orgId,
                new ComplianceApplicationChanged(a.id(), a.profileId(), versionNo, spaceIds, change));
    }
}
