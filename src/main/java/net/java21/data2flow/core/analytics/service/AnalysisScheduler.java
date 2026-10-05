package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.analytics.domain.AnalysisSchedule;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * 분석 일정 실행기(ANA-04.01, UC-ANA-10 3단계 "core-api 스케줄러가 시각이 되면 run(trigger=SCHEDULE)을 만든다", TC-ANA-096).
 * 1분마다 때가 된 일정을 행 잠금({@code FOR UPDATE SKIP LOCKED})으로 잡으므로 파드가 둘이어도 한 시각에 실행은 하나다.
 * 분석 소유자의 신원으로 analytics에 실행을 요청하고(API-ANA-33, mode=ASYNC — 기간은 analytics가 실행 시각 기준 상대 기간으로 정한다),
 * 다음 시각(조직 시간대 cron)으로 옮긴다. 요청이 실패하면 시각을 옮기지 않아 다음 분에 다시 한다. 연속 실패 중지(BR-ANA-11)는 analytics가
 * 판정해 EVT-ANA-05로 알리고, core는 그 이벤트로 일정을 STOPPED_BY_FAILURE로 둔다.
 */
@Component
public class AnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisScheduler.class);

    private final AnalysisRefRepository refs;
    private final AnalysisRunService runs;
    private final AnalysisService analyses;
    private final DeploymentOrganization deployment;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AnalysisScheduler(AnalysisRefRepository refs, AnalysisRunService runs, AnalysisService analyses, DeploymentOrganization deployment,
                             JsonMapper json, PlatformTransactionManager txManager, Clock clock) {
        this.refs = refs;
        this.runs = runs;
        this.analyses = analyses;
        this.deployment = deployment;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 한 번 돌기. 실행을 요청한 수 */
    public int runOnce() {
        Instant now = clock.instant();
        OptionalLong restriction = deployment.restriction();
        Integer n = tx.execute(status -> {
            int requested = 0;
            List<AnalysisRef> due = refs.lockDue(now, restriction.isPresent() ? restriction.getAsLong() : null, 20);
            for (AnalysisRef ref : due) {
                if (fire(ref, now)) {
                    requested++;
                }
            }
            return requested;
        });
        return n == null ? 0 : n;
    }

    private boolean fire(AnalysisRef ref, Instant now) {
        AnalysisSchedule schedule;
        try {
            String cron = json.readTree(ref.scheduleJson()).path("cron").asString(null);
            schedule = new AnalysisSchedule(cron);
            schedule.next(now, analyses.zone(ref.organizationId()));
        } catch (RuntimeException ex) {
            log.warn("분석 {} 일정 형식 오류로 건너뜁니다", ref.analysisId());
            refs.advanceSchedule(ref.organizationId(), ref.analysisId(), ref.nextRunAt(), null);
            return false;
        }
        try {
            CurrentUserHolder.callAs(new CurrentUser(ref.ownerUserId(), ref.organizationId()),
                    () -> runs.request(ref.organizationId(), ref.analysisId(), "SCHEDULE", "ASYNC"));
        } catch (RuntimeException ex) {
            log.warn("분석 {} 일정 실행 요청 실패(다음 분에 다시): {}", ref.analysisId(), ex.toString());
            return false;
        }
        refs.advanceSchedule(ref.organizationId(), ref.analysisId(), ref.nextRunAt(),
                schedule.next(now, analyses.zone(ref.organizationId())));
        return true;
    }

    /** 스케줄. 테스트는 {@code data2flow.core.analytics.scheduler-enabled=false}로 끄고 {@link #runOnce()}를 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.analytics", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final AnalysisScheduler scheduler;

        Schedule(AnalysisScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT1M")
        void run() {
            scheduler.runOnce();
        }
    }
}
