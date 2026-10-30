package net.java21.data2flow.core.energy.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 가상 센서(API-ENE-20·21, ENE-02). ID는 문자열 */
public final class VirtualSensorDtos {

    private VirtualSensorDtos() {
    }

    /** API-ENE-20 PUT 요청. params가 없으면 기본값 */
    public record UpdateRequest(Boolean enabled, JsonNode params) {
    }

    /** 입력 측정 항목 하나: 공간에 그 항목을 내는 기기가 있는가 */
    public record Input(String metricKey, boolean available, List<String> deviceIds) {
    }

    /**
     * 공간의 가상 센서 하나
     *
     * @param status  DISABLED(꺼짐), INPUT_MISSING(입력 없음, 지금 기기 기준이거나 마지막 계산 결과), OK
     * @param missing INPUT_MISSING일 때 없는 입력 측정 항목
     */
    public record SensorView(String kind, boolean enabled, JsonNode params, List<String> outputMetrics, List<Input> inputs, Instant lastComputedAt,
                             String status, List<String> missing, Double volumeM3) {
    }

    public record DecayWindow(Instant from, Instant to) {
    }

    /** API-ENE-21 하루 추정값. 환기량 = ACH × 부피(BR-ENE-09) */
    public record AchDay(String day, double ach, Double ciLow, Double ciHigh, Double ventilationM3h, DecayWindow decayWindow, Double fitR2) {
    }

    /** API-ENE-21 응답: 계산 가정(공간 부피·실외 CO2)과 일별 추정값 */
    public record AchSeries(String spaceId, Double volumeM3, double outdoorCo2Ppm, List<AchDay> items) {
    }
}
