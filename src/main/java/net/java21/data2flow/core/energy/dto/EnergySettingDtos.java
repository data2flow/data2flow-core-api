package net.java21.data2flow.core.energy.dto;

import net.java21.data2flow.core.energy.domain.TouSchedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 에너지 설정 API-ENE-01(단가)·02(배출계수)·04(원천)·05(고지서) 요청·응답. ID는 문자열이다 */
public final class EnergySettingDtos {

    private EnergySettingDtos() {
    }

    public record Ref(String id, String name) {
    }

    /** API-ENE-01 요청. 고칠 때는 baseVersion이 필요하다 */
    public record TariffRequest(String scopeSpaceId, LocalDate validFrom, String mode, BigDecimal flatRateKrwPerKwh, TouSchedule touSchedule,
                                BigDecimal baseChargeKrwPerMonth, Integer baseVersion) {
    }

    /** 단가. scopeSpace가 null이면 조직 전체, validTo가 null이면 끝이 없다 */
    public record TariffView(String tariffId, Ref scopeSpace, LocalDate validFrom, LocalDate validTo, String mode, BigDecimal flatRateKrwPerKwh,
                             TouSchedule touSchedule, BigDecimal baseChargeKrwPerMonth, int version, Instant updatedAt) {
    }

    /** API-ENE-02 요청 */
    public record EmissionFactorRequest(LocalDate validFrom, BigDecimal factorTco2PerMwh, String sourceTitle, Integer sourceYear) {
    }

    /** 배출계수. system이면 시스템 기본값(읽기 전용) */
    public record EmissionFactorView(String emissionFactorId, LocalDate validFrom, LocalDate validTo, BigDecimal factorTco2PerMwh, String sourceTitle,
                                     int sourceYear, boolean system) {
    }

    /** API-ENE-04 요청. kind·deviceId는 만든 뒤 바꿀 수 없다 */
    public record SourceRequest(String spaceId, String kind, String deviceId, String metricKey, Boolean cumulative, Boolean coversDescendants,
                                Integer ratedPowerW, Integer standbyPowerW, Boolean enabled, Integer baseVersion) {
    }

    /** 원천. status는 OK·NO_DATA(측정 계량의 최근 24시간 수신 없음)·DISABLED */
    public record SourceView(String sourceId, Ref space, String kind, Ref device, String metricKey, Boolean cumulative, boolean coversDescendants,
                             Integer ratedPowerW, Integer standbyPowerW, boolean enabled, Instant lastValueAt, String status, int version,
                             Instant updatedAt) {
    }

    /** 측정 원천 후보: 모델에 {@code energy}·{@code power} 측정 항목이 있는 기기 */
    public record SourceCandidate(Ref device, Ref space, List<String> metricKeys, List<String> linkedMetricKeys) {
    }

    /** API-ENE-05 요청. 이미 있는 달은 overwrite=true여야 덮어쓴다 */
    public record BillRequest(BigDecimal kwh, BigDecimal amountKrw, String attachmentObjectKey, Boolean overwrite) {
    }

    /** 고지서. meteredKwh는 같은 달 측정 합계(일 집계가 생기기 전에는 null), diffRatio는 (고지서 − 측정) / 고지서 */
    public record BillView(String billId, String spaceId, String month, BigDecimal kwh, BigDecimal amountKrw, String attachmentObjectKey,
                           BigDecimal meteredKwh, BigDecimal diffRatio, UserRef createdBy, Instant updatedAt) {
    }

    public record UserRef(String userId, String name) {
    }
}
