package net.java21.data2flow.core.device.domain;

import java.math.BigDecimal;

/**
 * 기기가 참조하는 다른 기능의 행(읽기 전용): 모델(WP-B1 소유), 공간(WP-A), 데이터 소스(WP-C). 같은 스키마 안이라 직접 읽는다.
 */
public final class References {

    private References() {
    }

    /** 기기 모델 요약({@code device_models}) */
    public record ModelRef(long id, String code, String name, String vendor, String kind, String status, Integer defaultIntervalSec,
                           BigDecimal defaultOfflineMultiplier, String attributeSchema) {
    }

    /** 공간 요약({@code spaces}). path는 조상 ID 경로(/1/4/9/) */
    public record SpaceRef(long id, Long parentId, String name, String path, int depth) {
    }

    /** 데이터 소스 요약({@code data_sources}) */
    public record SourceRef(long id, long organizationId, String code, String name, String type, String lifecycle,
                            String decoderKey, String unknownDevicePolicy, Long defaultModelId, Long defaultSpaceId,
                            int autoregLimitPerHour) {

        public boolean platformBroker() {
            return "PLATFORM_BROKER".equals(type);
        }
    }
}
