package net.java21.data2flow.core.telemetry.service;

import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.core.telemetry.repository.TelemetryHistoryRepository;
import net.java21.data2flow.core.telemetry.repository.TelemetryHistoryRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 과거 텔레메트리를 표준 텔레메트리(CanonicalTelemetry) 모양으로(API-FLW-87, ADR-051). flow-engine 과거 재생(API-FLW-13)과 시험 실행의
 * {@code rawMessageId} 변환(API-FLW-12)이 쓴다. 메시지 ID는 (조직, 기기, 측정 시각)에서 정해지는 이름 기반 UUID라 다시 읽어도 같다.
 * 원본 메시지 ID가 없는 행(가상·파생)은 {@code rawMessageId=1}로 채운다(드라이런 전용이라 원본으로 따라가지 않는다).
 */
@Service
public class TelemetryHistoryService {

    public static final Duration MAX_RANGE = Duration.ofDays(7);
    public static final int MAX_SIZE = 1000;

    private final TelemetryHistoryRepository history;

    public TelemetryHistoryService(TelemetryHistoryRepository history) {
        this.history = history;
    }

    public record Page(List<CanonicalTelemetry> items, long totalCount, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public Page page(long organizationId, Instant from, Instant to, List<Long> deviceIds, String cursor, int size) {
        Long afterDevice = null;
        Instant afterTime = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
            afterTime = Instant.ofEpochSecond(0, 0).plusNanos(Long.parseLong(parts[0]) * 1000L);
            afterDevice = Long.parseLong(parts[1]);
        }
        List<Row> rows = history.page(organizationId, from, to, deviceIds, afterTime, afterDevice, size + 1);
        boolean more = rows.size() > size;
        List<Row> pageRows = more ? rows.subList(0, size) : rows;
        List<CanonicalTelemetry> items = new ArrayList<>();
        for (Row r : pageRows) {
            items.add(toTelemetry(organizationId, r));
        }
        String next = null;
        if (more) {
            Row last = pageRows.getLast();
            long micros = last.time().getEpochSecond() * 1_000_000L + last.time().getNano() / 1000;
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + last.deviceId()).getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, cursor == null || cursor.isBlank() ? history.count(organizationId, from, to, deviceIds) : -1, next);
    }

    /** 원본 메시지 → 표준 텔레메트리(첫 메시지). 없으면 빈 값 */
    @Transactional(readOnly = true)
    public java.util.Optional<CanonicalTelemetry> fromRawMessage(long organizationId, long rawMessageId) {
        return history.findByRawMessage(organizationId, rawMessageId).stream().findFirst().map(r -> toTelemetry(organizationId, r));
    }

    static CanonicalTelemetry toTelemetry(long organizationId, Row r) {
        List<CanonicalTelemetry.Metric> metrics = new ArrayList<>();
        for (String line : r.metrics()) {
            String[] p = line.split("\\|", -1);
            metrics.add(new CanonicalTelemetry.Metric(p[0], Double.parseDouble(p[1]), p[3].isEmpty() ? null : p[3], Integer.parseInt(p[2]), null));
        }
        UUID messageId = UUID.nameUUIDFromBytes((organizationId + ":" + r.deviceId() + ":" + r.time()).getBytes(StandardCharsets.UTF_8));
        return new CanonicalTelemetry(CanonicalTelemetry.VERSION, messageId, organizationId, r.sourceId(), r.externalId(), r.deviceId(),
                CanonicalTelemetry.DeviceStatus.valueOf(r.deviceStatus()), r.modelId() == null ? null : Long.toString(r.modelId()), r.spaceId(),
                r.time(), r.receivedAt() == null ? r.time() : r.receivedAt(), false, r.virtual(), metrics, null, null,
                r.rawMessageId() == null || r.rawMessageId() < 1 ? 1 : r.rawMessageId());
    }
}
