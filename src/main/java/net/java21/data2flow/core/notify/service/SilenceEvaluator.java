package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.core.notify.domain.SilenceMatcher;
import net.java21.data2flow.core.notify.domain.TimeWindow;
import net.java21.data2flow.core.notify.repository.SilenceRepository;
import net.java21.data2flow.core.notify.repository.SilenceRepository.SilenceRow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** 알람이 지금 무음인지(RUL-02.07, BR-RUL-14). 알림 요청 전에 core가, 발송 전에 action이(API-RUL-41) 같은 판정을 쓴다 */
@Component
public class SilenceEvaluator {

    private final SilenceRepository silences;
    private final JsonMapper json;

    public SilenceEvaluator(SilenceRepository silences, JsonMapper json) {
        this.silences = silences;
        this.json = json;
    }

    /** 걸린 무음 ID(첫 번째). 없으면 빈 값 */
    public Optional<Long> matching(long organizationId, SilenceMatcher.Target target, Instant at, ZoneId zone) {
        for (SilenceRow s : silences.listCurrent(organizationId, at)) {
            if (SilenceMatcher.covers(s.targetType(), s.targetId(), s.targetSpacePath(), target) && SilenceMatcher.active(period(s, json), at, zone)) {
                return Optional.of(s.id());
            }
        }
        return Optional.empty();
    }

    /** 무음 행 → 시간 조건. recurrence는 {@code {days[], from, to}} 또는 {@code {dateFrom, dateTo}} */
    public static SilenceMatcher.Period period(SilenceRow s, JsonMapper json) {
        if (!"RECURRING".equals(s.kind()) || s.recurrence() == null) {
            return new SilenceMatcher.Period(s.kind(), s.startsAt(), s.endsAt(), null, null, null);
        }
        JsonNode r = json.readTree(s.recurrence());
        if (r.hasNonNull("dateFrom") || r.hasNonNull("dateTo")) {
            return new SilenceMatcher.Period(s.kind(), null, null, null,
                    r.hasNonNull("dateFrom") ? LocalDate.parse(r.get("dateFrom").asString()) : null,
                    r.hasNonNull("dateTo") ? LocalDate.parse(r.get("dateTo").asString()) : null);
        }
        Set<Integer> days = new LinkedHashSet<>();
        for (JsonNode d : r.path("days").values()) {
            days.add(d.asInt());
        }
        return new SilenceMatcher.Period(s.kind(), null, null, new TimeWindow(days, TimeWindow.time(r.path("from").asString(null)),
                TimeWindow.time(r.path("to").asString(null))), null, null);
    }
}
