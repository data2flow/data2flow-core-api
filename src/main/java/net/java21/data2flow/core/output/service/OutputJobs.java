package net.java21.data2flow.core.output.service;

import net.java21.data2flow.core.output.repository.OutputConnectionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** 출력 연결 1분 지표 7일 보관 정리(DSC domain-model §2.9). 지우기만 해서 여러 파드가 함께 돌아도 결과가 같다 */
@Component
public class OutputJobs {

    private final OutputConnectionRepository outputs;
    private final Clock clock;

    public OutputJobs(OutputConnectionRepository outputs, Clock clock) {
        this.outputs = outputs;
        this.clock = clock;
    }

    /** 지운 행 수 */
    public int purgeOnce() {
        return outputs.purgeStats(clock.instant().minus(OutputInternalService.STATS_RETENTION));
    }

    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final OutputJobs jobs;

        Schedule(OutputJobs jobs) {
            this.jobs = jobs;
        }

        @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
        void run() {
            jobs.purgeOnce();
        }
    }
}
