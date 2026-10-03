package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CapabilityDefinition;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.dto.ControlDtos.ControlProfileResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.ProfileCapability;
import net.java21.data2flow.core.control.dto.ControlDtos.ProfileDriver;
import net.java21.data2flow.core.control.dto.ControlDtos.ProfileSettings;
import net.java21.data2flow.core.control.dto.ControlDtos.SandboxSpaces;
import net.java21.data2flow.core.control.repository.ControlProfileRepository;
import net.java21.data2flow.core.control.repository.ControlProfileRepository.ProfileRow;
import net.java21.data2flow.core.control.repository.ControlSettingsRepository.SettingsRow;
import net.java21.data2flow.core.control.repository.DriverRepository;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * action 제어 창구가 읽는 core 정의(ACT-api §5.4 API-ACT-40~43, 내부망 신뢰 ADR-021). action은 이 값을 캐시하고
 * {@code data2flow.config}(MODEL·DEVICE·SETTING(control)·SIM_SANDBOX·OUTPUT 아님)로 무효화한다.
 */
@Service
public class ControlInternalService {

    private final ControlProfileRepository profiles;
    private final DriverRepository drivers;
    private final CapabilityService capabilities;
    private final ControlSettingsService settings;
    private final JsonMapper json;

    public ControlInternalService(ControlProfileRepository profiles, DriverRepository drivers, CapabilityService capabilities,
                                  ControlSettingsService settings, JsonMapper json) {
        this.profiles = profiles;
        this.drivers = drivers;
        this.capabilities = capabilities;
        this.settings = settings;
        this.json = json;
    }

    /**
     * API-ACT-40 제어 프로필: 지원 기능(모델)과 모델 제약, 연결 드라이버, 조직 제어 설정, 샌드박스 여부.
     * {@code controllable}은 ACTIVE 기기 + 명령 있는 기능 1개 이상 + 드라이버 연결(아니면 action이 DEVICE_NOT_CONTROLLABLE).
     */
    @Transactional(readOnly = true)
    public ControlProfileResponse profile(long deviceId) {
        ProfileRow row = profiles.findByDeviceId(deviceId).filter(r -> !"DELETED".equals(r.status()))
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        long orgId = row.organizationId();
        CapabilityCatalog catalog = capabilities.catalog(orgId);
        JsonNode modelCaps = row.modelCapabilities() == null ? null : json.readTree(row.modelCapabilities());
        Map<String, ProfileCapability> caps = new LinkedHashMap<>();
        boolean commandable = false;
        for (String name : ControlModels.capabilityNames(modelCaps)) {
            Optional<CapabilityDefinition> def = catalog.find(name);
            if (def.isEmpty()) {
                continue;
            }
            commandable |= !def.get().stateOnly();
            JsonNode item = item(modelCaps, name);
            caps.put(name, new ProfileCapability(ControlModels.toJson(ControlModels.modelConstraints(modelCaps, def.get()), json),
                    item == null || !item.hasNonNull("protection") ? null : item.get("protection"),
                    item == null || item.path("reapplyOnReconnect").asBoolean(true),
                    item != null && item.path("classADownlink").asBoolean(false)));
        }
        ProfileDriver driver = null;
        if (row.driverId() != null) {
            driver = drivers.findById(orgId, row.driverId()).map(d -> new ProfileDriver(Long.toString(d.id()), d.type(),
                    json.readTree(d.config()), d.ackTimeoutSec(), d.applyTimeoutSec(), json.readTree(d.retry()))).orElse(null);
        }
        SettingsRow s = settings.settings(orgId);
        boolean controllable = "ACTIVE".equals(row.status()) && commandable && driver != null;
        return new ControlProfileResponse(Long.toString(row.deviceId()), Long.toString(orgId),
                row.spaceId() == null ? null : Long.toString(row.spaceId()), row.name(), row.externalId(), row.virtual(), row.status(),
                row.modelId() == null ? null : Long.toString(row.modelId()), caps, driver,
                new ProfileSettings(json.readTree(s.absoluteLimits()), s.manualOverrideMinutes(), s.minIntervalSec(),
                        json.readTree(s.oscillation()), s.defaultValiditySec(), s.scheduleRespectsManualOverride()),
                row.sandbox(), controllable);
    }

    private static JsonNode item(JsonNode modelCaps, String name) {
        if (modelCaps == null || !modelCaps.isArray()) {
            return null;
        }
        for (JsonNode n : modelCaps.values()) {
            if (name.equals(n.path("capability").asString(""))) {
                return n;
            }
        }
        return null;
    }

    /** API-ACT-41 샌드박스 공간(표시한 공간과 하위 전체). 조직을 주지 않으면 배포 조직 전체 */
    @Transactional(readOnly = true)
    public SandboxSpaces sandbox(List<Long> organizationIds) {
        List<String> ids = new ArrayList<>();
        for (long org : organizationIds) {
            profiles.listSandboxSpaceIds(org).forEach(id -> ids.add(Long.toString(id)));
        }
        return new SandboxSpaces(ids);
    }
}
