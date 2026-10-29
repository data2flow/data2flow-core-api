package net.java21.data2flow.core.energy.dto;

import net.java21.data2flow.core.energy.dto.EnergySettingDtos.UserRef;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 기준선(API-ENE-12, ENE-01.04). ID는 문자열 */
public final class EnergyBaselineDtos {

    private EnergyBaselineDtos() {
    }

    /**
     * 학습 요청. 회귀(DEGREE_DAY_REGRESSION)는 기온 원천이 있어야 한다(KMA 날씨 원천이 생기기 전에는 외기 온도 기기를 고른다)
     *
     * @param temperatureSource 기온 원천 {@code {deviceId, metricKey}}(metricKey 기본 temperature)
     */
    public record CreateVersionRequest(String scopeSpaceId, String method, String trainFrom, String trainTo, Params params,
                                       TemperatureSource temperatureSource) {
    }

    public record Params(BigDecimal coolingBaseC, BigDecimal heatingBaseC, Boolean splitWeekday) {
    }

    public record TemperatureSource(String deviceId, String metricKey) {
    }

    public record Accepted(String jobId, String versionId) {
    }

    public record SpaceRef(String id, String name) {
    }

    public record ActiveVersion(String versionId, String method, BigDecimal r2, String trainFrom, String trainTo) {
    }

    public record BaselineView(String baselineId, SpaceRef scopeSpace, ActiveVersion activeVersion, Instant updatedAt) {
    }

    /** 버전 목록 한 줄. 실패면 errorCode(INSUFFICIENT_DATA·NO_WEATHER·INTERNAL_ERROR)와 neededDays */
    public record VersionView(String versionId, String baselineId, String method, String trainFrom, String trainTo, String status, BigDecimal r2,
                              String warning, String errorCode, Integer neededDays, UserRef createdBy, Instant createdAt) {
    }

    /** 버전 상세. residuals는 analytics 모델(`baseline_models`)에서 읽는다(학습 전이거나 닿지 않으면 빈 목록) */
    public record VersionDetail(String versionId, String baselineId, String scopeSpaceId, String method, String trainFrom, String trainTo,
                                JsonNode params, String status, BigDecimal r2, JsonNode coefficients, List<JsonNode> residuals, String warning,
                                String errorCode, Integer neededDays, UserRef createdBy, Instant createdAt) {
    }
}
