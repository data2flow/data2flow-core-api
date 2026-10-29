package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.EnergyBaselineTrained;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.Accepted;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.ActiveVersion;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.BaselineView;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.CreateVersionRequest;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.SpaceRef;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.VersionDetail;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.VersionView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.UserRef;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository.BaselineRow;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository.VersionRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.TreeRow;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository.DeviceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.java21.data2flow.core.energy.service.EnergyAccess.invalid;

/**
 * 기준선(ENE-01.04, API-ENE-12, UC-ENE-04, BR-ENE-08). 학습은 analytics(API-ENE-81)가 비동기로 하고 결과는 EVT-ENE-02로 받는다.
 * <ul>
 *   <li>학습 요청(ENE_ANALYZE): 범위 공간의 기준선(없으면 만든다)에 TRAINING 버전을 쓰고 analytics에 권한 범위로 자른 공간 트리(가상 공간 표시)와
 *       기온 원천을 넘긴다. 학습 기간이 30일보다 짧으면 409 ENE_BASELINE_INSUFFICIENT_DATA(neededDays), 730일보다 길면 400</li>
 *   <li>적용(ENE_ANALYZE): READY → ACTIVE, 이전 ACTIVE → RETIRED. READY가 아니면 409 ENE_BASELINE_NOT_READY</li>
 *   <li>EVT-ENE-02: TRAINING 버전만 READY(r2·계수·경고)·FAILED(사유·neededDays)로 바꾼다</li>
 * </ul>
 */
@Service
public class EnergyBaselineService {

    static final String TRAIN = "/internal/analytics/energy/baselines/train";
    static final String MODEL = "/internal/analytics/energy/baselines/";
    static final int MIN_DAYS = 30;
    static final int MAX_DAYS = 730;
    static final Set<String> METHODS = Set.of("PERIOD_AVERAGE", "DEGREE_DAY_REGRESSION");
    private static final Logger log = LoggerFactory.getLogger(EnergyBaselineService.class);

    private final EnergyBaselineRepository baselines;
    private final EnergyConfigRepository config;
    private final EnergySourceRepository sources;
    private final EnergyAccess access;
    private final RoleChecker roleChecker;
    private final AnalyticsClient analytics;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public EnergyBaselineService(EnergyBaselineRepository baselines, EnergyConfigRepository config, EnergySourceRepository sources, EnergyAccess access,
                                 RoleChecker roleChecker, AnalyticsClient analytics, Audits audits, JsonMapper json, Clock clock) {
        this.baselines = baselines;
        this.config = config;
        this.sources = sources;
        this.access = access;
        this.roleChecker = roleChecker;
        this.analytics = analytics;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    // ---- 조회 ----

    @Transactional(readOnly = true)
    public ListApiResponse<BaselineView> list(String scopeSpaceId, Integer page, Integer size) {
        roleChecker.require(Permission.ENE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Long space = EnergyAccess.id(scopeSpaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        if (space != null) {
            access.space(Permission.ENE_READ, orgId, space);
        }
        SpaceScope scope = roleChecker.spaceScope();
        List<BaselineRow> rows = baselines.list(orgId, space).stream().filter(b -> scope.includes(b.scopeSpaceId())).toList();
        PageParams p = PageParams.of(page, size);
        int start = (int) Math.min(p.offset(), rows.size());
        return ListApiResponse.of(p, rows.subList(start, Math.min(start + p.size(), rows.size())).stream().map(EnergyBaselineService::view).toList(),
                rows.size());
    }

    @Transactional(readOnly = true)
    public ListApiResponse<VersionView> versions(String baselineId, Integer page, Integer size) {
        BaselineRow b = baseline(Permission.ENE_READ, baselineId);
        List<VersionRow> rows = baselines.versions(roleChecker.currentUser().organizationId(), b.id());
        PageParams p = PageParams.of(page, size);
        int start = (int) Math.min(p.offset(), rows.size());
        return ListApiResponse.of(p, rows.subList(start, Math.min(start + p.size(), rows.size())).stream().map(EnergyBaselineService::view).toList(),
                rows.size());
    }

    @Transactional(readOnly = true)
    public VersionDetail version(String baselineId, String versionId) {
        BaselineRow b = baseline(Permission.ENE_READ, baselineId);
        VersionRow v = versionOf(b, versionId, false);
        List<JsonNode> residuals = new ArrayList<>();
        if (v.analyticsModelId() != null && !"TRAINING".equals(v.status()) && !"FAILED".equals(v.status())) {
            try {
                JsonNode model = analytics.call(HttpMethod.GET, MODEL + v.analyticsModelId(), null, null, null);
                if (model != null && model.path("residuals").isArray()) {
                    model.path("residuals").values().forEach(residuals::add);
                }
            } catch (RuntimeException ex) {  // 잔차는 보조 정보다. analytics에 닿지 않아도 상세는 보여 준다
                log.warn("baseline residuals unavailable version={}: {}", v.id(), ex.toString());
            }
        }
        return new VersionDetail(Long.toString(v.id()), Long.toString(v.baselineId()), Long.toString(v.scopeSpaceId()), v.method(),
                v.trainFrom().toString(), v.trainTo().toString(), readJson(v.paramsJson()), v.status(), v.r2(), readJson(v.coefficientsJson()), residuals,
                v.warning(), v.errorCode(), v.neededDays(), user(v), v.createdAt());
    }

    /** 범위 공간의 활성 버전 모델 키(지표의 기준선 비교). 없으면 비어 있다 */
    @Transactional(readOnly = true)
    public Optional<BaselineRow> active(long orgId, long spaceId) {
        return baselines.forSpace(orgId, spaceId).filter(b -> b.activeVersionId() != null && b.activeModelKey() != null);
    }

    // ---- 학습 요청 ----

    @Transactional
    public Accepted create(CreateVersionRequest r) {
        roleChecker.require(Permission.ENE_ANALYZE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (r == null || r.scopeSpaceId() == null || r.scopeSpaceId().isBlank()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "scopeSpaceId", "NotNull");
        }
        long spaceId = EnergyAccess.id(r.scopeSpaceId(), EnergyErrorCode.SPACE_NOT_FOUND);
        SpaceRow space = access.space(Permission.ENE_ANALYZE, orgId, spaceId);
        String method = r.method() == null ? "" : r.method().strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "method", String.join(", ", METHODS));
        }
        LocalDate from = date("trainFrom", r.trainFrom());
        LocalDate to = date("trainTo", r.trainTo());
        if (to.isBefore(from)) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "trainTo", "Order");
        }
        if (!to.isBefore(LocalDate.now(clock))) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "trainTo", "Past");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < MIN_DAYS) {
            long needed = MIN_DAYS - days;
            throw new BusinessException(EnergyErrorCode.ENE_BASELINE_INSUFFICIENT_DATA,
                    List.of(new FieldErrorDetail("neededDays", "NeededDays", Long.toString(needed))), needed);
        }
        if (days > MAX_DAYS) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "trainFrom", "Range");
        }
        Map<String, Object> params = params(r, method, orgId);

        long baselineId = baselines.ensure(orgId, spaceId, clock.instant());
        long versionId = baselines.insertVersion(orgId, baselineId, method, from, to, json.writeValueAsString(params), user.userId(), clock.instant());

        List<Map<String, Object>> spaces = new ArrayList<>();
        for (TreeRow t : config.spaceTree(orgId, space.path(), roleChecker.spaceScope())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", t.id());
            node.put("parentId", t.parentId());
            node.put("name", t.name());
            node.put("kind", t.type());
            node.put("areaM2", t.areaM2());
            node.put("coversDescendants", t.coversDescendants());
            node.put("virtual", t.virtual());
            spaces.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionId", versionId);
        body.put("orgId", orgId);
        body.put("scopeSpaceId", spaceId);
        body.put("method", method);
        body.put("trainFrom", from.toString());
        body.put("trainTo", to.toString());
        body.put("params", Map.of("coolingBaseC", params.get("coolingBaseC"), "heatingBaseC", params.get("heatingBaseC"),
                "splitWeekday", params.get("splitWeekday")));
        body.put("excludeVirtual", true);
        body.put("spaces", spaces);
        body.put("temperatureSource", params.get("temperatureSource"));
        body.put("timezone", config.timezone(orgId));
        // analytics가 받지 못하면(503) 트랜잭션째 되돌려 TRAINING에 머무는 버전을 남기지 않는다
        JsonNode accepted = analytics.call(HttpMethod.POST, TRAIN, null, body, EnergyErrorCode.SPACE_NOT_FOUND);
        String jobId = accepted == null ? null : accepted.path("jobId").asString(null);
        baselines.setModelKey(versionId, accepted == null ? null : accepted.path("modelKey").asString(null));
        return new Accepted(jobId, Long.toString(versionId));
    }

    private Map<String, Object> params(CreateVersionRequest r, String method, long orgId) {
        BigDecimal cooling = r.params() != null && r.params().coolingBaseC() != null ? r.params().coolingBaseC() : BigDecimal.valueOf(24);
        BigDecimal heating = r.params() != null && r.params().heatingBaseC() != null ? r.params().heatingBaseC() : BigDecimal.valueOf(18);
        if (cooling.compareTo(BigDecimal.valueOf(10)) < 0 || cooling.compareTo(BigDecimal.valueOf(35)) > 0) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "params.coolingBaseC", "Range");
        }
        if (heating.compareTo(BigDecimal.valueOf(5)) < 0 || heating.compareTo(cooling) > 0) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "params.heatingBaseC", "Range");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coolingBaseC", cooling.doubleValue());
        out.put("heatingBaseC", heating.doubleValue());
        out.put("splitWeekday", r.params() == null || r.params().splitWeekday() == null || r.params().splitWeekday());
        Map<String, Object> temperature = null;
        if (r.temperatureSource() != null && r.temperatureSource().deviceId() != null) {
            long deviceId = EnergyAccess.id(r.temperatureSource().deviceId(), EnergyErrorCode.DEVICE_NOT_FOUND);
            String metric = r.temperatureSource().metricKey() == null || r.temperatureSource().metricKey().isBlank()
                    ? "temperature" : r.temperatureSource().metricKey().strip();
            DeviceRow device = sources.device(orgId, deviceId).orElseThrow(() -> new BusinessException(EnergyErrorCode.DEVICE_NOT_FOUND));
            if (!device.metricKeys().contains(metric)) {
                throw invalid(CommonErrorCode.INVALID_REQUEST, "temperatureSource.metricKey", "UnknownMetric");
            }
            temperature = new LinkedHashMap<>();
            temperature.put("deviceId", deviceId);
            temperature.put("metricKey", metric);
        }
        if ("DEGREE_DAY_REGRESSION".equals(method) && temperature == null) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, "temperatureSource", "NotNull");
        }
        out.put("temperatureSource", temperature);
        return out;
    }

    // ---- 적용 ----

    @Transactional
    public VersionView activate(String versionId) {
        roleChecker.require(Permission.ENE_ANALYZE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        long id = EnergyAccess.id(versionId, EnergyErrorCode.ENE_BASELINE_NOT_FOUND);
        VersionRow v = baselines.version(orgId, id, true).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_FOUND));
        roleChecker.require(Permission.ENE_ANALYZE, v.scopeSpaceId(), EnergyErrorCode.ENE_BASELINE_NOT_FOUND);
        if (!"ACTIVE".equals(v.status())) {
            if (!"READY".equals(v.status())) {
                throw new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_READY);
            }
            baselines.activate(v.baselineId(), v.id(), clock.instant());
            audits.record(audits.event(orgId, EnergyAuditCodes.ENE_BASELINE_ACTIVATED).actor(user)
                    .target("ENERGY_BASELINE_VERSION", Long.toString(v.id())).detail("baselineId", Long.toString(v.baselineId())));
        }
        return view(baselines.version(orgId, id, false).orElseThrow());
    }

    // ---- EVT-ENE-02 ----

    @Transactional
    public boolean onTrained(long orgId, EnergyBaselineTrained e) {
        if (e.status() == EnergyBaselineTrained.Status.READY) {
            BigDecimal r2 = e.r2() == null ? null
                    : BigDecimal.valueOf(Math.max(-9.999, Math.min(1.0, e.r2()))).setScale(3, RoundingMode.HALF_UP);
            String coef = e.coefficients() == null ? null : json.writeValueAsString(e.coefficients());
            return baselines.trained(orgId, e.versionId(), r2, coef, e.residualsRef(), e.warning(), clock.instant()) > 0;
        }
        return baselines.failed(orgId, e.versionId(), e.error() == null ? "INTERNAL_ERROR" : e.error(), e.neededDays(), clock.instant()) > 0;
    }

    // ---- 도움 ----

    private BaselineRow baseline(Permission permission, String baselineId) {
        roleChecker.require(permission);
        long orgId = roleChecker.currentUser().organizationId();
        long id = EnergyAccess.id(baselineId, EnergyErrorCode.ENE_BASELINE_NOT_FOUND);
        BaselineRow b = baselines.find(orgId, id).orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_FOUND));
        roleChecker.require(permission, b.scopeSpaceId(), EnergyErrorCode.ENE_BASELINE_NOT_FOUND);
        return b;
    }

    private VersionRow versionOf(BaselineRow b, String versionId, boolean lock) {
        long id = EnergyAccess.id(versionId, EnergyErrorCode.ENE_BASELINE_NOT_FOUND);
        return baselines.version(roleChecker.currentUser().organizationId(), id, lock).filter(v -> v.baselineId() == b.id())
                .orElseThrow(() -> new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_FOUND));
    }

    private static LocalDate date(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, field, "NotNull");
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw invalid(CommonErrorCode.INVALID_REQUEST, field, "Date");
        }
    }

    private JsonNode readJson(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    private static UserRef user(VersionRow v) {
        return v.createdBy() == 0 ? null : new UserRef(Long.toString(v.createdBy()), v.createdByName());
    }

    static BaselineView view(BaselineRow b) {
        ActiveVersion active = b.activeVersionId() == null ? null
                : new ActiveVersion(Long.toString(b.activeVersionId()), b.activeMethod(), b.activeR2(), String.valueOf(b.activeFrom()),
                String.valueOf(b.activeTo()));
        return new BaselineView(Long.toString(b.id()), new SpaceRef(Long.toString(b.scopeSpaceId()), b.scopeSpaceName()), active, b.updatedAt());
    }

    static VersionView view(VersionRow v) {
        return new VersionView(Long.toString(v.id()), Long.toString(v.baselineId()), v.method(), v.trainFrom().toString(), v.trainTo().toString(),
                v.status(), v.r2(), v.warning(), v.errorCode(), v.neededDays(), user(v), v.createdAt());
    }
}
