package net.java21.data2flow.core.live.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실시간 구독 토픽(API-DSH-20·21). 연결당 최대 {@value #MAX_TOPICS}개.
 *
 * <ul>
 *   <li>{@code home} → {@code home-summary}</li>
 *   <li>{@code space:{id}} → {@code device-update}(그 공간과 하위 공간의 기기)</li>
 *   <li>{@code telemetry:{deviceId}.{metric}} → {@code point}. 문서 예의 {@code d17} 표기도 받는다</li>
 *   <li>{@code ingest} → {@code ingest-stats}(5초)</li>
 *   <li>{@code sources} → {@code source-state}(소스 대표 연결 상태가 바뀔 때, DSC-02.01 "5초 이내 화면 반영")</li>
 *   <li>{@code ingest-messages?sourceId=&deviceId=&result=} → {@code message}(초당 최대 20건)</li>
 *   <li>{@code notifications}·{@code alarms}·{@code commands:{id}}·{@code analytics:run:{id}}: 이후 마일스톤(M4~M6)의 토픽. 받아 두지만 M2에서는 이벤트가 없다</li>
 * </ul>
 */
public sealed interface LiveTopic {

    int MAX_TOPICS = 200;

    /** 요청에 쓴 그대로의 이름(ready 이벤트·로그용) */
    String raw();

    record Home(String raw) implements LiveTopic {
    }

    record Space(String raw, long spaceId) implements LiveTopic {
    }

    record Telemetry(String raw, long deviceId, String metricKey) implements LiveTopic {
    }

    record Ingest(String raw) implements LiveTopic {
    }

    /** 조직의 데이터 소스 연결 상태 변경(SRC_READ) */
    record Sources(String raw) implements LiveTopic {
    }

    /** 수집 메시지 필터. 비어 있는 조건은 null */
    record IngestMessages(String raw, Long sourceId, Long deviceId, String result) implements LiveTopic {
    }

    /** 이후 마일스톤 토픽(이벤트 없음) */
    record Future(String raw) implements LiveTopic {
    }

    Pattern SPACE = Pattern.compile("space:(\\d{1,18})");
    Pattern TELEMETRY = Pattern.compile("telemetry:d?(\\d{1,18})\\.([A-Za-z][A-Za-z0-9_]{0,63})");
    Pattern FUTURE = Pattern.compile("notifications|alarms|commands:\\d{1,18}|analytics:run:\\d{1,18}");
    Pattern RESULT = Pattern.compile("[A-Z_]{1,32}");

    /** 쉼표로 이은 토픽 목록을 읽는다. 형식이 틀린 토픽은 {@link Parsed#invalid()}에 모은다(중복은 한 번만) */
    static Parsed parse(String topics) {
        Set<String> unique = new LinkedHashSet<>();
        if (topics != null) {
            for (String part : topics.split(",")) {
                String t = part.strip();
                if (!t.isEmpty()) {
                    unique.add(t);
                }
            }
        }
        List<LiveTopic> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String t : unique) {
            LiveTopic topic = parseOne(t);
            if (topic == null) {
                invalid.add(t);
            } else {
                valid.add(topic);
            }
        }
        return new Parsed(valid, invalid, unique.size());
    }

    private static LiveTopic parseOne(String t) {
        if ("home".equals(t)) {
            return new Home(t);
        }
        if ("ingest".equals(t)) {
            return new Ingest(t);
        }
        if ("sources".equals(t)) {
            return new Sources(t);
        }
        if (FUTURE.matcher(t).matches()) {
            return new Future(t);
        }
        Matcher space = SPACE.matcher(t);
        if (space.matches()) {
            return new Space(t, Long.parseLong(space.group(1)));
        }
        Matcher telemetry = TELEMETRY.matcher(t);
        if (telemetry.matches()) {
            return new Telemetry(t, Long.parseLong(telemetry.group(1)), telemetry.group(2));
        }
        if (t.equals("ingest-messages") || t.startsWith("ingest-messages?")) {
            return ingestMessages(t);
        }
        return null;
    }

    private static LiveTopic ingestMessages(String t) {
        Long sourceId = null;
        Long deviceId = null;
        String result = null;
        int q = t.indexOf('?');
        if (q >= 0) {
            for (String pair : t.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1).strip();
                if (value.isEmpty()) {
                    continue;
                }
                switch (key) {
                    case "sourceId" -> {
                        if (!value.matches("\\d{1,18}")) {
                            return null;
                        }
                        sourceId = Long.parseLong(value);
                    }
                    case "deviceId" -> {
                        if (!value.matches("\\d{1,18}")) {
                            return null;
                        }
                        deviceId = Long.parseLong(value);
                    }
                    case "result" -> {
                        String upper = value.toUpperCase(Locale.ROOT);
                        if (!RESULT.matcher(upper).matches()) {
                            return null;
                        }
                        result = upper;
                    }
                    default -> {
                        return null;
                    }
                }
            }
        }
        return new IngestMessages(t, sourceId, deviceId, result);
    }

    /** 읽은 결과. count는 중복을 뺀 요청 토픽 수 */
    record Parsed(List<LiveTopic> valid, List<String> invalid, int count) {
    }
}
