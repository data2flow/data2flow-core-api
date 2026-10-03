package net.java21.data2flow.core.device.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 목록 한 행(기기 + 모델·소스 이름 + pipeline {@code device_state}의 연결 상태·최근 수신·배터리·신호, 측정 항목 키, 태그) */
public record DeviceListRow(Device device, String modelCode, String modelName, String modelKind, String sourceName,
                            String connectivity, Instant lastSeenAt, BigDecimal battery, BigDecimal rssi, List<String> metricKeys,
                            List<String> tags) {
}
