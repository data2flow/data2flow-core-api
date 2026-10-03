package net.java21.data2flow.core.audit.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.audit.AuditCause;
import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.audit.AuditResult;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.contracts.web.CursorParams;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.AuditLogDetailResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.AuditLogSummaryResponse;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.CreateAuditLogRequest;
import net.java21.data2flow.core.audit.dto.AuditLogDtos.ExportAuditLogsRequest;
import net.java21.data2flow.core.audit.repository.AuditLogRepository;
import net.java21.data2flow.core.audit.repository.AuditLogRepository.AuditLogRow;
import net.java21.data2flow.core.audit.repository.AuditLogRepository.AuditSearch;
import net.java21.data2flow.core.common.Pg;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 감사 로그 검색·상세·CSV 내보내기·외부 기록 접수(IAM-06.01~06.04, NFR-12.02). 조회는 ADMIN(AUDIT_READ)만 한다.
 */
@Service
public class AuditLogService {

    /** IAM-06.03 "기간 최대 1년" */
    static final Duration MAX_RANGE = Duration.ofDays(366);
    static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    /** 동기 CSV 내보내기 상한(행) */
    static final int EXPORT_MAX_ROWS = 50_000;
    private static final Set<String> ACTOR_TYPES = Set.of("USER", "SERVICE_ACCOUNT", "FLOW", "SERVICE", "SYSTEM");
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILURE", "DENIED");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final AuditLogRepository repository;
    private final RoleChecker roleChecker;
    private final AuditRecorder recorder;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AuditLogService(AuditLogRepository repository, RoleChecker roleChecker, AuditRecorder recorder, Audits audits,
                           JsonMapper json, Clock clock) {
        this.repository = repository;
        this.roleChecker = roleChecker;
        this.recorder = recorder;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-IAM-50 커서 목록. 최신순 */
    @Transactional(readOnly = true)
    public CursorListApiResponse<AuditLogSummaryResponse> search(Filter filter, String cursor, Integer size) {
        roleChecker.require(Permission.AUDIT_READ);
        long orgId = roleChecker.currentUser().organizationId();
        CursorParams params = CursorParams.of(cursor, size);
        AuditSearch search = toSearch(orgId, filter, params.cursor());
        List<AuditLogRow> rows = repository.search(search, params.fetchSize());
        boolean hasNext = rows.size() > params.size();
        List<AuditLogRow> page = hasNext ? rows.subList(0, params.size()) : rows;
        String next = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
        return CursorListApiResponse.of(params.size(), page.stream().map(AuditLogService::toSummary).toList(), next);
    }

    /** API-IAM-52 상세. 다른 조직 기록은 404 */
    @Transactional(readOnly = true)
    public AuditLogDetailResponse detail(long auditLogId) {
        roleChecker.require(Permission.AUDIT_READ);
        long orgId = roleChecker.currentUser().organizationId();
        AuditLogRow row = repository.findByIdAndOrganizationId(auditLogId, orgId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return toDetail(row);
    }

    /**
     * API-IAM-51 CSV 내보내기(IAM-06.03). 비동기 작업과 다운로드 링크(문서)는 파일 저장소가 생기는 마일스톤으로 미루고,
     * 지금은 같은 조건의 CSV(최대 5만 행)를 바로 돌려준다. 내보내기 자체를 감사 {@code AUDIT_EXPORTED}로 남긴다.
     */
    @Transactional
    public String exportCsv(ExportAuditLogsRequest req) {
        roleChecker.require(Permission.AUDIT_READ);
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        Filter filter = new Filter(req.from(), req.to(), req.actorType(), req.actor(), req.action(), req.targetType(),
                req.targetId(), req.result(), req.ip());
        List<AuditLogRow> rows = repository.search(toSearch(user.organizationId(), filter, null), EXPORT_MAX_ROWS + 1);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        List<AuditLogRow> limited = truncated ? rows.subList(0, EXPORT_MAX_ROWS) : rows;
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("id,occurredAt,actorType,actorId,actorName,action,targetType,targetId,result,ip,requestId,detail\r\n");
        for (AuditLogRow r : limited) {
            csv.append(String.join(",", cell(Long.toString(r.id())), cell(r.occurredAt().toString()), cell(r.actorType()),
                    cell(r.actorId()), cell(r.actorName()), cell(r.action()), cell(r.targetType()), cell(r.targetId()),
                    cell(r.result()), cell(r.ip()), cell(r.requestId()), cell(r.detail()))).append("\r\n");
        }
        audits.record(audits.event(user.organizationId(), AuditCodes.AUDIT_EXPORTED).actor(user)
                .target("AUDIT_LOG", null)
                .detail("rows", limited.size()).detail("truncated", truncated)
                .detail("filter", filterDetail(filter)));
        return csv.toString();
    }

    /**
     * API-IAM-39: 다른 서비스의 감사 기록을 받아 저장한다. organizationId가 없으면 사용자를 대신한 호출의 X-ORG-ID를 쓴다.
     * 비밀값은 {@link AuditEvent}가 만들 때 가린다(BR-IAM-21).
     */
    public void recordExternal(CreateAuditLogRequest req, String callerService) {
        Long orgId = req.organizationId() != null ? req.organizationId()
                : CurrentUserHolder.find().map(CurrentUser::organizationId).orElse(null);
        if (orgId == null || orgId <= 0) {
            throw invalid("organizationId", "NotNull");
        }
        AuditActorType actorType = parse(req.actorType(), "actorType", AuditActorType.class);
        AuditResult result = parse(req.result(), "result", AuditResult.class);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (req.detail() != null) {
            detail.putAll(req.detail());
        }
        if (callerService != null && !callerService.isBlank()) {
            detail.put("callerService", callerService);
        }
        AuditCause cause = req.cause() == null ? null
                : new AuditCause(req.cause().flowId(), req.cause().flowVersion(), req.cause().nodeId(), req.cause().triggerMessageId());
        AuditEvent.Builder builder = AuditEvent.builder(orgId, req.action())
                .occurredAt(req.occurredAt() == null ? clock.instant() : req.occurredAt())
                .actor(actorType, req.actorId(), req.actorName())
                .target(req.targetType(), req.targetId())
                .result(result).detail(detail).cause(cause).ip(req.ip()).userAgent(req.userAgent());
        if (req.requestId() != null) {
            builder.requestId(req.requestId());
        }
        recorder.record(builder.build());
    }

    private AuditSearch toSearch(long orgId, Filter f, String cursor) {
        Instant to = f.to() == null ? clock.instant().plusSeconds(1) : f.to();
        Instant from = f.from() == null ? to.minus(DEFAULT_RANGE) : f.from();
        if (!from.isBefore(to)) {
            throw invalid("from", "INVALID_RANGE");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw invalid("from", "RANGE_TOO_LONG");
        }
        String actorType = upperOrNull(f.actorType());
        if (actorType != null && !ACTOR_TYPES.contains(actorType)) {
            throw invalid("actorType", "INVALID");
        }
        String result = upperOrNull(f.result());
        if (result != null && !RESULTS.contains(result)) {
            throw invalid("result", "INVALID");
        }
        String ip = blankToNull(f.ip());
        if (ip != null && Pg.inetOrNull(ip) == null) {
            throw invalid("ip", "INVALID");
        }
        Instant cOccurredAt = null;
        Long cId = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
                String[] time = parts[0].split("\\.");
                cOccurredAt = Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1]));
                cId = Long.parseLong(parts[1]);
            } catch (RuntimeException ex) {
                throw invalid("cursor", "INVALID");
            }
        }
        return new AuditSearch(orgId, from, to, actorType, blankToNull(f.actor()), upperOrNull(f.action()),
                blankToNull(f.targetType()), blankToNull(f.targetId()), result, ip, cOccurredAt, cId);
    }

    static String encodeCursor(AuditLogRow row) {
        String raw = row.occurredAt().getEpochSecond() + "." + row.occurredAt().getNano() + ":" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static AuditLogSummaryResponse toSummary(AuditLogRow r) {
        return new AuditLogSummaryResponse(Long.toString(r.id()), r.occurredAt(), r.actorType(), r.actorId(), r.actorName(),
                r.action(), r.targetType(), r.targetId(), r.result(), r.ip(), r.requestId());
    }

    private AuditLogDetailResponse toDetail(AuditLogRow r) {
        Map<String, Object> cause = null;
        if (r.cause() != null) {
            cause = new LinkedHashMap<>(json.readValue(r.cause(), MAP));
            Object flowId = cause.get("flowId");
            if (flowId != null) {
                String url = "/flows/" + flowId + "/executions";
                Object trigger = cause.get("triggerMessageId");
                cause.put("executionUrl", trigger == null ? url : url + "?triggerMessageId=" + trigger);
            }
        }
        Map<String, Object> detail = r.detail() == null ? null : json.readValue(r.detail(), MAP);
        return new AuditLogDetailResponse(Long.toString(r.id()), r.occurredAt(), r.actorType(), r.actorId(), r.actorName(),
                r.action(), r.targetType(), r.targetId(), r.result(), detail, cause, r.ip(), r.userAgent(), r.requestId());
    }

    /** CSV 셀: 큰따옴표로 감싸고, 수식으로 해석될 수 있는 첫 글자는 작은따옴표로 막는다(CSV 주입 방지) */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static Map<String, Object> filterDetail(Filter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f.from() != null) m.put("from", f.from().toString());
        if (f.to() != null) m.put("to", f.to().toString());
        if (f.actorType() != null) m.put("actorType", f.actorType());
        if (f.actor() != null) m.put("actor", f.actor());
        if (f.action() != null) m.put("action", f.action());
        if (f.targetType() != null) m.put("targetType", f.targetType());
        if (f.targetId() != null) m.put("targetId", f.targetId());
        if (f.result() != null) m.put("result", f.result());
        return m;
    }

    private static <E extends Enum<E>> E parse(String raw, String field, Class<E> type) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw invalid(field, "INVALID");
        }
    }

    private static String upperOrNull(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    /** 목록·내보내기 조건 */
    public record Filter(Instant from, Instant to, String actorType, String actor, String action, String targetType,
                         String targetId, String result, String ip) {
    }
}
