package net.java21.data2flow.core.dataexchange.domain;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 최소 XLSX(Office Open XML) 스트리밍 쓰기(API-TSD-20, BR-TSD-14). 외부 라이브러리 없이 시트 XML을 행마다 바로 zip에 쓴다(메모리 일정).
 * 문자열은 inline string, 숫자는 숫자 셀, 시각은 tz 기준 ISO-8601 문자열. 시트당 데이터 100만 행을 넘으면 새 시트(머리글 반복, AT-TSD-04.3).
 */
public final class XlsxRowSink implements RowSink {

    /** 시트당 데이터 행 수(BR-TSD-14). 엑셀 한도 1,048,576행 안 */
    public static final int MAX_ROWS_PER_SHEET = 1_000_000;
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private final ZipOutputStream zip;
    private final Writer writer;
    private final DateTimeFormatter time;
    private final int maxRowsPerSheet;
    private List<Column> columns;
    private int sheets;
    private int rowsInSheet;
    private long rows;

    public XlsxRowSink(OutputStream out, ZoneId zone, int maxRowsPerSheet) {
        this.zip = new ZipOutputStream(new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        });
        this.writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        this.time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone);
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    @Override
    public void header(List<Column> columns) throws IOException {
        this.columns = List.copyOf(columns);
        startSheet();
    }

    @Override
    public void row(Object[] values) throws IOException {
        if (rowsInSheet >= maxRowsPerSheet) {
            endSheet();
            startSheet();
        }
        writer.write("<row>");
        for (Object v : values) {
            if (v == null) {
                writer.write("<c/>");
            } else if (v instanceof Number n && !(v instanceof Double d && (d.isNaN() || d.isInfinite()))) {
                writer.write("<c><v>");
                writer.write(v instanceof Double d ? CsvRowSink.number(d) : n.toString());
                writer.write("</v></c>");
            } else {
                text(v instanceof Instant t ? time.format(t) : v.toString());
            }
        }
        writer.write("</row>");
        rowsInSheet++;
        rows++;
    }

    /** 만든 시트 수 */
    public int sheets() {
        return sheets;
    }

    @Override
    public long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        if (columns == null) {
            header(List.of());
        }
        endSheet();
        entry("[Content_Types].xml", contentTypes());
        entry("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
                Target="xl/workbook.xml"/></Relationships>""");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"" + NS
                + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets; i++) {
            wb.append("<sheet name=\"data").append(i).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        entry("xl/workbook.xml", wb.append("</sheets></workbook>").toString());
        entry("xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        entry("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"" + NS + "\">"
                + "<fonts count=\"1\"><font/></fonts><fills count=\"1\"><fill/></fills><borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"1\"><xf/></cellXfs></styleSheet>");
        zip.finish();
        zip.flush();
    }

    private String contentTypes() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return b.append("</Types>").toString();
    }

    private void startSheet() throws IOException {
        sheets++;
        rowsInSheet = 0;
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheets + ".xml"));
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"" + NS + "\"><sheetData>");
        writer.write("<row>");
        for (Column c : columns) {
            text(c.name());
        }
        writer.write("</row>");
    }

    private void endSheet() throws IOException {
        writer.write("</sheetData></worksheet>");
        writer.flush();
        zip.closeEntry();
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        writer.write(content);
        writer.flush();
        zip.closeEntry();
    }

    private void text(String s) throws IOException {
        writer.write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">");
        writer.write(xml(s));
        writer.write("</t></is></c>");
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
