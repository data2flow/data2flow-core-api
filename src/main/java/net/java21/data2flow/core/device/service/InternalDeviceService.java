package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.ChangedDevice;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.DeviceRuntimeResponse;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.RuntimeAttributes;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.TransformScript;
import net.java21.data2flow.core.device.repository.DeviceAttributeRepository;
import net.java21.data2flow.core.device.repository.DeviceAttributeRepository.AttributeRow;
import net.java21.data2flow.core.device.repository.InternalDeviceRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/** 서비스 간 기기 조회(API-DEV-122 처리용 정보, API-DEV-130 변경분 페이지). 신원 없이 부르며 배포 조직으로 좁힌다(ADR-030) */
@Service
public class InternalDeviceService {

    /** 캐시 예열 한 페이지 최대 */
    static final int MAX_PAGE = 1000;

    private final InternalDeviceRepository internal;
    private final DeviceAttributeRepository attributes;
    private final DeviceAttributeService attributeService;
    private final DeploymentOrganization deployment;
    private final JsonMapper json;

    public InternalDeviceService(InternalDeviceRepository internal, DeviceAttributeRepository attributes,
                                 DeviceAttributeService attributeService, DeploymentOrganization deployment, JsonMapper json) {
        this.internal = internal;
        this.attributes = attributes;
        this.attributeService = attributeService;
        this.deployment = deployment;
        this.json = json;
    }

    /** API-DEV-122: TRANSFORM 스크립트(모델 → 기기 순), 서버 속성(모델 스키마 기본값 포함, DEV-07.05)·공유 속성 */
    @Transactional(readOnly = true)
    public DeviceRuntimeResponse runtime(long deviceId) {
        Device device = internal.findAnyOrganization(deviceId, deployment.restriction()).filter(d -> !d.deleted())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        long org = device.organizationId();
        List<TransformScript> scripts = internal.findTransformBindings(org, deviceId, device.modelId()).stream()
                .map(b -> new TransformScript((String) b[0], Long.toString((Long) b[1]), (Long) b[2])).toList();
        Map<String, JsonNode> server = new LinkedHashMap<>();
        Map<String, JsonNode> shared = new LinkedHashMap<>();
        for (AttributeRow row : attributes.findByDevice(org, deviceId)) {
            if ("SERVER".equals(row.scope())) {
                server.put(row.key(), json.readTree(row.value()));
            } else if ("SHARED".equals(row.scope())) {
                shared.put(row.key(), json.readTree(row.value()));
            }
        }
        return new DeviceRuntimeResponse(Long.toString(deviceId), Long.toString(org), DeviceViews.id(device.modelId()), device.status(),
                device.virtual(), scripts, new RuntimeAttributes(attributeService.withDefaults(device, server), shared), Map.of(),
                device.version());
    }

    /** API-DEV-130 변경분(updatedAfter 뒤, 변경 시각 순). status를 주지 않으면 삭제(DELETED)도 포함한다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ChangedDevice> changed(List<String> status, String updatedAfter, Integer page, Integer size) {
        List<String> statuses = new ArrayList<>();
        if (status != null) {
            for (String s : status) {
                for (String part : s.split(",")) {
                    String v = part.strip().toUpperCase(Locale.ROOT);
                    if (!v.isEmpty()) {
                        if (!List.of("PENDING", "ACTIVE", "INACTIVE", "DELETED").contains(v)) {
                            throw invalid("status");
                        }
                        statuses.add(v);
                    }
                }
            }
        }
        Instant after = null;
        if (updatedAfter != null && !updatedAfter.isBlank()) {
            try {
                after = Instant.parse(updatedAfter.strip());
            } catch (DateTimeParseException ex) {
                throw invalid("updatedAfter");
            }
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? PageParams.DEFAULT_SIZE : Math.min(size, MAX_PAGE);
        PageParams params = new PageParams(p, s);
        OptionalLong org = deployment.restriction();
        var rows = internal.listChanged(org, statuses, after, s, params.offset());
        var defaults = internal.runtimeDefaults(rows.stream().map(d -> d.id()).toList());
        List<ChangedDevice> items = rows.stream()
                .map(d -> {
                    var x = defaults.getOrDefault(d.id(), InternalDeviceRepository.RuntimeDefaults.NONE);
                    return new ChangedDevice(Long.toString(d.id()), Long.toString(d.organizationId()), Long.toString(d.sourceId()),
                            d.externalId(), d.status(), DeviceViews.id(d.modelId()), DeviceViews.id(d.spaceId()), d.virtual(), d.version(),
                            d.updatedAt(), d.expectedIntervalSec(), d.offlineMultiplier() == null ? null : d.offlineMultiplier().doubleValue(),
                            x.modelExpectedIntervalSec(), x.modelOfflineMultiplier(), x.timezone());
                })
                .toList();
        return ListApiResponse.of(params, items, internal.countChanged(org, statuses, after));
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
