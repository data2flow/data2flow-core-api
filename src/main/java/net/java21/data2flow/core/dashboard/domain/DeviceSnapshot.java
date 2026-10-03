package net.java21.data2flow.core.dashboard.domain;

import java.time.Instant;
import java.util.List;

/**
 * 기기 하나의 현재 모습: core {@code devices}(이름·공간·모델·상태)와 pipeline {@code device_state}(연결·최신값·배터리·신호)를 합친 읽기 전용 값.
 *
 * @param connectivity ONLINE·OFFLINE·UNKNOWN(device_state가 없으면 UNKNOWN)
 * @param metrics      최신값({@code device_state.latest}). 단위는 값에 있으면 그것, 없으면 측정 항목 정의의 단위
 */
public record DeviceSnapshot(long id, String name, Long spaceId, Long modelId, String modelName, String status, boolean virtual,
                             String connectivity, Instant lastSeenAt, Double battery, Double rssi, List<MetricValue> metrics) {

    /** 최신값 하나(API-DSH-02 {@code metrics[{key, value, unit, quality, at}]}) */
    public record MetricValue(String key, double value, String unit, Integer quality, Instant at) {
    }
}
