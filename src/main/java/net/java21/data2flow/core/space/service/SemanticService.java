package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.space.domain.SemanticVocabulary;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.EquipmentDto;
import net.java21.data2flow.core.space.dto.SpaceDtos.PointDto;
import net.java21.data2flow.core.space.dto.SpaceDtos.SemanticDocument;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.DeviceRef;
import net.java21.data2flow.core.space.repository.SemanticRepository;
import net.java21.data2flow.core.space.repository.SemanticRepository.EquipmentRow;
import net.java21.data2flow.core.space.repository.SemanticRepository.PointRow;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * 시맨틱 계층 Location → Equipment → Point(DEV-13.01, API-DEV-131, BR-DEV-32·33). 기기마다 장비(Brick 클래스)와 점(점 유형 +
 * 물리량 + 태그)을 둔다. 물리량·장비 클래스는 표준 어휘나 {@code custom:}만 받고, 틀리면 400 SEMANTIC_TAG_UNKNOWN과 가까운 후보를
 * 알려 준다(AT-DEV-23.3). 바꾸면 감사 {@code SEMANTIC_CHANGED}에 이전·이후 값을 남긴다(AT-DEV-23.2).
 * 모델 템플릿 적용({@link #applyModelTemplate})은 승인할 때(기기 기능, BR-DEV-32)와 [모델 태그 다시 적용](AT-DEV-23.4)에서 쓴다.
 * EVT-DEV-12 {@code semantic.changed}는 contracts에 이벤트 종류가 생기면 여기서 낸다.
 */
@Service
public class SemanticService {

    public static final String AUDIT_SEMANTIC_CHANGED = "SEMANTIC_CHANGED";
    static final int MAX_EQUIPMENT = 20;
    static final int MAX_POINTS = 100;

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);

    private final SemanticRepository semantic;
    private final DeviceRelationRepository devices;
    private final SpaceRepository spaces;
    private final SpaceSettingsRepository settings;
    private final SpaceSupport support;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;

    public SemanticService(SemanticRepository semantic, DeviceRelationRepository devices, SpaceRepository spaces,
                           SpaceSettingsRepository settings, SpaceSupport support, RoleChecker roleChecker, JsonMapper json,
                           Clock clock) {
        this.semantic = semantic;
        this.devices = devices;
        this.spaces = spaces;
        this.settings = settings;
        this.support = support;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-131 GET */
    @Transactional(readOnly = true)
    public SemanticDocument get(long deviceId) {
        DeviceRef device = support.visibleDevice(deviceId, Permission.DEV_READ);
        return document(support.user().organizationId(), device.id());
    }

    /** API-DEV-131 PUT: 기기의 장비·점을 통째로 바꾼다(점 source=USER) */
    @Transactional
    public SemanticDocument replace(long deviceId, SemanticDocument req) {
        DeviceRef device = support.visibleDevice(deviceId, Permission.DEV_ADMIN);
        long org = support.user().organizationId();
        List<EquipmentRow> rows = validate(org, device, req == null || req.equipment() == null ? List.of() : req.equipment());
        return store(org, device, rows, "EDITED");
    }

    /** API-DEV-131 POST reapply-model: 모델의 시맨틱 템플릿으로 다시 만든다(AT-DEV-23.4) */
    @Transactional
    public SemanticDocument reapplyModel(long deviceId) {
        DeviceRef device = support.visibleDevice(deviceId, Permission.DEV_ADMIN);
        if (device.modelId() == null) {
            throw SpaceSupport.invalid("modelId", "NotNull");
        }
        long org = support.user().organizationId();
        return store(org, device, fromTemplate(org, device), "MODEL_REAPPLIED");
    }

    /**
     * 모델 템플릿(BR-DEV-32)으로 기기의 장비·점을 만든다. 권한은 호출 쪽(기기 승인)이 본 뒤 부른다. 감사는 남기지 않는다(승인 감사가 남음).
     * 템플릿 형식: {@code {equipClass, name?, points[{metricKey, pointType, quantity, tags[]}]}}, 그 배열, 또는 {@code {equipment:[…]}}.
     * 조직에 없는 측정 항목의 점은 건너뛴다.
     */
    @Transactional
    public void applyModelTemplate(long organizationId, long deviceId) {
        DeviceRef device = devices.findDevice(organizationId, deviceId).orElse(null);
        if (device == null || device.modelId() == null) {
            return;
        }
        semantic.deleteByDevice(organizationId, device.id());
        Instant now = clock.instant();
        for (EquipmentRow row : fromTemplate(organizationId, device)) {
            semantic.insertEquipment(organizationId, device.id(), row, now);
        }
    }

    private SemanticDocument store(long org, DeviceRef device, List<EquipmentRow> rows, String reason) {
        SemanticDocument before = document(org, device.id());
        semantic.deleteByDevice(org, device.id());
        Instant now = clock.instant();
        for (EquipmentRow row : rows) {
            semantic.insertEquipment(org, device.id(), row, now);
        }
        SemanticDocument after = document(org, device.id());
        support.audit(AUDIT_SEMANTIC_CHANGED, "DEVICE", device.id(), Map.of("reason", reason,
                "before", auditView(before), "after", auditView(after)));
        return after;
    }

    private List<EquipmentRow> fromTemplate(long org, DeviceRef device) {
        String raw = semantic.findModelTemplate(org, device.modelId()).orElse(null);
        List<EquipmentRow> rows = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return rows;
        }
        JsonNode root = json.readTree(raw);
        List<JsonNode> equipment = new ArrayList<>();
        if (root.isArray()) {
            root.values().forEach(equipment::add);
        } else if (root.has("equipment") && root.get("equipment").isArray()) {
            root.get("equipment").values().forEach(equipment::add);
        } else if (root.isObject()) {
            equipment.add(root);
        }
        Set<String> metricKeys = new LinkedHashSet<>();
        for (JsonNode e : equipment) {
            for (JsonNode p : e.path("points").values()) {
                metricKeys.add(p.path("metricKey").asString(""));
            }
        }
        Set<String> existing = settings.findExistingMetricKeys(org, metricKeys);
        for (JsonNode e : equipment) {
            String equipClass = SemanticVocabulary.equipClass(e.path("equipClass").asString(""))
                    .orElse(e.path("equipClass").asString("Equipment"));
            String name = e.path("name").asString("").isBlank() ? device.name() : e.path("name").asString();
            List<PointRow> points = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode p : e.path("points").values()) {
                String key = p.path("metricKey").asString("");
                if (!existing.contains(key) || !seen.add(key)) {
                    log.info("시맨틱 템플릿 점 건너뜀: 모델 {} 측정 항목 {}", device.modelId(), key);
                    continue;
                }
                String type = p.path("pointType").asString("MEASUREMENT").toUpperCase(Locale.ROOT);
                String quantity = p.path("quantity").isMissingNode() || p.path("quantity").isNull() ? null
                        : SemanticVocabulary.quantity(p.path("quantity").asString()).orElse(p.path("quantity").asString());
                List<String> tags = new ArrayList<>();
                p.path("tags").values().forEach(t -> tags.add(t.asString("")));
                points.add(new PointRow(null, key, SemanticVocabulary.POINT_TYPES.contains(type) ? type : "MEASUREMENT",
                        quantity, tags, "MODEL"));
            }
            rows.add(new EquipmentRow(null, truncate(equipClass, 80), truncate(name, 100), device.spaceId(), points));
        }
        return rows;
    }

    private List<EquipmentRow> validate(long org, DeviceRef device, List<EquipmentDto> input) {
        if (input.size() > MAX_EQUIPMENT) {
            throw SpaceSupport.invalid("equipment", "Size");
        }
        List<FieldErrorDetail> invalid = new ArrayList<>();
        List<FieldErrorDetail> unknown = new ArrayList<>();
        String suggestion = null;
        Set<String> metricKeys = new LinkedHashSet<>();
        Set<Long> spaceIds = new LinkedHashSet<>();
        List<EquipmentRow> rows = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            EquipmentDto e = input.get(i);
            String prefix = "equipment[" + i + "].";
            if (e == null) {
                invalid.add(new FieldErrorDetail("equipment[" + i + "]", "NotNull", null));
                continue;
            }
            String equipClass = SemanticVocabulary.equipClass(e.equipClass()).orElse(null);
            if (equipClass == null) {
                String hint = SemanticVocabulary.suggestEquipClass(e.equipClass());
                suggestion = suggestion == null ? hint : suggestion;
                unknown.add(new FieldErrorDetail(prefix + "equipClass", SpaceErrorCode.SEMANTIC_TAG_UNKNOWN.code(), hint));
            }
            String name = e.name() == null ? "" : e.name().strip();
            if (name.isEmpty() || name.length() > 100) {
                invalid.add(new FieldErrorDetail(prefix + "name", "Size", "1~100"));
            }
            Long spaceId = SpaceSupport.parseId(e.spaceId(), prefix + "spaceId");
            if (spaceId != null) {
                spaceIds.add(spaceId);
            }
            List<PointDto> pointsIn = e.points() == null ? List.of() : e.points();
            if (pointsIn.size() > MAX_POINTS) {
                invalid.add(new FieldErrorDetail(prefix + "points", "Size", "0~" + MAX_POINTS));
                continue;
            }
            List<PointRow> points = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int j = 0; j < pointsIn.size(); j++) {
                PointDto p = pointsIn.get(j);
                String pp = prefix + "points[" + j + "].";
                String key = p == null || p.metricKey() == null ? "" : p.metricKey().strip();
                if (key.isEmpty()) {
                    invalid.add(new FieldErrorDetail(pp + "metricKey", "NotBlank", null));
                    continue;
                }
                if (!seen.add(key)) {
                    invalid.add(new FieldErrorDetail(pp + "metricKey", "DUPLICATE", null));
                }
                metricKeys.add(key);
                String type = p.pointType() == null ? "" : p.pointType().strip().toUpperCase(Locale.ROOT);
                if (!SemanticVocabulary.POINT_TYPES.contains(type)) {
                    invalid.add(new FieldErrorDetail(pp + "pointType", "INVALID", "MEASUREMENT|CONTROL|STATUS|SETPOINT"));
                }
                String quantity = SemanticVocabulary.quantity(p.quantity()).orElse(null);
                if (quantity == null) {
                    String hint = SemanticVocabulary.suggestQuantity(p.quantity());
                    suggestion = suggestion == null ? hint : suggestion;
                    unknown.add(new FieldErrorDetail(pp + "quantity", SpaceErrorCode.SEMANTIC_TAG_UNKNOWN.code(), hint));
                }
                List<String> tags = new ArrayList<>();
                List<String> tagsIn = p.tags() == null ? List.of() : p.tags();
                if (tagsIn.size() > 20) {
                    invalid.add(new FieldErrorDetail(pp + "tags", "Size", "0~20"));
                }
                for (String tag : tagsIn) {
                    String t = tag == null ? "" : tag.strip();
                    if (!t.matches("[A-Za-z0-9_:.-]{1,40}")) {
                        invalid.add(new FieldErrorDetail(pp + "tags", "Pattern", null));
                        break;
                    }
                    if (!tags.contains(t)) {
                        tags.add(t);
                    }
                }
                points.add(new PointRow(null, key, type, quantity, tags, "USER"));
            }
            rows.add(new EquipmentRow(null, equipClass, name, spaceId == null ? device.spaceId() : spaceId, points));
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, invalid);
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(SpaceErrorCode.SEMANTIC_TAG_UNKNOWN, unknown, suggestion);
        }
        if (!settings.findExistingMetricKeys(org, metricKeys).containsAll(metricKeys)) {
            throw new BusinessException(SpaceErrorCode.METRIC_NOT_FOUND);
        }
        Set<Long> found = new LinkedHashSet<>(spaces.findByIds(org, spaceIds).stream().map(Space::id).toList());
        for (Long spaceId : spaceIds) {
            if (!found.contains(spaceId) || !roleChecker.spaceScope().includes(spaceId)) {
                throw new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND);
            }
        }
        return rows;
    }

    private SemanticDocument document(long org, long deviceId) {
        List<EquipmentDto> equipment = semantic.findByDevice(org, deviceId).stream()
                .map(e -> new EquipmentDto(Long.toString(e.id()), e.equipClass(), e.name(), SpaceSupport.id(e.spaceId()),
                        e.points().stream().map(p -> new PointDto(Long.toString(p.id()), p.metricKey(), p.pointType(), p.quantity(),
                                p.tags(), p.source())).toList()))
                .toList();
        return new SemanticDocument(Long.toString(deviceId), equipment);
    }

    private static List<Map<String, Object>> auditView(SemanticDocument doc) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EquipmentDto e : doc.equipment()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("equipClass", e.equipClass());
            m.put("name", e.name());
            m.put("points", e.points().stream().map(p -> p.metricKey() + "|" + p.pointType() + "|" + p.quantity()
                    + "|" + String.join(",", p.tags())).toList());
            result.add(m);
        }
        return result;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
