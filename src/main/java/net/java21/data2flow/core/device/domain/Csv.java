package net.java21.data2flow.core.device.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 작은 CSV 읽기·쓰기(RFC 4180: 큰따옴표로 감싼 칸, 칸 안의 "" 는 ", 줄바꿈 CRLF·LF). 엑셀 수식 주입을 막으려고 = + - @ 로 시작하는 칸은
 * 내보낼 때 앞에 ' 를 붙인다.
 */
public final class Csv {

    private Csv() {
    }

    /** 줄 번호(1부터)와 칸들 */
    public record Line(int number, List<String> cells) {
    }

    public static List<Line> parse(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        List<Line> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int start = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                add(lines, start, cells);
                cells = new ArrayList<>();
                line++;
                start = line;
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            add(lines, start, cells);
        }
        return lines;
    }

    private static void add(List<Line> lines, int number, List<String> cells) {
        if (cells.size() == 1 && cells.get(0).isBlank()) {
            return;
        }
        lines.add(new Line(number, List.copyOf(cells)));
    }

    public static String row(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        String v = !raw.isEmpty() && "=+-@".indexOf(raw.charAt(0)) >= 0 ? "'" + raw : raw;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
