package net.java21.data2flow.core.commissioning.domain;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QR 라벨 PDF(DEV-09.04, API-DEV-24 {@code layout=A4_3x8}). A4(595×842pt)에 3열×8행, 라벨마다 QR(내용 {@code https://…/d/{qr_token}},
 * 오류 정정 M)과 외부 ID·이름. PDF는 라이브러리 없이 직접 쓴다(QR 칸은 사각형 채우기). 표준 글꼴(Helvetica)은 Latin-1만 그리므로
 * 그 밖의 글자(한글 이름 등)는 '?'로 바꾼다 — 이름은 화면 인쇄(UI-DEV-17)에서 완전하게 보인다.
 */
public final class QrLabelPdf {

    public static final String LAYOUT_A4_3X8 = "A4_3x8";
    static final double PAGE_W = 595.28;
    static final double PAGE_H = 841.89;
    static final int COLS = 3;
    static final int ROWS = 8;
    static final double MARGIN_X = 18;
    static final double MARGIN_Y = 24;

    private QrLabelPdf() {
    }

    public record Label(String url, String externalId, String name) {
    }

    /** QR 행렬(여백 없음). 칸 수는 내용 길이로 정해진다 */
    public static BitMatrix matrix(String text) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException ex) {
            throw new IllegalArgumentException("QR을 만들 수 없습니다", ex);
        }
    }

    public static byte[] render(List<Label> labels) {
        int perPage = COLS * ROWS;
        int pages = Math.max(1, (labels.size() + perPage - 1) / perPage);
        double cellW = (PAGE_W - 2 * MARGIN_X) / COLS;
        double cellH = (PAGE_H - 2 * MARGIN_Y) / ROWS;
        List<String> contents = new ArrayList<>();
        for (int p = 0; p < pages; p++) {
            StringBuilder c = new StringBuilder();
            for (int i = p * perPage; i < Math.min(labels.size(), (p + 1) * perPage); i++) {
                int idx = i - p * perPage;
                double x0 = MARGIN_X + (idx % COLS) * cellW;
                double y0 = PAGE_H - MARGIN_Y - (idx / COLS + 1) * cellH;
                Label l = labels.get(i);
                double qrSize = cellH - 16;
                BitMatrix m = matrix(l.url());
                double unit = qrSize / m.getWidth();
                double qx = x0 + 8;
                double qy = y0 + 8;
                c.append("0 g\n");
                for (int y = 0; y < m.getHeight(); y++) {
                    for (int x = 0; x < m.getWidth(); x++) {
                        if (m.get(x, y)) {
                            c.append(num(qx + x * unit)).append(' ').append(num(qy + (m.getHeight() - 1 - y) * unit)).append(' ')
                                    .append(num(unit)).append(' ').append(num(unit)).append(" re\n");
                        }
                    }
                }
                c.append("f\n");
                double tx = qx + qrSize + 8;
                text(c, tx, y0 + cellH - 24, 9, l.externalId());
                text(c, tx, y0 + cellH - 38, 8, l.name());
            }
            contents.add(c.toString());
        }
        return write(contents);
    }

    private static void text(StringBuilder c, double x, double y, int size, String s) {
        c.append("BT /F1 ").append(size).append(" Tf ").append(num(x)).append(' ').append(num(y)).append(" Td (")
                .append(escape(truncate(s, 22))).append(") Tj ET\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "~" : s;
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') {
                b.append('\\').append(ch);
            } else if (ch < 32 || ch > 126) {
                b.append('?');
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** 객체: 1 카탈로그, 2 페이지 트리, 3 글꼴, 그 뒤로 페이지·내용 쌍 */
    private static byte[] write(List<String> contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int pageCount = contents.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(4 + i * 2).append(" 0 R ");
        }
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pageCount + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < pageCount; i++) {
            int contentObj = 5 + i * 2;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(PAGE_W) + " " + num(PAGE_H) + "] /Resources << /Font << /F1 3 0 R >> >>"
                    + " /Contents " + contentObj + " 0 R >>");
            byte[] stream = contents.get(i).getBytes(StandardCharsets.ISO_8859_1);
            objects.add("<< /Length " + stream.length + " >>\nstream\n" + contents.get(i) + "endstream");
        }
        put(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            put(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) {
            x.append(String.format(Locale.ROOT, "%010d 00000 n \n", o));
        }
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        put(out, x.toString());
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
