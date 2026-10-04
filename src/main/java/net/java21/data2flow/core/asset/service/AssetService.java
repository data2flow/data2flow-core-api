package net.java21.data2flow.core.asset.service;

import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.asset.dto.AssetDtos.AssetInfoRequest;
import net.java21.data2flow.core.asset.dto.AssetDtos.AssetInfoResponse;
import net.java21.data2flow.core.asset.repository.AssetRepository;
import net.java21.data2flow.core.asset.repository.AssetRepository.AssetInfo;
import net.java21.data2flow.core.asset.repository.AssetRepository.Expiring;
import net.java21.data2flow.core.asset.repository.AssetRepository.Fields;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.service.DeviceAccess;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.filestore.service.StoredFiles.Stored;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

/**
 * 기기 자산 정보(DEV-08.01, API-DEV-96): 시리얼·구매일·설치일·보증 만료일·공급처·설치 담당자·사진. 보증 만료 30일 전에 한 번
 * 알린다(AT-DEV-16.5): 시스템 알람 {@code system:WARRANTY_EXPIRING:{deviceId}}(INFO)으로 만들어 조직 알림 정책대로 관리자에게 간다.
 */
@Service
public class AssetService {

    static final String AUDIT_ASSET = "ASSET_INFO_UPDATED";
    public static final String WARRANTY_ALARM = "WARRANTY_EXPIRING";
    static final int WARN_DAYS = 30;

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final AssetRepository assets;
    private final StoredFiles files;
    private final AlarmService alarms;
    private final Audits audits;
    private final Clock clock;

    public AssetService(RoleChecker roleChecker, DeviceAccess access, AssetRepository assets, StoredFiles files, AlarmService alarms,
                        Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.assets = assets;
        this.files = files;
        this.alarms = alarms;
        this.audits = audits;
        this.clock = clock;
    }

    /** 자산 정보 조회 — DEV_READ. 없으면 빈 값 */
    @Transactional(readOnly = true)
    public AssetInfoResponse get(long deviceId) {
        roleChecker.require(Permission.DEV_READ);
        Device d = access.device(deviceId, Permission.DEV_READ);
        return assets.find(d.organizationId(), deviceId).map(AssetService::toResponse)
                .orElse(new AssetInfoResponse(Long.toString(deviceId), null, null, null, null, null, null, List.of(), null));
    }

    /** API-DEV-96 — DEV_ADMIN. 날짜 순서(구매 ≤ 설치)가 틀리면 400 */
    @Transactional
    public AssetInfoResponse put(long deviceId, AssetInfoRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        Device d = access.device(deviceId, Permission.DEV_ADMIN);
        if (req.purchasedOn() != null && req.installedOn() != null && req.installedOn().isBefore(req.purchasedOn())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("installedOn", "BeforePurchase", null)));
        }
        assets.upsert(d.organizationId(), deviceId, new Fields(blank(req.serialNo()), req.purchasedOn(), req.installedOn(),
                req.warrantyUntil(), blank(req.supplier()), blank(req.installer())), user.userId(), clock.instant());
        audits.record(audits.event(d.organizationId(), AUDIT_ASSET).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("serialNo", req.serialNo()).detail("warrantyUntil", req.warrantyUntil()));
        return toResponse(assets.find(d.organizationId(), deviceId).orElseThrow());
    }

    /** 자산 사진 추가(이미지만, ≤20MB) — DEV_ADMIN, 201 */
    @Transactional
    public AssetInfoResponse addPhoto(long deviceId, String fileName, byte[] data) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        Device d = access.device(deviceId, Permission.DEV_ADMIN);
        Stored stored = files.storeMedia(d.organizationId(), "ASSET_PHOTO", fileName, data, user.userId(), true);
        assets.addPhoto(d.organizationId(), deviceId, stored.key(), user.userId(), clock.instant());
        return toResponse(assets.find(d.organizationId(), deviceId).orElseThrow());
    }

    @Transactional
    public void deletePhoto(long deviceId, long photoId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        Device d = access.device(deviceId, Permission.DEV_ADMIN);
        String key = StoredFiles.KEY_PREFIX + photoId;
        if (assets.removePhoto(d.organizationId(), deviceId, key, user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        files.delete(d.organizationId(), List.of(key));
    }

    @Transactional(readOnly = true)
    public Blob photo(long deviceId, long photoId) {
        roleChecker.require(Permission.DEV_READ);
        Device d = access.device(deviceId, Permission.DEV_READ);
        String key = StoredFiles.KEY_PREFIX + photoId;
        boolean owned = assets.find(d.organizationId(), deviceId).map(a -> a.photoKeys().contains(key)).orElse(false);
        if (!owned) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return files.load(d.organizationId(), key).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** AT-DEV-16.5: 보증 만료 30일 이내(오늘 포함)이고 그 만료일로 아직 알리지 않은 기기마다 시스템 알람 1건. 알린 수 */
    public int warnWarrantyExpiring(OptionalLong onlyOrganization) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        int n = 0;
        for (Expiring e : assets.listExpiring(onlyOrganization, today, today.plusDays(WARN_DAYS))) {
            alarms.raise(new AlarmService.Raise(e.organizationId(), "system:" + WARRANTY_ALARM + ":" + e.deviceId(), AlarmSourceType.SYSTEM,
                    null, null, null, AlarmSeverity.INFO, "보증 만료 예정(" + e.warrantyUntil() + "): " + e.deviceName(), e.deviceId(), null,
                    null, null, null, null, now, "SYSTEM", false, false));
            assets.markNotified(e.organizationId(), e.deviceId(), e.warrantyUntil());
            n++;
        }
        return n;
    }

    static AssetInfoResponse toResponse(AssetInfo a) {
        List<String> urls = a.photoKeys().stream().map(StoredFiles::idOf).flatMap(java.util.Optional::stream)
                .map(id -> "/api/v1/core/devices/" + a.deviceId() + "/asset-info/photos/" + id).toList();
        return new AssetInfoResponse(Long.toString(a.deviceId()), a.serialNo(), a.purchasedOn(), a.installedOn(), a.warrantyUntil(),
                a.supplier(), a.installer(), urls, a.updatedAt());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
