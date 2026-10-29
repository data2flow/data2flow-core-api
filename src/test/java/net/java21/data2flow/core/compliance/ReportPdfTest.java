package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Request;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Rows;
import net.java21.data2flow.core.compliance.domain.ReportHash;
import net.java21.data2flow.core.compliance.domain.ReportModels;
import net.java21.data2flow.core.compliance.domain.ReportModels.Meta;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportModels.SpaceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Unmeasurable;
import net.java21.data2flow.core.compliance.domain.ReportPdfRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CMP-02.02·02.06 PDF와 해시 — TC-CMP-045(한글·고지·초안 표시), TC-CMP-050(해시 넣기·검증·변조) */
class ReportPdfTest {

    private static Model model() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        return KrSelfCheckReportBuilder.build(new Request("본관 자가 점검", null, day, day, ZoneId.of("Asia/Seoul")),
                List.of(new SpaceItem(3, "실습실", "/1/3/")), List.of(), List.of(), List.of(), List.of(),
                List.of(new Unmeasurable("hcho", "NOT_MEASURABLE", List.of("실습실"))), new Rows(0, 0, null), day, Map.of());
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("[TC-CMP-045] 한글 본문·첫 장 고지·시설 유형 없음, 초안은 워터마크, 발행본은 없음")
    void render() throws Exception {
        Instant at = Instant.parse("2026-10-03T00:00:00Z");
        byte[] draft = ReportPdfRenderer.render(model(), new Meta(1, 1, at, "운영자", null, null, true));
        String text = text(draft);
        assertThat(text).contains("본관 자가 점검").contains("실습실").contains(ReportModels.DISCLAIMER).contains("초안").contains("지정 없음")
                .contains("hcho");
        assertThat(new String(draft, StandardCharsets.ISO_8859_1)).contains(ReportHash.MARKER + ReportHash.PLACEHOLDER);
        byte[] published = ReportPdfRenderer.render(model(), new Meta(1, 1, at, "운영자", at, "관리자", false));
        assertThat(text(published)).doesNotContain("초안");
    }

    @Test
    @DisplayName("[TC-CMP-050] 해시를 넣으면 다시 0으로 돌려 계산한 값과 같고, 한 바이트라도 바뀌면 검증되지 않는다")
    void seal() throws Exception {
        byte[] pdf = ReportPdfRenderer.render(model(), new Meta(1, 1, Instant.EPOCH, null, null, null, true));
        ReportHash.Sealed sealed = ReportHash.seal(pdf);
        assertThat(sealed.sha256()).matches("[0-9a-f]{64}");
        assertThat(sealed.pdf()).hasSameSizeAs(pdf);
        assertThat(ReportHash.verify(sealed.pdf())).contains(sealed.sha256());
        assertThat(text(sealed.pdf())).contains(sealed.sha256());
        // 해시를 넣지 않은 파일, 변조한 파일
        assertThat(ReportHash.verify(pdf)).isEmpty();
        byte[] tampered = sealed.pdf().clone();
        tampered[tampered.length / 2] ^= 1;
        assertThat(ReportHash.verify(tampered)).isEmpty();
        assertThatThrownBy(() -> ReportHash.seal("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(IllegalStateException.class);
    }
}
