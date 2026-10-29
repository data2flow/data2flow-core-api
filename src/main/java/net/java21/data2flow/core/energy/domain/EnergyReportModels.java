package net.java21.data2flow.core.energy.domain;

import java.util.List;

/** 월간 에너지 리포트 모델(ENE-01.05, AT-ENE-05.1). 화면(JSON)과 PDF가 같은 모델을 쓴다 */
public final class EnergyReportModels {

    /** 장 순서: 지표, 기준선 대비, 상위 사용 공간, 절감 조치 결과 */
    public static final List<String> CHAPTERS = List.of("METRICS", "BASELINE", "TOP_USAGE", "SAVINGS");

    private EnergyReportModels() {
    }

    public record Model(String title, String scopeName, String month, String from, String to, List<String> chapters, Metrics metrics,
                        Baseline baseline, List<TopItem> topUsage, List<Saving> savings) {
    }

    /** 사용량·비용·탄소·EUI·추정 비율·기타·공용 */
    public record Metrics(Double kwh, Double krw, Double tco2, Double euiKwhPerM2, Double estimatedRatio, Double otherCommonKwh) {
    }

    /** 기준선 대비(날씨 보정). present=false면 reason(NO_BASELINE·NO_WEATHER)을 안내한다 */
    public record Baseline(boolean present, Double kwh, Double actualKwh, Double diffRatio, String versionId, String reason) {
    }

    public record TopItem(String id, String name, String kind, Double kwh, Double share, Double estimatedRatio) {
    }

    /** 절감 조치 결과(ENE-04 M&V). VERIFIED만 리포트에 들어간다 */
    public record Saving(String id, String title, String status, Double kwh, Double krw, Double tco2) {
    }
}
