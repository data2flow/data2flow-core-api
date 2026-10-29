package net.java21.data2flow.core.energy.domain;

import net.java21.data2flow.core.energy.domain.EnergyReportModels.Model;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.Saving;
import net.java21.data2flow.core.energy.domain.EnergyReportModels.TopItem;
import org.openpdf.text.Chapter;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 월간 에너지 리포트 PDF(ENE-01.05). OpenPDF A4 한국어 문서, 글꼴은 준수 리포트와 같은 NanumSquare(OFL, {@code fonts/}).
 * 첫 장 제목·범위·기간·생성 시각 아래로 장 4개(에너지 지표, 기준선 대비, 상위 사용 공간, 절감 조치 결과)를 순서대로 그리고 장마다 책갈피를 단다
 */
public final class EnergyReportPdfRenderer {

    private static final Color LINE = new Color(0xC8, 0xCD, 0xD3);
    private static final Color HEAD = new Color(0xF1, 0xF3, 0xF5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static volatile BaseFont[] fonts;

    private EnergyReportPdfRenderer() {
    }

    public static byte[] render(Model model, Instant generatedAt, ZoneId zone, String generatedBy) {
        BaseFont[] f = fonts();
        Font body = new Font(f[0], 9.5f);
        Font strong = new Font(f[1], 9.5f);
        Font h1 = new Font(f[1], 18f);
        Font h2 = new Font(f[1], 13f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 56);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle(model.title());
            doc.addCreator("data2flow");
            doc.open();
            Paragraph title = new Paragraph(model.title(), h1);
            title.setSpacingAfter(6);
            doc.add(title);
            doc.add(new Paragraph("범위: " + model.scopeName() + " · 기간: " + model.from() + " ~ " + model.to() + " · 생성: "
                    + TIME.format(generatedAt.atZone(zone)) + " (" + zone.getId() + ")" + (generatedBy == null ? "" : " · " + generatedBy), body));
            doc.add(new Paragraph(" ", body));

            Chapter metrics = chapter("에너지 지표", h2, 1);
            PdfPTable mt = table(new float[]{1.4f, 2f});
            row(mt, "사용량", kwh(model.metrics().kwh()), strong, body);
            row(mt, "비용", won(model.metrics().krw()), strong, body);
            row(mt, "탄소", tco2(model.metrics().tco2()), strong, body);
            row(mt, "EUI", eui(model.metrics().euiKwhPerM2()), strong, body);
            row(mt, "추정 비율", percent(model.metrics().estimatedRatio()), strong, body);
            row(mt, "기타·공용", kwh(model.metrics().otherCommonKwh()), strong, body);
            metrics.add(mt);
            doc.add(metrics);

            Chapter baseline = chapter("기준선 대비", h2, 2);
            if (model.baseline().present()) {
                PdfPTable bt = table(new float[]{1.4f, 2f});
                row(bt, "기준선 예상", kwh(model.baseline().kwh()), strong, body);
                row(bt, "실제(비교한 날)", kwh(model.baseline().actualKwh()), strong, body);
                row(bt, "증감", signed(model.baseline().diffRatio()), strong, body);
                baseline.add(bt);
            } else {
                baseline.add(new Paragraph("NO_WEATHER".equals(model.baseline().reason()) ? "기준선 비교에 쓸 기온 데이터가 없습니다" : "기준선이 없습니다", body));
            }
            doc.add(baseline);

            Chapter top = chapter("상위 사용 공간", h2, 3);
            if (model.topUsage().isEmpty()) {
                top.add(new Paragraph("하위 단위가 없습니다", body));
            } else {
                PdfPTable tt = table(new float[]{2.4f, 1.4f, 1f, 1f});
                head(tt, strong, "공간", "사용량", "비중", "추정 비율");
                for (TopItem i : model.topUsage()) {
                    add(tt, body, i.name(), kwh(i.kwh()), percent(i.share()), percent(i.estimatedRatio()));
                }
                top.add(tt);
            }
            doc.add(top);

            Chapter savings = chapter("절감 조치 결과", h2, 4);
            if (model.savings().isEmpty()) {
                savings.add(new Paragraph("검증된 절감 조치가 없습니다", body));
            } else {
                PdfPTable st = table(new float[]{2.6f, 1.2f, 1.2f, 1f});
                head(st, strong, "조치", "절감량", "절감액", "탄소");
                for (Saving s : model.savings()) {
                    add(st, body, s.title(), kwh(s.kwh()), won(s.krw()), tco2(s.tco2()));
                }
                savings.add(st);
            }
            doc.add(savings);
        } catch (DocumentException ex) {
            throw new IllegalStateException(ex);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    static String kwh(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%,.0fkWh", v);
    }

    static String won(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%,.0f원", v);
    }

    static String tco2(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%.2f tCO2eq", v);
    }

    static String eui(Double v) {
        return v == null ? "– (면적 없음)" : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " kWh/㎡";
    }

    static String percent(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    static String signed(Double v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%+.1f%%", v * 100);
    }

    private static Chapter chapter(String title, Font font, int number) {
        Paragraph p = new Paragraph(number + ". " + title, font);
        p.setSpacingBefore(10);
        p.setSpacingAfter(6);
        Chapter c = new Chapter(p, number);
        c.setNumberDepth(0);
        c.setTriggerNewPage(false);
        c.setBookmarkTitle(title);
        return c;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(8);
        return t;
    }

    private static void head(PdfPTable t, Font font, String... cells) {
        t.setHeaderRows(1);
        for (String s : cells) {
            PdfPCell c = new PdfPCell(new Phrase(s, font));
            c.setBackgroundColor(HEAD);
            c.setBorderColor(LINE);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private static void add(PdfPTable t, Font font, String... cells) {
        for (String s : cells) {
            PdfPCell c = new PdfPCell(new Phrase(s == null ? "–" : s, font));
            c.setBorderColor(LINE);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, String label, String value, Font strong, Font body) {
        PdfPCell l = new PdfPCell(new Phrase(label, strong));
        l.setBackgroundColor(HEAD);
        l.setBorderColor(LINE);
        l.setPadding(5);
        PdfPCell v = new PdfPCell(new Phrase(value, body));
        v.setBorderColor(LINE);
        v.setPadding(5);
        t.addCell(l);
        t.addCell(v);
    }

    private static BaseFont[] fonts() {
        BaseFont[] f = fonts;
        if (f == null) {
            synchronized (EnergyReportPdfRenderer.class) {
                f = fonts;
                if (f == null) {
                    try {
                        f = new BaseFont[]{font("NanumSquareR.ttf"), font("NanumSquareB.ttf")};
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    fonts = f;
                }
            }
        }
        return f;
    }

    private static BaseFont font(String name) throws IOException {
        try (InputStream in = EnergyReportPdfRenderer.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IOException("글꼴이 없습니다: " + name);
            }
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }
}
