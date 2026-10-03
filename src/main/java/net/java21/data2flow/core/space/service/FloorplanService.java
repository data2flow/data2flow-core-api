package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.space.domain.FloorplanImage;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.FloorplanResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkerDto;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkerItem;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkersRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkersResponse;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.DeviceRef;
import net.java21.data2flow.core.space.repository.FloorplanRepository;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Floorplan;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Image;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Marker;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 평면도(DEV-01.03): 업로드·교체·삭제(API-DEV-09), 기기 마커 전체 교체(API-DEV-10), 조회(API-DSH-03 기본 부분)와 원본 이미지 내려받기.
 * 좌표는 이미지 대비 비율(0~1)이라 평면도를 크기가 다른 이미지로 바꿔도 마커는 그대로 두고 {@code markersNeedReview=true}로
 * "위치 확인 필요"를 알린다(AT-DEV-13.2). 오브젝트 저장소가 아직 없어 원본은 DB에 둔다({@code floorplan_images}).
 * 이미지 주소는 BFF를 거쳐 세션으로 읽는 API 경로({@code /api/v1/core/spaces/{id}/floorplan/image?v=버전})라 서명 URL이 필요 없다.
 */
@Service
public class FloorplanService {

    static final int MAX_MARKERS = 500;

    private final FloorplanRepository floorplans;
    private final DeviceRelationRepository devices;
    private final SpaceRepository spaces;
    private final SpaceSupport support;
    private final Clock clock;

    public FloorplanService(FloorplanRepository floorplans, DeviceRelationRepository devices, SpaceRepository spaces,
                            SpaceSupport support, Clock clock) {
        this.floorplans = floorplans;
        this.devices = devices;
        this.spaces = spaces;
        this.support = support;
        this.clock = clock;
    }

    /** 평면도 조회. 없으면 404 RESOURCE_NOT_FOUND */
    @Transactional(readOnly = true)
    public FloorplanResponse get(long spaceId) {
        Space space = support.visible(spaceId, Permission.DEV_READ);
        Floorplan fp = floorplans.findBySpace(space.organizationId(), space.id())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return response(space, fp, false);
    }

    /** API-DEV-09 업로드·교체. PNG·JPG·SVG ≤10MB(내용으로 판정), 그 밖은 400 FLOORPLAN_IMAGE_INVALID */
    @Transactional
    public FloorplanResponse upload(long spaceId, byte[] data, BigDecimal scaleMPerPx) {
        Space space = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = space.organizationId();
        FloorplanImage.Info info = FloorplanImage.inspect(data)
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.FLOORPLAN_IMAGE_INVALID));
        if (scaleMPerPx != null && (scaleMPerPx.signum() <= 0 || scaleMPerPx.compareTo(new BigDecimal("1000")) >= 0)) {
            throw SpaceSupport.invalid("scaleMPerPx", "Range");
        }
        BigDecimal scale = scaleMPerPx == null ? null : scaleMPerPx.setScale(6, RoundingMode.HALF_UP);
        Floorplan before = floorplans.findBySpace(org, space.id()).orElse(null);
        Instant now = clock.instant();
        long id = floorplans.upsert(org, space.id(), info.width(), info.height(), scale, support.user().userId(), now);
        floorplans.storeImage(org, id, info.contentType(), data, sha256(data), now);
        boolean resized = before != null && (before.widthPx() != info.width() || before.heightPx() != info.height());
        boolean needReview = resized && !floorplans.findMarkers(org, id).isEmpty();
        int version = spaces.touch(org, space.id(), support.user().userId(), now);
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, space.id(), Map.of("floorplan", info.contentType(),
                "widthPx", info.width(), "heightPx", info.height()));
        support.changed(org, space.id(), space.path(), "UPDATED", version);
        return response(space, floorplans.findBySpace(org, space.id()).orElseThrow(), needReview);
    }

    /** API-DEV-09 DELETE: 평면도와 마커를 지운다 */
    @Transactional
    public void delete(long spaceId) {
        Space space = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = space.organizationId();
        if (floorplans.deleteBySpace(org, space.id()) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = clock.instant();
        int version = spaces.touch(org, space.id(), support.user().userId(), now);
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, space.id(), Map.of("floorplan", "DELETED"));
        support.changed(org, space.id(), space.path(), "UPDATED", version);
    }

    /** 원본 이미지 */
    @Transactional(readOnly = true)
    public Image image(long spaceId) {
        Space space = support.visible(spaceId, Permission.DEV_READ);
        return floorplans.findImage(space.organizationId(), space.id())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * API-DEV-10 마커 전체 교체. 기기는 이 공간이나 하위 공간에 설치된 기기만(그 밖·다른 조직·권한 밖은 404 DEVICE_NOT_FOUND).
     * 좌표 0~1(소수 4자리), 회전 0~359.
     */
    @Transactional
    public MarkersResponse replaceMarkers(long spaceId, MarkersRequest req) {
        Space space = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = space.organizationId();
        Floorplan fp = floorplans.findBySpace(org, space.id())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        List<MarkerItem> items = req == null || req.markers() == null ? List.of() : req.markers();
        if (items.size() > MAX_MARKERS) {
            throw SpaceSupport.invalid("markers", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        List<Marker> markers = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            MarkerItem item = items.get(i);
            String field = "markers[" + i + "]";
            Long deviceId = item == null ? null : SpaceSupport.parseId(item.deviceId(), field + ".deviceId");
            if (deviceId == null) {
                errors.add(new FieldErrorDetail(field + ".deviceId", "NotNull", null));
                continue;
            }
            if (!ids.add(deviceId)) {
                errors.add(new FieldErrorDetail(field + ".deviceId", "DUPLICATE", null));
            }
            if (!ratio(item.x()) || !ratio(item.y())) {
                errors.add(new FieldErrorDetail(field, "Range", "0~1"));
                continue;
            }
            int rotation = item.rotation() == null ? 0 : item.rotation();
            if (rotation < 0 || rotation > 359) {
                errors.add(new FieldErrorDetail(field + ".rotation", "Range", "0~359"));
                continue;
            }
            markers.add(new Marker(deviceId, null, BigDecimal.valueOf(item.x()).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(item.y()).setScale(4, RoundingMode.HALF_UP), rotation));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Map<Long, DeviceRef> found = new HashMap<>();
        devices.findDevices(org, ids).forEach(d -> found.put(d.id(), d));
        Map<Long, Space> placed = new HashMap<>();
        Set<Long> spaceIds = new LinkedHashSet<>();
        found.values().forEach(d -> {
            if (d.spaceId() != null) {
                spaceIds.add(d.spaceId());
            }
        });
        spaces.findByIds(org, spaceIds).forEach(s -> placed.put(s.id(), s));
        for (Long id : ids) {
            DeviceRef device = found.get(id);
            Space where = device == null || device.spaceId() == null ? null : placed.get(device.spaceId());
            if (where == null || !space.isAncestorOrSelfOf(where)) {
                throw new BusinessException(SpaceErrorCode.DEVICE_NOT_FOUND);
            }
        }
        Instant now = clock.instant();
        floorplans.replaceMarkers(org, fp.id(), markers, now);
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, space.id(), Map.of("markers", markers.size()));
        return new MarkersResponse(markerDtos(org, fp.id()));
    }

    static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean ratio(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private List<MarkerDto> markerDtos(long org, long floorplanId) {
        return floorplans.findMarkers(org, floorplanId).stream()
                .map(m -> new MarkerDto(Long.toString(m.deviceId()), m.deviceName(), m.x(), m.y(), m.rotation())).toList();
    }

    private FloorplanResponse response(Space space, Floorplan fp, boolean needReview) {
        String url = "/api/v1/core/spaces/" + space.id() + "/floorplan/image?v=" + fp.version();
        return new FloorplanResponse(Long.toString(space.id()), url, fp.widthPx(), fp.heightPx(), fp.widthPx(), fp.heightPx(),
                fp.scaleMPerPx(), fp.contentType(), fp.version(), fp.updatedAt(), needReview,
                markerDtos(space.organizationId(), fp.id()));
    }
}
