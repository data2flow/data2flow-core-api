package net.java21.data2flow.core.source.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * generic-json 매핑의 JSONPath 일부 문법 검사(웹 {@code features/sources/model/mapping.ts parseJsonPath}와 같은 규칙, TC-ING-040):
 * {@code $}로 시작하고 {@code .name}, {@code ['name']}, {@code [0]}, {@code [*]}만 쓴다.
 */
public final class JsonPathLite {

    private static final Pattern DOT_KEY = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");
    private static final Pattern STAR = Pattern.compile("\\[\\*]");
    private static final Pattern QUOTED = Pattern.compile("\\['([^']+)']");

    private JsonPathLite() {
    }

    public static boolean valid(String path) {
        if (path == null || path.length() > 256 || !path.startsWith("$")) {
            return false;
        }
        int i = 1;
        while (i < path.length()) {
            int next = advance(path, i);
            if (next < 0) {
                return false;
            }
            i = next;
        }
        return true;
    }

    private static int advance(String path, int i) {
        for (Pattern p : new Pattern[]{DOT_KEY, INDEX, STAR, QUOTED}) {
            Matcher m = p.matcher(path).region(i, path.length());
            if (m.lookingAt()) {
                return m.end();
            }
        }
        return -1;
    }
}
