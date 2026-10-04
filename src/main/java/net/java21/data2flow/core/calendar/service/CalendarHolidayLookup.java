package net.java21.data2flow.core.calendar.service;

import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.service.SpaceModes;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** {@link HolidayLookup} 구현: 공간 경로의 달력 일정으로 판정 */
@Component
public class CalendarHolidayLookup implements HolidayLookup {

    private final SpaceRepository spaces;
    private final SpaceModes modes;

    public CalendarHolidayLookup(SpaceRepository spaces, SpaceModes modes) {
        this.spaces = spaces;
        this.modes = modes;
    }

    @Override
    public boolean isHoliday(long organizationId, Long spaceId, LocalDate date) {
        List<Long> chain = spaceId == null || spaceId <= 0 ? List.of()
                : spaces.findById(organizationId, spaceId).map(Space::pathIds).orElse(List.of());
        return modes.isHoliday(organizationId, chain, date);
    }
}
