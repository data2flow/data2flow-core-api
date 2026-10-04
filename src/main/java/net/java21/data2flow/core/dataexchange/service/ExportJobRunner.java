package net.java21.data2flow.core.dataexchange.service;

import jakarta.annotation.PreDestroy;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ExportJobFinished;
import net.java21.data2flow.core.dataexchange.domain.ExportFormat;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.repository.ExchangeFileRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportJobRepository.ExportJobRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 내보내기 작업 실행(TSD-04.01, BR-TSD-14, NFR-04.02). 파일은 임시 파일에 흘려 쓴 뒤 1MiB 조각으로 DB에 옮긴다(메모리 일정).
 * 끝나면 EVT-TSD-01 {@code export.completed}·{@code export.failed}(알림 센터·SSE). 파드마다 실행 스레드 {@code workers}개.
 * <ul>
 *   <li>{@link #submit}: 동기 시도(요청 스레드가 30초까지 기다림)</li>
 *   <li>{@link #runQueued}: 대기 작업을 하나씩 잡아(SKIP LOCKED) 실행 — 15초마다</li>
 *   <li>{@link #housekeeping}: 7일 지난 결과 EXPIRED·파일 삭제, 1시간 넘게 RUNNING인 작업 되살리기 — 10분마다</li>
 * </ul>
 */
@Component
public class ExportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportJobRunner.class);
    static final String OWNER = "EXPORT";

    private final ExportJobRepository jobs;
    private final ExchangeFileRepository files;
    private final ExportFileWriter writer;
    private final CoreEventPublisher events;
    private final ExchangeProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService executor;

    public ExportJobRunner(ExportJobRepository jobs, ExchangeFileRepository files, ExportFileWriter writer, CoreEventPublisher events,
                           ExchangeProperties properties, PlatformTransactionManager txManager, JsonMapper json, Clock clock) {
        this.jobs = jobs;
        this.files = files;
        this.writer = writer;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(properties.workers(), Thread.ofPlatform().name("export-", 0).daemon().factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** 작업 하나를 실행 스레드에 넘긴다(이미 RUNNING으로 잡은 작업) */
    public Future<String> submit(ExportJobRow job) {
        return executor.submit(() -> generate(job));
    }

    /** 대기 작업을 모두 처리(정해진 수까지). 처리한 수 */
    public int runQueued() {
        int n = 0;
        while (n < 100) {
            Optional<ExportJobRow> next = tx.execute(s -> jobs.claimNextQueued(clock.instant()));
            if (next == null || next.isEmpty()) {
                break;
            }
            generate(next.get());
            n++;
        }
        return n;
    }

    /**
     * 작업 하나를 만든다(RUNNING 상태에서). 결과 상태(SUCCEEDED·FAILED·CANCELLED)를 돌려준다.
     */
    public String generate(ExportJobRow job) {
        long org = job.organizationId();
        Path temp = null;
        try {
            ExportPlan plan = json.readValue(job.planJson(), ExportPlan.class);
            ExportFormat format = ExportFormat.valueOf(job.format());
            temp = Files.createTempFile("d2f-export-" + job.id() + "-", "." + format.extension());
            long rows;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 256 * 1024)) {
                rows = writer.write(org, plan, format, out, () -> "CANCELLED".equals(jobs.findStatus(org, job.id())));
            }
            Path file = temp;
            Instant now = clock.instant();
            Boolean done = tx.execute(s -> {
                long bytes;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    bytes = files.store(org, OWNER, job.id(), in);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
                boolean ok = jobs.updateSucceeded(org, job.id(), rows, bytes, ExchangeFileRepository.objectKey(OWNER, job.id()),
                        now.plus(properties.fileRetention()), now);
                if (!ok) {
                    s.setRollbackOnly();
                    return false;
                }
                events.event(EventType.EXPORT_COMPLETED, org, new ExportJobFinished(Long.toString(job.id()), job.requestedBy(), "SUCCEEDED",
                        rows, "/api/v1/core/exports/" + job.id(), null));
                return true;
            });
            return Boolean.TRUE.equals(done) ? "SUCCEEDED" : jobs.findStatus(org, job.id());
        } catch (CancellationException ex) {
            return "CANCELLED";
        } catch (RuntimeException | IOException ex) {
            log.warn("내보내기 작업 {} 실패: {}", job.id(), ex.toString());
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            tx.executeWithoutResult(s -> {
                if (jobs.updateFailed(org, job.id(), error, clock.instant())) {
                    events.event(EventType.EXPORT_FAILED, org, new ExportJobFinished(Long.toString(job.id()), job.requestedBy(), "FAILED", 0,
                            null, error.length() > 300 ? error.substring(0, 300) : error));
                }
            });
            return "FAILED";
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 임시 파일은 다음 재시작에 사라진다
                }
            }
        }
    }

    /** 만료·멈춤 정리. 지운 결과 파일 수 */
    public int housekeeping() {
        Instant now = clock.instant();
        return tx.execute(s -> {
            List<Long> expired = jobs.expireFinished(now);
            jobs.requeueStale(now.minus(java.time.Duration.ofHours(1)), now);
            files.deleteOrphans(expired, List.of());
            return expired.size();
        });
    }

    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.exchange", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ExportJobRunner runner;

        Schedule(ExportJobRunner runner) {
            this.runner = runner;
        }

        @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT15S")
        void queued() {
            runner.runQueued();
        }

        @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
        void housekeeping() {
            runner.housekeeping();
        }
    }
}
