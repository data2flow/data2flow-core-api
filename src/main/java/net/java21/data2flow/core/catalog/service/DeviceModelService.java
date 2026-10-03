package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import net.java21.data2flow.core.catalog.domain.CatalogModels.DeviceModel;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelFields;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelMetric;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelPackage;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CapabilityDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CloneModelRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateModelRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelMetricDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelSummaryResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageResponse;
import net.java21.data2flow.core.catalog.event.CatalogEvents;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository.ModelFilter;
import net.java21.data2flow.core.catalog.repository.MetricRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 기기 모델(DEV-03.01, API-DEV-40~43·46·47)과 모델 패키지·속성 스키마(DEV-07.05).
 *
 * <ul>
 *   <li>BR-DEV-14: 기본 제공(builtin) 모델은 수정·패키지 변경·사용 중지·삭제가 403 MODEL_BUILTIN_READONLY이고 복제는 된다</li>
 *   <li>BR-DEV-15: 쓰는 기기(소프트 삭제된 기기 포함 — FK가 모델을 잡고 있다)나 소스의 기본 모델이면 삭제는 409 MODEL_IN_USE,
 *       대신 사용 중지(DEPRECATED)</li>
 *   <li>측정 항목은 VERIFIED 항목만 연결한다(없거나 미검증이면 404 METRIC_NOT_FOUND). SENSOR 모델은 1개 이상</li>
 *   <li>기능(Capability)은 이름과 제약만 저장한다. 검증은 M3(ACT-01.03)</li>
 * </ul>
 * 바뀌면 MODEL 설정 변경(EVT-DEV-04)을 보내고 설정 버전 MODELS를 올린다. 모델·측정 항목은 조직 단위라 공간 범위가 없고,
 * 사용 기기 수만 호출자의 공간 범위로 센다(BR-DEV-25).
 */
@Service
public class DeviceModelService {

    static final int DEFAULT_INTERVAL_SEC = 600;
    static final double DEFAULT_OFFLINE_MULTIPLIER = 3.0;

    private final DeviceModelRepository models;
    private final MetricRepository metrics;
    private final ModelAttributeSchemaValidator schemas;
    private final RoleChecker roleChecker;
    private final CatalogEvents events;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;
    private final net.java21.data2flow.core.control.repository.DriverRepository driverBindings;
    private final net.java21.data2flow.core.control.service.CapabilityService capabilityCatalog;

    public DeviceModelService(DeviceModelRepository models, MetricRepository metrics, ModelAttributeSchemaValidator schemas,
                              RoleChecker roleChecker, CatalogEvents events, Audits audits, JsonMapper json, Clock clock,
                              net.java21.data2flow.core.control.repository.DriverRepository driverBindings,
                              net.java21.data2flow.core.control.service.CapabilityService capabilityCatalog) {
        this.driverBindings = driverBindings;
        this.capabilityCatalog = capabilityCatalog;
        this.models = models;
        this.metrics = metrics;
        this.schemas = schemas;
        this.roleChecker = roleChecker;
        this.events = events;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-46 목록(DEV_READ). code로 찾으면 DEPRECATED도 나온다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ModelSummaryResponse> list(String q, String code, String protocol, String kind, Boolean includeDeprecated,
                                                      Boolean builtin, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        ModelFilter filter = new ModelFilter(orgId, PageParams.keyword(q), PageParams.keyword(code), upperOrNull(protocol),
                upperOrNull(kind), Boolean.TRUE.equals(includeDeprecated), builtin);
        List<DeviceModel> rows = models.list(filter, params.size(), params.offset());
        List<Long> ids = rows.stream().map(DeviceModel::id).toList();
        Map<Long, Long> devices = models.countDevicesByModel(orgId, ids, roleChecker.spaceScope());
        Map<Long, Long> metricCounts = models.countMetricsByModel(orgId, ids);
        List<ModelSummaryResponse> items = rows.stream().map(m -> new ModelSummaryResponse(Long.toString(m.id()), m.code(), m.vendor(),
                m.name(), m.protocol(), m.kind(), m.builtin(), m.status(), devices.getOrDefault(m.id(), 0L),
                metricCounts.getOrDefault(m.id(), 0L), m.updatedAt())).toList();
        return ListApiResponse.of(params, items, models.count(filter));
    }

    /** API-DEV-46 상세(DEV_READ) */
    @Transactional(readOnly = true)
    public ModelResponse get(long modelId) {
        roleChecker.require(Permission.DEV_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return toResponse(load(orgId, modelId));
    }

    /** API-DEV-40 생성(DEV_ADMIN) */
    @Transactional
    public ModelResponse create(CreateModelRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Patch check = new Patch(null);
        String code = req.code().strip();
        if (!code.matches(CatalogModels.MODEL_CODE_PATTERN)) {
            check.error("code", "Pattern", CatalogModels.MODEL_CODE_PATTERN);
        }
        String protocol = req.protocol().strip().toUpperCase(Locale.ROOT);
        String kind = req.kind().strip().toUpperCase(Locale.ROOT);
        if (!CatalogModels.PROTOCOLS.contains(protocol)) {
            check.error("protocol", "Invalid", String.join("|", CatalogModels.PROTOCOLS));
        }
        if (!CatalogModels.KINDS.contains(kind)) {
            check.error("kind", "Invalid", String.join("|", CatalogModels.KINDS));
        }
        int interval = req.defaultIntervalSec() == null ? DEFAULT_INTERVAL_SEC : req.defaultIntervalSec();
        if (interval < 10 || interval > 86400) {
            check.error("defaultIntervalSec", "Range", "10~86400");
        }
        double multiplier = req.defaultOfflineMultiplier() == null ? DEFAULT_OFFLINE_MULTIPLIER : req.defaultOfflineMultiplier();
        if (!(multiplier >= 1.5 && multiplier <= 10)) {
            check.error("defaultOfflineMultiplier", "Range", "1.5~10");
        }
        List<ModelMetric> modelMetrics = toModelMetrics(req.metrics());
        if ("SENSOR".equals(kind) && modelMetrics.isEmpty()) {
            check.error("metrics", "NotEmpty", null);
        }
        check.throwIfInvalid();
        requireVerifiedMetrics(orgId, modelMetrics);
        if (models.existsCode(orgId, code)) {
            throw new BusinessException(CatalogErrorCode.MODEL_CODE_DUPLICATE);
        }
        Instant now = clock.instant();
        ModelFields fields = new ModelFields(req.vendor().strip(), req.name().strip(), protocol, kind, interval, multiplier,
                blankToNull(req.description()), capabilitiesJson(req.capabilities()));
        long id = models.insert(orgId, code, fields, new ModelPackage(null, null, null, null, List.of(), null), user.userId(), now);
        models.replaceMetrics(orgId, id, modelMetrics, now);
        audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_CREATED).actor(user).target("DEVICE_MODEL", Long.toString(id))
                .detail("code", code).detail("metrics", modelMetrics.stream().map(ModelMetric::key).toList()));
        events.modelChanged(orgId, id, 0);
        return toResponse(load(orgId, id));
    }

    /** API-DEV-41 부분 수정(DEV_ADMIN, 온 키만). 코드는 바꿀 수 없다 */
    @Transactional
    public ModelResponse update(long modelId, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DeviceModel before = load(orgId, modelId);
        requireEditable(before);
        long baseVersion = VersionCheck.baseVersion(body);
        VersionCheck.require(baseVersion, before.version());
        Patch patch = new Patch(body);
        if (patch.has("code") && !before.code().equals(patch.node("code").asString(""))) {
            patch.error("code", "Immutable", null);
        }
        String vendor = patch.text("vendor", before.vendor(), 100, true);
        String name = patch.text("name", before.name(), 100, true);
        String protocol = patch.choice("protocol", before.protocol(), CatalogModels.PROTOCOLS);
        String kind = patch.choice("kind", before.kind(), CatalogModels.KINDS);
        Integer interval = patch.integer("defaultIntervalSec", before.defaultIntervalSec(), 10, 86400, false);
        Double multiplier = patch.decimal("defaultOfflineMultiplier", before.defaultOfflineMultiplier(), 1.5, 10.0, false);
        String description = patch.text("description", before.description(), 500, false);
        String capabilities = before.capabilitiesJson();
        if (patch.has("capabilities")) {
            capabilities = capabilitiesJson(readList(patch, "capabilities", CapabilityDto.class));
        }
        List<ModelMetric> modelMetrics = null;
        if (patch.has("metrics")) {
            modelMetrics = toModelMetrics(readList(patch, "metrics", ModelMetricDto.class));
        }
        List<ModelMetric> effective = modelMetrics != null ? modelMetrics : models.listMetrics(orgId, modelId);
        if ("SENSOR".equals(kind) && effective.isEmpty()) {
            patch.error("metrics", "NotEmpty", null);
        }
        patch.throwIfInvalid();
        if (modelMetrics != null) {
            requireVerifiedMetrics(orgId, modelMetrics);
        }
        Instant now = clock.instant();
        ModelFields fields = new ModelFields(vendor, name, protocol, kind, interval, multiplier, description, capabilities);
        VersionCheck.requireUpdated(models.update(orgId, modelId, (int) baseVersion, fields, user.userId(), now));
        if (modelMetrics != null) {
            models.replaceMetrics(orgId, modelId, modelMetrics, now);
        }
        audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_UPDATED).actor(user)
                .target("DEVICE_MODEL", Long.toString(modelId)).detail("code", before.code())
                .detail("fields", fieldNames(body)));
        events.modelChanged(orgId, modelId, before.version() + 1L);
        return toResponse(load(orgId, modelId));
    }

    /** API-DEV-42 패키지(스크립트·드라이버·기본 대시보드·기본 규칙 템플릿·속성 스키마) 저장(DEV_ADMIN) */
    @Transactional
    public PackageResponse updatePackage(long modelId, PackageRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DeviceModel before = load(orgId, modelId);
        requireEditable(before);
        if (req.baseVersion() != null) {
            VersionCheck.require(req.baseVersion(), before.version());
        }
        Patch check = new Patch(null);
        Long transform = idOrNull(check, "transformScriptId", req.transformScriptId());
        Long decode = idOrNull(check, "decodeScriptId", req.decodeScriptId());
        Long dashboard = idOrNull(check, "defaultDashboardId", req.defaultDashboardId());
        List<Long> ruleTemplates = new ArrayList<>();
        for (String raw : req.defaultRuleTemplateIds() == null ? List.<String>of() : req.defaultRuleTemplateIds()) {
            Long id = idOrNull(check, "defaultRuleTemplateIds", raw);
            if (id != null && !ruleTemplates.contains(id)) {
                ruleTemplates.add(id);
            }
        }
        check.throwIfInvalid();
        schemas.parse(req.attributeSchema());
        requireScript(orgId, transform, "TRANSFORM", "transformScriptId");
        requireScript(orgId, decode, "DECODE", "decodeScriptId");
        String schemaJson = req.attributeSchema() == null || req.attributeSchema().isNull() ? null : json.writeValueAsString(req.attributeSchema());
        ModelPackage pkg = new ModelPackage(transform, decode, blankToNull(req.driverKey()), dashboard, ruleTemplates, schemaJson);
        VersionCheck.requireUpdated(models.updatePackage(orgId, modelId, req.baseVersion(), pkg, user.userId(), clock.instant()));
        audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_PACKAGE_UPDATED).actor(user)
                .target("DEVICE_MODEL", Long.toString(modelId)).detail("code", before.code()));
        events.modelChanged(orgId, modelId, before.version() + 1L);
        DeviceModel after = load(orgId, modelId);
        PackageDto dto = packageOf(after);
        return new PackageResponse(Long.toString(modelId), dto.transformScriptId(), dto.decodeScriptId(), dto.driverKey(),
                dto.defaultDashboardId(), dto.defaultRuleTemplateIds(), dto.attributeSchema(), after.version());
    }

    /** API-DEV-43 사용 중지(DEPRECATED, DEV_ADMIN). 이미 DEPRECATED면 그대로 */
    @Transactional
    public ModelResponse deprecate(long modelId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DeviceModel model = load(orgId, modelId);
        requireEditable(model);
        if (!CatalogModels.MODEL_DEPRECATED.equals(model.status())) {
            models.updateStatus(orgId, modelId, CatalogModels.MODEL_DEPRECATED, user.userId(), clock.instant());
            audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_DEPRECATED).actor(user)
                    .target("DEVICE_MODEL", Long.toString(modelId)).detail("code", model.code()));
            events.modelChanged(orgId, modelId, model.version() + 1L);
        }
        return toResponse(load(orgId, modelId));
    }

    /** API-DEV-43 삭제(DEV_ADMIN). 쓰는 곳이 있으면 409 MODEL_IN_USE(BR-DEV-15) */
    @Transactional
    public void delete(long modelId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DeviceModel model = load(orgId, modelId);
        requireEditable(model);
        if (models.countReferences(orgId, modelId) > 0) {
            throw new BusinessException(CatalogErrorCode.MODEL_IN_USE);
        }
        models.delete(orgId, modelId);
        audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_DELETED).actor(user)
                .target("DEVICE_MODEL", Long.toString(modelId)).detail("code", model.code()));
        events.modelDeleted(orgId, modelId, model.version() + 1L);
    }

    /** API-DEV-47 복제(DEV_ADMIN). 기본 모델도 복제할 수 있다(BR-DEV-14). 측정 항목·기능·패키지를 그대로 가져간다 */
    @Transactional
    public ModelResponse cloneModel(long modelId, CloneModelRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        DeviceModel source = load(orgId, modelId);
        String code = req.newCode().strip();
        if (!code.matches(CatalogModels.MODEL_CODE_PATTERN)) {
            throw Patch.invalid("newCode", "Pattern", CatalogModels.MODEL_CODE_PATTERN);
        }
        if (models.existsCode(orgId, code)) {
            throw new BusinessException(CatalogErrorCode.MODEL_CODE_DUPLICATE);
        }
        String name = req.name() == null || req.name().isBlank() ? source.name() : req.name().strip();
        Instant now = clock.instant();
        ModelFields fields = new ModelFields(source.vendor(), name, source.protocol(), source.kind(), source.defaultIntervalSec(),
                source.defaultOfflineMultiplier(), source.description(), source.capabilitiesJson());
        ModelPackage pkg = new ModelPackage(source.transformScriptId(), source.decodeScriptId(), source.driverKey(),
                source.defaultDashboardId(), source.defaultRuleTemplateIds(), source.attributeSchemaJson());
        long id = models.insert(orgId, code, fields, pkg, user.userId(), now);
        models.replaceMetrics(orgId, id, models.listMetrics(orgId, modelId), now);
        audits.record(audits.event(orgId, CatalogAuditCodes.DEVICE_MODEL_CREATED).actor(user).target("DEVICE_MODEL", Long.toString(id))
                .detail("code", code).detail("clonedFrom", source.code()));
        events.modelChanged(orgId, id, 0);
        return toResponse(load(orgId, id));
    }

    // ---- 내부

    private DeviceModel load(long orgId, long modelId) {
        return models.find(orgId, modelId).orElseThrow(() -> new BusinessException(CatalogErrorCode.MODEL_NOT_FOUND));
    }

    private static void requireEditable(DeviceModel model) {
        if (model.builtin()) {
            throw new BusinessException(CatalogErrorCode.MODEL_BUILTIN_READONLY);
        }
    }

    private void requireVerifiedMetrics(long orgId, List<ModelMetric> modelMetrics) {
        Map<String, String> statuses = metrics.findStatuses(orgId, modelMetrics.stream().map(ModelMetric::key).toList());
        for (ModelMetric m : modelMetrics) {
            if (!CatalogModels.VERIFIED.equals(statuses.get(m.key()))) {
                throw new BusinessException(CatalogErrorCode.METRIC_NOT_FOUND);
            }
        }
    }

    private void requireScript(long orgId, Long scriptId, String kind, String field) {
        if (scriptId == null) {
            return;
        }
        String actual = models.findScriptKind(orgId, scriptId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.SCRIPT_NOT_FOUND));
        if (!kind.equals(actual)) {
            throw Patch.invalid(field, "Kind", kind);
        }
    }

    private static List<ModelMetric> toModelMetrics(List<ModelMetricDto> raw) {
        Map<String, ModelMetric> result = new LinkedHashMap<>();
        for (ModelMetricDto dto : raw == null ? List.<ModelMetricDto>of() : raw) {
            if (dto == null || dto.key() == null || dto.key().isBlank()) {
                throw Patch.invalid("metrics", "NotBlank", null);
            }
            String key = dto.key().strip();
            result.putIfAbsent(key, new ModelMetric(key, Boolean.TRUE.equals(dto.required())));
        }
        return List.copyOf(result.values());
    }

    private <T> List<T> readList(Patch patch, String field, Class<T> type) {
        JsonNode node = patch.node(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw Patch.invalid(field, "Type", null);
        }
        List<T> result = new ArrayList<>();
        for (JsonNode item : node.values()) {
            try {
                result.add(json.treeToValue(item, type));
            } catch (RuntimeException ex) {
                throw Patch.invalid(field, "Type", null);
            }
        }
        return result;
    }

    /** 기능 목록 JSON. 이름은 1~60자, 같은 이름은 한 번만 */
    private String capabilitiesJson(List<CapabilityDto> raw) {
        ArrayNode array = json.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        var catalog = raw == null || raw.isEmpty() ? null : capabilityCatalog.catalog(roleChecker.currentUser().organizationId());
        for (CapabilityDto dto : raw == null ? List.<CapabilityDto>of() : raw) {
            String name = dto == null || dto.capability() == null ? "" : dto.capability().strip();
            if (name.isEmpty() || name.length() > 60) {
                throw Patch.invalid("capabilities", "Size", "1~60");
            }
            // ACT-01.03: 모델 지원 기능은 카탈로그(표준 7종 + 조직 custom.*)에 있어야 한다
            if (catalog != null && catalog.find(name).isEmpty()) {
                throw Patch.invalid("capabilities", "CAPABILITY_NOT_SUPPORTED", name);
            }
            if (seen.add(name)) {
                ObjectNode item = array.addObject();
                item.put("capability", name);
                item.set("constraints", dto.constraints() == null ? json.nullNode() : dto.constraints());
            }
        }
        return json.writeValueAsString(array);
    }

    private static Long idOrNull(Patch check, String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.strip());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 아래 오류
        }
        check.error(field, "Type", null);
        return null;
    }

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(n -> {
            if (!VersionCheck.BASE_VERSION.equals(n)) {
                names.add(n);
            }
        });
        return names;
    }

    ModelResponse toResponse(DeviceModel m) {
        SpaceScope scope = roleChecker.spaceScope();
        long deviceCount = models.countDevicesByModel(m.organizationId(), List.of(m.id()), scope).getOrDefault(m.id(), 0L);
        List<ModelMetricDto> modelMetrics = models.listMetrics(m.organizationId(), m.id()).stream()
                .map(x -> new ModelMetricDto(x.key(), x.required())).toList();
        PackageDto pkg = packageOf(m);
        return new ModelResponse(Long.toString(m.id()), m.code(), m.vendor(), m.name(), m.protocol(), m.kind(), m.defaultIntervalSec(),
                m.defaultOfflineMultiplier(), m.description(), null, m.builtin(), m.status(), modelMetrics, capabilities(m), pkg,
                pkg.attributeSchema(), deviceCount, m.version(), m.updatedAt());
    }

    private List<CapabilityDto> capabilities(DeviceModel m) {
        List<CapabilityDto> result = new ArrayList<>();
        if (m.capabilitiesJson() == null) {
            return result;
        }
        for (JsonNode item : json.readTree(m.capabilitiesJson()).values()) {
            JsonNode constraints = item.get("constraints");
            result.add(new CapabilityDto(item.path("capability").asString(""),
                    constraints == null || constraints.isNull() ? null : constraints));
        }
        return result;
    }

    private PackageDto packageOf(DeviceModel m) {
        JsonNode schema = m.attributeSchemaJson() == null ? null : json.readTree(m.attributeSchemaJson());
        var binding = driverBindings.findBinding(m.organizationId(), m.id()).orElse(null);
        return new PackageDto(str(m.transformScriptId()), str(m.decodeScriptId()), m.driverKey(), str(m.defaultDashboardId()),
                m.defaultRuleTemplateIds().stream().map(String::valueOf).toList(), schema,
                binding == null ? null : Long.toString(binding.driverId()), binding == null ? null : str(binding.encoderScriptId()));
    }

    private static String str(Long id) {
        return id == null ? null : Long.toString(id);
    }

    private static String upperOrNull(String raw) {
        String v = PageParams.keyword(raw);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }
}
