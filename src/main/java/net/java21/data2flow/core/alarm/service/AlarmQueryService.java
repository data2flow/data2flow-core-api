package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.dto.AlarmDtos;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AlarmDetail;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AlarmStats;
import net.java21.data2flow.core.alarm.repository.AlarmEventRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.alarm.repository.AlarmStatsRepository;
import net.java21.data2flow.core.common.CountedListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 알람 조회(API-RUL-10·11·20). ALARM_READ(통계는 RULE_READ), 공간 범위 필터(IAM-04.05): 범위가 제한된 사용자는 범위 공간 아래의 알람만 본다.
 * 남의 것·범위 밖은 404 ALARM_NOT_FOUND.
 */
@Service
public class AlarmQueryService {

    static final Set<String> STATUSES = Set.of("ACTIVE", "ACKNOWLEDGED", "SUPPRESSED", "CLEARED");
    static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    static final Set<String> SOURCE_TYPES = Set.of("RULE", "FLOW", "SYSTEM");
    static final List<String> DEFAULT_STATUSES = List.of("ACTIVE", "ACKNOWLEDGED", "SUPPRESSED");

    private final AlarmRepository alarms;
    private final AlarmEventRepository events;
    private final AlarmStatsRepository stats;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;

    public AlarmQueryService(AlarmRepository alarms, AlarmEventRepository events, AlarmStatsRepository stats, RoleChecker roleChecker,
                             JsonMapper json, Clock clock) {
        this.alarms = alarms;
        this.events = events;
        this.stats = stats;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
    }

    /** 목록 조건(쿼리 문자열 그대로) */
    public record Query(String status, String severity, String spaceId, String ruleId, String sourceType, String deviceId, Instant from,
                        Instant to, Integer page, Integer size) {
    }

    @Transactional(readOnly = true)
    public CountedListResponse<AlarmDtos.Alarm> list(Query q) {
        roleChecker.require(Permission.ALARM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        List<String> statuses = q.status() == null || q.status().isBlank() ? DEFAULT_STATUSES : split(q.status(), STATUSES, "status");
        List<String> severities = q.severity() == null || q.severity().isBlank() ? null : split(q.severity(), SEVERITIES, "severity");
        String prefix = null;
        if (q.spaceId() != null && !q.spaceId().isBlank()) {
            long space = id(q.spaceId(), "spaceId");
            // 범위 밖 공간을 걸러도 범위 경로 조건(scopePaths)이 함께 걸려 범위 안 알람만 나온다
            prefix = alarms.findSpacePath(orgId, space).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        }
        String source = q.sourceType() == null || q.sourceType().isBlank() ? null : one(q.sourceType(), SOURCE_TYPES, "sourceType");
        AlarmRepository.Search search = new AlarmRepository.Search(orgId, statuses, severities, prefix,
                q.ruleId() == null || q.ruleId().isBlank() ? null : id(q.ruleId(), "ruleId"), source, q.from(), q.to(), scopePaths(orgId),
                q.deviceId() == null || q.deviceId().isBlank() ? null : id(q.deviceId(), "deviceId"), null);
        PageParams params = PageParams.of(q.page(), q.size());
        List<AlarmDtos.Alarm> items = alarms.search(search, params.size(), params.offset()).stream().map(a -> AlarmViews.view(a, json)).toList();
        return CountedListResponse.of(ListApiResponse.of(params, items, alarms.count(search)), alarms.countByStatusAndSeverity(search));
    }

    /** API-RUL-11 상세: 타임라인(시각 순), 하위 알람, 차트 구간(발생 1시간 전 ~ 해제 또는 지금) */
    @Transactional(readOnly = true)
    public AlarmDetail detail(long alarmId) {
        roleChecker.require(Permission.ALARM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        AlarmRow a = visible(orgId, alarmId);
        List<AlarmDtos.TimelineEvent> timeline = new ArrayList<>();
        for (AlarmEventRepository.EventRow e : events.listByAlarm(orgId, alarmId)) {
            Map<String, Object> data = e.data() == null ? null : json.readValue(e.data(), new TypeReference<Map<String, Object>>() { });
            timeline.add(new AlarmDtos.TimelineEvent(Long.toString(e.id()), e.type(), e.at(),
                    new AlarmDtos.Actor(e.actorType(), e.actorId() == null ? null : Long.toString(e.actorId()), e.actorName()), data));
        }
        List<AlarmDtos.Alarm> children = alarms.search(new AlarmRepository.Search(orgId, null, null, null, null, null, null, null, null,
                null, alarmId), 200, 0).stream().map(c -> AlarmViews.view(c, json)).toList();
        Instant end = a.clearedAt() == null ? clock.instant() : a.clearedAt();
        AlarmDtos.Chart chart = a.metricKey() == null ? null : new AlarmDtos.Chart(a.metricKey(), a.raisedAt().minus(Duration.ofHours(1)), end);
        return new AlarmDetail(AlarmViews.view(a, json), timeline, children, chart);
    }

    /** API-RUL-20 — RULE_READ. 기간 기본 최근 7일 */
    @Transactional(readOnly = true)
    public AlarmStats stats(Instant from, Instant to, String spaceId) {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (!start.isBefore(end) || Duration.between(start, end).toDays() > 366) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("from", "Range", null)));
        }
        String prefix = spaceId == null || spaceId.isBlank() ? null
                : alarms.findSpacePath(orgId, id(spaceId, "spaceId")).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return stats.compute(orgId, start, end, prefix, scopePaths(orgId), zone(orgId));
    }

    /** 보이는 알람(남의 것·범위 밖은 404) */
    public AlarmRow visible(long orgId, long alarmId) {
        AlarmRow a = alarms.findById(orgId, alarmId).orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
        if (!visibleTo(roleChecker.spaceScope(), a)) {
            throw new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND);
        }
        return a;
    }

    /** 범위가 제한된 사용자는 공간이 범위 안인 알람만(공간 없는 알람은 보이지 않음) */
    public static boolean visibleTo(SpaceScope scope, AlarmRow a) {
        return scope.unrestricted() || (a.spaceId() != null && scope.includes(a.spaceId()));
    }

    /** 사용자 공간 범위의 경로(제한 없으면 null) */
    Collection<String> scopePaths(long orgId) {
        SpaceScope scope = roleChecker.spaceScope();
        return scope.unrestricted() ? null : alarms.listSpacePaths(orgId, scope.allowedSpaceIds());
    }

    ZoneId zone(long orgId) {
        try {
            return ZoneId.of(stats.findTimezone(orgId));
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    static List<String> split(String raw, Set<String> allowed, String field) {
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            if (!p.isBlank()) {
                out.add(one(p, allowed, field));
            }
        }
        return out;
    }

    public static String one(String raw, Set<String> allowed, String field) {
        String v = raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Pattern", null)));
        }
        return v;
    }

    public static long id(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Pattern", null)));
        }
    }
}
