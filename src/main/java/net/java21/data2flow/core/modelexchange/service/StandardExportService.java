package net.java21.data2flow.core.modelexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceExportCompleted;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.catalog.domain.CatalogModels.DeviceModel;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.modelexchange.domain.Dtdl;
import net.java21.data2flow.core.modelexchange.domain.StandardFormats;
import net.java21.data2flow.core.modelexchange.domain.StandardFormats.Output;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ExportJobCreated;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.ExportJobResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.NgsiPushRequest;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.NgsiPushResponse;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.Scope;
import net.java21.data2flow.core.modelexchange.dto.ModelExchangeDtos.StandardExportRequest;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.ExportDevice;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.Job;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.Push;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
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
 * 표준 형식 내보내기(DEV-13.04, API-DEV-135·136, BR-DEV-36, EVT-DEV-13). v1은 요청 안에서 파일을 만들고(조직 기기 1만 대 이하에서 수 초)
 * 작업을 DONE으로 남긴 뒤 202 {jobId}를 준다 — 화면은 작업 조회(API-DEV-135 GET)와 EVT-DEV-13으로 같은 흐름을 쓴다. 검증을 통과한 항목이
 * 하나도 없으면 400 EXPORT_INVALID_REQUEST. NGSI-LD 주기 전송(API-DEV-136)은 설정만 저장한다(전송은 출력 연결 DSC-04.01의 action과 함께
 * — 미구현, 보고서 참고).
 */
@Service
public class StandardExportService {

    static final String AUDIT_EXPORTED = "DEVICE_STANDARD_EXPORTED";
    static final String AUDIT_PUSH = "NGSI_PUSH_CHANGED";
    static final Set<String> FORMATS = Set.of("DTDL", "NGSI_LD", "BRICK_TTL", "BRICK_JSONLD");

    private final RoleChecker roleChecker;
    private final ModelExchangeRepository repository;
    private final DeviceModelRepository models;
    private final ModelExchangeService modelExchange;
    private final StoredFiles files;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public StandardExportService(RoleChecker roleChecker, ModelExchangeRepository repository, DeviceModelRepository models,
                                 ModelExchangeService modelExchange, StoredFiles files, CoreEventPublisher publisher, Audits audits,
                                 JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.models = models;
        this.modelExchange = modelExchange;
        this.files = files;
        this.publisher = publisher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-135 — DEV_ADMIN, 202 */
    @Transactional(noRollbackFor = BusinessException.class)
    public ExportJobCreated export(StandardExportRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        String format = req.format().strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!FORMATS.contains(format)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("format", "Invalid", String.join("|", FORMATS))));
        }
        List<Long> spaceIds = ids(req.scope() == null ? null : req.scope().spaceIds(), "scope.spaceIds");
        List<Long> deviceIds = ids(req.scope() == null ? null : req.scope().deviceIds(), "scope.deviceIds");
        for (long s : spaceIds) {
            roleChecker.require(Permission.DEV_ADMIN, s, CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        boolean values = Boolean.TRUE.equals(req.includeValues());
        Instant now = clock.instant();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("spaceIds", spaceIds.stream().map(String::valueOf).toList());
        scope.put("deviceIds", deviceIds.stream().map(String::valueOf).toList());
        long jobId = repository.insertJob(org, format, json.writeValueAsString(scope), values, user.userId(), now);
        SpaceScope userScope = roleChecker.spaceScope();
        List<ExportDevice> devices = repository.findDevices(org, spaceIds, deviceIds, userScope.unrestricted() ? null : userScope.allowedSpaceIds());
        Output out = switch (format) {
            case "NGSI_LD" -> StandardFormats.ngsiLd(json, org, devices, values, now);
            case "BRICK_TTL", "BRICK_JSONLD" -> StandardFormats.brick(json, org,
                    repository.findSpacesWithAncestors(org, devices.stream().map(ExportDevice::spaceId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))),
                    devices, repository.findPoints(org, devices.stream().map(ExportDevice::id).toList()), format.equals("BRICK_TTL"));
            default -> dtdl(org, devices);
        };
        Map<String, Object> report = new LinkedHashMap<>(out.summary());
        report.put("exported", out.exported());
        report.put("skipped", out.skipped());
        String reportJson = json.writeValueAsString(report);
        if (out.exported() == 0) {
            repository.finishJob(org, jobId, "FAILED", null, reportJson, clock.instant());
            publisher.event(EventType.DEVICE_EXPORT_COMPLETED, org, new DeviceExportCompleted(Long.toString(jobId), format, "FAILED", null));
            throw new BusinessException(WorkOrderErrorCode.EXPORT_INVALID_REQUEST);
        }
        String fileName = "data2flow-" + format.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + jobId + "." + out.extension();
        String key = files.storeGenerated(org, "DEVICE_EXPORT", fileName, out.contentType(), out.content(), user.userId()).key();
        repository.finishJob(org, jobId, "DONE", key, reportJson, clock.instant());
        publisher.event(EventType.DEVICE_EXPORT_COMPLETED, org, new DeviceExportCompleted(Long.toString(jobId), format, "COMPLETED", key));
        audits.record(audits.event(org, AUDIT_EXPORTED).actor(user).target("DEVICE_EXPORT_JOB", Long.toString(jobId)).detail("format", format)
                .detail("devices", devices.size()).detail("exported", out.exported()));
        return new ExportJobCreated(Long.toString(jobId), "DONE");
    }

    /** API-DEV-135 작업 상태 — DEV_ADMIN */
    @Transactional(readOnly = true)
    public ExportJobResponse job(long jobId) {
        roleChecker.require(Permission.DEV_ADMIN);
        long org = roleChecker.currentUser().organizationId();
        Job j = repository.findJob(org, jobId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new ExportJobResponse(Long.toString(j.id()), j.format(), j.status(),
                j.fileRef() == null ? null : "/api/v1/core/export-jobs/" + j.id() + "/file",
                j.reportJson() == null ? null : json.readTree(j.reportJson()), j.createdAt(), j.updatedAt());
    }

    @Transactional(readOnly = true)
    public Blob file(long jobId) {
        roleChecker.require(Permission.DEV_ADMIN);
        long org = roleChecker.currentUser().organizationId();
        Job j = repository.findJob(org, jobId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return files.load(org, j.fileRef()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** DTDL v3: 공간 Interface + 범위 기기가 쓰는 모델마다 Interface(검증 실패 모델은 빠지고 보고) */
    private Output dtdl(long org, List<ExportDevice> devices) {
        ArrayNode doc = json.createArrayNode();
        List<Map<String, Object>> skipped = new ArrayList<>();
        ObjectNode space = doc.addObject();
        ArrayNode ctx = space.putArray("@context");
        ctx.add(Dtdl.CONTEXT);
        space.put("@id", "dtmi:data2flow:space;1");
        space.put("@type", "Interface");
        space.put("displayName", "Space");
        ArrayNode contents = space.putArray("contents");
        contents.addObject().put("@type", "Property").put("name", "spaceType").put("schema", "string");
        contents.addObject().put("@type", "Property").put("name", "code").put("schema", "string");
        contents.addObject().put("@type", "Relationship").put("name", "contains").put("target", "dtmi:data2flow:space;1");
        contents.addObject().put("@type", "Relationship").put("name", "hosts");
        Set<Long> modelIds = new LinkedHashSet<>();
        devices.forEach(d -> {
            if (d.modelId() != null) {
                modelIds.add(d.modelId());
            }
        });
        int models = 0;
        for (long id : modelIds) {
            DeviceModel m = this.models.find(org, id).orElse(null);
            if (m == null) {
                continue;
            }
            JsonNode iface = Dtdl.toInterface(json, modelExchange.document(org, m, false));
            List<String> problems = Dtdl.validate(iface);
            if (problems.isEmpty()) {
                doc.add(iface);
                models++;
            } else {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("modelId", Long.toString(id));
                s.put("type", "Interface");
                s.put("reason", String.join("; ", problems));
                skipped.add(s);
            }
        }
        devices.stream().filter(d -> d.modelId() == null).forEach(d -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("deviceId", Long.toString(d.id()));
            s.put("type", "Device");
            s.put("reason", "모델이 없습니다");
            skipped.add(s);
        });
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("interfaces", 1 + models);
        summary.put("devices", devices.size());
        return new Output(json.writeValueAsString(doc).getBytes(StandardCharsets.UTF_8), "application/json", "json", models == 0 ? 0 : 1 + models,
                skipped, summary);
    }

    // ---- API-DEV-136

    @Transactional(readOnly = true)
    public List<NgsiPushResponse> pushes() {
        roleChecker.require(Permission.DEV_ADMIN);
        return repository.listPushes(roleChecker.currentUser().organizationId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public NgsiPushResponse createPush(NgsiPushRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long output = ids(List.of(req.outputConnectionId()), "outputConnectionId").getFirst();
        if (req.intervalSec() < 60 || req.intervalSec() > 86400) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("intervalSec", "Range", "60~86400")));
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("spaceIds", ids(req.scope() == null ? null : req.scope().spaceIds(), "scope.spaceIds").stream().map(String::valueOf).toList());
        scope.put("deviceIds", ids(req.scope() == null ? null : req.scope().deviceIds(), "scope.deviceIds").stream().map(String::valueOf).toList());
        long id = repository.insertPush(user.organizationId(), output, json.writeValueAsString(scope), req.intervalSec(), user.userId(),
                clock.instant());
        audits.record(audits.event(user.organizationId(), AUDIT_PUSH).actor(user).target("NGSI_PUSH", Long.toString(id)).detail("op", "CREATE"));
        return toResponse(repository.findPush(user.organizationId(), id).orElseThrow());
    }

    @Transactional
    public void deletePush(long id) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        if (repository.deletePush(user.organizationId(), id) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        audits.record(audits.event(user.organizationId(), AUDIT_PUSH).actor(user).target("NGSI_PUSH", Long.toString(id)).detail("op", "DELETE"));
    }

    private NgsiPushResponse toResponse(Push p) {
        JsonNode scope = json.readTree(p.scopeJson());
        List<String> spaces = new ArrayList<>();
        List<String> devices = new ArrayList<>();
        scope.path("spaceIds").forEach(x -> spaces.add(x.asString("")));
        scope.path("deviceIds").forEach(x -> devices.add(x.asString("")));
        return new NgsiPushResponse(Long.toString(p.id()), Long.toString(p.outputConnectionId()), new Scope(spaces, devices), p.intervalSec(),
                p.enabled(), p.lastSentAt(), p.lastError());
    }

    private static List<Long> ids(List<String> raw, String field) {
        List<Long> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String r : raw) {
            if (r == null || !r.strip().matches("\\d{1,18}")) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
            }
            out.add(Long.valueOf(r.strip()));
        }
        return out;
    }
}
