package net.java21.data2flow.core.calendar.service;

import net.java21.data2flow.contracts.message.event.CalendarSynced;
import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.domain.CalendarModels.EventRow;
import net.java21.data2flow.core.calendar.repository.CalendarEventRepository;
import net.java21.data2flow.core.calendar.repository.CalendarEventRepository.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 외부 달력 반영(BR-DSC-18, DSC-06.03·06.04 → DEV-12.01). 공휴일·iCal 동기화 결과와 ingress의 EVT-DSC-07 {@code calendar.synced}가 같은 규칙으로 들어온다.
 * <ul>
 *   <li>UID로 중복을 막는다: {@code origin_uid = s{sourceId}:{UID}}(200자를 넘으면 SHA-256)</li>
 *   <li>새 UID → 추가, 바뀐 일정 → 갱신. 사람이 고친(locally_modified) 일정은 원본 값으로 덮지 않는다</li>
 *   <li>원본에서 사라진 UID → 삭제. 사람이 고친 일정은 남기고 "원본 삭제됨"(origin_deleted_at) 표시</li>
 *   <li>적용 범위는 소스의 사이트, 운영 모드 영향은 유형 기본값(HOLIDAY·CLOSURE → HOLIDAY, VACATION → UNOCCUPIED)</li>
 * </ul>
 */
@Service
public class CalendarSyncService {

    private final CalendarEventRepository events;
    private final Clock clock;

    public CalendarSyncService(CalendarEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** 반영 결과(+추가 ~갱신 −삭제) */
    public record Result(int added, int updated, int removed) {
    }

    /**
     * @param origin HOLIDAY_API 또는 ICAL
     * @param scope  적용 범위(소스의 사이트, 없으면 조직 전체)
     */
    @Transactional
    public Result apply(long organizationId, long sourceId, String origin, List<Long> scope, List<CalendarSynced.Event> incoming,
                        List<String> removedUids) {
        Instant now = clock.instant();
        int added = 0;
        int updated = 0;
        int removed = 0;
        for (CalendarSynced.Event e : incoming) {
            String type = e.type() == null ? "EVENT" : e.type().toUpperCase(Locale.ROOT);
            if (!CalendarModels.TYPES.contains(type)) {
                type = "OTHER";
            }
            String title = e.title() == null || e.title().isBlank() ? "(제목 없음)" : e.title().strip();
            if (title.length() > 150) {
                title = title.substring(0, 150);
            }
            String uid = originUid(sourceId, e.uid());
            String affects = CalendarModels.defaultAffects(type);
            var existing = events.findByOriginUid(organizationId, origin, uid);
            if (existing.isEmpty()) {
                events.insert(organizationId, new Values(title, type, e.startsOn(), e.endsOn(), e.startTime(), e.endTime(), scope, affects),
                        origin, uid, sourceId, null, now);
                added++;
                continue;
            }
            EventRow row = existing.get();
            if (row.locallyModified()) {
                events.clearOriginDeleted(organizationId, row.id(), now);
                continue;
            }
            if (!same(row, title, type, e, scope) || row.originDeletedAt() != null) {
                events.updateFromOrigin(organizationId, row.id(), title, type, e.startsOn(), e.endsOn(), e.startTime(), e.endTime(), scope,
                        affects, now);
                updated++;
            }
        }
        for (String raw : removedUids) {
            var existing = events.findByOriginUid(organizationId, origin, originUid(sourceId, raw));
            if (existing.isEmpty()) {
                continue;
            }
            EventRow row = existing.get();
            if (row.locallyModified()) {
                events.markOriginDeleted(organizationId, row.id(), now);
            } else {
                events.delete(organizationId, row.id());
            }
            removed++;
        }
        return new Result(added, updated, removed);
    }

    /**
     * 전체 목록 동기화(core가 직접 받은 공휴일·iCal): 이 소스가 만든 일정 중 받은 목록에 없는 것을 지운다(사람이 고친 것은 "원본 삭제됨").
     *
     * @param inRange 비교 대상인 기존 일정(공휴일은 받은 해의 일정만)
     */
    @Transactional
    public int removeMissing(long organizationId, long sourceId, Collection<String> incomingUids, Predicate<EventRow> inRange) {
        Set<String> keep = new HashSet<>();
        incomingUids.forEach(u -> keep.add(originUid(sourceId, u)));
        Instant now = clock.instant();
        int removed = 0;
        for (EventRow row : events.listBySource(organizationId, sourceId)) {
            if (row.originUid() == null || keep.contains(row.originUid()) || !inRange.test(row)) {
                continue;
            }
            if (row.locallyModified()) {
                if (row.originDeletedAt() == null) {
                    events.markOriginDeleted(organizationId, row.id(), now);
                    removed++;
                }
            } else {
                events.delete(organizationId, row.id());
                removed++;
            }
        }
        return removed;
    }

    private static boolean same(EventRow row, String title, String type, CalendarSynced.Event e, List<Long> scope) {
        return row.title().equals(title) && row.type().equals(type) && row.startsOn().equals(e.startsOn()) && row.endsOn().equals(e.endsOn())
                && Objects.equals(row.startTime(), e.startTime()) && Objects.equals(row.endTime(), e.endTime())
                && row.scopeSpaceIds().equals(scope);
    }

    static String originUid(long sourceId, String uid) {
        String v = "s" + sourceId + ":" + uid;
        if (v.length() <= 200) {
            return v;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uid.getBytes(StandardCharsets.UTF_8));
            return "s" + sourceId + ":sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
