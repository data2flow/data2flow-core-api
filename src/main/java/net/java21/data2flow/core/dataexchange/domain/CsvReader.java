package net.java21.data2flow.core.dataexchange.domain;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 스트리밍 CSV 읽기(RFC 4180, 가져오기 TSD-04.02). 파일 전체를 메모리에 올리지 않고 한 줄(따옴표 안 줄바꿈 포함)씩 돌려준다.
 * 첫 글자의 UTF-8 BOM은 건너뛴다. 줄 번호는 물리적 시작 줄(1부터, 머리글이 1).
 */
public final class CsvReader {

    /** 한 레코드 */
    public record Record(long line, List<String> cells) {
    }

    private final Reader reader;
    private final char delimiter;
    private long line = 1;
    private int peeked = -2;
    private boolean first = true;

    public CsvReader(Reader reader, char delimiter) {
        this.reader = reader;
        this.delimiter = delimiter;
    }

    /** 다음 레코드. 끝이면 null. 닫히지 않은 따옴표면 IllegalStateException(줄 번호 포함) */
    public Record next() throws IOException {
        int c = read();
        if (first) {
            first = false;
            if (c == '﻿') {
                c = read();
            }
        }
        if (c == -1) {
            return null;
        }
        long start = line;
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        while (true) {
            if (quoted) {
                if (c == -1) {
                    throw new IllegalStateException("닫히지 않은 따옴표: " + start + "행");
                }
                if (c == '"') {
                    int n = read();
                    if (n == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        c = n;
                        continue;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append((char) c);
                }
                c = read();
                continue;
            }
            if (c == -1 || c == '\n' || c == '\r') {
                cells.add(wasQuoted ? cell.toString() : cell.toString().strip());
                if (c == '\r') {
                    int n = read();
                    if (n != '\n') {
                        unread(n);
                    }
                }
                if (c != -1) {
                    line++;
                }
                return new Record(start, cells);
            }
            if (c == delimiter) {
                cells.add(wasQuoted ? cell.toString() : cell.toString().strip());
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
                wasQuoted = true;
            } else {
                cell.append((char) c);
            }
            c = read();
        }
    }

    private int read() throws IOException {
        if (peeked != -2) {
            int p = peeked;
            peeked = -2;
            return p;
        }
        return reader.read();
    }

    private void unread(int c) {
        peeked = c;
    }
}
