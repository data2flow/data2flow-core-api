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

    /**
     * 입력 측정 항목 하나: 공간에 그 항목을 내는 기기가 있는가
     *
     * @param signal   입력 신호(ACH co2, 재실 추정 pir·co2·noise·illuminance)
     * @param selected 재실 추정에서 고른 신호인가(ACH는 항상 true)
     */
    public record Input(String metricKey, boolean available, List<String> deviceIds, String signal, boolean selected) {
    }

    /**
     * 재실 추정 현재 상태(BR-ENE-10, NFR-13.02)
     *
     * @param state OCCUPIED(사용 중)·VACANT(비어 있음)·UNKNOWN(값이 없거나 15분 넘게 오래됨)
     * @param band  인원 구간(예: "10~14"). 3명 미만이거나 정원 4명 이하(또는 모름)인 공간은 null — 숫자를 내보내지 않는다
     * @param at    마지막 추정 시각. 없으면 null
     */
    public record OccupancyNow(String state, String band, Instant at) {
    }

    /** API-ENE-22 한 점. 숫자 대신 상태(와 노출 가능한 경우 인원 구간)만 */
    public record OccupancyPoint(Instant ts, String state, String band) {
    }

    /** API-ENE-22 응답 */
    public record OccupancySeries(String spaceId, boolean estimated, List<OccupancyPoint> items) {
    }

    /**
     * 공간의 가상 센서 하나
     *
     * @param status  DISABLED(꺼짐), INPUT_MISSING(입력 없음, 지금 기기 기준이거나 마지막 계산 결과), OK
     * @param missing INPUT_MISSING일 때 없는 입력 측정 항목(재실 추정은 신호 이름)
     * @param outputDeviceId 계산값을 저장하는 공간 가상 기기(재실 추정을 켠 적이 있으면). 없으면 null
     * @param current 재실 추정 현재 상태(OCCUPANCY만, 그 밖은 null)
     */
    public record SensorView(String kind, boolean enabled, JsonNode params, List<String> outputMetrics, List<Input> inputs, Instant lastComputedAt,
                             String status, List<String> missing, Double volumeM3, String outputDeviceId, OccupancyNow current) {
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
