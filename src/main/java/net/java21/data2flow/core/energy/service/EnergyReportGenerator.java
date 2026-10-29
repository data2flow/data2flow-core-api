package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.energy.domain.EnergyMonthlyReportBuilder;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Model;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Saving;
import net.java21.data2flow.core.energy.domain.EnergyReportPdfRenderer;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyReportRepository;
import net.java21.data2flow.core.energy.repository.EnergyReportRepository.Claimed;
import net.java21.data2flow.core.energy.repository.EnergyReportRepository.ReportRow;
import net.java21.data2flow.core.energy.service.EnergyMetricsService.Prepared;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.filestore.service.StoredFiles.Stored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 월간 에너지 리포트 생성(ENE-01.05). GENERATING 리포트를 하나씩 잡아(SKIP LOCKED) 저장한 API-ENE-85 본문을 요청자 신원으로 다시 보내고, 모델을 만들어
 * PDF를 그린 뒤 READY로 바꾼다. analytics에 닿지 않으면 놓아주고 다음 차례에 다시 잡는다(3번째 실패면 FAILED).
 * 절감 조치 결과는 ENE-04 M&V(API-ENE-36)가 생기면 채운다(지금은 비어 있어 "검증된 절감 조치가 없습니다")
 */
@Component
public class EnergyReportGenerator {

    static final Duration STALE = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(EnergyReportGenerator.class);

    private final EnergyReportRepository reports;
    private final EnergyConfigRepository config;
    private final AnalyticsClient analytics;
    private final StoredFiles files;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public EnergyReportGenerator(EnergyReportRepository reports, EnergyConfigRepository config, AnalyticsClient analytics, StoredFiles files,
                                 JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.reports = reports;
        this.config = config;
        this.analytics = analytics;
        this.files = files;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** 대기 리포트를 만든다(정해진 수까지). 만든 수 */
    public int runQueued() {
        int n = 0;
        while (n < 20) {
            Instant now = clock.instant();
            Optional<Claimed> next = tx.execute(s -> reports.claimNext(now, now.minus(STALE)));
            if (next == null || next.isEmpty()) {
                break;
            }
            if ("RETRY".equals(generate(next.get()))) {
                break;
            }
            n++;
        }
        return n;
    }

    /** 리포트 하나. 결과 상태(READY·FAILED·RETRY) */
    String generate(Claimed c) {
        long org = c.organizationId();
        try {
            ReportRow row = reports.find(org, c.id()).orElseThrow(() -> new IllegalStateException("리포트가 없습니다"));
            JsonNode request = json.readTree(row.requestJson());
            JsonNode metrics = analytics.send(HttpMethod.POST, EnergyMetricsService.PATH, null, request.path("body"), h -> {
                h.set(DataflowHeaders.ORG_ID, Long.toString(org));
                h.set(DataflowHeaders.USER_ID, Long.toString(c.createdBy()));
            }, null).response();
            Long versionId = request.path("baselineVersionId").isNumber() ? request.path("baselineVersionId").asLong() : null;
            EnergyMetricsService.finish(metrics, new Prepared(Map.of(), versionId, null));
            YearMonth month = YearMonth.from(row.month());
            Model model = EnergyMonthlyReportBuilder.build(request.path("scopeName").asString(null), month, metrics, savings(org, row));
            Instant now = clock.instant();
            byte[] pdf = "PDF".equals(row.format())
                    ? EnergyReportPdfRenderer.render(model, now, ZoneId.of(config.timezone(org)), row.createdByName()) : null;
            String modelJson = json.writeValueAsString(model);
            Boolean done = tx.execute(s -> {
                Stored stored = pdf == null ? null
                        : files.storeGenerated(org, EnergyReportService.PURPOSE, "energy-report-" + month + "-" + row.id() + ".pdf", "application/pdf", pdf,
                        row.createdBy());
                return reports.ready(org, row.id(), modelJson, stored == null ? null : stored.key(), pdf == null ? null : sha256(pdf),
                        stored == null ? null : stored.sizeBytes(), now);
            });
            return Boolean.TRUE.equals(done) ? "READY" : "FAILED";
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE && reports.attempts(c.id()) < MAX_ATTEMPTS) {
                log.warn("energy report {} analytics unavailable, retry later", c.id());
                tx.executeWithoutResult(s -> reports.release(c.id(), clock.instant()));
                return "RETRY";
            }
            return fail(c, ex.getErrorCode().code());
        } catch (RuntimeException ex) {
            log.error("energy report {} failed", c.id(), ex);
            return fail(c, "INTERNAL_ERROR");
        }
    }

    /** 절감 조치 결과(ENE-04 M&V). 아직 기능이 없어 비어 있다 */
    List<Saving> savings(long org, ReportRow row) {
        return List.of();
    }

    private String fail(Claimed c, String reason) {
        tx.executeWithoutResult(s -> reports.failed(c.organizationId(), c.id(), reason, clock.instant()));
        return "FAILED";
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final EnergyReportGenerator generator;

        Schedule(EnergyReportGenerator generator) {
            this.generator = generator;
        }

        @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
        void run() {
            generator.runQueued();
        }
    }
}
