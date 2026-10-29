package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.core.calendar.service.HolidayLookup;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.ConfigBundle;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.MeterSource;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.Organizations;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository;
import net.java21.data2flow.core.energy.repository.EnergySourceRepository.MeterRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * API-ENE-70 에너지 설정 묶음: analytics 일 집계(ENE-01.01·01.02)가 쓰는 단가·배출계수·켜진 원천·사이트 휴일과 configVersion.
 * analytics는 5분 캐시하고 EVT-ENE-07을 받으면 지운다
 */
@Service
public class EnergyInternalService {

    /** 휴일을 돌려주는 기간(오늘 기준 이만큼 전부터 내일까지). 일 집계의 늦은 데이터 재계산(최근 7일)을 넉넉히 덮는다 */
    static final int HOLIDAY_DAYS_BACK = 35;

    private final EnergyConfigRepository config;
    private final EnergySourceRepository sources;
    private final TariffService tariffs;
    private final EmissionFactorService factors;
    private final HolidayLookup holidays;
    private final DeploymentOrganization deployment;
    private final Clock clock;

    public EnergyInternalService(EnergyConfigRepository config, EnergySourceRepository sources, TariffService tariffs,
                                 EmissionFactorService factors, HolidayLookup holidays, DeploymentOrganization deployment, Clock clock) {
        this.config = config;
        this.sources = sources;
        this.tariffs = tariffs;
        this.factors = factors;
        this.holidays = holidays;
        this.deployment = deployment;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Organizations organizations() {
        return new Organizations(config.organizationsWithSources(deployment.restriction()).stream().map(String::valueOf).toList());
    }

    @Transactional(readOnly = true)
    public ConfigBundle bundle(long orgId) {
        long version = config.configVersion(orgId);
        String timezone = config.timezone(orgId);
        List<MeterRow> meters = sources.enabledMeters(orgId);
        Map<String, List<LocalDate>> siteHolidays = new LinkedHashMap<>();
        meters.stream().map(MeterRow::siteId).filter(Objects::nonNull).distinct().forEach(site -> {
            String tz = meters.stream().filter(m -> site.equals(m.siteId())).findFirst().map(MeterRow::timezone).orElse(timezone);
            siteHolidays.put(site.toString(), holidaysOf(orgId, site, LocalDate.now(clock.withZone(ZoneId.of(tz)))));
        });
        return new ConfigBundle(Long.toString(orgId), timezone, tariffs.listOf(orgId), factors.listOf(orgId), meters.stream().map(EnergyInternalService::meter).toList(),
                siteHolidays, List.of(), List.of(), Map.of(), version, clock.instant());
    }

    private List<LocalDate> holidaysOf(long orgId, long siteId, LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = today.minusDays(HOLIDAY_DAYS_BACK); !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
            if (holidays.isHoliday(orgId, siteId, d)) {
                out.add(d);
            }
        }
        return out;
    }

    private static MeterSource meter(MeterRow m) {
        return new MeterSource(Long.toString(m.id()), Long.toString(m.spaceId()), m.siteId() == null ? null : m.siteId().toString(), m.timezone(),
                m.areaM2(), m.kind(), Long.toString(m.deviceId()), m.metricKey(), m.cumulative(), m.coversDescendants(), m.ratedPowerW(),
                m.standbyPowerW());
    }
}
