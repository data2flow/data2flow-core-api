package net.java21.data2flow.core.bim.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IFC(STEP 물리 파일, ISO 10303-21) 최소 읽기(DSH-12.04, BR-DSH-21). 3D 표시는 브라우저(web-ifc)가 하고 서버는 형식 확인·스키마·요소 수·
 * 공간 요소(IfcSpace) 목록만 뽑는다(공간 연결 화면용). 첫 줄이 {@code ISO-10303-21;}가 아니면 IFC가 아니다.
 */
public final class IfcFile {

    public static final long MAX_BYTES = 200L * 1024 * 1024;

    private static final Pattern SCHEMA = Pattern.compile("FILE_SCHEMA\\s*\\(\\s*\\(\\s*'([A-Z0-9_]+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile("^\\s*#\\d+\\s*=");
    private static final Pattern SPACE = Pattern.compile("=\\s*IFCSPACE\\s*\\(\\s*'([0-9A-Za-z_$]{22})'\\s*,[^,]*,\\s*(?:'((?:[^']|'')*)'|\\$)",
            Pattern.CASE_INSENSITIVE);

    private IfcFile() {
    }

    /** 읽은 결과. schema가 null이면 FILE_SCHEMA를 못 찾았거나 지원하지 않는 스키마 */
    public record Parsed(String schema, int elementCount, Map<String, String> spaces) {
    }

    /** 첫 줄(BOM·공백 무시)이 ISO-10303-21 머리글인가 */
    public static boolean looksLikeIfc(byte[] head) {
        String s = new String(head, 0, Math.min(head.length, 64), StandardCharsets.ISO_8859_1).replace("﻿", "").replace("ï»¿", "").strip();
        return s.startsWith("ISO-10303-21;");
    }

    public static Parsed parse(byte[] data) {
        String schema = null;
        int count = 0;
        Map<String, String> spaces = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (schema == null) {
                    Matcher m = SCHEMA.matcher(line);
                    if (m.find()) {
                        schema = normalize(m.group(1));
                    }
                }
                if (ENTITY.matcher(line).find()) {
                    count++;
                    Matcher sp = SPACE.matcher(line);
                    if (sp.find()) {
                        spaces.put(sp.group(1), sp.group(2) == null ? null : sp.group(2).replace("''", "'"));
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new Parsed(schema, count, spaces);
    }

    /** IFC2X3·IFC4·IFC4X3(IFC4X3_ADD2 등 개정판 포함). 그 밖은 null */
    static String normalize(String raw) {
        String s = raw.toUpperCase(Locale.ROOT);
        if (s.startsWith("IFC4X3")) {
            return "IFC4X3";
        }
        if (s.startsWith("IFC2X3")) {
            return "IFC2X3";
        }
        if (s.startsWith("IFC4")) {
            return "IFC4";
        }
        return null;
    }
}
