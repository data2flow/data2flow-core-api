package net.java21.data2flow.core.modelexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CapabilityDefinition;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import net.java21.data2flow.core.catalog.domain.CatalogModels.DeviceModel;
import net.java21.data2flow.core.catalog.domain.CatalogModels.Metric;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelMetric;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CapabilityDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateMetricRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateModelRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelMetricDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageRequest;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.catalog.repository.MetricRepository;
import net.java21.data2flow.core.catalog.service.DeviceModelService;
import net.java21.data2flow.core.catalog.service.MetricService;
import net.java21.data2flow.core.control.service.CapabilityService;
import net.java21.data2flow.core.modelexchange.domain.Dtdl;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Capability;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.MetricDef;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Script;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Unmapped;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ImportResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ImportedScript;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 모델 정의 가져오기·내보내기(DEV-03.04, API-DEV-44·45). {@code format=data2flow}는 측정 항목 정의·기능·속성 스키마·스크립트 코드까지
 * 담고(다른 조직에서 가져오면 같은 정의, 스크립트는 DRAFT — AT-DEV-09.4), {@code format=dtdl}은 DTDL v3 Interface로 주고받는다
 * (Telemetry → 측정 항목, Command → 기능, AT-DEV-09.5). 가져오기는 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않는다.
 */
@Service
public class ModelExchangeService {

    static final String AUDIT_IMPORTED = "DEVICE_MODEL_IMPORTED";
    static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;

    private final RoleChecker roleChecker;
    private final DeviceModelRepository models;
    private final MetricRepository metrics;
    private final DeviceModelService modelService;
    private final MetricService metricService;
    private final CapabilityService capabilities;
    private final ModelExchangeRepository repository;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ModelExchangeService(RoleChecker roleChecker, DeviceModelRepository models, MetricRepository metrics, DeviceModelService modelService,
                                MetricService metricService, CapabilityService capabilities, ModelExchangeRepository repository, Audits audits,
                                JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.models = models;
        this.metrics = metrics;
        this.modelService = modelService;
        this.metricService = metricService;
        this.capabilities = capabilities;
        this.repository = repository;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-44 — DEV_READ. data2flow면 문서, dtdl이면 DTDL v3 Interface 자체 */
    @Transactional(readOnly = true)
    public JsonNode export(long modelId, String format) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        String f = format == null || format.isBlank() ? "data2flow" : format.strip().toLowerCase(Locale.ROOT);
        if (!f.equals("data2flow") && !f.equals("dtdl")) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("format", "Invalid", "data2flow|dtdl")));
        }
        DeviceModel m = models.find(org, modelId).orElseThrow(() -> new BusinessException(CatalogErrorCode.MODEL_NOT_FOUND));
        ModelDocument doc = document(org, m, f.equals("data2flow"));
        return f.equals("dtdl") ? Dtdl.toInterface(json, doc) : json.valueToTree(doc);
    }

    /** 모델 → 문서(스크립트 포함 여부) */
    public ModelDocument document(long org, DeviceModel m, boolean withScripts) {
        List<MetricDef> defs = new ArrayList<>();
        for (ModelMetric mm : models.listMetrics(org, m.id())) {
            Optional<Metric> metric = metrics.findByKey(org, mm.key());
            defs.add(metric.map(x -> new MetricDef(x.key(), x.displayName(), x.unit(), x.valueType(), x.validMin(), x.validMax(), x.precision(),
                            x.aggDefault(), x.stateType() ? Boolean.TRUE : null, mm.required()))
                    .orElse(new MetricDef(mm.key(), mm.key(), null, "NUMBER", null, null, null, "AVG", null, mm.required())));
        }
        List<Capability> caps = new ArrayList<>();
        if (m.capabilitiesJson() != null) {
            for (JsonNode c : json.readTree(m.capabilitiesJson())) {
                JsonNode constraints = c.get("constraints");
                caps.add(new Capability(c.path("capability").asString(""), constraints == null || constraints.isNull() ? null : constraints));
            }
        }
        List<Script> scripts = new ArrayList<>();
        if (withScripts) {
            for (Long scriptId : java.util.Arrays.asList(m.decodeScriptId(), m.transformScriptId())) {
                if (scriptId != null) {
                    repository.findScriptCode(org, scriptId).ifPresent(s -> scripts.add(new Script(s.kind(), s.name(), s.code())));
                }
            }
        }
        JsonNode schema = m.attributeSchemaJson() == null ? null : json.readTree(m.attributeSchemaJson());
        return new ModelDocument(ModelDocument.FORMAT_VERSION, withScripts ? "data2flow" : "dtdl",
                new ModelDocument.Model(m.code(), m.vendor(), m.name(), m.protocol(), m.kind(), m.defaultIntervalSec(), m.defaultOfflineMultiplier(),
                        m.description()), defs, caps, schema, scripts);
    }

    /**
     * API-DEV-45 — DEV_ADMIN. format이 없으면 내용으로 고른다(@context가 있으면 dtdl). 없는 측정 항목은 createMissingMetrics(기본 true)면
     * VERIFIED로 만들고, 아니면 옮기지 못한 항목으로 보고한다. 같은 코드가 있으면 409 MODEL_CODE_DUPLICATE. 스크립트는 SCRIPT_WRITE가
     * 있을 때만 v1 DRAFT로 만든다(이름이 겹치면 뒤에 번호).
     */
    @Transactional
    public ImportResponse importModel(byte[] content, String format, Boolean createMissingMetrics, boolean dryRun) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        if (content == null || content.length == 0 || content.length > MAX_IMPORT_BYTES) {
            throw invalid("file", "Size");
        }
        JsonNode root;
        try {
            root = json.readTree(new String(content, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw invalid("file", "JSON");
        }
        String f = format == null || format.isBlank() ? (root.has("@context") ? "dtdl" : "data2flow") : format.strip().toLowerCase(Locale.ROOT);
        List<Unmapped> unmapped = new ArrayList<>();
        ModelDocument doc;
        if (f.equals("dtdl")) {
            List<String> problems = Dtdl.validate(root);
            if (!problems.isEmpty()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                        problems.stream().limit(20).map(p -> new FieldErrorDetail("file", "DTDL", p)).toList());
            }
            CapabilityCatalog catalog = capabilities.catalog(org);
            Dtdl.Imported imported = Dtdl.fromInterface(json, root, name -> capabilityOf(catalog, name));
            doc = imported.document();
            unmapped.addAll(imported.unmapped());
        } else if (f.equals("data2flow")) {
            try {
                doc = json.treeToValue(root, ModelDocument.class);
            } catch (RuntimeException ex) {
                throw invalid("file", "FORMAT");
            }
            if (doc.model() == null || doc.model().code() == null) {
                throw invalid("model.code", "NotBlank");
            }
        } else {
            throw invalid("format", "Invalid");
        }
        String code = doc.model().code().strip();
        if (models.existsCode(org, code)) {
            throw new BusinessException(CatalogErrorCode.MODEL_CODE_DUPLICATE);
        }
        boolean create = createMissingMetrics == null || createMissingMetrics;
        List<String> createdMetrics = new ArrayList<>();
        List<ModelMetricDto> modelMetrics = new ArrayList<>();
        int i = 0;
        for (MetricDef def : doc.metrics()) {
            String path = "metrics[" + i++ + "]";
            if (def.key() == null || !def.key().matches(CatalogModels.METRIC_KEY_PATTERN)) {
                unmapped.add(new Unmapped(path, "Metric", "측정 항목 키 형식이 아닙니다: " + def.key()));
                continue;
            }
            Optional<Metric> existing = metrics.findByKey(org, def.key());
            if (existing.isPresent() && !CatalogModels.VERIFIED.equals(existing.get().status())) {
                unmapped.add(new Unmapped(path, "Metric", "검증되지 않은 측정 항목: " + def.key()));
                continue;
            }
            if (existing.isEmpty()) {
                if (!create) {
                    unmapped.add(new Unmapped(path, "Metric", "없는 측정 항목: " + def.key()));
                    continue;
                }
                createdMetrics.add(def.key());
                if (!dryRun) {
                    metricService.create(new CreateMetricRequest(def.key(), truncate(def.displayName() == null ? def.key() : def.displayName(), 50),
                            def.unit() == null ? null : truncate(def.unit(), 16), def.valueType(), null, def.validMin(), def.validMax(),
                            def.precision(), def.aggDefault(), def.stateType(), null));
                }
            }
            modelMetrics.add(new ModelMetricDto(def.key(), def.required()));
        }
        List<CapabilityDto> caps = doc.capabilities().stream().map(c -> new CapabilityDto(c.capability(), c.constraints())).toList();
        List<ImportedScript> scripts = new ArrayList<>();
        if (dryRun) {
            doc.scripts().forEach(s -> scripts.add(new ImportedScript(null, s.name(), s.kind(), "DRAFT")));
            return new ImportResponse(true, f, null, createdMetrics, unmapped, scripts);
        }
        ModelDocument.Model m = doc.model();
        ModelResponse created = modelService.create(new CreateModelRequest(code, blankOr(m.vendor(), "unknown"), blankOr(m.name(), code),
                blankOr(m.protocol(), "OTHER"), blankOr(m.kind(), "SENSOR"), m.defaultIntervalSec(), m.defaultOfflineMultiplier(),
                m.description() == null ? null : truncate(m.description(), 500), modelMetrics, caps));
        if (doc.attributeSchema() != null && !doc.attributeSchema().isNull()) {
            modelService.updatePackage(Long.parseLong(created.id()), new PackageRequest(null, null, null, null, null, doc.attributeSchema(), null));
        }
        if (!doc.scripts().isEmpty()) {
            if (roleChecker.has(Permission.SCRIPT_WRITE)) {
                for (Script s : doc.scripts()) {
                    String kind = s.kind() == null ? "" : s.kind().toUpperCase(Locale.ROOT);
                    if (!kind.equals("DECODE") && !kind.equals("TRANSFORM") || s.code() == null) {
                        unmapped.add(new Unmapped("scripts", "Script", "지원하지 않는 스크립트: " + s.name()));
                        continue;
                    }
                    String name = uniqueScriptName(org, blankOr(s.name(), code + " " + kind.toLowerCase(Locale.ROOT)));
                    long id = repository.insertDraftScript(org, name, kind, s.code(), sha256(s.code()), user.userId(), clock.instant());
                    scripts.add(new ImportedScript(Long.toString(id), name, kind, "DRAFT"));
                }
            } else {
                doc.scripts().forEach(s -> unmapped.add(new Unmapped("scripts", "Script", "스크립트 작성 권한이 없어 건너뜀: " + s.name())));
            }
        }
        audits.record(audits.event(org, AUDIT_IMPORTED).actor(user).target("DEVICE_MODEL", created.id()).detail("format", f)
                .detail("createdMetrics", createdMetrics).detail("unmapped", unmapped.size()));
        return new ImportResponse(false, f, modelService.get(Long.parseLong(created.id())), createdMetrics, unmapped, scripts);
    }

    /** DTDL 명령 이름 → 카탈로그 기능: 기능 이름(대소문자 무시) 또는 그 기능의 명령 이름 */
    static String capabilityOf(CapabilityCatalog catalog, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (CapabilityDefinition d : catalog.all()) {
            if (d.name().equalsIgnoreCase(name) || d.name().replace('.', '_').equalsIgnoreCase(name)) {
                return d.name();
            }
        }
        for (CapabilityDefinition d : catalog.all()) {
            if (d.commands().stream().anyMatch(c -> c.name().equalsIgnoreCase(name))) {
                return d.name();
            }
        }
        return null;
    }

    private String uniqueScriptName(long org, String base) {
        String name = truncate(base, 75);
        int n = 2;
        while (repository.existsScriptName(org, name)) {
            name = truncate(base, 72) + " " + n++;
        }
        return name.length() < 2 ? name + "_" : name;
    }

    private static String sha256(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankOr(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
