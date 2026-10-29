package net.java21.data2flow.core.energy.dto;

import net.java21.data2flow.core.energy.dto.EnergySettingDtos.EmissionFactorView;
import net.java21.data2flow.core.energy.dto.EnergySettingDtos.TariffView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** analytics가 부르는 에너지 내부 API(API-ENE-70) 응답. ID는 문자열이다 */
public final class EnergyInternalDtos {

    private EnergyInternalDtos() {
    }

    /**
     * 조직 설정 묶음. holidays는 사이트별 휴일(조직 달력, 최근 {@code HOLIDAY_DAYS_BACK}일 ~ 내일, TOU 요일 유형 판정용).
     * fddRules·virtualSensors·detection은 해당 기능이 생기면 채운다
     */
    public record ConfigBundle(String organizationId, String timezone, List<TariffView> tariffs, List<EmissionFactorView> emissionFactors,
                               List<MeterSource> meterSources, Map<String, List<LocalDate>> holidays, List<Object> fddRules,
                               List<Object> virtualSensors, Map<String, Object> detection, long configVersion, Instant generatedAt) {
    }

    /** 켜진 원천 하나. siteId는 공간 경로의 맨 위(사이트 단가 판정), timezone은 사이트(없으면 조직) 시간대 */
    public record MeterSource(String sourceId, String spaceId, String siteId, String timezone, Double areaM2, String kind, String deviceId,
                              String metricKey, Boolean cumulative, boolean coversDescendants, Integer ratedPowerW, Integer standbyPowerW) {
    }

    /** 켜진 원천이 있는 조직(일 집계 대상) */
    public record Organizations(List<String> organizationIds) {
    }
}
