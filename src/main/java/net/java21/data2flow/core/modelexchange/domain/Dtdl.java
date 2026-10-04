package net.java21.data2flow.core.modelexchange.domain;

import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Capability;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.MetricDef;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Unmapped;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * DTDL v3 호환 매핑(DEV-03.04·13.04, BR-DEV-36). 모델 → Interface(측정 항목 = Telemetry, 기능 = Command, 속성 스키마 = Property),
 * Interface → 모델(Telemetry → 측정 항목, Command → 카탈로그 기능, Property → 속성 스키마). 단위는 DTDL 정량 형식 확장
 * ({@code dtmi:dtdl:extension:quantitativeTypes;1})의 의미 형식·단위로 바꾸고, 바꿀 수 없는 단위는 {@code comment}에 원래 단위를 남긴다.
 * {@link #validate}는 DTDL v3 언어 규칙(DTMI·이름·스키마·고유 이름·최대 개수)을 검사한다(.NET 파서 없이 같은 규칙).
 */
public final class Dtdl {

    public static final String CONTEXT = "dtmi:dtdl:context;3";
    public static final String QUANTITATIVE = "dtmi:dtdl:extension:quantitativeTypes;1";
    static final Pattern DTMI = Pattern.compile("^dtmi:(?:_+[A-Za-z0-9]|[A-Za-z])(?:[A-Za-z0-9_]*[A-Za-z0-9])?(?::(?:_+[A-Za-z0-9]|[A-Za-z])"
            + "(?:[A-Za-z0-9_]*[A-Za-z0-9])?)*(?:;[1-9][0-9]{0,8}(?:\\.[1-9][0-9]{0,5})?)?$");
    static final Pattern NAME = Pattern.compile("^[A-Za-z](?:[A-Za-z0-9_]*[A-Za-z0-9])?$");
    static final Set<String> PRIMITIVES = Set.of("boolean", "date", "dateTime", "double", "duration", "float", "integer", "long", "string",
            "time", "byte", "short", "unsignedByte", "unsignedShort", "unsignedInteger", "unsignedLong", "uuid", "bytes", "decimal");
    static final int MAX_CONTENTS = 100_000;

    /** 우리 단위 → (의미 형식, DTDL 단위) */
    private static final Map<String, String[]> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("℃", new String[]{"Temperature", "degreeCelsius"});
        UNITS.put("°c", new String[]{"Temperature", "degreeCelsius"});
        UNITS.put("℉", new String[]{"Temperature", "degreeFahrenheit"});
        UNITS.put("°f", new String[]{"Temperature", "degreeFahrenheit"});
        UNITS.put("lux", new String[]{"Illuminance", "lux"});
        UNITS.put("lx", new String[]{"Illuminance", "lux"});
        UNITS.put("hpa", new String[]{"Pressure", "millibar"});
        UNITS.put("kpa", new String[]{"Pressure", "kilopascal"});
        UNITS.put("v", new String[]{"Voltage", "volt"});
        UNITS.put("mv", new String[]{"Voltage", "millivolt"});
        UNITS.put("a", new String[]{"Current", "ampere"});
        UNITS.put("w", new String[]{"Power", "watt"});
        UNITS.put("kw", new String[]{"Power", "kilowatt"});
        UNITS.put("kwh", new String[]{"Energy", "kilowattHour"});
        UNITS.put("m/s", new String[]{"Velocity", "metrePerSecond"});
    }

    private Dtdl() {
    }

    /** 모델 코드 → DTMI(예: EM300-TH → dtmi:data2flow:model:em300_th;1) */
    public static String modelDtmi(String code) {
        return "dtmi:data2flow:model:" + segment(code) + ";1";
    }

    public static String segment(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) {
            s = "m" + s;
        }
        return s;
    }

    /** 측정 항목 키 → DTDL 이름(끝 밑줄 제거) */
    public static String name(String key) {
        String s = key.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+$", "");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "p" + s : s;
    }

    /** 모델 문서 → DTDL Interface */
    public static ObjectNode toInterface(JsonMapper json, ModelDocument doc) {
        ObjectNode root = json.createObjectNode();
        ArrayNode ctx = root.putArray("@context");
        ctx.add(CONTEXT);
        ctx.add(QUANTITATIVE);
        root.put("@id", modelDtmi(doc.model().code()));
        root.put("@type", "Interface");
        root.put("displayName", truncate(doc.model().name(), 64));
        if (doc.model().description() != null) {
            root.put("description", truncate(doc.model().description(), 512));
        }
        root.put("comment", truncate("data2flow model " + doc.model().code() + " (" + doc.model().vendor() + ", " + doc.model().protocol()
                + ", " + doc.model().kind() + ")", 512));
        ArrayNode contents = root.putArray("contents");
        for (MetricDef m : doc.metrics()) {
            ObjectNode t = contents.addObject();
            String[] unit = m.unit() == null ? null : UNITS.get(m.unit().strip().toLowerCase(Locale.ROOT));
            String semantic = unit != null ? unit[0] : semanticFromKey(m.key(), m.unit());
            if (semantic != null) {
                ArrayNode type = t.putArray("@type");
                type.add("Telemetry");
                type.add(semantic);
            } else {
                t.put("@type", "Telemetry");
            }
            t.put("name", name(m.key()));
            t.put("schema", "BOOLEAN".equals(m.valueType()) ? "boolean" : "ENUM".equals(m.valueType()) ? "integer" : "double");
            if (unit != null) {
                t.put("unit", unit[1]);
            } else if ("RelativeHumidity".equals(semantic)) {
                t.put("unit", "percent");
            }
            if (m.displayName() != null) {
                t.put("displayName", truncate(m.displayName(), 64));
            }
            if (m.unit() != null || !name(m.key()).equals(m.key())) {
                t.put("comment", truncate("data2flow key=" + m.key() + (m.unit() == null ? "" : "; unit=" + m.unit()), 512));
            }
        }
        for (Capability c : doc.capabilities()) {
            ObjectNode cmd = contents.addObject();
            cmd.put("@type", "Command");
            cmd.put("name", name(c.capability().replace('.', '_')));
            cmd.put("comment", truncate("data2flow capability=" + c.capability(), 512));
        }
        JsonNode props = doc.attributeSchema() == null ? null : doc.attributeSchema().get("properties");
        if (props != null && props.isObject()) {
            for (String key : props.propertyNames()) {
                JsonNode p = props.get(key);
                ObjectNode prop = contents.addObject();
                prop.put("@type", "Property");
                prop.put("name", name(key));
                String type = p.path("type").asString("string");
                prop.put("schema", switch (type) {
                    case "integer" -> "integer";
                    case "number" -> "double";
                    case "boolean" -> "boolean";
                    default -> "string";
                });
                prop.put("writable", true);
            }
        }
        return root;
    }

    /** DTDL Interface → 모델 문서(측정 항목·기능·속성 스키마)와 옮기지 못한 항목 */
    public record Imported(ModelDocument document, List<Unmapped> unmapped) {
    }

    /**
     * @param capabilityOf 명령 이름 → 카탈로그 기능 이름(없으면 null)
     */
    public static Imported fromInterface(JsonMapper json, JsonNode root, Function<String, String> capabilityOf) {
        List<Unmapped> unmapped = new ArrayList<>();
        String id = root.path("@id").asString("");
        String code = codeFromDtmi(id);
        String displayName = displayName(root.get("displayName"), code);
        List<MetricDef> metrics = new ArrayList<>();
        List<Capability> capabilities = new ArrayList<>();
        ObjectNode attributes = null;
        JsonNode contents = root.path("contents");
        int i = 0;
        for (JsonNode c : contents) {
            String path = "contents[" + i++ + "]";
            Set<String> types = types(c.get("@type"));
            String name = c.path("name").asString("");
            if (types.contains("Telemetry")) {
                String schema = c.path("schema").isString() ? c.path("schema").asString("") : "object";
                String valueType = switch (schema) {
                    case "boolean" -> "BOOLEAN";
                    case "double", "float", "integer", "long", "decimal", "short", "byte" -> "NUMBER";
                    default -> null;
                };
                if (valueType == null) {
                    unmapped.add(new Unmapped(path, "Telemetry", "지원하지 않는 schema: " + schema));
                    continue;
                }
                String key = keyFromComment(c.path("comment").asString(""), name);
                String unit = unitFromComment(c.path("comment").asString(""));
                if (unit == null && c.hasNonNull("unit")) {
                    unit = symbol(c.path("unit").asString(""));
                }
                metrics.add(new MetricDef(key, displayName(c.get("displayName"), key), unit, valueType, null, null, null,
                        "BOOLEAN".equals(valueType) ? "LAST" : "AVG", null, Boolean.FALSE));
            } else if (types.contains("Command")) {
                String fromComment = valueFromComment(c.path("comment").asString(""), "data2flow capability=");
                String capability = capabilityOf.apply(fromComment != null ? fromComment : name);
                if (capability == null) {
                    unmapped.add(new Unmapped(path, "Command", "카탈로그에 없는 기능: " + name));
                } else if (capabilities.stream().noneMatch(x -> x.capability().equals(capability))) {
                    capabilities.add(new Capability(capability, null));
                }
            } else if (types.contains("Property")) {
                if (attributes == null) {
                    attributes = json.createObjectNode();
                    attributes.put("type", "object");
                    attributes.putObject("properties");
                }
                String schema = c.path("schema").isString() ? c.path("schema").asString("") : "object";
                String jsonType = switch (schema) {
                    case "integer", "long", "short", "byte" -> "integer";
                    case "double", "float", "decimal" -> "number";
                    case "boolean" -> "boolean";
                    case "string", "date", "dateTime", "time", "duration", "uuid" -> "string";
                    default -> null;
                };
                if (jsonType == null) {
                    unmapped.add(new Unmapped(path, "Property", "지원하지 않는 schema: " + schema));
                    continue;
                }
                ((ObjectNode) attributes.get("properties")).putObject(name).put("type", jsonType);
            } else {
                unmapped.add(new Unmapped(path, String.join("|", types), "지원하지 않는 요소(Relationship·Component 등)"));
            }
        }
        ModelDocument.Model model = new ModelDocument.Model(code, "DTDL", displayName, "OTHER", metrics.isEmpty() ? "ACTUATOR" : capabilities.isEmpty()
                ? "SENSOR" : "HYBRID", null, null, root.hasNonNull("description") ? root.get("description").asString("") : null);
        return new Imported(new ModelDocument(ModelDocument.FORMAT_VERSION, "dtdl", model, metrics, capabilities, attributes, List.of()),
                unmapped);
    }

    /** DTDL v3 규칙 위반 목록(비면 통과) */
    public static List<String> validate(JsonNode root) {
        List<String> errors = new ArrayList<>();
        JsonNode ctx = root.get("@context");
        boolean hasContext = ctx != null && (ctx.isString() ? CONTEXT.equals(ctx.asString("")) : ctx.isArray() && contains(ctx, CONTEXT));
        if (!hasContext) {
            errors.add("@context에 " + CONTEXT + "가 없습니다");
        }
        if (!DTMI.matcher(root.path("@id").asString("")).matches()) {
            errors.add("@id가 DTMI가 아닙니다");
        }
        if (!types(root.get("@type")).contains("Interface")) {
            errors.add("@type은 Interface여야 합니다");
        }
        Set<String> names = new HashSet<>();
        JsonNode contents = root.path("contents");
        if (contents.size() > MAX_CONTENTS) {
            errors.add("contents가 너무 많습니다");
        }
        int i = 0;
        for (JsonNode c : contents) {
            String path = "contents[" + i++ + "]";
            String name = c.path("name").asString("");
            if (!NAME.matcher(name).matches() || name.length() > 512) {
                errors.add(path + ".name이 올바르지 않습니다");
            }
            if (!names.add(name)) {
                errors.add(path + ".name이 겹칩니다");
            }
            Set<String> types = types(c.get("@type"));
            if ((types.contains("Telemetry") || types.contains("Property")) && !PRIMITIVES.contains(c.path("schema").asString(""))
                    && !c.path("schema").isObject()) {
                errors.add(path + ".schema가 올바르지 않습니다");
            }
            if (c.hasNonNull("unit") && types.size() < 2) {
                errors.add(path + ".unit에는 의미 형식(@type)이 필요합니다");
            }
            if (c.hasNonNull("displayName") && c.get("displayName").isString() && c.get("displayName").asString("").length() > 64) {
                errors.add(path + ".displayName은 64자 이하입니다");
            }
        }
        return errors;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> types(JsonNode t) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (t == null) {
            return out;
        }
        if (t.isString()) {
            out.add(t.asString(""));
        } else if (t.isArray()) {
            t.forEach(x -> out.add(x.asString("")));
        }
        return out;
    }

    static String semanticFromKey(String key, String unit) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("humid") && unit != null && unit.contains("%")) {
            return "RelativeHumidity";
        }
        return null;
    }

    private static String symbol(String dtdlUnit) {
        for (Map.Entry<String, String[]> e : UNITS.entrySet()) {
            if (e.getValue()[1].equals(dtdlUnit)) {
                return switch (e.getKey()) {
                    case "°c" -> "℃";
                    case "°f" -> "℉";
                    case "hpa" -> "hPa";
                    case "kpa" -> "kPa";
                    case "kwh" -> "kWh";
                    case "kw" -> "kW";
                    case "v", "a", "w" -> e.getKey().toUpperCase(Locale.ROOT);
                    default -> e.getKey();
                };
            }
        }
        return "percent".equals(dtdlUnit) ? "%" : null;
    }

    private static String keyFromComment(String comment, String fallback) {
        String v = valueFromComment(comment, "data2flow key=");
        return v != null ? v : fallback;
    }

    private static String unitFromComment(String comment) {
        return valueFromComment(comment, "unit=");
    }

    private static String valueFromComment(String comment, String prefix) {
        int at = comment.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        String rest = comment.substring(at + prefix.length());
        int end = rest.indexOf(';');
        return (end < 0 ? rest : rest.substring(0, end)).strip();
    }

    private static String codeFromDtmi(String id) {
        String s = id.startsWith("dtmi:") ? id.substring(5) : id;
        int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        String last = s.contains(":") ? s.substring(s.lastIndexOf(':') + 1) : s;
        String code = last.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "-");
        if (code.isEmpty() || !Character.isLetterOrDigit(code.charAt(0))) {
            code = "M" + code;
        }
        return code.length() > 50 ? code.substring(0, 50) : code.length() < 2 ? code + "0" : code;
    }

    private static String displayName(JsonNode n, String fallback) {
        if (n == null || n.isNull()) {
            return fallback;
        }
        if (n.isString()) {
            return n.asString(fallback);
        }
        if (n.isObject()) {
            for (String lang : List.of("ko", "en")) {
                if (n.hasNonNull(lang)) {
                    return n.get(lang).asString(fallback);
                }
            }
            var it = n.propertyNames().iterator();
            return it.hasNext() ? n.get(it.next()).asString(fallback) : fallback;
        }
        return fallback;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
