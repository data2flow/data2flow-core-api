package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.dataexchange.domain.CsvReader;
import net.java21.data2flow.core.dataexchange.domain.ExchangeErrorCode;
import net.java21.data2flow.core.dataexchange.domain.ImportMapping;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ImportErrorResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ImportJobResponse;
import net.java21.data2flow.core.dataexchange.repository.ExchangeFileRepository;
import net.java21.data2flow.core.dataexchange.repository.ImportJobRepository;
import net.java21.data2flow.core.dataexchange.repository.ImportJobRepository.ImportJobRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 과거 데이터 가져오기 API(TSD-04.02, API-TSD-30~32, BR-TSD-15·16). TS_IMPORT(ADMIN·INTEGRATOR).
 * <ul>
 *   <li>CSV(multipart: file, mapping JSON, dryRun 기본 true, originLabel 필수): 머리글·매핑 열을 바로 확인하고(틀리면 400
 *       {@code IMPORT_FILE_INVALID}), 파일은 1MiB 조각으로 보관, 2GB 넘으면 400 {@code IMPORT_LIMIT_EXCEEDED}</li>
 *   <li>InfluxDB(JSON: url, org, bucket, measurement, token(쓰기 전용·암호화), from, to(최대 366일), tagMapping.deviceTag, fieldMapping):
 *       {@code /ping}이 안 되면 502 {@code IMPORT_SOURCE_UNREACHABLE}</li>
 *   <li>처리는 실행기가 비동기로(202). 미리 실행이 끝난 작업만 {@code /run}으로 실제 실행(아니면 409)</li>
 * </ul>
 */
@Service
public class ImportService {

    static final TypeReference<List<Map<String, Object>>> SAMPLE = new TypeReference<>() { };
    static final Duration MAX_INFLUX_SPAN = Duration.ofDays(366);

    private final RoleChecker roleChecker;
    private final ImportJobRepository jobs;
    private final ExchangeFileRepository files;
    private final InfluxQueryClient influx;
    private final SecretCipher cipher;
    private final ExchangeProperties properties;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public ImportService(RoleChecker roleChecker, ImportJobRepository jobs, ExchangeFileRepository files, InfluxQueryClient influx,
                         SecretCipher cipher, ExchangeProperties properties, Audits audits, PlatformTransactionManager txManager, JsonMapper json,
                         Clock clock) {
        this.roleChecker = roleChecker;
        this.jobs = jobs;
        this.files = files;
        this.influx = influx;
        this.cipher = cipher;
        this.properties = properties;
        this.audits = audits;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    static String secretContext(long organizationId) {
        return "data2flow_core.import_jobs.secret_enc:" + organizationId;
    }

    /** API-TSD-30 CSV */
    public ImportJobResponse createCsv(MultipartFile file, String mappingJson, Boolean dryRun, String originLabel) {
        roleChecker.require(Permission.TS_IMPORT);
        CurrentUser user = roleChecker.currentUser();
        String label = label(originLabel);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_FILE_INVALID, List.of(new FieldErrorDetail("file", "REQUIRED", null)), "file");
        }
        if (file.getSize() > properties.importMaxBytes()) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_LIMIT_EXCEEDED);
        }
        ImportMapping mapping;
        try {
            mapping = json.readValue(mappingJson == null ? "" : mappingJson, ImportMapping.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw invalid("mapping");
        }
        String bad = mapping.invalidField();
        if (bad != null) {
            throw invalid(bad);
        }
        checkHeader(file, mapping);
        boolean dry = dryRun == null || dryRun;
        Instant now = clock.instant();
        long id = tx.execute(s -> {
            long jobId = jobs.insert(user.organizationId(), user.userId(), "CSV", "{\"fileName\":" + json.writeValueAsString(
                    file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename()) + "}", json.writeValueAsString(mapping),
                    null, dry, label, now);
            try (InputStream in = file.getInputStream()) {
                long bytes = files.store(user.organizationId(), ImportRunner.OWNER, jobId, in);
                jobs.updateFile(user.organizationId(), jobId, ExchangeFileRepository.objectKey(ImportRunner.OWNER, jobId), bytes);
            } catch (IOException ex) {
                throw new BusinessException(ExchangeErrorCode.IMPORT_FILE_INVALID, ex.getMessage());
            }
            audits.record(audits.event(user.organizationId(), "TELEMETRY_IMPORT_REQUESTED").actor(user).target("IMPORT", Long.toString(jobId))
                    .detail("sourceKind", "CSV").detail("dryRun", dry).detail("originLabel", label));
            return jobId;
        });
        return toResponse(jobs.findById(user.organizationId(), id).orElseThrow());
    }

    /** API-TSD-30 InfluxDB */
    public ImportJobResponse createInflux(JsonNode body) {
        roleChecker.require(Permission.TS_IMPORT);
        CurrentUser user = roleChecker.currentUser();
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        String label = label(DeliveryTargets.text(body, "originLabel"));
        String url = DeliveryTargets.text(body, "url");
        if (url == null || !DeliveryTargets.isHttpUrl(url)) {
            throw invalid("url");
        }
        for (String field : List.of("org", "bucket", "token", "from", "to")) {
            if (DeliveryTargets.text(body, field) == null) {
                throw invalid(field);
            }
        }
        Instant from;
        Instant to;
        try {
            from = Instant.parse(DeliveryTargets.text(body, "from"));
            to = Instant.parse(DeliveryTargets.text(body, "to"));
        } catch (DateTimeException ex) {
            throw invalid("from");
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_INFLUX_SPAN) > 0) {
            throw invalid("to");
        }
        JsonNode fieldMapping = body.get("fieldMapping");
        if (fieldMapping != null && !fieldMapping.isNull() && !fieldMapping.isObject()) {
            throw invalid("fieldMapping");
        }
        ping(url);
        ObjectNode config = json.createObjectNode();
        config.put("url", url);
        config.put("org", DeliveryTargets.text(body, "org"));
        config.put("bucket", DeliveryTargets.text(body, "bucket"));
        config.put("measurement", DeliveryTargets.text(body, "measurement"));
        config.put("from", from.toString());
        config.put("to", to.toString());
        ObjectNode tagMapping = config.putObject("tagMapping");
        String deviceTag = body.path("tagMapping").path("deviceTag").asString("device_id");
        tagMapping.put("deviceTag", deviceTag == null || deviceTag.isBlank() ? "device_id" : deviceTag);
        if (fieldMapping != null && fieldMapping.isObject()) {
            config.set("fieldMapping", fieldMapping);
        }
        byte[] secret = cipher.encrypt(Secret.of(DeliveryTargets.text(body, "token")), secretContext(user.organizationId()));
        boolean dry = !body.has("dryRun") || body.get("dryRun").asBoolean(true);
        Instant now = clock.instant();
        long id = tx.execute(s -> {
            long jobId = jobs.insert(user.organizationId(), user.userId(), "INFLUXDB", json.writeValueAsString(config), "{}", secret, dry,
                    label, now);
            audits.record(audits.event(user.organizationId(), "TELEMETRY_IMPORT_REQUESTED").actor(user).target("IMPORT", Long.toString(jobId))
                    .detail("sourceKind", "INFLUXDB").detail("url", url).detail("bucket", config.path("bucket").asString())
                    .detail("dryRun", dry).detail("originLabel", label));
            return jobId;
        });
        return toResponse(jobs.findById(user.organizationId(), id).orElseThrow());
    }

    /** API-TSD-31 목록 */
    public ListApiResponse<ImportJobResponse> list(Integer page, Integer size) {
        roleChecker.require(Permission.TS_IMPORT);
        long org = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, jobs.list(org, params.size(), params.offset()).stream().map(this::toResponse).toList(),
                jobs.count(org));
    }

    /** API-TSD-31 상세 */
    public ImportJobResponse get(long id) {
        roleChecker.require(Permission.TS_IMPORT);
        return toResponse(load(id));
    }

    /** API-TSD-31 오류 목록(줄·점 번호, 코드, 문구) */
    public ListApiResponse<ImportErrorResponse> errors(long id, Integer page, Integer size) {
        roleChecker.require(Permission.TS_IMPORT);
        ImportJobRow job = load(id);
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, jobs.findErrors(job.organizationId(), id, params.size(), params.offset()).stream()
                .map(e -> new ImportErrorResponse(e[0], e[1], e[2])).toList(), jobs.countErrors(job.organizationId(), id));
    }

    /** API-TSD-32 미리 실행 뒤 실제 실행(202) */
    public ImportJobResponse run(long id) {
        roleChecker.require(Permission.TS_IMPORT);
        ImportJobRow job = load(id);
        if (!"DRY_RUN_DONE".equals(job.status())) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_STATE_CONFLICT);
        }
        if ("INFLUXDB".equals(job.sourceKind())) {
            ping(json.readTree(job.configJson()).path("url").asString());
        }
        if (!Boolean.TRUE.equals(tx.execute(s -> jobs.updateToRun(job.organizationId(), id, clock.instant())))) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_STATE_CONFLICT);
        }
        audits.record(audits.event(job.organizationId(), "TELEMETRY_IMPORT_STARTED").actor(roleChecker.currentUser())
                .target("IMPORT", Long.toString(id)));
        return toResponse(jobs.findById(job.organizationId(), id).orElseThrow());
    }

    private void ping(String url) {
        try {
            influx.ping(url);
        } catch (IOException ex) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_SOURCE_UNREACHABLE, ex.getMessage());
        }
    }

    private void checkHeader(MultipartFile file, ImportMapping mapping) {
        try (InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            CsvReader.Record header = new CsvReader(reader, mapping.delimiter().charAt(0)).next();
            if (header == null) {
                throw new BusinessException(ExchangeErrorCode.IMPORT_FILE_INVALID, "1행: 머리글이 없습니다");
            }
            for (String column : mapping.requiredColumns()) {
                if (!header.cells().contains(column)) {
                    throw new BusinessException(ExchangeErrorCode.IMPORT_FILE_INVALID,
                            List.of(new FieldErrorDetail("mapping", "COLUMN_NOT_FOUND", column)), "1행: " + column + " 열이 없습니다");
                }
            }
        } catch (IOException | IllegalStateException ex) {
            throw new BusinessException(ExchangeErrorCode.IMPORT_FILE_INVALID, ex.getMessage());
        }
    }

    private ImportJobRow load(long id) {
        long org = roleChecker.currentUser().organizationId();
        return jobs.findById(org, id).orElseThrow(() -> new BusinessException(ExchangeErrorCode.IMPORT_NOT_FOUND));
    }

    ImportJobResponse toResponse(ImportJobRow j) {
        List<Map<String, Object>> sample = j.sampleJson() == null ? List.of() : json.readValue(j.sampleJson(), SAMPLE);
        return new ImportJobResponse(Long.toString(j.id()), j.sourceKind(), j.status(), j.dryRun(), j.total(), j.inserted(),
                j.skippedDuplicate(), j.failed(), j.originLabel(), sample, j.error(), j.rangeFrom(), j.rangeTo(), j.startedAt(),
                j.finishedAt(), j.createdAt());
    }

    private static String label(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > 100) {
            throw invalid("originLabel");
        }
        return raw.strip();
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
