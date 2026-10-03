package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import net.java21.data2flow.core.catalog.domain.CatalogModels.Metric;
import net.java21.data2flow.core.catalog.domain.CatalogModels.MetricAlias;
import net.java21.data2flow.core.catalog.domain.CatalogModels.MetricFields;
import net.java21.data2flow.core.catalog.domain.CatalogModels.RemapJob;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasToRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasToResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateAliasRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateMetricRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.FirstSeen;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricStatusResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RemapJobResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.SampleDto;
import net.java21.data2flow.core.catalog.event.CatalogEvents;
import net.java21.data2flow.core.catalog.repository.MetricRepository;
import net.java21.data2flow.core.catalog.repository.MetricRepository.MetricFilter;
import net.java21.data2flow.core.common.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 측정 항목 정의(DEV-04.01, API-DEV-50·51), 미검증 항목 처리(DEV-04.02, API-DEV-52~54), 별칭(DEV-04.03, API-DEV-55),
 * 재매핑 진행률(API-DEV-56).
 *
 * <ul>
 *   <li>키 형식 {@code ^[A-Za-z][A-Za-z0-9_]{0,63}$}(센서가 보내는 키 그대로, 대소문자 섞임 허용 — 2026-10-04 결정). 틀리면 400 METRIC_KEY_INVALID</li>
 *   <li>BR-DEV-17: 별칭과 표준 키는 겹칠 수 없다(409 METRIC_KEY_DUPLICATE). 별칭 대상은 VERIFIED 표준 키여야 한다
 *       (별칭의 별칭·미검증 대상은 400 METRIC_ALIAS_INVALID)</li>
 *   <li>BR-DEV-16: 미검증 항목을 별칭으로 연결하면 항목을 지우고 별칭을 만든 뒤, 커밋 후 pipeline에 과거 시계열 키 바꾸기를 요청한다
 *       (API-TSD-51). 진행률은 남은 별칭 키 행 수로 계산한다</li>
 *   <li>BR-DEV-18: 유효 범위를 바꾸면 METRIC 설정 변경을 보내 pipeline이 다음 수신부터 반영한다(과거 품질은 재처리로만)</li>
 *   <li>BR-DEV-14: 기본 제공 항목의 키는 바꿀 수 없다(403 MODEL_BUILTIN_READONLY). 다른 정의 값은 고칠 수 있다.
 *       측정 항목 삭제 API는 없다</li>
 *   <li>이미 검증·무시된 항목을 다시 처리하면 409 METRIC_STATE_CONFLICT</li>
 * </ul>
 */
@Service
public class MetricService {

    private static final Logger log = LoggerFactory.getLogger(MetricService.class);
    /** 재매핑 진행률을 셀 때 미검증 첫 수신보다 이만큼 앞부터 본다(늦게 도착한 과거 측정값) */
    static final Duration REMAP_LOOKBACK = Duration.ofDays(31);
    static final int SAMPLE_SIZE = 3;

    private final MetricRepository metrics;
    private final RoleChecker roleChecker;
    private final CatalogEvents events;
    private final PipelineRemapClient pipeline;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public MetricService(MetricRepository metrics, RoleChecker roleChecker, CatalogEvents events, PipelineRemapClient pipeline,
                         Audits audits, JsonMapper json, Clock clock) {
        this.metrics = metrics;
        this.roleChecker = roleChecker;
        this.events = events;
        this.pipeline = pipeline;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-50 목록(DEV_READ). 미검증 항목에는 최근값 예시 3개 */
    @Transactional(readOnly = true)
    public ListApiResponse<MetricResponse> list(String status, String q, String key, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String st = PageParams.keyword(status);
        if (st != null) {
            st = st.toUpperCase(Locale.ROOT);
            if (!CatalogModels.METRIC_STATUSES.contains(st)) {
                throw Patch.invalid("status", "Invalid", String.join("|", CatalogModels.METRIC_STATUSES));
            }
        }
        PageParams params = PageParams.of(page, size);
        MetricFilter filter = new MetricFilter(orgId, st, PageParams.keyword(q), PageParams.keyword(key));
        List<Metric> rows = metrics.list(filter, params.size(), params.offset());
        Map<String, List<String>> aliases = metrics.listAliasesByKey(orgId, rows.stream().map(Metric::key).toList());
        List<MetricResponse> items = rows.stream().map(m -> toResponse(m, aliases.getOrDefault(m.key(), List.of()))).toList();
        return ListApiResponse.of(params, items, metrics.count(filter));
    }

    /** 측정 항목 하나(DEV_READ) */
    @Transactional(readOnly = true)
    public MetricResponse get(long metricId) {
        roleChecker.require(Permission.DEV_READ);
        return toResponse(load(roleChecker.currentUser().organizationId(), metricId));
    }

    /** API-DEV-51 생성(DEV_ADMIN). 사용자가 만든 항목은 VERIFIED */
    @Transactional
    public MetricResponse create(CreateMetricRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String key = requireKey(req.key());
        ObjectNode full = json.valueToTree(req);
        ObjectNode body = json.createObjectNode();
        full.properties().forEach(e -> {
            if (!e.getValue().isNull() && !"key".equals(e.getKey())) {
                body.set(e.getKey(), e.getValue());
            }
        });
        MetricFields fields = readFields(new Patch(body), defaults(req.displayName()));
        requireFreeKey(orgId, key);
        long id = metrics.insert(orgId, key, fields, CatalogModels.VERIFIED, false, user.userId(), clock.instant())
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.METRIC_KEY_DUPLICATE));
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_CREATED).actor(user).target("METRIC", Long.toString(id))
                .detail("key", key));
        events.metricChanged(orgId, id, 0);
        return toResponse(load(orgId, id));
    }

    /** API-DEV-51 부분 수정(DEV_ADMIN, 온 키만). 키는 바꿀 수 없다 */
    @Transactional
    public MetricResponse update(long metricId, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Metric before = load(orgId, metricId);
        if (body.has("key") && !before.key().equals(body.get("key").asString(""))) {
            if (before.builtin()) {
                throw new BusinessException(CatalogErrorCode.MODEL_BUILTIN_READONLY);
            }
            throw Patch.invalid("key", "Immutable", null);
        }
        long baseVersion = VersionCheck.baseVersion(body);
        VersionCheck.require(baseVersion, before.version());
        MetricFields fields = readFields(new Patch(body), fieldsOf(before));
        VersionCheck.requireUpdated(metrics.update(orgId, metricId, (int) baseVersion, fields, user.userId(), clock.instant()));
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_UPDATED).actor(user).target("METRIC", Long.toString(metricId))
                .detail("key", before.key()).detail("before", fieldMap(fieldsOf(before))).detail("after", fieldMap(fields)));
        events.metricChanged(orgId, metricId, before.version() + 1L);
        return toResponse(load(orgId, metricId));
    }

    /** API-DEV-52 미검증 항목을 표준으로 등록(DEV_ADMIN). 미검증이 아니면 409 METRIC_STATE_CONFLICT */
    @Transactional
    public MetricResponse verify(long metricId, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Metric before = load(orgId, metricId);
        requireStatus(before, CatalogModels.UNVERIFIED);
        MetricFields fields = readFields(new Patch(body == null ? JsonNodeFactory.instance.objectNode() : body), fieldsOf(before));
        if (metrics.updateVerified(orgId, metricId, fields, user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT);
        }
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_VERIFIED).actor(user).target("METRIC", Long.toString(metricId))
                .detail("key", before.key()));
        events.metricChanged(orgId, metricId, before.version() + 1L);
        return toResponse(load(orgId, metricId));
    }

    /**
     * API-DEV-53 미검증 항목을 표준 항목의 별칭으로 연결(DEV_ADMIN, BR-DEV-16·17). 미검증 항목을 지우고 별칭을 만든다.
     * remapHistory(기본 true)면 재매핑 작업을 만들고 커밋 후 pipeline(API-TSD-51)에 요청한다. 반환: {alias, remapJobId}
     */
    @Transactional
    public AliasToResponse aliasTo(long metricId, AliasToRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Metric source = load(orgId, metricId);
        requireStatus(source, CatalogModels.UNVERIFIED);
        Metric target = requireAliasTarget(orgId, req.targetKey().strip());
        if (target.id() == source.id()) {
            throw new BusinessException(CatalogErrorCode.METRIC_ALIAS_INVALID);
        }
        Instant now = clock.instant();
        try {
            if (metrics.deleteUnverified(orgId, metricId) == 0) {
                throw new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            // 다른 기능(점·목표 범위)이 이 미검증 키를 참조하고 있다
            throw new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT);
        }
        long aliasId = metrics.insertAlias(orgId, source.key(), target.key(), user.userId(), now);
        String jobId = null;
        if (!Boolean.FALSE.equals(req.remapHistory())) {
            Instant from = source.firstSeenAt() == null ? null : source.firstSeenAt().minus(REMAP_LOOKBACK);
            long total = metrics.countTelemetry(orgId, source.key(), from);
            long id = metrics.insertRemapJob(orgId, source.key(), target.key(), from, total, user.userId(), now);
            jobId = Long.toString(id);
            if (total > 0) {
                AfterCommit.run("metric remap " + id, () -> startRemap(orgId, id, source.key(), target.key()));
            }
        }
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_ALIASED).actor(user).target("METRIC", Long.toString(metricId))
                .detail("alias", source.key()).detail("targetKey", target.key()).detail("remapJobId", jobId));
        events.metricDeleted(orgId, metricId, source.version() + 1L);
        events.aliasChanged(orgId, aliasId);
        MetricAlias alias = metrics.findAlias(orgId, aliasId).orElseThrow();
        return new AliasToResponse(toAlias(alias), jobId);
    }

    /** 커밋 뒤 pipeline 재매핑 요청. 실패하면 작업을 FAILED로 남긴다(사용자가 진행률에서 본다) */
    void startRemap(long orgId, long jobId, String alias, String targetKey) {
        try {
            String pipelineJobId = pipeline.remap(orgId, alias, targetKey);
            metrics.updateRemapJob(orgId, jobId, "RUNNING", 0, pipelineJobId, null, clock.instant());
        } catch (RuntimeException ex) {
            log.warn("pipeline 재매핑 요청 실패 job={} alias={}", jobId, alias, ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            metrics.updateRemapJob(orgId, jobId, "FAILED", 0, null, message.length() > 300 ? message.substring(0, 300) : message,
                    clock.instant());
        }
    }

    /** API-DEV-54 무시(DEV_ADMIN). 미검증만 */
    @Transactional
    public MetricStatusResponse ignore(long metricId) {
        return transition(metricId, CatalogModels.UNVERIFIED, CatalogModels.IGNORED, CatalogAuditCodes.METRIC_IGNORED);
    }

    /** API-DEV-54 복원(DEV_ADMIN). 무시한 항목만 미검증으로 */
    @Transactional
    public MetricStatusResponse restore(long metricId) {
        return transition(metricId, CatalogModels.IGNORED, CatalogModels.UNVERIFIED, CatalogAuditCodes.METRIC_RESTORED);
    }

    private MetricStatusResponse transition(long metricId, String from, String to, String auditCode) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Metric before = load(orgId, metricId);
        requireStatus(before, from);
        if (metrics.updateStatus(orgId, metricId, from, to, user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT);
        }
        audits.record(audits.event(orgId, auditCode).actor(user).target("METRIC", Long.toString(metricId)).detail("key", before.key()));
        events.metricChanged(orgId, metricId, before.version() + 1L);
        return new MetricStatusResponse(Long.toString(metricId), before.key(), to, before.version() + 1);
    }

    // ---- 별칭(API-DEV-55)

    @Transactional(readOnly = true)
    public ListApiResponse<AliasResponse> listAliases(Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        List<AliasResponse> items = metrics.listAliases(orgId, params.size(), params.offset()).stream().map(MetricService::toAlias).toList();
        return ListApiResponse.of(params, items, metrics.countAliases(orgId));
    }

    @Transactional
    public AliasResponse createAlias(CreateAliasRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String alias = requireKey(req.alias());
        Metric target = requireAliasTarget(orgId, req.metricKey().strip());
        requireFreeKey(orgId, alias);
        long id;
        try {
            id = metrics.insertAlias(orgId, alias, target.key(), user.userId(), clock.instant());
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(CatalogErrorCode.METRIC_KEY_DUPLICATE);
        }
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_ALIAS_CREATED).actor(user).target("METRIC_ALIAS", Long.toString(id))
                .detail("alias", alias).detail("metricKey", target.key()));
        events.aliasChanged(orgId, id);
        return toAlias(metrics.findAlias(orgId, id).orElseThrow());
    }

    @Transactional
    public void deleteAlias(long aliasId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        MetricAlias alias = metrics.findAlias(orgId, aliasId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        metrics.deleteAlias(orgId, aliasId);
        audits.record(audits.event(orgId, CatalogAuditCodes.METRIC_ALIAS_DELETED).actor(user)
                .target("METRIC_ALIAS", Long.toString(aliasId)).detail("alias", alias.alias()).detail("metricKey", alias.metricKey()));
        events.aliasDeleted(orgId, aliasId);
    }

    /** API-DEV-56 재매핑 진행률(DEV_READ). 처리 수 = 시작할 때 행 수 - 남은 별칭 키 행 수. 다 바뀌면 SUCCEEDED로 남긴다 */
    @Transactional
    public RemapJobResponse remapJob(long jobId) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        RemapJob job = metrics.findRemapJob(orgId, jobId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if ("RUNNING".equals(job.status())) {
            Instant from = metrics.findRemapJobFrom(orgId, jobId).orElse(null);
            long remaining = metrics.countTelemetry(orgId, job.alias(), from);
            long processed = Math.max(0, job.total() - remaining);
            String status = remaining == 0 ? "SUCCEEDED" : "RUNNING";
            if (processed != job.processed() || !status.equals(job.status())) {
                metrics.updateRemapJob(orgId, jobId, status, processed, null, null, clock.instant());
                job = metrics.findRemapJob(orgId, jobId).orElseThrow();
            }
        }
        long processed = "SUCCEEDED".equals(job.status()) ? job.total() : job.processed();
        return new RemapJobResponse(Long.toString(job.id()), job.alias(), job.targetKey(), job.status(), processed, job.total(),
                job.error(), job.createdAt(), job.updatedAt());
    }

    // ---- 내부

    private Metric load(long orgId, long metricId) {
        return metrics.find(orgId, metricId).orElseThrow(() -> new BusinessException(CatalogErrorCode.METRIC_NOT_FOUND));
    }

    private static void requireStatus(Metric metric, String expected) {
        if (!expected.equals(metric.status())) {
            throw new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT);
        }
    }

    static String requireKey(String raw) {
        String key = raw == null ? "" : raw.strip();
        if (!key.matches(CatalogModels.METRIC_KEY_PATTERN)) {
            throw new BusinessException(CatalogErrorCode.METRIC_KEY_INVALID);
        }
        return key;
    }

    /** 표준 키·별칭과 겹치지 않아야 한다(BR-DEV-17) */
    private void requireFreeKey(long orgId, String key) {
        if (metrics.findByKey(orgId, key).isPresent() || metrics.findAliasTarget(orgId, key).isPresent()) {
            throw new BusinessException(CatalogErrorCode.METRIC_KEY_DUPLICATE);
        }
    }

    /** 별칭 대상: VERIFIED 표준 키. 별칭이거나 없거나 미검증·무시면 400 METRIC_ALIAS_INVALID */
    private Metric requireAliasTarget(long orgId, String targetKey) {
        return metrics.findByKey(orgId, targetKey)
                .filter(m -> CatalogModels.VERIFIED.equals(m.status()))
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.METRIC_ALIAS_INVALID));
    }

    private static MetricFields defaults(String displayName) {
        return new MetricFields(displayName, null, "NUMBER", null, null, null, 1, "AVG", false, null);
    }

    private static MetricFields fieldsOf(Metric m) {
        return new MetricFields(m.displayName(), m.unit(), m.valueType(), m.enumMapJson(), m.validMin(), m.validMax(), m.precision(),
                m.aggDefault(), m.stateType(), m.semantic());
    }

    /** 정의 값 읽기·검사(DEV-04.01). ENUM이면 매핑 필수·값은 숫자, 최소 < 최대, 소수 자릿수 0~6 */
    private MetricFields readFields(Patch patch, MetricFields current) {
        String displayName = patch.text("displayName", current.displayName(), 50, true);
        String unit = patch.text("unit", current.unit(), 16, false);
        String valueType = patch.choice("valueType", current.valueType(), CatalogModels.VALUE_TYPES);
        String enumMap = current.enumMapJson();
        if (patch.has("enumMap")) {
            JsonNode node = patch.node("enumMap");
            if (node == null || node.isNull()) {
                enumMap = null;
            } else if (!node.isObject() || node.isEmpty() || node.properties().stream().anyMatch(e -> !e.getValue().isNumber())) {
                patch.error("enumMap", "Invalid", "{label: number}");
            } else {
                enumMap = json.writeValueAsString(node);
            }
        }
        if ("ENUM".equals(valueType) && enumMap == null) {
            patch.error("enumMap", "NotNull", null);
        }
        Double validMin = patch.decimal("validMin", current.validMin(), null, null, true);
        Double validMax = patch.decimal("validMax", current.validMax(), null, null, true);
        if (validMin != null && validMax != null && validMin >= validMax) {
            patch.error("validMin", "MinMax", null);
        }
        Integer precision = patch.integer("precision", current.precision(), 0, 6, true);
        String agg = patch.choice("aggDefault", current.aggDefault(), CatalogModels.AGGREGATIONS);
        boolean stateType = patch.bool("stateType", current.stateType());
        String semantic = patch.text("semantic", current.semantic(), 32, false);
        patch.throwIfInvalid();
        return new MetricFields(displayName, unit, valueType, "ENUM".equals(valueType) ? enumMap : null, validMin, validMax,
                precision == null ? 1 : precision, agg, stateType, semantic);
    }

    private static Map<String, Object> fieldMap(MetricFields f) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("displayName", f.displayName());
        m.put("unit", f.unit());
        m.put("valueType", f.valueType());
        m.put("validMin", f.validMin());
        m.put("validMax", f.validMax());
        m.put("precision", f.precision());
        m.put("aggDefault", f.aggDefault());
        return m;
    }

    MetricResponse toResponse(Metric m) {
        return toResponse(m, metrics.listAliasesByKey(m.organizationId(), List.of(m.key())).getOrDefault(m.key(), List.of()));
    }

    private MetricResponse toResponse(Metric m, List<String> aliases) {
        FirstSeen firstSeen = m.firstSeenAt() == null ? null
                : new FirstSeen(m.firstSeenAt(), m.firstSeenDeviceId() == null ? null : Long.toString(m.firstSeenDeviceId()));
        List<SampleDto> sample = new ArrayList<>();
        if (CatalogModels.UNVERIFIED.equals(m.status())) {
            metrics.listSamples(m.organizationId(), m.key(), SAMPLE_SIZE)
                    .forEach(s -> sample.add(new SampleDto(Long.toString(s.deviceId()), s.value(), s.at())));
        }
        return new MetricResponse(Long.toString(m.id()), m.key(), m.displayName(), m.unit(), m.valueType(), enumMap(m.enumMapJson()),
                m.validMin(), m.validMax(), m.precision(), m.aggDefault(), m.stateType(), m.semantic(), m.status(), m.builtin(),
                aliases, firstSeen, sample, m.version(), m.updatedAt());
    }

    JsonNode enumMap(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    static AliasResponse toAlias(MetricAlias a) {
        return new AliasResponse(Long.toString(a.id()), a.alias(), a.metricKey(), a.metricId() == null ? null : Long.toString(a.metricId()),
                a.createdAt());
    }
}
