package net.java21.data2flow.core.output.domain;

import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.output.OutputFilter;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 테스트 발송 샘플(API-DSC-32 {@code sampleDeviceId}): 샘플 기기의 현재값({@code device_state.latest}: {@code {key: {v, t, q, unit}}})으로
 * 표준 텔레메트리 하나를 만든다. 필터의 측정 항목·품질 조건을 먼저 적용하고(AT-DSC-10.1 "co2만"), 남는 것이 없으면 모든 현재값을 쓴다
 * (연결 자체를 확인할 수 있게). 샘플 메시지 ID는 기기와 시각으로 정해지는 UUID다.
 */
public final class OutputSample {

    private OutputSample() {
    }

    /**
     * @param latest       {@code device_state.latest}를 푼 맵. 비면 샘플을 만들 수 없다(빈 값)
     * @param deviceStatus 기기 상태(PENDING·ACTIVE·INACTIVE, 그 밖은 ACTIVE)
     */
    public static java.util.Optional<CanonicalTelemetry> build(long organizationId, long sourceId, String externalId, long deviceId,
                                                                String deviceStatus, Long modelId, Long spaceId,
                                                                Map<String, Object> latest, OutputFilter filter, Instant now) {
        List<CanonicalTelemetry.Metric> all = new ArrayList<>();
        Instant measuredAt = null;
        for (Map.Entry<String, Object> e : new TreeMap<>(latest == null ? Map.<String, Object>of() : latest).entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> v) || !(v.get("v") instanceof Number value)) {
                continue;
            }
            double d = value.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || e.getKey().isBlank() || e.getKey().length() > CanonicalTelemetry.MAX_KEY_LENGTH) {
                continue;
            }
            int quality = v.get("q") instanceof Number q ? Math.max(0, Math.min(5, q.intValue())) : 0;
            String unit = v.get("unit") instanceof String u ? u : null;
            all.add(new CanonicalTelemetry.Metric(e.getKey(), d, unit, quality, null));
            Instant t = v.get("t") instanceof String ts ? parse(ts) : null;
            if (t != null && (measuredAt == null || t.isAfter(measuredAt))) {
                measuredAt = t;
            }
            if (all.size() == CanonicalTelemetry.MAX_METRICS) {
                break;
            }
        }
        if (all.isEmpty()) {
            return java.util.Optional.empty();
        }
        Instant at = measuredAt == null ? now : measuredAt;
        CanonicalTelemetry.DeviceStatus status = status(deviceStatus);
        UUID messageId = UUID.nameUUIDFromBytes(("output-test:" + deviceId + ":" + at).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CanonicalTelemetry t = new CanonicalTelemetry(CanonicalTelemetry.VERSION, messageId, organizationId, sourceId, externalId, deviceId, status,
                modelId == null ? null : Long.toString(modelId), spaceId, at, now, false, false, all, null, null, 1L);
        if (filter == null) {
            return java.util.Optional.of(t);
        }
        // 기기·그룹·공간 조건은 보지 않는다(샘플 기기를 사용자가 골랐다). 측정 항목·품질만 적용
        OutputFilter metricsOnly = new OutputFilter(null, null, null, filter.metrics(), filter.qualityMin());
        return java.util.Optional.of(metricsOnly.select(t, Set.of(), List.of()).orElse(t));
    }

    /** {@code /1/4/9/} → [1, 4, 9] */
    public static List<Long> pathIds(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return Arrays.stream(path.split("/")).filter(s -> !s.isBlank() && s.chars().allMatch(Character::isDigit)).map(Long::parseLong).toList();
    }

    static CanonicalTelemetry.DeviceStatus status(String raw) {
        try {
            return raw == null ? CanonicalTelemetry.DeviceStatus.ACTIVE : CanonicalTelemetry.DeviceStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CanonicalTelemetry.DeviceStatus.ACTIVE;
        }
    }

    private static Instant parse(String ts) {
        try {
            return Instant.parse(ts);
        } catch (DateTimeParseException ex) {
            try {
                return java.time.OffsetDateTime.parse(ts).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
