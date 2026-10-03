package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.DeviceChanged.Change;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.space.service.SemanticService;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.DeviceRules;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.domain.References.SpaceRef;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveItem;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.ApproveResult;
import net.java21.data2flow.core.device.dto.DeviceDtos.ModelSuggestion;
import net.java21.data2flow.core.device.dto.DeviceDtos.RejectRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.RejectResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.RejectResult;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.repository.DiscoveryRepository;
import net.java21.data2flow.core.devicecredential.service.DeviceCredentialService;
import net.java21.data2flow.core.devicegroup.service.DynamicGroupMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 승인 대기 기기의 승인·거부·모델 추천(DEV-02.03, UC-DEV-03, ADR-031). 승인 전 데이터는 기기 ID로 이미 저장돼 있어 승인 즉시 보인다.
 * 플랫폼 브로커 기기는 승인할 때 서명 키를 한 번 발급한다(DSC-03.05). 모델 패키지(기본 규칙·대시보드, BR-DEV-09)는 규칙·대시보드가
 * 생기는 M4·M5에서 적용한다({@code applyModelPackage}는 받기만 한다).
 */
@Service
public class DeviceApprovalService {

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final DeviceRepository devices;
    private final DeviceReferenceRepository refs;
    private final DiscoveryRepository discovery;
    private final DeviceViews views;
    private final DeviceEvents events;
    private final DynamicGroupMembership groups;
    private final DeviceCredentialService credentials;
    private final Audits audits;
    private final SemanticService semantic;
    private final Clock clock;

    public DeviceApprovalService(RoleChecker roleChecker, DeviceAccess access, DeviceRepository devices, DeviceReferenceRepository refs,
                                 DiscoveryRepository discovery, DeviceViews views, DeviceEvents events, DynamicGroupMembership groups,
                                 DeviceCredentialService credentials, Audits audits, SemanticService semantic, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.devices = devices;
        this.refs = refs;
        this.discovery = discovery;
        this.views = views;
        this.events = events;
        this.groups = groups;
        this.credentials = credentials;
        this.audits = audits;
        this.semantic = semantic;
        this.clock = clock;
    }

    /**
     * API-DEV-15 일괄 승인(≤200). 모델·공간 필수(400 DEVICE_MODEL_REQUIRED·DEVICE_SPACE_REQUIRED), 항목별 결과: 이미 승인됐거나
     * 버전이 다르면 DEVICE_STATE_CONFLICT(AT-DEV-03.6). 감사는 기기마다 1건(AT-DEV-03.2).
     */
    @Transactional
    public ApproveResponse approve(ApproveRequest req) {
        roleChecker.require(Permission.DEV_PLACE);
        if (req.modelId() == null || req.modelId().isBlank()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_MODEL_REQUIRED);
        }
        if (req.spaceId() == null || req.spaceId().isBlank()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_SPACE_REQUIRED);
        }
        ModelRef model = access.assignableModel(DeviceAccess.id(req.modelId(), "modelId"), "modelId");
        SpaceRef space = access.space(DeviceAccess.id(req.spaceId(), "spaceId"));
        roleChecker.require(Permission.DEV_PLACE, space.id(), DeviceErrorCode.SPACE_NOT_FOUND);
        List<String> tags = DeviceRules.cleanTags(req.tags(), "tags");
        String name = req.items().size() == 1 && req.name() != null && !req.name().isBlank() ? req.name().strip() : null;
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        List<ApproveResult> results = new ArrayList<>();
        for (ApproveItem item : req.items()) {
            long id = DeviceAccess.id(item.deviceId(), "items.deviceId");
            Device device = access.find(id).orElse(null);
            if (device == null) {
                results.add(new ApproveResult(item.deviceId(), false, DeviceErrorCode.DEVICE_NOT_FOUND.code(), null));
                continue;
            }
            if (!DeviceRules.PENDING.equals(device.status()) || device.version() != item.baseVersion()) {
                results.add(new ApproveResult(item.deviceId(), false, DeviceErrorCode.DEVICE_STATE_CONFLICT.code(), null));
                continue;
            }
            List<String> mergedTags = DeviceRules.mergeTags(devices.findTags(org, id), tags, null);
            if (mergedTags == null) {
                results.add(new ApproveResult(item.deviceId(), false, DeviceErrorCode.DEVICE_TAG_LIMIT.code(), null));
                continue;
            }
            // 자동 등록 때 정한 종류는 추정값이라 모델 종류를 따른다. 수동 등록 기기는 지정한 종류를 둔다(BR-DEV-05 경고만)
            String kind = device.autoRegistered() ? model.kind() : device.kind();
            int updated = devices.approve(org, id, item.baseVersion(), model.id(), space.id(), kind, name == null ? device.name() : name,
                    user.userId(), now);
            if (updated == 0) {
                results.add(new ApproveResult(item.deviceId(), false, DeviceErrorCode.DEVICE_STATE_CONFLICT.code(), null));
                continue;
            }
            devices.replaceTags(org, id, mergedTags, now);
            SourceRef source = refs.findSource(org, device.sourceId()).orElse(null);
            String signingKey = source != null && source.platformBroker() ? credentials.issueOnApproval(device, user.userId()) : null;
            // BR-DEV-32: 승인할 때 모델의 시맨틱 템플릿으로 장비·점을 만든다(DEV-13.01)
            semantic.applyModelTemplate(org, id);
            Device after = devices.findById(org, id).orElseThrow();
            events.changed(after, Change.APPROVED, List.of("status", "modelId", "spaceId"));
            audits.record(audits.event(org, DeviceAudits.DEVICE_APPROVED).actor(user).target("DEVICE", Long.toString(id))
                    .detail("modelId", model.id()).detail("spaceId", space.id()).detail("signingKeyIssued", signingKey != null));
            results.add(new ApproveResult(item.deviceId(), true, null, signingKey));
        }
        return new ApproveResponse(results);
    }

    /**
     * API-DEV-28 거부: PENDING → DELETED. 기본으로 소스 무시 목록에 넣어 같은 외부 ID가 다시 자동 등록되지 않게 한다(BR-DEV-07).
     * 이미 받은 데이터는 그대로 둔다.
     */
    @Transactional
    public RejectResponse reject(RejectRequest req) {
        roleChecker.require(Permission.DEV_PLACE);
        boolean ignore = req.addToIgnoreList() == null || req.addToIgnoreList();
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        List<RejectResult> results = new ArrayList<>();
        for (String raw : req.deviceIds()) {
            long id = DeviceAccess.id(raw, "deviceIds");
            Device device = access.find(id).orElse(null);
            if (device == null) {
                results.add(new RejectResult(raw, false, DeviceErrorCode.DEVICE_NOT_FOUND.code(), false));
                continue;
            }
            if (!DeviceRules.PENDING.equals(device.status()) || devices.reject(org, id, user.userId(), now) == 0) {
                results.add(new RejectResult(raw, false, DeviceErrorCode.DEVICE_STATE_CONFLICT.code(), false));
                continue;
            }
            if (ignore) {
                discovery.insertIgnoreEntry(org, device.sourceId(), device.externalId(), "REJECTED", user.userId(), now);
            }
            groups.removeDevice(org, id);
            devices.updateCredentialsRevoked(org, id, now);
            events.changed(devices.findById(org, id).orElseThrow(), Change.DELETED, List.of("status"));
            audits.record(audits.event(org, DeviceAudits.DEVICE_REJECTED).actor(user).target("DEVICE", Long.toString(id))
                    .detail("externalId", device.externalId()).detail("ignored", ignore));
            results.add(new RejectResult(raw, true, null, ignore));
        }
        return new RejectResponse(results);
    }

    /**
     * API-DEV-29 모델 추천(최대 3개): 기기가 보낸 측정 항목 키와 모델 측정 항목의 겹침(자카드 지수). 겹치는 키가 없으면 빼고,
     * 점수가 같으면 겹친 키가 많은 순, 모델 ID 순.
     */
    @Transactional(readOnly = true)
    public List<ModelSuggestion> modelSuggestions(long deviceId) {
        roleChecker.require(Permission.DEV_PLACE);
        Device device = access.device(deviceId, Permission.DEV_PLACE);
        long org = device.organizationId();
        Set<String> keys = new HashSet<>(views.metricKeys(device));
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> modelMetrics = refs.findActiveModelMetrics(org);
        List<Object[]> scored = new ArrayList<>();
        for (Map.Entry<Long, List<String>> e : modelMetrics.entrySet()) {
            List<String> matched = e.getValue().stream().filter(keys::contains).sorted().toList();
            if (matched.isEmpty()) {
                continue;
            }
            Set<String> union = new HashSet<>(keys);
            union.addAll(e.getValue());
            double score = BigDecimal.valueOf((double) matched.size() / union.size()).setScale(2, RoundingMode.HALF_UP).doubleValue();
            scored.add(new Object[] {e.getKey(), matched, score});
        }
        scored.sort(Comparator.<Object[]>comparingDouble(o -> -(double) o[2])
                .thenComparing(o -> -((List<?>) o[1]).size())
                .thenComparingLong(o -> (Long) o[0]));
        List<Object[]> top = scored.subList(0, Math.min(3, scored.size()));
        Map<Long, ModelRef> models = refs.findModels(org, top.stream().map(o -> (Long) o[0]).toList());
        List<ModelSuggestion> result = new ArrayList<>();
        for (Object[] o : top) {
            ModelRef m = models.get((Long) o[0]);
            @SuppressWarnings("unchecked")
            List<String> matched = (List<String>) o[1];
            result.add(new ModelSuggestion(Long.toString(m.id()), m.code(), m.name(), matched, (double) o[2]));
        }
        return result;
    }
}
