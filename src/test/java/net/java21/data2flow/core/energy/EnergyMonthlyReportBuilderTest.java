package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.energy.domain.EnergyMonthlyReportBuilder;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Model;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Saving;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.TopItem;
import net.java21.data2flow.core.energy.domain.EnergyReportPdfRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ENE-01.05 월간 리포트 모델·PDF — TC-ENE-029 */
class EnergyMonthlyReportBuilderTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode metrics(String compare) {
        String children = String.join(",", IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "{\"id\":\"%d\",\"name\":\"%d호\",\"kind\":\"ROOM\",\"kwh\":%d,\"share\":0.05}".formatted(i, 100 + i, i * 100)).toList());
        return JSON.readTree("""
                {"totals":{"kwh":6000.0,"krw":900000.0,"tco2":2.7384,"euiKwhPerM2":12.0,"estimatedRatio":0.1,"otherCommonKwh":300.0},
                 "compare":%s,"children":[%s]}""".formatted(compare, children));
    }

    @Test
    @DisplayName("[TC-ENE-029] 장 4개가 순서대로, 상위 10개는 사용량 내림차순, 절감은 VERIFIED만, 탄소는 소수 둘째 자리")
    void build() {
        List<Saving> savings = List.of(new Saving("1", "조명 LED 교체", "VERIFIED", 120.0, 18000.0, 0.05),
                new Saving("2", "공조 스케줄", "IN_PROGRESS", 80.0, null, null));
        Model m = EnergyMonthlyReportBuilder.build("본관", YearMonth.of(2026, 9),
                metrics("{\"kind\":\"BASELINE\",\"kwh\":6500.0,\"actualKwh\":6000.0,\"diffRatio\":-0.0769,\"baselineVersionId\":\"701\"}"), savings);

        assertThat(m.chapters()).containsExactly("METRICS", "BASELINE", "TOP_USAGE", "SAVINGS");
        assertThat(m.title()).isEqualTo("본관 2026-09 에너지 리포트");
        assertThat(m.from()).isEqualTo("2026-09-01");
        assertThat(m.to()).isEqualTo("2026-09-30");
        assertThat(m.metrics().tco2()).isEqualTo(2.74);
        assertThat(m.baseline().present()).isTrue();
        assertThat(m.baseline().versionId()).isEqualTo("701");
        assertThat(m.topUsage()).hasSize(10).extracting(TopItem::kwh).first().isEqualTo(1200.0);
        assertThat(m.topUsage()).extracting(TopItem::kwh).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(m.savings()).extracting(Saving::title).containsExactly("조명 LED 교체");
    }

    @Test
    @DisplayName("[TC-ENE-029] 기준선이 없거나 기온이 없으면 present=false와 사유, 범위가 없으면 포트폴리오")
    void noBaseline() {
        Model none = EnergyMonthlyReportBuilder.build(null, YearMonth.of(2026, 9), metrics("null"), null);
        assertThat(none.scopeName()).isEqualTo("포트폴리오");
        assertThat(none.baseline().present()).isFalse();
        assertThat(none.baseline().reason()).isEqualTo("NO_BASELINE");
        assertThat(none.savings()).isEmpty();

        Model noWeather = EnergyMonthlyReportBuilder.build("본관", YearMonth.of(2026, 9),
                metrics("{\"kind\":\"BASELINE\",\"kwh\":null,\"reason\":\"NO_WEATHER\"}"), List.of());
        assertThat(noWeather.baseline().present()).isFalse();
        assertThat(noWeather.baseline().reason()).isEqualTo("NO_WEATHER");
    }

    @Test
    @DisplayName("[TC-ENE-029] PDF 본문에 장 제목이 순서대로, 숫자는 단위와 함께")
    void pdf() throws Exception {
        Model m = EnergyMonthlyReportBuilder.build("본관", YearMonth.of(2026, 9),
                metrics("{\"kind\":\"BASELINE\",\"kwh\":6500.0,\"actualKwh\":6000.0,\"diffRatio\":-0.0769,\"baselineVersionId\":\"701\"}"),
                List.of(new Saving("1", "조명 LED 교체", "VERIFIED", 120.0, 18000.0, 0.05)));
        byte[] pdf = EnergyReportPdfRenderer.render(m, Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"), "분석가");
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("본관 2026-09 에너지 리포트", "6,000kWh", "2.74 tCO2eq", "112호", "조명 LED 교체");
        int a = text.indexOf("에너지 지표");
        int b = text.indexOf("기준선 대비");
        int c = text.indexOf("상위 사용 공간");
        int d = text.indexOf("절감 조치 결과");
        assertThat(a).isNotNegative();
        assertThat(List.of(a, b, c, d)).isSorted();
    }
}
