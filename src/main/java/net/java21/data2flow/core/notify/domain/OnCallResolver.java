package net.java21.data2flow.core.notify.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 현재 당직자(RUL-05.03, BR-RUL-19): 대체 근무 > 주간 교대 순서. 비어 있으면 빈 값(호출자가 정책의 다른 수신자·조직 ADMIN으로).
 */
public final class OnCallResolver {

    private OnCallResolver() {
    }

    public record Shift(int dayOfWeek, LocalTime from, LocalTime to, long userId) {
    }

    public record Override(Instant startsAt, Instant endsAt, long originalUserId, long substituteUserId) {
    }

    /** 결과: 당직자와 그 근무가 끝나는 시각(모르면 null) */
    public record Current(long userId, Instant until, boolean substitute) {
    }

    public static Optional<Current> resolve(List<Shift> shifts, List<Override> overrides, Instant at, ZoneId zone) {
        LocalDateTime local = at.atZone(zone).toLocalDateTime();
        Shift shift = null;
        for (Shift s : shifts) {
            if (new TimeWindow(Set.of(s.dayOfWeek()), s.from(), s.to()).contains(local)) {
                shift = s;
                break;
            }
        }
        for (Override o : overrides) {
            if (!at.isBefore(o.startsAt()) && at.isBefore(o.endsAt())
                    && (shift == null || shift.userId() == o.originalUserId())) {
                return Optional.of(new Current(o.substituteUserId(), o.endsAt(), true));
            }
        }
        if (shift == null) {
            return Optional.empty();
        }
        return Optional.of(new Current(shift.userId(), shiftEnd(shift, local, zone), false));
    }

    static Instant shiftEnd(Shift s, LocalDateTime local, ZoneId zone) {
        if (s.to() == null) {
            return null;
        }
        LocalDateTime end = local.toLocalDate().atTime(s.to());
        if (!end.isAfter(local)) {
            end = end.plusDays(1);
        }
        return end.atZone(zone).toInstant();
    }
}
