package net.java21.data2flow.core.source.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 10분 넘게 끝나지 않은 무중단 자격증명 교체를 끝낸다(DSC-07.02). 행 잠금으로 같은 교체를 한 파드만 처리한다 */
@Component
@ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class SourceRotationJobs {

    private final SourceRotationService rotations;

    SourceRotationJobs(SourceRotationService rotations) {
        this.rotations = rotations;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void run() {
        rotations.expireStale();
    }
}
