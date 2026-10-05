package net.java21.data2flow.core.commissioning.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.contracts.message.event.DeviceCommissioningChanged;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.commissioning.domain.CommissioningRules;
import net.java21.data2flow.core.commissioning.domain.CommissioningRules.Observed;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.BoardDeviceResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.BoardResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.CommissionResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.CommissioningStatusResponse;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.Floor;
import net.java21.data2flow.core.commissioning.dto.CommissioningDtos.ServerCommission;
import net.java21.data2flow.core.commissioning.repository.CommissioningRepository;
import net.java21.data2flow.core.commissioning.repository.CommissioningRepository.BoardDevice;
import net.java21.data2flow.core.commissioning.repository.CommissioningRepository.Commissioning;
import net.java21.data2flow.core.commissioning.repository.CommissioningRepository.FloorRef;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.References.SpaceRef;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.service.DeviceAccess;
import net.java21.data2flow.core.device.service.DeviceEvents;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.space.repository.FloorplanRepository;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Marker;
import net.java21.data2flow.core.space.service.SemanticService;
import net.java21.data2flow.core.workorder.domain.WorkOrderErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QR 현장 설치(DEV-13.05, API-DEV-137, BR-DEV-37·38)와 설치 현황판(DEV-13.06, API-DEV-138). 저장하면 승인 대기 기기는 승인(ACTIVE)되고
 * 공간·평면도 좌표가 지정된다. {@code clientOpId}로 멱등이고(오프라인 재전송), 서버에 더 새로운 설치 기록이 있으면 409
 * {@code COMMISSION_CONFLICT}와 서버 값을 준다. 첫 수신(설치 시각 뒤 수신)이면 VERIFIED, 10분 안에 없으면 PROBLEM + 점검 체크리스트.
 * 상태가 바뀔 때마다 EVT-DEV-14 {@code device.commissioning.changed}(현황판 SSE).
 */
@Service
public class CommissioningService {

    static final String AUDIT_COMMISSIONED = "DEVICE_COMMISSIONED";

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final DeviceRepository devices;
    private final DeviceEvents deviceEvents;
    private final SemanticService semantic;
    private final FloorplanRepository floorplans;
    private final CommissioningRepository repository;
    private final StoredFiles files;
    private final CoreEventPublisher publisher;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public CommissioningService(RoleChecker roleChecker, DeviceAccess access, DeviceRepository devices, DeviceEvents deviceEvents,
                                SemanticService semantic, FloorplanRepository floorplans, CommissioningRepository repository,
                                StoredFiles files, CoreEventPublisher publisher, Audits audits, JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.devices = devices;
        this.deviceEvents = deviceEvents;
        this.semantic = semantic;
        this.floorplans = floorplans;
        this.repository = repository;
        this.files = files;
        this.publisher = publisher;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 사진 한 장(파일 이름, 내용) */
    public record Photo(String fileName, byte[] data) {
    }

    /**
     * API-DEV-137 — DEV_PLACE(설치 담당자 = 승인·공간 지정 권한, UC-DEV-27). 승인 대기 기기는 모델이 정해져 있어야 승인할 수 있다(400
     * DEVICE_MODEL_REQUIRED). 같은 clientOpId 재전송은 처음 결과를 그대로 준다.
     */
    @Transactional
    public CommissionResponse commission(long deviceId, String clientOpIdRaw, String spaceIdRaw, BigDecimal x, BigDecimal y,
                                         Instant installedAt, List<Photo> photos) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        List<FieldErrorDetail> errors = new ArrayList<>();
        UUID clientOpId = null;
        try {
            clientOpId = UUID.fromString(Objects.requireNonNull(clientOpIdRaw).strip());
        } catch (RuntimeException ex) {
            errors.add(new FieldErrorDetail("clientOpId", "Invalid", "UUID"));
        }
        if ((x == null) != (y == null) || (x != null && (x.signum() < 0 || x.compareTo(BigDecimal.ONE) > 0 || y.signum() < 0
                || y.compareTo(BigDecimal.ONE) > 0))) {
            errors.add(new FieldErrorDetail("x", "Range", "0~1"));
        }
        Instant now = clock.instant();
        Instant at = installedAt == null ? now : installedAt;
        if (at.isAfter(now.plusSeconds(300))) {
            errors.add(new FieldErrorDetail("installedAt", "Future", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        long org = user.organizationId();
        Optional<Commissioning> sameOp = repository.findByClientOp(org, clientOpId);
        if (sameOp.isPresent()) {
            if (sameOp.get().deviceId() != deviceId) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("clientOpId", "Reused", null)));
            }
            access.device(deviceId, Permission.DEV_PLACE);
            return response(sameOp.get());
        }
        Device device = access.device(deviceId, Permission.DEV_PLACE);
        SpaceRef space = access.space(DeviceAccess.id(spaceIdRaw, "spaceId"));
        roleChecker.require(Permission.DEV_PLACE, space.id(), DeviceErrorCode.SPACE_NOT_FOUND);
        Optional<Commissioning> existing = repository.lock(org, deviceId);
        if (existing.isPresent() && existing.get().installedAt() != null && existing.get().installedAt().isAfter(at)) {
            Commissioning c = existing.get();
            throw new FailureWithResponse(WorkOrderErrorCode.COMMISSION_CONFLICT, List.of(),
                    new ServerCommission(Long.toString(deviceId), c.status(), str(c.spaceId()), c.x(), c.y(), c.installedAt(),
                            str(c.installedBy()), c.installedByName()),
                    c.installedByName() == null ? "?" : c.installedByName(), c.installedAt().toString());
        }
        placeDevice(device, space.id(), user.userId(), now);
        List<String> photoRefs = new ArrayList<>();
        for (Photo p : photos) {
            photoRefs.add(files.storeMedia(org, "COMMISSIONING", p.fileName(), p.data(), user.userId(), true).key());
        }
        repository.upsertInstalled(org, deviceId, space.id(), user.userId(), at, photoRefs, x, y, clientOpId, now);
        if (x != null) {
            mark(org, space.id(), deviceId, x, y, now);
        }
        audits.record(audits.event(org, AUDIT_COMMISSIONED).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("spaceId", space.id()).detail("photos", photoRefs.size()).detail("installedAt", at.toString()));
        publisher.event(EventType.DEVICE_COMMISSIONING_CHANGED, org,
                new DeviceCommissioningChanged(deviceId, CommissioningRules.INSTALLED, null, null));
        // 이미 설치 뒤 데이터가 와 있으면 바로 확인
        Commissioning saved = repository.find(org, deviceId).orElseThrow();
        Commissioning checked = evaluate(saved, now).orElse(saved);
        return response(checked);
    }

    /** 설치 상태(대기 화면) — DEV_READ */
    @Transactional(readOnly = true)
    public CommissioningStatusResponse status(long deviceId) {
        roleChecker.require(Permission.DEV_READ);
        Device d = access.device(deviceId, Permission.DEV_READ);
        long org = d.organizationId();
        Commissioning c = repository.find(org, deviceId).orElse(null);
        if (c == null) {
            return new CommissioningStatusResponse(Long.toString(deviceId), CommissioningRules.PLANNED, str(d.spaceId()), null, null, null, null,
                    null, null, null, null, null, List.of());
        }
        JsonNode latest = CommissioningRules.VERIFIED.equals(c.status())
                ? repository.findLatest(org, deviceId).map(json::readTree).orElse(null) : null;
        List<String> photoUrls = c.photoRefs().stream().map(StoredFiles::idOf).flatMap(Optional::stream)
                .map(id -> "/api/v1/core/devices/" + deviceId + "/commission/photos/" + id).toList();
        return new CommissioningStatusResponse(Long.toString(deviceId), c.status(), str(c.spaceId()), c.x(), c.y(), c.installedAt(),
                str(c.installedBy()), c.installedByName(), waitUntil(c), c.firstSeenAt(), checklist(c.checklistJson()), latest, photoUrls);
    }

    @Transactional(readOnly = true)
    public Blob photo(long deviceId, long photoId) {
        roleChecker.require(Permission.DEV_READ);
        Device d = access.device(deviceId, Permission.DEV_READ);
        String key = StoredFiles.KEY_PREFIX + photoId;
        boolean owned = repository.find(d.organizationId(), deviceId).map(c -> c.photoRefs().contains(key)).orElse(false);
        if (!owned) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return files.load(d.organizationId(), key).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 한 기기의 설치 확인(BR-DEV-38): 설치 뒤 수신이 있으면 VERIFIED, 10분이 지나도 없으면 PROBLEM + 체크리스트. 바뀌면 이벤트를 내고
     * 바뀐 기록을 돌려준다. 정기 작업(1분)과 기기 온라인 이벤트(EVT-DEV-02)가 부른다.
     */
    @Transactional
    public Optional<Commissioning> check(long organizationId, long deviceId) {
        return repository.lock(organizationId, deviceId).flatMap(c -> evaluate(c, clock.instant()));
    }

    private Optional<Commissioning> evaluate(Commissioning c, Instant now) {
        long org = c.organizationId();
        Observed o = repository.observe(org, c.deviceId(), now).orElse(null);
        Instant lastSeen = o == null ? null : o.lastSeenAt();
        String next = CommissioningRules.next(c.status(), c.installedAt(), lastSeen, now);
        if (next.equals(c.status())) {
            return Optional.empty();
        }
        Map<String, Boolean> checklist = CommissioningRules.checklist(c.installedAt(), c.x() != null, !c.photoRefs().isEmpty(), o);
        Instant firstSeen = CommissioningRules.VERIFIED.equals(next) ? lastSeen : null;
        repository.updateStatus(org, c.deviceId(), next, firstSeen, json.writeValueAsString(checklist), now);
        publisher.event(EventType.DEVICE_COMMISSIONING_CHANGED, org, new DeviceCommissioningChanged(c.deviceId(), next, firstSeen, checklist));
        return repository.find(org, c.deviceId());
    }

    /** API-DEV-138 층별 수 — DEV_READ. siteId가 없으면 모든 사이트 */
    @Transactional(readOnly = true)
    public BoardResponse board(String siteIdRaw) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        Long siteId = siteIdRaw == null || siteIdRaw.isBlank() ? null : DeviceAccess.id(siteIdRaw, "siteId");
        if (siteId != null) {
            access.space(siteId);
        }
        SpaceScope scope = roleChecker.spaceScope();
        List<Floor> floors = new ArrayList<>();
        for (FloorRef f : repository.listFloors(org, siteId, scope.unrestricted() ? null : scope.allowedSpaceIds())) {
            List<BoardDevice> list = repository.listBoardDevices(org, f.path(), null, scope.unrestricted() ? null : scope.allowedSpaceIds());
            long installed = list.stream().filter(b -> !CommissioningRules.PLANNED.equals(b.status())).count();
            long verified = list.stream().filter(b -> CommissioningRules.VERIFIED.equals(b.status())).count();
            long problem = list.stream().filter(b -> CommissioningRules.PROBLEM.equals(b.status())).count();
            floors.add(new Floor(Long.toString(f.id()), f.name(), list.size(), installed, verified, problem));
        }
        return new BoardResponse(siteId == null ? null : Long.toString(siteId), floors);
    }

    /** API-DEV-138 기기 목록(공간 하위 포함, 상태 필터) — DEV_READ */
    @Transactional(readOnly = true)
    public List<BoardDeviceResponse> boardDevices(String spaceIdRaw, String status) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        SpaceRef space = access.space(DeviceAccess.id(spaceIdRaw, "spaceId"));
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (st != null && !CommissioningRules.STATUSES.contains(st)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("status", "Invalid", null)));
        }
        SpaceScope scope = roleChecker.spaceScope();
        Instant now = clock.instant();
        return repository.listBoardDevices(org, space.path(), st, scope.unrestricted() ? null : scope.allowedSpaceIds()).stream()
                .map(b -> {
                    Map<String, Boolean> checklist = checklist(b.checklistJson());
                    if (checklist == null && !CommissioningRules.PLANNED.equals(b.status())) {
                        checklist = CommissioningRules.checklist(b.installedAt(), b.positioned(), b.photographed(),
                                repository.observe(org, b.deviceId(), now).orElse(null));
                    }
                    return new BoardDeviceResponse(Long.toString(b.deviceId()), b.name(), str(b.spaceId()), b.status(), checklist,
                            b.installedAt());
                }).toList();
    }

    // ---- 내부

    /** 승인 대기면 승인(공간 지정), 아니면 공간을 옮긴다 */
    private void placeDevice(Device device, long spaceId, long userId, Instant now) {
        long org = device.organizationId();
        if ("PENDING".equals(device.status())) {
            if (device.modelId() == null) {
                throw new BusinessException(DeviceErrorCode.DEVICE_MODEL_REQUIRED);
            }
            if (devices.approve(org, device.id(), device.version(), device.modelId(), spaceId, device.kind(), device.name(), userId, now) == 0) {
                throw new BusinessException(DeviceErrorCode.DEVICE_STATE_CONFLICT);
            }
            semantic.applyModelTemplate(org, device.id());
            deviceEvents.changed(devices.findById(org, device.id()).orElseThrow(), DeviceChanged.Change.APPROVED,
                    List.of("status", "spaceId"));
        } else if (!Objects.equals(device.spaceId(), spaceId)) {
            if (devices.update(org, device.id(), device.version(), device.name(), device.kind(), device.modelId(), spaceId,
                    device.expectedIntervalSec(), device.offlineMultiplier(), userId, now) == 0) {
                throw new BusinessException(DeviceErrorCode.DEVICE_STATE_CONFLICT);
            }
            deviceEvents.changed(devices.findById(org, device.id()).orElseThrow(), DeviceChanged.Change.UPDATED, List.of("spaceId"));
        }
    }

    /** 평면도가 있으면 기기 마커를 설치 위치로 */
    private void mark(long org, long spaceId, long deviceId, BigDecimal x, BigDecimal y, Instant now) {
        floorplans.findBySpace(org, spaceId).ifPresent(fp -> {
            List<Marker> markers = new ArrayList<>(floorplans.findMarkers(org, fp.id()).stream().filter(m -> m.deviceId() != deviceId).toList());
            markers.add(new Marker(deviceId, null, x, y, 0));
            floorplans.replaceMarkers(org, fp.id(), markers, now);
        });
    }

    private CommissionResponse response(Commissioning c) {
        return new CommissionResponse(Long.toString(c.deviceId()), c.status(), c.installedAt(), str(c.installedBy()), waitUntil(c));
    }

    private static Instant waitUntil(Commissioning c) {
        return c.installedAt() == null ? null : c.installedAt().plus(CommissioningRules.FIRST_DATA_WAIT);
    }

    private Map<String, Boolean> checklist(String raw) {
        return raw == null ? null : json.readValue(raw, new TypeReference<LinkedHashMap<String, Boolean>>() {
        });
    }

    private static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
