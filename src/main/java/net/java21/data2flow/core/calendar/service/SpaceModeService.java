package net.java21.data2flow.core.calendar.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SpaceModeChanged;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.ModeView;
import net.java21.data2flow.core.calendar.repository.SpaceModeRepository;
import net.java21.data2flow.core.calendar.repository.SpaceModeRepository.ModeState;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceMode;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.service.SpaceModes;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 공간 운영 모드(DEV-11.02, BR-DEV-23).
 * <ul>
 *   <li>{@link #override}: API-DEV-08 POST 수동 지정(mode=null이면 해제, until 선택) — DEV_PLACE(OPERATOR+)</li>
 *   <li>{@link #tick}: 1분 작업. 조직의 ACTIVE 공간 모드를 계산해 마지막 값과 다르면 EVT-DEV-06 {@code space.mode.changed}를 낸다
 *       (처음 계산하면 from=null로 한 번). 판단 근거는 SCHEDULE·CALENDAR·MAINTENANCE·MANUAL(수동 지정)</li>
 * </ul>
 */
@Service
public class SpaceModeService {

    static final Set<String> MODES = Set.of("OCCUPIED", "UNOCCUPIED", "HOLIDAY", "MAINTENANCE");

    private final SpaceSupport support;
    private final SpaceRepository spaces;
    private final SpaceModes modes;
    private final SpaceModeRepository states;
    private final CoreEventPublisher publisher;
    private final Clock clock;

    public SpaceModeService(SpaceSupport support, SpaceRepository spaces, SpaceModes modes, SpaceModeRepository states,
                            CoreEventPublisher publisher, Clock clock) {
        this.support = support;
        this.spaces = spaces;
        this.modes = modes;
        this.states = states;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** API-DEV-08 POST {@code {mode|null, until?}} */
    @Transactional
    public ModeView override(long spaceId, JsonNode body) {
        Space space = support.locked(spaceId, Permission.DEV_PLACE);
        CurrentUser user = support.user();
        Instant now = clock.instant();
        String mode = null;
        if (body != null && body.hasNonNull("mode")) {
            mode = body.get("mode").asString("").toUpperCase(Locale.ROOT);
            if (!MODES.contains(mode)) {
                throw invalid("mode");
            }
        }
        Instant until = null;
        if (mode != null && body.hasNonNull("until")) {
            try {
                until = Instant.parse(body.get("until").asString(""));
            } catch (DateTimeParseException ex) {
                throw invalid("until");
            }
            if (!until.isAfter(now)) {
                throw invalid("until");
            }
        }
        states.updateOverride(space.organizationId(), space.id(), mode, until, user.userId(), now);
        support.audit("SPACE_MODE_OVERRIDDEN", space.id(), mode == null ? Map.of("mode", "CLEARED")
                : until == null ? Map.of("mode", mode) : Map.of("mode", mode, "until", until.toString()));
        Space updated = spaces.findById(space.organizationId(), space.id()).orElseThrow();
        SpaceMode m = modes.modeOf(support.chain(updated), now);
        return new ModeView(m.mode(), m.source(), m.until(), m.nextChangeAt());
    }

    /** 1분 작업 한 조직. 바뀐 공간 수 */
    @Transactional
    public int tick(long organizationId) {
        Instant now = clock.instant();
        List<Space> active = spaces.listActive(organizationId);
        Map<Long, SpaceMode> current = modes.modesOf(organizationId, active, now);
        Map<Long, ModeState> last = new HashMap<>(states.listStates(organizationId));
        int changed = 0;
        for (Map.Entry<Long, SpaceMode> e : current.entrySet()) {
            SpaceMode m = e.getValue();
            String source = eventSource(m.source());
            ModeState before = last.get(e.getKey());
            if (before != null && before.mode().equals(m.mode()) && before.source().equals(source)) {
                continue;
            }
            boolean modeChanged = before == null || !before.mode().equals(m.mode());
            states.upsertState(organizationId, e.getKey(), m.mode(), source, modeChanged, now);
            if (modeChanged) {
                publisher.event(EventType.SPACE_MODE_CHANGED, organizationId,
                        new SpaceModeChanged(e.getKey(), before == null ? null : before.mode(), m.mode(), source, now));
                changed++;
            }
        }
        return changed;
    }

    /** API 출처 이름 → 이벤트 출처 이름(수동 지정은 MANUAL) */
    static String eventSource(String apiSource) {
        return "OVERRIDE".equals(apiSource) ? "MANUAL" : apiSource;
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
