package net.java21.data2flow.core.commissioning.service;

import net.java21.data2flow.core.commissioning.repository.CommissioningRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분 설치 확인(BR-DEV-38): 기다리는 설치(INSTALLED·PROBLEM)마다 첫 수신·10분 경과를 본다. 기기마다 행 잠금으로 처리하므로 파드가 여럿이어도
 * 같은 상태 변경을 두 번 내지 않는다. 이 배포의 조직만(ADR-030).
 */
@Component
public class CommissioningJobs {

    private final CommissioningRepository repository;
    private final CommissioningService service;
    private final DeploymentOrganization deployment;

    public CommissioningJobs(CommissioningRepository repository, CommissioningService service, DeploymentOrganization deployment) {
        this.repository = repository;
        this.service = service;
        this.deployment = deployment;
    }

    /** 한 번 실행. 상태가 바뀐 수 */
    public int runOnce() {
        int changed = 0;
        for (Long[] row : repository.listAwaiting(deployment.restriction())) {
            if (service.check(row[0], row[1]).isPresent()) {
                changed++;
            }
        }
        return changed;
    }

    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final CommissioningJobs jobs;

        Schedule(CommissioningJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
        void run() {
            jobs.runOnce();
        }
    }
}
