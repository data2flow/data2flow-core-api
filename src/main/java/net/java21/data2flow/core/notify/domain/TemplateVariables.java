package net.java21.data2flow.core.notify.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알림 템플릿 변수(RUL-03.04·05.01, UI-RUL-07): {@code {{이름}}} 치환과 알 수 없는 변수 경고(TEMPLATE_VARIABLE_UNKNOWN).
 */
public final class TemplateVariables {

    /** 쓸 수 있는 변수(API-RUL-22 변수 목록) */
    public static final List<String> KNOWN = List.of("alarm.severity", "alarm.title", "alarm.id", "alarm.status", "device.name",
            "space.path", "space.name", "value", "threshold", "metric", "rule.name", "link", "occurredAt", "occurrenceCount");

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.]*)\\s*}}");

    private TemplateVariables() {
    }

    /** 본문에 쓰인 변수 중 모르는 것(쓴 순서, 중복 없이) */
    public static List<String> unknown(String... texts) {
        Set<String> result = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = VARIABLE.matcher(text);
            while (m.find()) {
                if (!KNOWN.contains(m.group(1))) {
                    result.add(m.group(1));
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 치환. 값이 없는 변수는 빈 문자열 */
    public static String render(String template, Map<String, ?> values) {
        if (template == null) {
            return null;
        }
        Matcher m = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
