package net.java21.data2flow.core.modelexchange.domain;

import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.ExportDevice;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.ExportPoint;
import net.java21.data2flow.core.modelexchange.repository.ModelExchangeRepository.ExportSpace;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 공간·기기·점 모델 표준 형식 쓰기(DEV-13.04, BR-DEV-36): NGSI-LD Smart Data Models({@code Device}, 현재값이면
 * {@code AirQualityObserved}), Brick 1.3(Turtle·JSON-LD). 각 형식의 공개 스키마 필수 항목·어휘를 검사해 통과한 항목만 담고, 빠진 항목은
 * 보고서({@code skipped})에 남긴다. 시맨틱 태그가 없는 점은 "태그 없음"으로 빠진다(UC-DEV-26 예외 흐름).
 */
public final class StandardFormats {

    public static final String NGSI_CORE_CONTEXT = "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld";
    public static final String SDM_DEVICE_CONTEXT = "https://raw.githubusercontent.com/smart-data-models/dataModel.Device/master/context.jsonld";
    public static final String SDM_ENVIRONMENT_CONTEXT =
            "https://raw.githubusercontent.com/smart-data-models/dataModel.Environment/master/context.jsonld";
    public static final String BRICK = "https://brickschema.org/schema/Brick#";
    static final Pattern URN = Pattern.compile("^urn:ngsi-ld:[A-Za-z][A-Za-z0-9]*:[A-Za-z0-9_.:-]+$");

    /** Smart Data Models Device.controlledProperty 열거값 중 우리 측정 항목과 맞는 것 */
    static final Map<String, String> CONTROLLED = Map.ofEntries(Map.entry("temperature", "temperature"), Map.entry("humidity", "humidity"),
            Map.entry("co2", "airPollution"), Map.entry("tvoc", "airPollution"), Map.entry("pm10", "airPollution"),
            Map.entry("pm2_5", "airPollution"), Map.entry("pm25", "airPollution"), Map.entry("pressure", "atmosphericPressure"),
            Map.entry("battery", "batteryLevel"), Map.entry("illuminance", "light"), Map.entry("light", "light"),
            Map.entry("occupancy", "occupancy"), Map.entry("pir", "motion"), Map.entry("motion", "motion"),
            Map.entry("noise", "noiseLevel"), Map.entry("power", "power"), Map.entry("energy", "energy"));
    /** AirQualityObserved 속성(이름, UN/CEFACT 단위, 비율 변환) */
    static final Map<String, Object[]> AIR = Map.of("co2", new Object[]{"co2", "59", false}, "temperature", new Object[]{"temperature", "CEL", false},
            "humidity", new Object[]{"relativeHumidity", "P1", true}, "pm10", new Object[]{"pm10", "GQ", false},
            "pm2_5", new Object[]{"pm25", "GQ", false}, "pm25", new Object[]{"pm25", "GQ", false}, "tvoc", new Object[]{"tvoc", "61", false});
    static final Map<String, String> SPACE_CLASSES = Map.of("SITE", "Site", "BUILDING", "Building", "FLOOR", "Floor", "ROOM", "Room",
            "ZONE", "Zone", "AREA", "Space");
    static final Map<String, String> POINT_CLASSES = Map.ofEntries(Map.entry("temperature", "Temperature_Sensor"),
            Map.entry("relative_humidity", "Relative_Humidity_Sensor"), Map.entry("humidity", "Relative_Humidity_Sensor"),
            Map.entry("co2_concentration", "CO2_Sensor"), Map.entry("co2", "CO2_Sensor"), Map.entry("illuminance", "Illuminance_Sensor"),
            Map.entry("occupancy", "Occupancy_Sensor"), Map.entry("pressure", "Pressure_Sensor"), Map.entry("power", "Electric_Power_Sensor"),
            Map.entry("energy", "Energy_Sensor"), Map.entry("pm10_concentration", "PM10_Sensor"), Map.entry("pm2.5_concentration", "PM2.5_Sensor"),
            Map.entry("tvoc_concentration", "TVOC_Sensor"));
    static final Map<String, String> POINT_TYPES = Map.of("MEASUREMENT", "Sensor", "SETPOINT", "Setpoint", "CONTROL", "Command", "STATUS", "Status",
            "ALARM", "Alarm", "PARAMETER", "Parameter");

    private StandardFormats() {
    }

    /** 만든 파일과 보고서 */
    public record Output(byte[] content, String contentType, String extension, int exported, List<Map<String, Object>> skipped,
                         Map<String, Object> summary) {
    }

    // ---- NGSI-LD

    public static Output ngsiLd(JsonMapper json, long organizationId, List<ExportDevice> devices, boolean includeValues, Instant now) {
        ArrayNode entities = json.createArrayNode();
        List<Map<String, Object>> skipped = new ArrayList<>();
        int air = 0;
        for (ExportDevice d : devices) {
            JsonNode latest = d.latestJson() == null ? json.createObjectNode() : json.readTree(d.latestJson());
            Set<String> controlled = new LinkedHashSet<>();
            latest.propertyNames().forEach(k -> {
                String c = CONTROLLED.get(k.toLowerCase(Locale.ROOT));
                if (c != null) {
                    controlled.add(c);
                }
            });
            for (String k : d.modelMetricKeys()) {
                String c = CONTROLLED.get(k.toLowerCase(Locale.ROOT));
                if (c != null) {
                    controlled.add(c);
                }
            }
            String urn = "urn:ngsi-ld:Device:d2f-" + organizationId + "-" + d.id();
            if (controlled.isEmpty()) {
                skipped.add(skip(d.id(), "Device", "controlledProperty에 맞는 측정 항목이 없습니다"));
                continue;
            }
            ObjectNode e = entities.addObject();
            e.put("id", urn);
            e.put("type", "Device");
            property(e, "name", d.name());
            property(e, "serialNumber", d.externalId());
            ArrayNode cp = e.putObject("controlledProperty").put("type", "Property").putArray("value");
            controlled.forEach(cp::add);
            e.putObject("deviceCategory").put("type", "Property").putArray("value").add("sensor");
            if (d.vendor() != null) {
                property(e, "manufacturerName", d.vendor());
            }
            if (d.modelCode() != null) {
                property(e, "modelName", d.modelCode());
            }
            if (d.battery() != null) {
                e.putObject("batteryLevel").put("type", "Property")
                        .put("value", d.battery().divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP).doubleValue());
            }
            if (includeValues && d.lastSeenAt() != null) {
                dateTime(e, "dateLastValueReported", d.lastSeenAt());
            }
            ArrayNode ctx = e.putArray("@context");
            ctx.add(SDM_DEVICE_CONTEXT);
            ctx.add(NGSI_CORE_CONTEXT);
            List<String> problems = validateNgsi(e);
            if (!problems.isEmpty()) {
                entities.remove(entities.size() - 1);
                skipped.add(skip(d.id(), "Device", String.join("; ", problems)));
                continue;
            }
            if (includeValues) {
                ObjectNode a = json.createObjectNode();
                boolean any = false;
                Instant observed = null;
                for (String key : iterable(latest)) {
                    Object[] m = AIR.get(key.toLowerCase(Locale.ROOT));
                    JsonNode v = latest.get(key).get("v");
                    if (m == null || v == null || !v.isNumber()) {
                        continue;
                    }
                    double value = (Boolean) m[2] ? v.asDouble() / 100.0 : v.asDouble();
                    a.putObject((String) m[0]).put("type", "Property").put("value", value).put("unitCode", (String) m[1]);
                    any = true;
                    String t = latest.get(key).path("t").asString("");
                    if (!t.isEmpty()) {
                        Instant ti = Instant.parse(t);
                        observed = observed == null || ti.isAfter(observed) ? ti : observed;
                    }
                }
                if (any) {
                    a.put("id", "urn:ngsi-ld:AirQualityObserved:d2f-" + organizationId + "-" + d.id());
                    a.put("type", "AirQualityObserved");
                    dateTime(a, "dateObserved", observed == null ? now : observed);
                    a.putObject("refDevice").put("type", "Relationship").put("object", urn);
                    ArrayNode actx = a.putArray("@context");
                    actx.add(SDM_ENVIRONMENT_CONTEXT);
                    actx.add(NGSI_CORE_CONTEXT);
                    if (validateNgsi(a).isEmpty()) {
                        entities.add(a);
                        air++;
                    }
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("devices", entities.size() - air);
        summary.put("airQualityObserved", air);
        return new Output(json.writeValueAsString(entities).getBytes(StandardCharsets.UTF_8), "application/ld+json", "jsonld",
                entities.size(), skipped, summary);
    }

    /** Smart Data Models 필수 항목·형식 검사(Device: id·type·controlledProperty, AirQualityObserved: id·type·dateObserved·refDevice) */
    public static List<String> validateNgsi(JsonNode e) {
        List<String> problems = new ArrayList<>();
        if (!URN.matcher(e.path("id").asString("")).matches()) {
            problems.add("id는 urn:ngsi-ld: URN이어야 합니다");
        }
        String type = e.path("type").asString("");
        if (!e.path("id").asString("").startsWith("urn:ngsi-ld:" + type + ":")) {
            problems.add("id의 형식 이름이 type과 다릅니다");
        }
        switch (type) {
            case "Device" -> {
                JsonNode cp = e.path("controlledProperty").path("value");
                if (!cp.isArray() || cp.isEmpty()) {
                    problems.add("controlledProperty가 필요합니다");
                } else {
                    cp.forEach(v -> {
                        if (!CONTROLLED.containsValue(v.asString(""))) {
                            problems.add("controlledProperty 값이 열거값이 아닙니다: " + v.asString(""));
                        }
                    });
                }
            }
            case "AirQualityObserved" -> {
                if (!e.path("dateObserved").path("value").has("@value")) {
                    problems.add("dateObserved가 필요합니다");
                }
                if (!"Relationship".equals(e.path("refDevice").path("type").asString(""))) {
                    problems.add("refDevice는 Relationship이어야 합니다");
                }
            }
            default -> problems.add("지원하지 않는 형식: " + type);
        }
        if (!e.has("@context")) {
            problems.add("@context가 필요합니다");
        }
        return problems;
    }

    // ---- Brick

    /** Brick 1.3 그래프(공간 계층, 장비 = 기기, 점 = 시맨틱 점). turtle이 false면 JSON-LD */
    public static Output brick(JsonMapper json, long organizationId, List<ExportSpace> spaces, List<ExportDevice> devices,
                               List<ExportPoint> points, boolean turtle) {
        List<String[]> triples = new ArrayList<>(); // subject, predicate, object(이미 표기된 값)
        List<Map<String, Object>> skipped = new ArrayList<>();
        String ns = "urn:data2flow:org-" + organizationId + ":";
        for (ExportSpace s : spaces) {
            String subject = "space_" + s.id();
            triples.add(new String[]{subject, "a", "brick:" + SPACE_CLASSES.getOrDefault(s.type(), "Space")});
            triples.add(new String[]{subject, "rdfs:label", literal(s.name())});
            if (s.parentId() != null && spaces.stream().anyMatch(x -> x.id() == s.parentId())) {
                triples.add(new String[]{subject, "brick:isPartOf", "d2f:space_" + s.parentId()});
            }
        }
        int pointCount = 0;
        for (ExportDevice d : devices) {
            String subject = "device_" + d.id();
            String equipClass = points.stream().filter(p -> p.deviceId() == d.id()).map(ExportPoint::equipClass)
                    .filter(c -> c != null && c.matches("^(brick:)?[A-Z][A-Za-z0-9_.]*$")).findFirst()
                    .map(c -> c.startsWith("brick:") ? c : "brick:" + c).orElse("brick:Equipment");
            triples.add(new String[]{subject, "a", equipClass});
            triples.add(new String[]{subject, "rdfs:label", literal(d.name())});
            if (d.spaceId() != null) {
                triples.add(new String[]{subject, "brick:hasLocation", "d2f:space_" + d.spaceId()});
            }
            for (ExportPoint p : points.stream().filter(x -> x.deviceId() == d.id()).toList()) {
                String cls = pointClass(p);
                if (cls == null) {
                    Map<String, Object> s = skip(d.id(), "Point", "태그 없음");
                    s.put("metricKey", p.metricKey());
                    skipped.add(s);
                    continue;
                }
                String ps = "point_" + p.pointId();
                triples.add(new String[]{ps, "a", "brick:" + cls});
                triples.add(new String[]{ps, "rdfs:label", literal(p.metricKey())});
                triples.add(new String[]{ps, "brick:isPointOf", "d2f:" + subject});
                pointCount++;
            }
        }
        byte[] content;
        if (turtle) {
            StringBuilder b = new StringBuilder();
            b.append("@prefix brick: <").append(BRICK).append("> .\n");
            b.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
            b.append("@prefix d2f: <").append(ns).append("> .\n\n");
            for (String[] t : triples) {
                b.append("d2f:").append(t[0]).append(' ').append(t[1]).append(' ').append(t[2]).append(" .\n");
            }
            content = b.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            ObjectNode root = json.createObjectNode();
            ObjectNode ctx = root.putObject("@context");
            ctx.put("brick", BRICK);
            ctx.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
            ctx.put("d2f", ns);
            Map<String, ObjectNode> nodes = new LinkedHashMap<>();
            ArrayNode graph = root.putArray("@graph");
            for (String[] t : triples) {
                ObjectNode n = nodes.computeIfAbsent(t[0], k -> graph.addObject().put("@id", "d2f:" + k));
                switch (t[1]) {
                    case "a" -> n.put("@type", t[2]);
                    case "rdfs:label" -> n.put("rdfs:label", unliteral(t[2]));
                    default -> n.putObject(t[1]).put("@id", t[2]);
                }
            }
            content = json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spaces", spaces.size());
        summary.put("equipment", devices.size());
        summary.put("points", pointCount);
        return new Output(content, turtle ? "text/turtle" : "application/ld+json", turtle ? "ttl" : "jsonld", spaces.size() + devices.size()
                + pointCount, skipped, summary);
    }

    /** 점의 Brick 클래스. 물리량이 알려진 것이면 그 센서, 아니면 점 종류, 태그·물리량이 모두 없으면 null(태그 없음) */
    static String pointClass(ExportPoint p) {
        if (p.quantity() != null && !p.quantity().isBlank()) {
            String q = p.quantity().toLowerCase(Locale.ROOT).replace("brick:", "").replace(' ', '_');
            String cls = POINT_CLASSES.get(q);
            if (cls != null) {
                return cls;
            }
        }
        if ((p.quantity() == null || p.quantity().isBlank()) && p.tags().isEmpty()) {
            return null;
        }
        return POINT_TYPES.getOrDefault(p.pointType() == null ? "" : p.pointType().toUpperCase(Locale.ROOT), "Point");
    }

    // ---- 공통

    private static Iterable<String> iterable(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.propertyNames().forEach(out::add);
        return out;
    }

    private static void property(ObjectNode e, String name, String value) {
        if (value != null) {
            e.putObject(name).put("type", "Property").put("value", value);
        }
    }

    private static void dateTime(ObjectNode e, String name, Instant at) {
        ObjectNode v = e.putObject(name).put("type", "Property").putObject("value");
        v.put("@type", "DateTime");
        v.put("@value", at.toString());
    }

    static String literal(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String unliteral(String lit) {
        return lit.substring(1, lit.length() - 1).replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Map<String, Object> skip(long deviceId, String type, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", Long.toString(deviceId));
        m.put("type", type);
        m.put("reason", reason);
        return m;
    }
}
