package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ImportCompleted;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.dataexchange.domain.CsvReader;
import net.java21.data2flow.core.dataexchange.domain.ImportMapping;
import net.java21.data2flow.core.dataexchange.domain.ImportMapping.MetricColumn;
import net.java21.data2flow.core.dataexchange.repository.ExchangeFileRepository;
import net.java21.data2flow.core.dataexchange.repository.ImportJobRepository;
import net.java21.data2flow.core.dataexchange.repository.ImportJobRepository.ImportJobRow;
import net.java21.data2flow.core.dataexchange.repository.ImportLookupRepository;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 가져오기 실행기(TSD-04.02, BR-TSD-15·16, AT-TSD-05.1~05.3). 대기 작업을 하나씩 잡아:
 * <ul>
 *   <li>미리 실행(dryRun): 읽고 검사만 — 전체·실패 수, 이미 있는 점 수(중복 예상), 앞 10행 → DRY_RUN_DONE</li>
 *   <li>실행: 1만 행씩 pipeline API-TSD-52로 넣고(이미 있으면 덮어쓰지 않고 skipped), 가져온 구간을 API-TSD-50으로 재계산 요청,
 *       끝나면 EVT-TSD-02 {@code import.completed}. 일부 실패면 PARTIALLY_FAILED</li>
 *   <li>매핑 안 되는 기기·측정 항목은 그 점만 실패로 남기고 기기를 만들지 않는다(BR-TSD-16). 오류는 작업당 1,000건까지 저장</li>
 * </ul>
 */
@Component
public class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    static final String OWNER = "IMPORT";
    static final int SAMPLE = 10;
    static final int DUPLICATE_CHECK = 5000;

    private final ImportJobRepository jobs;
    private final ImportLookupRepository lookup;
    private final ExchangeFileRepository files;
    private final ExportScheduleRepository organizations;
    private final PipelineTelemetryClient pipeline;
    private final InfluxQueryClient influx;
    private final SecretCipher cipher;
    private final CoreEventPublisher events;
    private final ExchangeProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public ImportRunner(ImportJobRepository jobs, ImportLookupRepository lookup, ExchangeFileRepository files,
                        ExportScheduleRepository organizations, PipelineTelemetryClient pipeline, InfluxQueryClient influx, SecretCipher cipher,
                        CoreEventPublisher events, ExchangeProperties properties, PlatformTransactionManager txManager, JsonMapper json,
                        Clock clock) {
        this.jobs = jobs;
        this.lookup = lookup;
        this.files = files;
        this.organizations = organizations;
        this.pipeline = pipeline;
        this.influx = influx;
        this.cipher = cipher;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    /** 대기 작업 모두 처리. 처리한 수 */
    public int runQueued() {
        int n = 0;
        while (n < 20) {
            Optional<ImportJobRow> next = tx.execute(s -> jobs.claimNextQueued(clock.instant()));
            if (next == null || next.isEmpty()) {
                break;
            }
            process(next.get());
            n++;
        }
        return n;
    }

    /** 끝난 지 7일 지난 CSV 원본 파일 지우기 */
    public int housekeeping() {
        return tx.execute(s -> {
            List<Long> ids = jobs.listFinishedWithFiles(clock.instant().minus(properties.fileRetention()));
            files.deleteOrphans(List.of(), ids);
            jobs.clearFileKeys(ids);
            return ids.size();
        });
    }

    /** 한 점 */
    record Point(String line, long deviceId, String metricKey, Instant time, double value) {
    }

    /** 실행 상태(한 작업) */
    final class Run {
        final ImportJobRow job;
        final boolean dryRun;
        long total;
        long inserted;
        long skipped;
        long failed;
        long duplicateEstimate;
        Instant minTime;
        Instant maxTime;
        final List<String[]> errors = new ArrayList<>();
        final List<Map<String, Object>> sample = new ArrayList<>();
        final List<Point> batch = new ArrayList<>();
        final Map<String, Instant[]> dirty = new LinkedHashMap<>();
        final TreeSet<Long> devices = new TreeSet<>();

        Run(ImportJobRow job) {
            this.job = job;
            this.dryRun = job.dryRun();
        }

        void error(String line, String code, String message) {
            failed++;
            total++;
            if (errors.size() < ImportJobRepository.MAX_ERRORS) {
                errors.add(new String[]{line, code, message});
            }
        }

        void point(Point p) {
            total++;
            if (total > properties.importMaxRows()) {
                throw new LimitExceeded();
            }
            if (sample.size() < SAMPLE) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("line", p.line());
                s.put("deviceId", Long.toString(p.deviceId()));
                s.put("metricKey", p.metricKey());
                s.put("measuredAt", p.time().toString());
                s.put("value", p.value());
                sample.add(s);
            }
            minTime = minTime == null || p.time().isBefore(minTime) ? p.time() : minTime;
            maxTime = maxTime == null || p.time().isAfter(maxTime) ? p.time() : maxTime;
            devices.add(p.deviceId());
            batch.add(p);
            int limit = dryRun ? DUPLICATE_CHECK : properties.importBatch();
            if (batch.size() >= limit) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            if (dryRun) {
                duplicateEstimate += lookup.countExisting(job.organizationId(), batch.stream().map(Point::deviceId).toList(),
                        batch.stream().map(Point::metricKey).toList(), batch.stream().map(Point::time).toList());
            } else {
                PipelineTelemetryClient.Inserted r = pipeline.bulkInsert(job.organizationId(), job.id(), batch.stream()
                        .map(p -> new PipelineTelemetryClient.Row(p.deviceId(), p.metricKey(), p.time(), p.value())).toList());
                inserted += r.inserted();
                skipped += r.skipped();
                for (Point p : batch) {
                    Instant[] range = dirty.computeIfAbsent(p.deviceId() + "|" + p.metricKey(), k -> new Instant[]{p.time(), p.time()});
                    range[0] = p.time().isBefore(range[0]) ? p.time() : range[0];
                    range[1] = p.time().isAfter(range[1]) ? p.time() : range[1];
                }
            }
            batch.clear();
        }
    }

    static final class LimitExceeded extends RuntimeException {
        LimitExceeded() {
            super("IMPORT_LIMIT_EXCEEDED", null, false, false);
        }
    }

    void process(ImportJobRow job) {
        long org = job.organizationId();
        Run run = new Run(job);
        String error = null;
        try {
            if ("CSV".equals(job.sourceKind())) {
                readCsv(run);
            } else {
                readInflux(run);
            }
            run.flush();
            if (!run.dryRun && !run.dirty.isEmpty()) {
                List<PipelineTelemetryClient.DirtyRange> ranges = new ArrayList<>();
                run.dirty.forEach((k, r) -> {
                    String[] parts = k.split("\\|", 2);
                    ranges.add(new PipelineTelemetryClient.DirtyRange(Long.parseLong(parts[0]), parts[1], r[0], r[1].plusSeconds(1)));
                });
                pipeline.markDirty(org, ranges);
            }
        } catch (LimitExceeded ex) {
            error = "IMPORT_LIMIT_EXCEEDED";
        } catch (IOException | RuntimeException ex) {
            log.warn("가져오기 작업 {} 실패: {}", job.id(), ex.toString());
            error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
        String status;
        if (run.dryRun) {
            status = error == null ? "DRY_RUN_DONE" : "FAILED";
        } else if (error != null) {
            status = run.inserted > 0 ? "PARTIALLY_FAILED" : "FAILED";
        } else {
            status = run.failed > 0 ? (run.inserted + run.skipped > 0 ? "PARTIALLY_FAILED" : "FAILED") : "SUCCEEDED";
        }
        String finalStatus = status;
        String finalError = error;
        long skipped = run.dryRun ? run.duplicateEstimate : run.skipped;
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            jobs.deleteErrors(org, job.id());
            jobs.insertErrors(org, job.id(), run.errors, now);
            jobs.updateResult(org, job.id(), finalStatus, run.total, run.dryRun ? 0 : run.inserted, skipped, run.failed,
                    json.writeValueAsString(run.sample), finalError, run.minTime, run.maxTime, now);
            if (!run.dryRun && run.inserted + run.skipped > 0) {
                events.event(EventType.IMPORT_COMPLETED, org, new ImportCompleted(Long.toString(job.id()), run.inserted, run.skipped, run.failed,
                        run.minTime, run.maxTime, List.copyOf(run.devices)));
            }
        });
    }

    private void readCsv(Run run) throws IOException {
        ImportJobRow job = run.job;
        long org = job.organizationId();
        ImportMapping mapping = json.readValue(job.mappingJson(), ImportMapping.class);
        ZoneId zone = ZoneId.of(organizations.findOrganization(org).timezone());
        Map<String, Long> deviceKeys = mapping.deviceId() == null ? lookup.findDeviceKeys(org, mapping.deviceKey()) : Map.of();
        if (mapping.deviceId() != null && lookup.findDeviceIds(org, List.of(mapping.deviceId())).isEmpty()) {
            throw new IllegalStateException("DEVICE_NOT_FOUND: " + mapping.deviceId());
        }
        Map<String, String> metrics = lookup.findMetricKeys(org);
        try (Reader reader = new InputStreamReader(files.open(org, OWNER, job.id()), StandardCharsets.UTF_8)) {
            CsvReader csv = new CsvReader(reader, mapping.delimiter().charAt(0));
            CsvReader.Record header = csv.next();
            if (header == null) {
                throw new IllegalStateException("IMPORT_FILE_INVALID: 빈 파일");
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.cells().size(); i++) {
                index.put(header.cells().get(i), i);
            }
            CsvReader.Record rec;
            while ((rec = csv.next()) != null) {
                List<String> cells = rec.cells();
                if (cells.size() == 1 && cells.getFirst().isEmpty()) {
                    continue;
                }
                String line = Long.toString(rec.line());
                Instant time;
                try {
                    time = mapping.parseTime(cell(cells, index, mapping.timeColumn()), zone);
                } catch (DateTimeException | NumberFormatException | NullPointerException ex) {
                    run.error(line, "TIME_INVALID", "시각을 읽을 수 없습니다: " + cell(cells, index, mapping.timeColumn()));
                    continue;
                }
                Long deviceId = mapping.deviceId();
                if (deviceId == null) {
                    String key = cell(cells, index, mapping.deviceColumn());
                    deviceId = key == null ? null : deviceKeys.get("ID".equals(mapping.deviceKey()) ? key.strip() : key.strip().toLowerCase(Locale.ROOT));
                    if (deviceId == null) {
                        run.error(line, "DEVICE_NOT_FOUND", "기기를 찾을 수 없습니다: " + key);
                        continue;
                    }
                }
                if (mapping.longFormat()) {
                    String metricRaw = cell(cells, index, mapping.metricColumn());
                    String metric = metricRaw == null ? null : metrics.get(metricRaw.strip().toLowerCase(Locale.ROOT));
                    if (metric == null) {
                        run.error(line, "METRIC_NOT_FOUND", "측정 항목을 찾을 수 없습니다: " + metricRaw);
                        continue;
                    }
                    value(run, line, deviceId, metric, time, cell(cells, index, mapping.valueColumn()), true);
                } else {
                    for (MetricColumn mc : mapping.metricColumns()) {
                        String metric = metrics.get(mc.metricKey().strip().toLowerCase(Locale.ROOT));
                        if (metric == null) {
                            run.error(line, "METRIC_NOT_FOUND", "측정 항목을 찾을 수 없습니다: " + mc.metricKey());
                            continue;
                        }
                        value(run, line, deviceId, metric, time, cell(cells, index, mc.column()), false);
                    }
                }
            }
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("따옴표")) {
                throw new IllegalStateException("IMPORT_FILE_INVALID: " + ex.getMessage());
            }
            throw ex;
        }
    }

    private static void value(Run run, String line, long deviceId, String metric, Instant time, String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                run.error(line, "VALUE_INVALID", "값이 비어 있습니다");
            }
            return;
        }
        double v;
        try {
            v = Double.parseDouble(raw.strip());
        } catch (NumberFormatException ex) {
            run.error(line, "VALUE_INVALID", "숫자가 아닙니다: " + raw);
            return;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            run.error(line, "VALUE_INVALID", "유한한 숫자가 아닙니다: " + raw);
            return;
        }
        run.point(new Point(line, deviceId, metric, time, v));
    }

    private static String cell(List<String> cells, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        return i == null || i >= cells.size() ? null : cells.get(i);
    }

    /** InfluxDB: 기간을 1일씩 나눠 Flux로 읽는다(BR-TSD-16) */
    private void readInflux(Run run) throws IOException {
        ImportJobRow job = run.job;
        long org = job.organizationId();
        JsonNode config = json.readTree(job.configJson());
        String token = job.secretEnc() == null ? null : cipher.decrypt(job.secretEnc(), ImportService.secretContext(org)).reveal();
        String url = config.path("url").asString();
        String bucket = config.path("bucket").asString();
        String measurement = config.path("measurement").asString("");
        String deviceTag = config.path("tagMapping").path("deviceTag").asString("device_id");
        Map<String, String> fieldMapping = new HashMap<>();
        JsonNode fm = config.path("fieldMapping");
        if (fm.isObject()) {
            fm.properties().forEach(e -> fieldMapping.put(e.getKey(), e.getValue().asString()));
        }
        Map<String, Long> deviceKeys = lookup.findDeviceKeys(org, "EXTERNAL_ID");
        Map<String, String> metrics = lookup.findMetricKeys(org);
        Instant from = Instant.parse(config.path("from").asString());
        Instant to = Instant.parse(config.path("to").asString());
        for (Instant day = from; day.isBefore(to); day = day.plus(Duration.ofDays(1))) {
            Instant end = day.plus(Duration.ofDays(1)).isAfter(to) ? to : day.plus(Duration.ofDays(1));
            String flux = flux(bucket, measurement, day, end, fieldMapping.keySet());
            influx.query(url, config.path("org").asString(), token, flux, p -> {
                String line = p.time() + " " + p.field() + " " + p.tags().getOrDefault(deviceTag, "");
                String tag = p.tags().get(deviceTag);
                Long deviceId = tag == null ? null : deviceKeys.get(tag.strip().toLowerCase(Locale.ROOT));
                if (deviceId == null) {
                    run.error(line, "DEVICE_NOT_FOUND", "기기를 찾을 수 없습니다(자동 등록하지 않음): " + tag);
                    return;
                }
                String mapped = p.field() == null ? null : fieldMapping.getOrDefault(p.field(), p.field());
                String metric = mapped == null ? null : metrics.get(mapped.toLowerCase(Locale.ROOT));
                if (metric == null) {
                    run.error(line, "METRIC_NOT_FOUND", "측정 항목을 찾을 수 없습니다: " + p.field());
                    return;
                }
                if (p.value() == null || p.value().isNaN() || p.value().isInfinite()) {
                    run.error(line, "VALUE_INVALID", "숫자가 아닙니다");
                    return;
                }
                run.point(new Point(line, deviceId, metric, p.time(), p.value()));
            });
        }
    }

    static String flux(String bucket, String measurement, Instant from, Instant to, java.util.Set<String> fields) {
        StringBuilder b = new StringBuilder("from(bucket: \"").append(escape(bucket)).append("\")\n  |> range(start: ").append(from)
                .append(", stop: ").append(to).append(")");
        if (measurement != null && !measurement.isBlank()) {
            b.append("\n  |> filter(fn: (r) => r._measurement == \"").append(escape(measurement)).append("\")");
        }
        if (!fields.isEmpty()) {
            b.append("\n  |> filter(fn: (r) => ");
            int i = 0;
            for (String f : new TreeSet<>(fields)) {
                b.append(i++ == 0 ? "" : " or ").append("r._field == \"").append(escape(f)).append("\"");
            }
            b.append(")");
        }
        return b.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.exchange", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ImportRunner runner;

        Schedule(ImportRunner runner) {
            this.runner = runner;
        }

        @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT15S")
        void queued() {
            runner.runQueued();
        }

        @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1H")
        void housekeeping() {
            runner.housekeeping();
        }
    }
}
