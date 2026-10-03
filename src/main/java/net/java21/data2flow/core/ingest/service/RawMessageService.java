package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.CursorParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import net.java21.data2flow.core.ingest.domain.RawCursor;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawMessageDetail;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawMessageListResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawMessageSummary;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawPayloadResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.StoredValue;
import net.java21.data2flow.core.ingest.repository.RawMessageRepository;
import net.java21.data2flow.core.ingest.repository.RawMessageRepository.RawDetailRow;
import net.java21.data2flow.core.ingest.repository.RawMessageRepository.RawFilter;
import net.java21.data2flow.core.ingest.repository.RawMessageRepository.RawSummaryRow;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 원본 메시지 조회(ING-01.01·01.02, UC-ING-06): API-ING-05 커서 목록, API-ING-06 상세, payload 원문.
 * <ul>
 *   <li>권한 INGEST_READ(OPERATOR 이상). payload 원문은 INGEST_PAYLOAD_READ(INTEGRATOR·ADMIN, BR-ING-15)일 때만 주고 열람을 감사한다
 *       (RAW_PAYLOAD_VIEWED, TC-ING-007). OPERATOR 상세는 payload=null, payloadMasked=true</li>
 *   <li>기간은 31일까지(ING_QUERY_RANGE_TOO_LARGE), 원본 보관 30일(BR-ING-14) 밖을 통째로 물으면 ING_REPROCESS_OUT_OF_RETENTION</li>
 *   <li>공간 범위(IAM-04.06): 범위 밖 기기·기기를 모르는 원본은 목록과 건수에서 빠지고, 상세는 404 ING_RAW_MESSAGE_NOT_FOUND</li>
 * </ul>
 * CSV 내보내기({@code format=csv})는 TSD-04 내보내기 작업(M5)과 함께 만든다. 지금은 400이다.
 */
@Service
public class RawMessageService {

    static final Duration MAX_SPAN = Duration.ofDays(31);
    static final Duration RETENTION = Duration.ofDays(30);
    static final String AUDIT_PAYLOAD_VIEWED = "RAW_PAYLOAD_VIEWED";
    static final Set<String> STATUSES = Set.of("RECEIVED", "OK", "DUPLICATE", "DECODE_ERROR", "SCRIPT_ERROR", "UNKNOWN_DEVICE_REJECTED",
            "INVALID", "STORE_ERROR", "PUBLISH_ERROR");

    private final RawMessageRepository repository;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public RawMessageService(RawMessageRepository repository, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-ING-05 쿼리 */
    public record RawQuery(Instant from, Instant to, List<Long> sourceIds, List<Long> deviceIds, List<String> statuses, String topicContains,
                           Boolean includeVirtual, String cursor, Integer size, String format) {
    }

    /** API-ING-05 원본 메시지 목록(커서 목록, 최근 순) */
    public RawMessageListResponse list(RawQuery q) {
        roleChecker.require(Permission.INGEST_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (q.format() != null && !q.format().isBlank() && !"json".equalsIgnoreCase(q.format().strip())) {
            throw IngestMonitorService.invalid("format");
        }
        if (q.from() == null) {
            throw IngestMonitorService.invalid("from");
        }
        if (q.to() == null || !q.from().isBefore(q.to())) {
            throw IngestMonitorService.invalid("to");
        }
        if (Duration.between(q.from(), q.to()).compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        Instant retentionStart = clock.instant().minus(RETENTION);
        if (!q.to().isAfter(retentionStart)) {
            throw new BusinessException(IngestErrorCode.ING_REPROCESS_OUT_OF_RETENTION);
        }
        List<String> statuses = q.statuses() == null ? List.of() : q.statuses().stream().filter(Objects::nonNull)
                .map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().toList();
        if (!STATUSES.containsAll(statuses)) {
            throw IngestMonitorService.invalid("status");
        }
        String topic = q.topicContains() == null ? "" : q.topicContains();
        if (topic.length() > 200) {
            throw IngestMonitorService.invalid("topicContains");
        }
        CursorParams page = CursorParams.of(q.cursor(), q.size());
        RawCursor cursor;
        try {
            cursor = page.cursor() == null ? null : RawCursor.decode(page.cursor());
        } catch (IllegalArgumentException ex) {
            throw IngestMonitorService.invalid("cursor");
        }
        Instant from = q.from().isBefore(retentionStart) ? retentionStart : q.from();
        RawFilter filter = new RawFilter(orgId, from, q.to(), nonNull(q.sourceIds()), nonNull(q.deviceIds()), statuses, topic,
                !Boolean.FALSE.equals(q.includeVirtual()), roleChecker.spaceScope());
        List<RawSummaryRow> rows = repository.findPage(filter, cursor, page.fetchSize());
        String next = null;
        if (rows.size() > page.size()) {
            rows = rows.subList(0, page.size());
            RawSummaryRow last = rows.getLast();
            next = new RawCursor(last.receivedAt(), last.id()).encode();
        }
        List<RawMessageSummary> items = rows.stream().map(r -> new RawMessageSummary(Long.toString(r.id()), r.receivedAt(),
                Long.toString(r.sourceId()), r.sourceName(), r.topic(), r.deviceId() == null ? null : r.deviceId().toString(), r.deviceName(),
                r.externalId(), r.status(), r.metricCount(), null, r.sizeBytes(), r.virtual())).toList();
        return new RawMessageListResponse(ApiHeader.success(), page.size(), items, repository.countByStatus(filter), next);
    }

    /** API-ING-06 원본 메시지 상세. payload는 INGEST_PAYLOAD_READ일 때만 */
    public RawMessageDetail detail(long id) {
        roleChecker.require(Permission.INGEST_READ);
        CurrentUser user = roleChecker.currentUser();
        RawDetailRow row = visible(user.organizationId(), id);
        boolean canPayload = roleChecker.has(Permission.INGEST_PAYLOAD_READ);
        String payload = canPayload ? payloadText(row) : null;
        if (canPayload) {
            auditPayload(user, row);
        }
        List<StoredValue> stored = row.deviceId() == null ? List.of()
                : repository.findStored(user.organizationId(), row.deviceId(), row.id()).stream()
                        .map(s -> new StoredValue(s.metricKey(), s.value(), s.unit(), s.quality(), s.late())).toList();
        Object trace = readJson(row.processingTrace());
        Object canonical = null;
        if (trace instanceof Map<?, ?> map) {
            canonical = map.get("canonical");
            trace = map.containsKey("stages") ? map.get("stages") : map.get("trace");
        }
        return new RawMessageDetail(Long.toString(row.id()), row.receivedAt(), row.processedAt(), Long.toString(row.sourceId()), row.sourceName(),
                row.sourceType(), row.topic(), row.deviceId() == null ? null : row.deviceId().toString(), row.deviceName(), row.externalId(),
                row.ingressInstance(), row.dedupKey(), payload, !canPayload, row.payloadEncoding(), row.payload().length, row.status(),
                row.errorCode(), readJson(row.errorDetail()), trace == null ? List.of() : trace, canonical, stored, row.virtual());
    }

    /** 원본 payload만(TC-ING-007). 권한이 없으면 403 ING_PAYLOAD_FORBIDDEN */
    public RawPayloadResponse payload(long id) {
        roleChecker.require(Permission.INGEST_READ);
        CurrentUser user = roleChecker.currentUser();
        RawDetailRow row = visible(user.organizationId(), id);
        if (!roleChecker.has(Permission.INGEST_PAYLOAD_READ)) {
            throw new BusinessException(IngestErrorCode.ING_PAYLOAD_FORBIDDEN);
        }
        auditPayload(user, row);
        return new RawPayloadResponse(Long.toString(row.id()), payloadText(row), row.payloadEncoding());
    }

    private RawDetailRow visible(long orgId, long id) {
        RawDetailRow row = repository.findById(orgId, id, clock.instant().minus(RETENTION))
                .orElseThrow(() -> new BusinessException(IngestErrorCode.ING_RAW_MESSAGE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        if (!scope.unrestricted() && (row.deviceId() == null || !scope.includes(row.deviceSpaceId()))) {
            throw new BusinessException(IngestErrorCode.ING_RAW_MESSAGE_NOT_FOUND);
        }
        return row;
    }

    private void auditPayload(CurrentUser user, RawDetailRow row) {
        audits.record(audits.event(user.organizationId(), AUDIT_PAYLOAD_VIEWED).actor(user).target("RAW_MESSAGE", Long.toString(row.id()))
                .detail("sourceId", row.sourceId()));
    }

    /** JSON·TEXT는 UTF-8 문자열, BINARY는 base64(API-ING-06) */
    static String payloadText(RawDetailRow row) {
        if ("BINARY".equals(row.payloadEncoding())) {
            return Base64.getEncoder().encodeToString(row.payload());
        }
        return new String(row.payload(), StandardCharsets.UTF_8);
    }

    private Object readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (JacksonException ex) {
            return raw;
        }
    }

    private static List<Long> nonNull(List<Long> ids) {
        return ids == null ? List.of() : new ArrayList<>(ids.stream().filter(Objects::nonNull).distinct().toList());
    }
}
