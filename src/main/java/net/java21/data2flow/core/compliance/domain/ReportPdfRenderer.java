package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.core.compliance.domain.ReportModels.DeviceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.ExcursionItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Meta;
import net.java21.data2flow.core.compliance.domain.ReportModels.MetricStat;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportModels.ProfileItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.SpaceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Unmeasurable;
import org.openpdf.text.Chapter;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 준수 리포트 PDF(CMP-02.02·02.06). OpenPDF로 A4 한국어 문서를 그린다. 글꼴은 NanumSquare(OFL, {@code fonts/})를 문서에 넣는다.
 *
 * <ul>
 *   <li>첫 장: 제목, 고지(BR-CMP-13, 끌 수 없음), 시설 유형·공간·기간·프로필 버전·판 번호·생성 시각(UTC와 조직 시간대)·생성자·데이터 범위</li>
 *   <li>장: 요약(결측 10% 초과면 데이터 품질 경고), 항목별 통계, 측정 기기·교정, 측정 불가 항목, 개선 조치. 장마다 PDF 책갈피가 생긴다</li>
 *   <li>쪽 아래: 제목·판·쪽 번호와 {@code SHA-256} 자리({@link ReportHash}). 초안은 모든 쪽에 "초안" 워터마크</li>
 * </ul>
 * 내용 흐름은 압축하지 않는다(해시 자리를 파일 바이트에서 찾으려고).
 */
public final class ReportPdfRenderer {

    static final Map<String, String> FACILITY = Map.of("GENERAL", "일반 다중이용시설", "SENSITIVE", "민감계층 이용시설", "INDOOR_PARKING", "실내주차장");
    static final Map<String, String> WINDOW = Map.of("INSTANT", "순간값", "HOUR_1", "1시간 평균", "HOUR_8", "8시간 평균", "DAY_1", "일 평균");
    static final Map<String, String> CALIBRATION = Map.of("OK", "정상", "DUE_SOON", "교정 기한 임박", "OVERDUE", "교정 기한 초과", "NO_RECORD",
            "교정 기록 없음");
    static final Map<String, String> MEASURABILITY = Map.of("NOT_MEASURABLE", "측정 불가(센서 없음)", "PARTIAL", "일부 공간만");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Color LINE = new Color(0xC8, 0xCD, 0xD3);
    private static final Color HEAD = new Color(0xF1, 0xF3, 0xF5);
    private static final Color WARN = new Color(0xB4, 0x23, 0x18);

    private static volatile Fonts fonts;

    private ReportPdfRenderer() {
    }

    private record Fonts(BaseFont regular, BaseFont bold, BaseFont mono) {
    }

    public static byte[] render(Model model, Meta meta) {
        Fonts f = fonts();
        Font body = new Font(f.regular(), 9.5f);
        Font small = new Font(f.regular(), 8f);
        Font strong = new Font(f.bold(), 9.5f);
        Font h1 = new Font(f.bold(), 20f);
        Font h2 = new Font(f.bold(), 13f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 64);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setCompressionLevel(0);
            writer.setPageEvent(new Footer(f, model.title(), meta));
            doc.addTitle(model.title());
            doc.addCreator("data2flow");
            doc.open();
            writer.getInfo().put(new PdfName("D2FReport"), new PdfString(meta.reportId() + "/r" + meta.revisionNo()));
            writer.getInfo().put(new PdfName("D2FHash"), new PdfString(ReportHash.MARKER + ReportHash.PLACEHOLDER));

            cover(doc, model, meta, body, strong, h1);
            int n = 1;
            Chapter summary = chapter("요약", h2, n++);
            summary(summary, model, body, strong);
            doc.add(summary);
            if (model.summary().qualityWarning()) {
                Chapter warning = chapter("데이터 품질 경고", h2, n++);
                warning.add(new Paragraph("기간 결측률 " + percent(model.summary().missingRatio()) + "가 데이터 품질 기준(10%)을 넘었습니다. "
                        + "결측 구간은 충족으로 세지 않았으며, 수치를 해석할 때 주의하세요.", new Font(f.bold(), 9.5f, Font.NORMAL, WARN)));
                doc.add(warning);
            }
            Chapter metrics = chapter("항목별 통계", h2, n++);
            metrics.add(metricTable(model.metrics(), small, strong));
            doc.add(metrics);
            Chapter devices = chapter("측정 기기·교정 이력", h2, n++);
            devices.add(deviceTable(model.devices(), small, strong, body));
            doc.add(devices);
            Chapter unmeasurable = chapter("측정 불가 항목", h2, n++);
            unmeasurable(unmeasurable, model.unmeasurable(), body);
            doc.add(unmeasurable);
            Chapter actions = chapter("개선 조치", h2, n);
            actions.add(excursionTable(model.excursions(), ZoneId.of(model.timezone()), small, strong, body));
            doc.add(actions);
            doc.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("리포트 PDF를 그리지 못했습니다", ex);
        }
        return out.toByteArray();
    }

    private static void cover(Document doc, Model model, Meta meta, Font body, Font strong, Font h1) {
        Paragraph title = new Paragraph(model.title(), h1);
        title.setSpacingAfter(4);
        doc.add(title);
        doc.add(new Paragraph("실내공기질 자가 점검 리포트(실내공기질관리법)" + (meta.draft() ? " · 초안" : ""), body));
        PdfPTable notice = new PdfPTable(1);
        notice.setWidthPercentage(100);
        notice.setSpacingBefore(14);
        notice.setSpacingAfter(14);
        PdfPCell cell = new PdfPCell(new Phrase("※ " + ReportModels.DISCLAIMER + "입니다.", strong));
        cell.setPadding(10);
        cell.setBorderColor(WARN);
        cell.setBorderWidth(1.2f);
        notice.addCell(cell);
        doc.add(notice);

        ZoneId zone = ZoneId.of(model.timezone());
        PdfPTable info = new PdfPTable(new float[]{1.3f, 4f});
        info.setWidthPercentage(100);
        row(info, "시설 유형", model.facilityType() == null ? "지정 없음" : FACILITY.getOrDefault(model.facilityType(), model.facilityType()), strong, body);
        row(info, "대상 공간", String.join(", ", model.spaces().stream().map(SpaceItem::name).toList()), strong, body);
        row(info, "기간", model.periodFrom() + " ~ " + model.periodTo() + " (" + model.timezone() + ")", strong, body);
        row(info, "기준 프로필", String.join("\n", model.profiles().stream().map(ReportPdfRenderer::profile).toList()), strong, body);
        row(info, "판", "r" + meta.revisionNo() + (meta.draft() ? " (초안)" : " (발행)"), strong, body);
        row(info, "생성", both(meta.generatedAt(), zone) + " · " + nz(meta.generatedBy()), strong, body);
        if (meta.publishedAt() != null) {
            row(info, "발행", both(meta.publishedAt(), zone) + " · " + nz(meta.publishedBy()), strong, body);
        }
        ReportModels.DataRange range = model.dataRange();
        String data = TIME.format(range.from().atZone(ZoneOffset.UTC)) + " ~ " + TIME.format(range.to().atZone(ZoneOffset.UTC)) + " UTC, 1분 집계 "
                + String.format("%,d", range.rows()) + "행, 결측률 " + percent(range.missingRatio())
                + (range.excludedVirtualDevices() > 0 ? ", 가상 기기 " + range.excludedVirtualDevices() + "대 제외" : "");
        row(info, "데이터 범위", data, strong, body);
        if (range.lateRowsSincePrevious() > 0) {
            row(info, "늦게 온 데이터", "이전 판 이후 데이터 " + String.format("%,d", range.lateRowsSincePrevious()) + "건 추가됨", strong, body);
        }
        row(info, "무결성", "쪽 아래 SHA-256 값으로 이 파일이 발행본과 같은지 확인할 수 있습니다", strong, body);
        doc.add(info);
    }

    private static void summary(Chapter c, Model model, Font body, Font strong) {
        PdfPTable t = new PdfPTable(new float[]{2f, 3f});
        t.setWidthPercentage(60);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        row(t, "기간 충족률", percent(model.summary().ratio()), strong, body);
        row(t, "결측률", percent(model.summary().missingRatio()), strong, body);
        row(t, "기준 이탈 구간", model.summary().excursions() + "건", strong, body);
        row(t, "측정 기기", model.devices().size() + "대", strong, body);
        c.add(t);
        c.add(new Paragraph("충족률 = 충족 분 / (충족 분 + 이탈 분). 결측 구간은 충족으로 세지 않습니다(BR-CMP-10).", body));
    }

    private static PdfPTable metricTable(List<MetricStat> rows, Font cell, Font head) {
        PdfPTable t = table(new float[]{2.2f, 1.2f, 1.6f, 1.3f, 1.1f, 1.1f, 0.9f, 1.0f, 1.1f, 1.1f}, head,
                "공간", "항목", "기준", "평가 단위", "평균", "최대", "초과 횟수", "이탈(h)", "충족률", "결측률");
        for (MetricStat m : rows) {
            add(t, cell, nz(m.spaceName()), m.metricKey(), limit(m.lower(), m.upper(), m.unit()), m.evalWindow() == null ? "-" : WINDOW.getOrDefault(m.evalWindow(), m.evalWindow()),
                    num(m.average()), num(m.max()), Integer.toString(m.exceedances()), num(m.exceededHours()), percent(m.ratio()),
                    percent(m.missingRatio()));
        }
        if (rows.isEmpty()) {
            empty(t, cell, "기간 안에 판정한 값이 없습니다", 10);
        }
        return t;
    }

    private static PdfPTable deviceTable(List<DeviceItem> rows, Font cell, Font head, Font body) {
        PdfPTable t = table(new float[]{1.8f, 2.4f, 1.3f, 1.6f, 1.2f, 0.8f, 1.2f, 1.3f}, head,
                "기기", "위치", "모델", "측정 항목", "마지막 교정", "주기(일)", "교정 기한", "상태");
        for (DeviceItem d : rows) {
            Font f = "OVERDUE".equals(d.calibration()) ? new Font(cell.getBaseFont(), cell.getSize(), Font.NORMAL, WARN) : cell;
            add(t, f, d.name(), nz(d.spacePath()), nz(d.model()), String.join(", ", d.metrics()), d.lastCalibratedOn() == null ? "–"
                    : d.lastCalibratedOn().toString(), Integer.toString(d.intervalDays()), d.dueOn() == null ? "–" : d.dueOn().toString(),
                    CALIBRATION.getOrDefault(d.calibration(), d.calibration()));
        }
        if (rows.isEmpty()) {
            empty(t, cell, "대상 공간에 ACTIVE 실제 기기가 없습니다", 8);
        }
        return t;
    }

    private static void unmeasurable(Chapter c, List<Unmeasurable> rows, Font body) {
        if (rows.isEmpty()) {
            c.add(new Paragraph("프로필의 모든 필수 항목을 측정하고 있습니다.", body));
            return;
        }
        for (Unmeasurable u : rows) {
            String spaces = u.spacesWithout().isEmpty() ? "" : " — 센서 없는 공간: " + String.join(", ", u.spacesWithout());
            c.add(new Paragraph("· " + u.metricKey() + ": " + MEASURABILITY.getOrDefault(u.status(), u.status()) + spaces, body));
        }
    }

    private static PdfPTable excursionTable(List<ExcursionItem> rows, ZoneId zone, Font cell, Font head, Font body) {
        PdfPTable t = table(new float[]{2f, 1.1f, 1.8f, 1.8f, 1.1f, 2.6f}, head, "공간", "항목", "시작", "끝", "최고값", "조치");
        for (ExcursionItem x : rows) {
            add(t, cell, nz(x.spaceName()), x.metricKey(), TIME.format(x.startedAt().atZone(zone)),
                    x.endedAt() == null ? "진행 중" : TIME.format(x.endedAt().atZone(zone)), num(x.peakValue()),
                    x.action() == null ? "기록된 조치 없음" : x.action());
        }
        if (rows.isEmpty()) {
            empty(t, cell, "기간 안에 기준 이탈이 없었습니다", 6);
        }
        return t;
    }

    private static Chapter chapter(String title, Font font, int number) {
        Paragraph p = new Paragraph(number + ". " + title, font);
        p.setSpacingAfter(8);
        Chapter c = new Chapter(p, number);
        c.setNumberDepth(0);
        c.setTriggerNewPage(false);
        c.setBookmarkTitle(title);
        return c;
    }

    private static PdfPTable table(float[] widths, Font head, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.setSpacingBefore(4);
        t.setSpacingAfter(10);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, new Font(head.getBaseFont(), 8f)));
            c.setBackgroundColor(HEAD);
            c.setBorderColor(LINE);
            c.setPadding(4);
            t.addCell(c);
        }
        return t;
    }

    private static void add(PdfPTable t, Font font, String... cells) {
        for (String s : cells) {
            PdfPCell c = new PdfPCell(new Phrase(s, font));
            c.setBorderColor(LINE);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private static void empty(PdfPTable t, Font font, String text, int span) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setColspan(span);
        c.setBorderColor(LINE);
        c.setPadding(6);
        t.addCell(c);
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

    private static String profile(ProfileItem p) {
        return p.name() + " v" + p.versionNo() + (p.sourceTitle() == null ? "" : " — " + p.sourceTitle())
                + (p.effectiveDate() == null ? "" : " (" + p.effectiveDate() + " 시행)");
    }

    static String limit(BigDecimal lower, BigDecimal upper, String unit) {
        String u = unit == null ? "" : " " + unit;
        if (lower != null && upper != null) {
            return lower.stripTrailingZeros().toPlainString() + "~" + upper.stripTrailingZeros().toPlainString() + u;
        }
        if (upper != null) {
            return "≤ " + upper.stripTrailingZeros().toPlainString() + u;
        }
        return lower == null ? "–" : "≥ " + lower.stripTrailingZeros().toPlainString() + u;
    }

    private static String both(Instant t, ZoneId zone) {
        return TIME.format(t.atZone(ZoneOffset.UTC)) + " UTC (" + TIME.format(t.atZone(zone)) + " " + zone.getId() + ")";
    }

    private static String percent(BigDecimal v) {
        return v == null ? "–" : v.stripTrailingZeros().toPlainString() + "%";
    }

    private static String num(BigDecimal v) {
        return v == null ? "–" : v.toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "–" : s;
    }

    /** 쪽 아래(제목·판·쪽 번호·해시 자리)와 초안 워터마크 */
    private static final class Footer extends PdfPageEventHelper {

        private final Fonts fonts;
        private final String label;
        private final boolean draft;

        Footer(Fonts fonts, String title, Meta meta) {
            this.fonts = fonts;
            this.label = title + " · r" + meta.revisionNo() + (meta.draft() ? " 초안" : "");
            this.draft = meta.draft();
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            cb.beginText();
            cb.setFontAndSize(fonts.regular(), 7.5f);
            cb.setColorFill(Color.GRAY);
            cb.showTextAligned(Element.ALIGN_LEFT, label, document.left(), 36, 0);
            cb.showTextAligned(Element.ALIGN_RIGHT, writer.getPageNumber() + "쪽", document.right(), 36, 0);
            cb.endText();
            cb.beginText();
            cb.setFontAndSize(fonts.mono(), 6.5f);
            cb.setTextMatrix(document.left(), 26);
            cb.showText(ReportHash.MARKER + ReportHash.PLACEHOLDER);
            cb.endText();
            if (draft) {
                PdfContentByte under = writer.getDirectContentUnder();
                under.saveState();
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.12f);
                under.setGState(gs);
                under.beginText();
                under.setFontAndSize(fonts.bold(), 110);
                under.setColorFill(Color.GRAY);
                under.showTextAligned(Element.ALIGN_CENTER, "초안", page.getWidth() / 2, page.getHeight() / 2, 35);
                under.endText();
                under.restoreState();
            }
        }
    }

    private static Fonts fonts() {
        Fonts f = fonts;
        if (f == null) {
            synchronized (ReportPdfRenderer.class) {
                f = fonts;
                if (f == null) {
                    try {
                        f = new Fonts(font("NanumSquareR.ttf"), font("NanumSquareB.ttf"),
                                BaseFont.createFont(BaseFont.COURIER, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED));
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
        try (InputStream in = ReportPdfRenderer.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IOException("글꼴이 없습니다: " + name);
            }
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }
}
