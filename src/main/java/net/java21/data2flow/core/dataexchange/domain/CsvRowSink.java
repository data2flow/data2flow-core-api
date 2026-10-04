package net.java21.data2flow.core.dataexchange.domain;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 쓰기(API-TSD-20, AT-TSD-04.1): UTF-8 BOM(엑셀 호환), RFC 4180 따옴표, 줄바꿈 CRLF 대신 LF, 시각은 tz 기준 ISO-8601(오프셋 포함).
 * 엑셀이 수식으로 읽는 값(=,+,-,@로 시작하는 문자열)은 앞에 작은따옴표를 붙여 수식 주입을 막는다.
 */
public final class CsvRowSink implements RowSink {

    static final char BOM = '﻿';

    private final BufferedWriter writer;
    private final DateTimeFormatter time;
    private List<Column> columns;
    private long rows;

    public CsvRowSink(OutputStream out, ZoneId zone) {
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        this.time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone);
    }

    @Override
    public void header(List<Column> columns) throws IOException {
        this.columns = List.copyOf(columns);
        writer.write(BOM);
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escape(columns.get(i).name()));
        }
        writer.write('\n');
    }

    @Override
    public void row(Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            Object v = values[i];
            if (v == null) {
                continue;
            }
            if (v instanceof Instant t) {
                writer.write(time.format(t));
            } else if (v instanceof Double d) {
                writer.write(number(d));
            } else if (v instanceof Number n) {
                writer.write(n.toString());
            } else {
                writer.write(escape(v.toString()));
            }
        }
        writer.write('\n');
        rows++;
    }

    @Override
    public long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }

    /** 숫자: 지수 표기 없이, 정수면 소수점 없이 */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "";
        }
        BigDecimal b = BigDecimal.valueOf(d).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
    }

    /** RFC 4180 따옴표 + 수식 주입 방지 */
    public static String escape(String raw) {
        String s = raw;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !isNumeric(s)) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    List<Column> columns() {
        return columns;
    }
}
