package net.java21.data2flow.core.energy.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** 에너지 대시보드 응답(API-ENE-11). 지표(API-ENE-10)는 analytics 계산 결과를 그대로 전한다 */
public final class EnergyMetricDtos {

    private EnergyMetricDtos() {
    }

    /** 하위 단위 순위 한 쪽과 기타·공용(상위 계량 − 하위 합, BR-ENE-02) */
    public record Breakdown(List<JsonNode> items, JsonNode other, int page, int size, long totalCount) {
    }
}
