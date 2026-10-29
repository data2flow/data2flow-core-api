package net.java21.data2flow.core.energy.domain;

import net.java21.data2flow.core.energy.domain.EnergyReportModels.Baseline;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Metrics;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Model;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Saving;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.TopItem;
import tools.jackson.databind.JsonNode;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 월간 에너지 리포트 모델 만들기(ENE-01.05, UC-ENE-05). analytics 지표 응답(API-ENE-85, 기준선 비교 포함)과 절감 조치 결과로 장 4개를 순서대로 채운다.
 * <ul>
 *   <li>지표: 사용량·비용·탄소(소수 둘째 자리)·EUI·추정 비율·기타·공용</li>
 *   <li>기준선 대비: 활성 기준선이 없거나 기온이 없으면 present=false와 사유</li>
 *   <li>상위 사용 공간: 하위 단위를 사용량 내림차순으로 최대 10개</li>
 *   <li>절감 조치 결과: VERIFIED만</li>
 * </ul>
 */
public final class EnergyMonthlyReportBuilder {

    public static final int TOP = 10;

    private EnergyMonthlyReportBuilder() {
    }

    public static Model build(String scopeName, YearMonth month, JsonNode metrics, List<Saving> savings) {
        String name = scopeName == null ? "포트폴리오" : scopeName;
        JsonNode t = metrics == null ? null : metrics.path("totals");
        Metrics m = new Metrics(num(t, "kwh"), num(t, "krw"), round(num(t, "tco2"), 2), num(t, "euiKwhPerM2"), num(t, "estimatedRatio"),
                num(t, "otherCommonKwh"));
        JsonNode c = metrics == null ? null : metrics.get("compare");
        Baseline baseline;
        if (c == null || c.isNull() || !"BASELINE".equals(c.path("kind").asString(null)) || c.hasNonNull("reason") || !c.path("kwh").isNumber()) {
            String reason = c == null || c.isNull() ? "NO_BASELINE" : c.path("reason").asString("NO_BASELINE");
            baseline = new Baseline(false, null, null, null, null, reason);
        } else {
            baseline = new Baseline(true, num(c, "kwh"), num(c, "actualKwh"), num(c, "diffRatio"), c.path("baselineVersionId").asString(null), null);
        }
        List<TopItem> top = new ArrayList<>();
        if (metrics != null && metrics.path("children").isArray()) {
            for (JsonNode ch : metrics.path("children").values()) {
                top.add(new TopItem(ch.path("id").asString(), ch.path("name").asString(null), ch.path("kind").asString(null), num(ch, "kwh"),
                        num(ch, "share"), num(ch, "estimatedRatio")));
            }
        }
        top.sort(Comparator.comparing(TopItem::kwh, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Saving> verified = savings == null ? List.of() : savings.stream().filter(s -> "VERIFIED".equals(s.status())).toList();
        return new Model(name + " " + month + " 에너지 리포트", name, month.toString(), month.atDay(1).toString(), month.atEndOfMonth().toString(),
                EnergyReportModels.CHAPTERS, m, baseline, List.copyOf(top.subList(0, Math.min(TOP, top.size()))), verified);
    }

    private static Double num(JsonNode n, String field) {
        return n == null || !n.path(field).isNumber() ? null : n.path(field).asDouble();
    }

    private static Double round(Double v, int digits) {
        if (v == null) {
            return null;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
