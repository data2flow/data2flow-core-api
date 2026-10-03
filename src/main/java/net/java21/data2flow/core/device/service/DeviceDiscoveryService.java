package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.DeviceRules;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.AutoRegisterRequest;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.AutoRegisterResponse;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.DeviceLookupResponse;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.QuotaResetRequest;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.QuotaResponse;
import net.java21.data2flow.core.device.repository.InternalDeviceRepository;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository.NewDevice;
import net.java21.data2flow.core.device.repository.DiscoveryRepository;
import net.java21.data2flow.core.device.repository.DiscoveryRepository.Quota;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 수신 데이터로 기기 발견(ADR-031, ING-03.01~03, ING-07.02, DSC-01.07, DSC-03.05).
 * <ul>
 *   <li>API-DEV-121 자동 등록: (소스, 외부 ID)마다 멱등. 거부 정책·무시 목록이면 409 DEVICE_REJECTED, 시간당 한도를 넘으면 429
 *       DEVICE_AUTOREG_LIMIT(관리자가 풀 때까지 차단 유지, BR-ING-09). 소스 기본 모델·공간을 채우고(DSC-01.07), 원본 tags로 공간을
 *       추천하고(ING-03.03), EVT-DEV-03을 낸다. 같은 소스의 등록은 한도 행 잠금으로 한 줄로 서서 동시 요청에도 기기 1대·정확한 한도.</li>
 *   <li>API-DEV-120 식별: 기기 정보 + 무시 목록 여부</li>
 *   <li>API-ING-16 한도 해제(ADMIN)</li>
 * </ul>
 * 운영 알람 {@code ING_AUTO_REGISTER_QUOTA}(EVT-ING-05)는 contracts {@code IngestAlert}의 생산자인 pipeline이 429를 받고 낸다.
 */
@Service
public class DeviceDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DeviceDiscoveryService.class);
    /** source_meta 8KB 이하(domain-model §2.5) */
    static final int MAX_META_BYTES = 8 * 1024;

    private final DeviceRepository devices;
    private final DeviceReferenceRepository refs;
    private final DiscoveryRepository discovery;
    private final DeviceEvents events;
    private final SpaceSuggestion suggestion;
    private final DeploymentOrganization deployment;
    private final RoleChecker roleChecker;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final Audits audits;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    private final InternalDeviceRepository internalDevices;

    public DeviceDiscoveryService(DeviceRepository devices, DeviceReferenceRepository refs, DiscoveryRepository discovery,
                                  DeviceEvents events, SpaceSuggestion suggestion, DeploymentOrganization deployment,
                                  RoleChecker roleChecker, CoreEventPublisher publisher, ConfigVersions configVersions, Audits audits,
                                  JsonMapper json, PlatformTransactionManager txManager, Clock clock,
                                  InternalDeviceRepository internalDevices) {
        this.internalDevices = internalDevices;
        this.devices = devices;
        this.refs = refs;
        this.discovery = discovery;
        this.events = events;
        this.suggestion = suggestion;
        this.deployment = deployment;
        this.roleChecker = roleChecker;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.audits = audits;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    private enum Outcome { CREATED, EXISTING, REJECTED, LIMIT }

    private record Result(Outcome outcome, Device device) {
    }

    /**
     * API-DEV-121. 한도 초과·거부도 한도 행(거부 수·차단 시각)은 커밋해야 하므로 트랜잭션을 끝낸 뒤에 오류를 던진다.
     */
    public AutoRegisterResponse autoRegister(AutoRegisterRequest req) {
        SourceRef source = refs.findSourceAnyOrganization(req.sourceId(), deployment.restriction())
                .filter(s -> s.organizationId() == req.organizationId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.SOURCE_NOT_FOUND));
        String ext = DeviceRules.normalizeExternalId(req.externalId());
        if (ext.isEmpty() || ext.length() > DeviceRules.MAX_EXTERNAL_ID) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("externalId", "INVALID", null)));
        }
        Result result = tx.execute(status -> register(source, ext, req));
        return switch (result.outcome()) {
            case REJECTED -> throw new BusinessException(DeviceErrorCode.DEVICE_REJECTED);
            // 관리자가 풀 때까지 차단이므로 Retry-After를 주지 않는다(BR-ING-09)
            case LIMIT -> throw new BusinessException(DeviceErrorCode.DEVICE_AUTOREG_LIMIT);
            case CREATED, EXISTING -> new AutoRegisterResponse(Long.toString(result.device().id()), result.device().status(),
                    result.outcome() == Outcome.CREATED);
        };
    }

    private Result register(SourceRef source, String ext, AutoRegisterRequest req) {
        long org = source.organizationId();
        Instant now = clock.instant();
        Instant window = hourStart(now);
        Quota quota = discovery.lockQuota(org, source.id(), window);
        Optional<Device> existing = devices.findBySourceAndExternalId(org, source.id(), ext);
        if (existing.isPresent() && !existing.get().deleted()) {
            Device device = existing.get();
            if (DeviceRules.PENDING.equals(device.status())) {
                ObjectNode meta = meta(req.sourceMeta(), req.metrics());
                devices.updatePendingMeta(org, device.id(), json.writeValueAsString(meta), suggestion.suggest(org, meta));
            }
            return new Result(Outcome.EXISTING, device);
        }
        if (discovery.existsIgnoreEntry(org, source.id(), ext) || "REJECT".equals(source.unknownDevicePolicy())) {
            return new Result(Outcome.REJECTED, null);
        }
        if (quota.windowStartedAt().isBefore(window)) {
            quota = new Quota(window, 0, quota.rejectedCount(), quota.blockedAt(), quota.releasedAt());
        }
        if (quota.blockedAt() != null || quota.usedCount() >= source.autoregLimitPerHour()) {
            Instant blockedAt = quota.blockedAt() == null ? now : quota.blockedAt();
            if (quota.blockedAt() == null) {
                log.warn("소스 {} 자동 등록 한도 {}대/시간 초과 — 관리자가 풀 때까지 차단(BR-ING-09)", source.id(), source.autoregLimitPerHour());
            }
            discovery.updateQuota(org, source.id(), new Quota(quota.windowStartedAt(), quota.usedCount(), quota.rejectedCount() + 1,
                    blockedAt, quota.releasedAt()), now);
            return new Result(Outcome.LIMIT, null);
        }
        ObjectNode meta = meta(req.sourceMeta(), req.metrics());
        ModelRef model = source.defaultModelId() == null ? null
                : refs.findModel(org, source.defaultModelId()).filter(m -> "ACTIVE".equals(m.status())).orElse(null);
        Long spaceId = source.defaultSpaceId() == null ? null : refs.findSpace(org, source.defaultSpaceId()).map(s -> s.id()).orElse(null);
        NewDevice row = new NewDevice(org, source.id(), ext, name(req, meta, ext), model == null ? "SENSOR" : model.kind(),
                model == null ? null : model.id(), spaceId, suggestion.suggest(org, meta), DeviceRules.PENDING, false, null, null, null,
                null, true, req.firstSeenAt() == null ? now : req.firstSeenAt(), json.writeValueAsString(meta), null, now);
        long id;
        if (existing.isPresent()) {
            devices.revive(existing.get().id(), row);
            id = existing.get().id();
        } else {
            Optional<Long> inserted = devices.insert(row);
            if (inserted.isEmpty()) {
                // 잠금 밖의 경로(수동 등록 등)와 겹친 경우: 이미 생긴 기기를 돌려준다(UC-ING-03 2d-1)
                return new Result(Outcome.EXISTING, devices.findBySourceAndExternalId(org, source.id(), ext).orElseThrow());
            }
            id = inserted.get();
        }
        discovery.updateQuota(org, source.id(), new Quota(quota.windowStartedAt(), quota.usedCount() + 1, quota.rejectedCount(),
                null, quota.releasedAt()), now);
        Device device = devices.findById(org, id).orElseThrow();
        events.pendingCreated(device);
        return new Result(Outcome.CREATED, device);
    }

    /** API-DEV-120 (소스, 외부 ID) 식별. 기기도 무시 목록도 없으면 404 DEVICE_NOT_FOUND */
    @Transactional(readOnly = true)
    public DeviceLookupResponse lookup(long sourceId, String externalId) {
        SourceRef source = refs.findSourceAnyOrganization(sourceId, deployment.restriction())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        long org = source.organizationId();
        String ext = DeviceRules.normalizeExternalId(externalId);
        boolean ignored = discovery.existsIgnoreEntry(org, sourceId, ext);
        Device device = devices.findBySourceAndExternalId(org, sourceId, ext).filter(d -> !d.deleted()).orElse(null);
        if (device == null) {
            if (!ignored) {
                throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
            }
            return new DeviceLookupResponse(null, null, Long.toString(org), null, null, false, true, null, null, null, null, null);
        }
        var x = internalDevices.runtimeDefaults(List.of(device.id()))
                .getOrDefault(device.id(), InternalDeviceRepository.RuntimeDefaults.NONE);
        return new DeviceLookupResponse(Long.toString(device.id()), device.status(), Long.toString(org), DeviceViews.id(device.modelId()),
                DeviceViews.id(device.spaceId()), device.virtual(), ignored, device.expectedIntervalSec(),
                device.offlineMultiplier() == null ? null : device.offlineMultiplier().doubleValue(),
                x.modelExpectedIntervalSec(), x.modelOfflineMultiplier(), x.timezone());
    }

    /** API-ING-16 자동 등록 한도 해제(ADMIN, OPS_MANAGE). 새 한도를 주면 소스 설정도 바꾼다 */
    @Transactional
    public QuotaResponse resetQuota(long sourceId, QuotaResetRequest req) {
        roleChecker.require(Permission.OPS_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        SourceRef source = refs.findSource(org, sourceId).orElseThrow(() -> new BusinessException(DeviceErrorCode.SOURCE_NOT_FOUND));
        Instant now = clock.instant();
        int limit = source.autoregLimitPerHour();
        if (req != null && req.newHourlyLimit() != null && req.newHourlyLimit() != limit) {
            limit = req.newHourlyLimit();
            discovery.updateSourceLimit(org, sourceId, limit, user.userId(), now);
            publisher.configChanged(EntityType.SOURCE, sourceId, discovery.findSourceVersion(org, sourceId), org);
            configVersions.bump(org, ConfigVersions.SOURCES);
        }
        discovery.releaseQuota(org, sourceId, hourStart(now), user.userId(), now);
        audits.record(audits.event(org, DeviceAudits.AUTO_REGISTER_QUOTA_RESET).actor(user).target("SOURCE", Long.toString(sourceId))
                .detail("hourlyLimit", limit).detail("previousHourlyLimit", source.autoregLimitPerHour()));
        return new QuotaResponse(Long.toString(sourceId), limit, false, 0);
    }

    /** 원본 정보 정리: 객체만 받고 metrics를 붙인다. 8KB를 넘으면 deviceName·tags·metrics만 남긴다 */
    ObjectNode meta(JsonNode raw, List<String> metrics) {
        ObjectNode meta = raw != null && raw.isObject() ? (ObjectNode) raw.deepCopy() : json.createObjectNode();
        if (metrics != null && !metrics.isEmpty()) {
            Set<String> keys = new LinkedHashSet<>();
            metrics.stream().filter(m -> m != null && !m.isBlank() && m.length() <= 64).forEach(keys::add);
            ArrayNode array = meta.putArray("metrics");
            keys.forEach(array::add);
        }
        if (json.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8).length <= MAX_META_BYTES) {
            return meta;
        }
        ObjectNode small = json.createObjectNode();
        for (String field : List.of("deviceName", "tags", "metrics")) {
            if (meta.has(field)) {
                small.set(field, meta.get(field));
            }
        }
        return json.writeValueAsString(small).getBytes(StandardCharsets.UTF_8).length <= MAX_META_BYTES ? small : json.createObjectNode();
    }

    private static String name(AutoRegisterRequest req, JsonNode meta, String ext) {
        String name = req.name();
        if (name == null || name.isBlank()) {
            JsonNode deviceName = meta.get("deviceName");
            name = deviceName != null && deviceName.isString() ? deviceName.stringValue() : null;
        }
        if (name == null || name.isBlank()) {
            name = ext;
        }
        name = name.strip();
        return name.length() > DeviceRules.MAX_NAME ? name.substring(0, DeviceRules.MAX_NAME) : name;
    }

    static Instant hourStart(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS);
    }
}
