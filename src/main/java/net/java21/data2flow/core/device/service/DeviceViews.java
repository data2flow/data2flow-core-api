package net.java21.data2flow.core.device.service;

import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceListRow;
import net.java21.data2flow.core.device.domain.DeviceRules;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceDetailResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceSummaryResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.EffectiveView;
import net.java21.data2flow.core.device.dto.DeviceDtos.GroupRef;
import net.java21.data2flow.core.device.dto.DeviceDtos.LatestValue;
import net.java21.data2flow.core.device.dto.DeviceDtos.LorawanView;
import net.java21.data2flow.core.device.dto.DeviceDtos.ModelDetail;
import net.java21.data2flow.core.device.dto.DeviceDtos.ModelSummary;
import net.java21.data2flow.core.device.dto.DeviceDtos.OnboardingView;
import net.java21.data2flow.core.device.dto.DeviceDtos.RelationView;
import net.java21.data2flow.core.device.dto.DeviceDtos.SourceSummary;
import net.java21.data2flow.core.device.dto.DeviceDtos.SpaceSummary;
import net.java21.data2flow.core.device.dto.DeviceDtos.StateView;
import net.java21.data2flow.core.device.repository.DeviceQueryRepository;
import net.java21.data2flow.core.device.repository.DeviceQueryRepository.StateRow;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 기기 응답 모양 만들기(API-DEV-11 목록 항목, API-DEV-23 상세) */
@Component
public class DeviceViews {

    private final DeviceRepository devices;
    private final DeviceQueryRepository queries;
    private final DeviceReferenceRepository refs;
    private final JsonMapper json;

    public DeviceViews(DeviceRepository devices, DeviceQueryRepository queries, DeviceReferenceRepository refs, JsonMapper json) {
        this.devices = devices;
        this.queries = queries;
        this.refs = refs;
        this.json = json;
    }

    public List<DeviceSummaryResponse> summaries(long organizationId, List<DeviceListRow> rows) {
        Set<Long> spaceIds = new HashSet<>();
        rows.forEach(r -> {
            if (r.device().spaceId() != null) {
                spaceIds.add(r.device().spaceId());
            }
        });
        Map<Long, List<String>> paths = refs.findSpacePathNames(organizationId, spaceIds);
        return rows.stream().map(r -> summary(r, paths)).toList();
    }

    private DeviceSummaryResponse summary(DeviceListRow r, Map<Long, List<String>> paths) {
        Device d = r.device();
        boolean firstData = r.lastSeenAt() != null || d.firstSeenAt() != null;
        DeviceRules.Onboarding onboarding = DeviceRules.onboarding(firstData, d.modelId() != null, d.spaceId() != null, d.kind());
        ModelSummary model = d.modelId() == null ? null : new ModelSummary(id(d.modelId()), r.modelCode(), r.modelName());
        SpaceSummary space = d.spaceId() == null ? null : space(d.spaceId(), paths);
        return new DeviceSummaryResponse(id(d.id()), d.name(), d.externalId(), d.kind(), d.status(), r.connectivity(), r.lastSeenAt(),
                d.firstSeenAt(), r.battery(), r.rssi(), model, space, new SourceSummary(id(d.sourceId()), r.sourceName(), null), r.tags(),
                d.virtual(), onboarding.complete(), r.metricKeys(), meta(d.sourceMeta()), id(d.suggestedSpaceId()), d.autoRegistered(),
                d.version(), d.createdAt());
    }

    /** API-DEV-23 상세 */
    public DeviceDetailResponse detail(Device d) {
        long org = d.organizationId();
        ModelRef model = d.modelId() == null ? null : refs.findModel(org, d.modelId()).orElse(null);
        SourceRef source = refs.findSource(org, d.sourceId()).orElse(null);
        Set<Long> spaceIds = new LinkedHashSet<>();
        if (d.spaceId() != null) {
            spaceIds.add(d.spaceId());
        }
        Map<Long, List<String>> paths = refs.findSpacePathNames(org, spaceIds);
        StateRow state = queries.findState(org, d.id()).orElse(null);
        List<LatestValue> latest = latest(org, state);
        DeviceRules.Effective effective = DeviceRules.effective(d.expectedIntervalSec(), d.offlineMultiplier(),
                model == null ? null : model.defaultIntervalSec(), model == null ? null : model.defaultOfflineMultiplier());
        List<RelationView> relations = new ArrayList<>();
        if (d.spaceId() != null) {
            for (String relation : DeviceRules.autoRelations(d.kind())) {
                relations.add(new RelationView(id(d.spaceId()), relation, true));
            }
        }
        for (Object[] rel : queries.findRelations(org, d.id())) {
            relations.add(new RelationView(id((Long) rel[0]), (String) rel[1], false));
        }
        boolean firstData = (state != null && state.lastSeenAt() != null) || d.firstSeenAt() != null;
        DeviceRules.Onboarding o = DeviceRules.onboarding(firstData, d.modelId() != null, d.spaceId() != null, d.kind());
        List<GroupRef> groups = queries.findGroups(org, d.id()).stream().map(g -> new GroupRef(id((Long) g[0]), (String) g[1])).toList();
        JsonNode meta = meta(d.sourceMeta());
        LorawanView lorawan = source != null && "chirpstack-v4".equals(source.decoderKey())
                ? new LorawanView(d.externalId(), text(meta, "joinEui"), text(meta, "applicationId"), text(meta, "deviceProfileId"))
                : null;
        StateView stateView = state == null ? new StateView("UNKNOWN", null, null, null, null, null, null, null)
                : new StateView(state.connectivity(), state.lastSeenAt(), state.lastMeasuredAt(), state.battery(), state.rssi(),
                state.snr(), state.bestGatewayEui(), state.msgCount24h());
        return new DeviceDetailResponse(id(d.id()), d.name(), d.externalId(), d.kind(), d.status(), d.virtual(), d.version(),
                source == null ? null : new SourceSummary(id(source.id()), source.name(), source.type()),
                model == null ? null : new ModelDetail(id(model.id()), model.code(), model.name(), model.vendor(), model.kind(), List.of()),
                d.spaceId() == null ? null : space(d.spaceId(), paths), stateView, latest,
                new EffectiveView(effective.expectedIntervalSec(), effective.offlineMultiplier(), effective.inheritedFrom()), relations,
                new OnboardingView(o.firstData(), o.model(), o.space(), o.decodeOk(), o.rulesApplied(), o.complete()),
                devices.findTags(org, d.id()), groups, id(d.logicalDeviceId()), id(d.replacedByDeviceId()), lorawan, meta,
                d.expectedIntervalSec(), d.offlineMultiplier(), d.firstSeenAt(), id(d.suggestedSpaceId()), d.autoRegistered(),
                d.approvedAt(), d.createdAt(), d.updatedAt());
    }

    /** device_state.latest {@code {key:{v,t,q}}} → 최근값 목록(키 순) */
    List<LatestValue> latest(long organizationId, StateRow state) {
        if (state == null || state.latest() == null) {
            return List.of();
        }
        JsonNode node = json.readTree(state.latest());
        List<String> keys = new ArrayList<>(node.propertyNames());
        keys.sort(String::compareTo);
        Map<String, String[]> labels = queries.findMetricLabels(organizationId, keys);
        List<LatestValue> result = new ArrayList<>();
        for (String key : keys) {
            JsonNode v = node.get(key);
            String[] label = labels.get(key);
            JsonNode value = v.get("v");
            JsonNode q = v.get("q");
            result.add(new LatestValue(key, label == null ? null : label[0], label == null ? null : label[1],
                    value != null && value.isNumber() ? value.doubleValue() : null, instant(text(v, "t")),
                    q != null && q.isNumber() ? q.intValue() : null));
        }
        return result;
    }

    /** 수신한 측정 항목 키(최근값 + 자동 등록 때 받은 키) */
    public List<String> metricKeys(Device d) {
        Set<String> keys = new LinkedHashSet<>();
        queries.findState(d.organizationId(), d.id()).filter(s -> s.latest() != null)
                .ifPresent(s -> keys.addAll(json.readTree(s.latest()).propertyNames()));
        JsonNode metrics = meta(d.sourceMeta()).get("metrics");
        if (metrics != null && metrics.isArray()) {
            metrics.values().forEach(m -> {
                if (m.isString()) {
                    keys.add(m.stringValue());
                }
            });
        }
        return List.copyOf(keys);
    }

    JsonNode meta(String sourceMeta) {
        return json.readTree(sourceMeta == null ? "{}" : sourceMeta);
    }

    private static SpaceSummary space(Long spaceId, Map<Long, List<String>> paths) {
        List<String> path = paths.getOrDefault(spaceId, List.of());
        return new SpaceSummary(id(spaceId), path.isEmpty() ? null : path.get(path.size() - 1), path);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isString() ? v.stringValue() : null;
    }

    private static Instant instant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    static String id(Long value) {
        return value == null ? null : Objects.toString(value);
    }
}
