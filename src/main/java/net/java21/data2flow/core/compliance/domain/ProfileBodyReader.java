package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Coverage;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 프로필 버전 본문(API-CMP §1.1)을 읽고 검사한다(CMP-01.01, TC-CMP-002). 형식 오류는 400 {@code INVALID_REQUEST}, 기준값 오류(하한 &gt; 상한,
 * 등급 구간이 기준 밖, 기준 등급 수, 단위 불일치, 평가 단위)는 400 {@code CMP_THRESHOLD_INVALID}로 모아서 알린다. 필드 경로는
 * {@code metrics[co2].lower}처럼 항목 키로 쓴다.
 *
 * <p>등급 구간은 기준 등급 안쪽으로 좁혀 가는 단계다(예: RESET PM2.5 허용 &lt;35 ⊃ v2.1 기본 &lt;25 ⊃ 고성능 &lt;12). 기준 등급은 규칙당 하나이고
 * 규칙의 기준값과 같아야 하며, 다른 등급이 기준 등급보다 넓으면 겹침으로 본다. 등급을 주지 않으면 기준값으로 기준 등급 하나를 만든다.
 */
public final class ProfileBodyReader {

    /** 같은 단위로 보는 표기 */
    private static final Map<String, String> UNIT_ALIASES = Map.of("°C", "℃", "ug/m3", "μg/m³", "µg/m³", "μg/m³");

    private ProfileBodyReader() {
    }

    /**
     * @param body       버전 본문
     * @param metricUnit 조직 측정 항목 키 → 단위(단위 없는 항목은 빈 문자열). 없는 키는 404 {@code METRIC_NOT_FOUND}
     */
    public static VersionSpec read(JsonNode body, Map<String, String> metricUnit) {
        if (body == null || !body.isObject()) {
            throw invalid("version", "NotNull");
        }
        String sourceTitle = text(body, "sourceTitle");
        if (sourceTitle == null || sourceTitle.length() > 200) {
            throw invalid("sourceTitle", sourceTitle == null ? "NotBlank" : "Size");
        }
        String sourceUrl = text(body, "sourceUrl");
        if (sourceUrl != null && (sourceUrl.length() > 500 || !sourceUrl.startsWith("https://"))) {
            throw invalid("sourceUrl", "Pattern");
        }
        LocalDate effective;
        try {
            effective = LocalDate.parse(body.path("effectiveDate").asString(""));
        } catch (DateTimeParseException ex) {
            throw invalid("effectiveDate", "Pattern");
        }
        String description = text(body, "description");
        Integer streakDays = null;
        BigDecimal streakRatio = null;
        JsonNode streak = body.path("streak");
        if (streak.isObject()) {
            if (streak.hasNonNull("days")) {
                if (!streak.get("days").isIntegralNumber() || streak.get("days").asInt() < 1 || streak.get("days").asInt() > 365) {
                    throw invalid("streak.days", "Range");
                }
                streakDays = streak.get("days").asInt();
            }
            if (streak.hasNonNull("dailyRatio")) {
                streakRatio = number(streak.get("dailyRatio"), "streak.dailyRatio");
                if (streakRatio.compareTo(BigDecimal.valueOf(50)) < 0 || streakRatio.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw invalid("streak.dailyRatio", "Range");
                }
            }
        }
        boolean facilityRequired = body.path("facilityTypeRequired").asBoolean(false);
        String changeNote = text(body, "changeNote");
        if (changeNote != null && changeNote.length() > 500) {
            throw invalid("changeNote", "Size");
        }

        JsonNode metricsNode = body.path("metrics");
        if (!metricsNode.isArray() || metricsNode.isEmpty()) {
            throw invalid("metrics", "NotEmpty");
        }
        List<MetricRule> metrics = new ArrayList<>();
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode m : metricsNode) {
            String key = text(m, "metricKey");
            if (key == null) {
                throw invalid("metrics[" + i + "].metricKey", "NotBlank");
            }
            String at = "metrics[" + key + "]";
            String facility = text(m, "facilityType");
            if (!seen.add(key + "\u0000" + facility)) {
                throw invalid(at + ".metricKey", "Duplicate");
            }
            if (!metricUnit.containsKey(key)) {
                throw new BusinessException(ComplianceErrorCode.METRIC_NOT_FOUND, List.of(new FieldErrorDetail(at + ".metricKey", "NotFound", null)));
            }
            String window = m.path("evalWindow").asString("").toUpperCase(Locale.ROOT);
            if (!ProfileModels.EVAL_WINDOWS.contains(window)) {
                errors.add(new FieldErrorDetail(at + ".evalWindow", "Pattern", null));
            }
            String requirement = m.path("requirement").asString("LIMIT").toUpperCase(Locale.ROOT);
            if (!ProfileModels.REQUIREMENTS.contains(requirement)) {
                throw invalid(at + ".requirement", "Pattern");
            }
            BigDecimal lower = m.hasNonNull("lower") ? number(m.get("lower"), at + ".lower") : null;
            BigDecimal upper = m.hasNonNull("upper") ? number(m.get("upper"), at + ".upper") : null;
            boolean inclusive = !m.has("inclusive") || m.get("inclusive").asBoolean(true);
            String unit = text(m, "unit");
            if (unit == null || unit.length() > 16) {
                errors.add(new FieldErrorDetail(at + ".unit", "NotBlank", null));
            } else {
                String expected = metricUnit.get(key);
                if (expected != null && !expected.isEmpty() && !canonical(expected).equals(canonical(unit))) {
                    errors.add(new FieldErrorDetail(at + ".unit", "Mismatch", null));
                }
            }
            boolean continuous = ProfileModels.CONTINUOUS.equals(requirement);
            if (!continuous && lower == null && upper == null) {
                errors.add(new FieldErrorDetail(at + ".upper", "NotNull", null));
            }
            if (lower != null && upper != null && lower.compareTo(upper) > 0) {
                errors.add(new FieldErrorDetail(at + ".lower", "Range", null));
            }
            List<Band> bands = continuous ? List.of() : bands(m.path("bands"), at, lower, upper, errors);
            String note = text(m, "note");
            metrics.add(new MetricRule(key, facility, window, lower, upper, inclusive, unit, requirement, note, bands));
            i++;
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ComplianceErrorCode.CMP_THRESHOLD_INVALID, errors);
        }
        List<Coverage> coverage = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for (JsonNode c : body.path("coverage")) {
            String kind = c.path("kind").asString("").toUpperCase(Locale.ROOT);
            if (!ProfileModels.COVERAGE_KINDS.contains(kind) || !kinds.add(kind)) {
                throw invalid("coverage[" + kind + "].kind", "Pattern");
            }
            if (!c.path("params").isObject()) {
                throw invalid("coverage[" + kind + "].params", "NotNull");
            }
            coverage.add(new Coverage(kind, c.get("params")));
        }
        if (facilityRequired && metrics.stream().allMatch(r -> r.facilityType() == null)) {
            throw invalid("facilityTypeRequired", "NoFacilityRules");
        }
        return new VersionSpec(sourceTitle, sourceUrl, effective, description, streakDays, streakRatio, facilityRequired, changeNote, metrics,
                coverage);
    }

    static List<Band> bands(JsonNode node, String at, BigDecimal lower, BigDecimal upper, List<FieldErrorDetail> errors) {
        if (!node.isArray() || node.isEmpty()) {
            return List.of(new Band("기준", lower, upper, true));
        }
        List<Band> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode b : node) {
            String name = text(b, "name");
            if (name == null || name.length() > 30 || !names.add(name)) {
                throw invalid(at + ".bands.name", name == null ? "NotBlank" : "Size");
            }
            BigDecimal bl = b.hasNonNull("lower") ? number(b.get("lower"), at + ".bands[" + name + "].lower") : null;
            BigDecimal bu = b.hasNonNull("upper") ? number(b.get("upper"), at + ".bands[" + name + "].upper") : null;
            out.add(new Band(name, bl, bu, b.path("isBase").asBoolean(false)));
        }
        List<Band> base = out.stream().filter(Band::base).toList();
        if (base.size() != 1) {
            errors.add(new FieldErrorDetail(at + ".bands", "BaseCount", null));
            return out;
        }
        Band b0 = base.getFirst();
        if (!same(b0.lower(), lower) || !same(b0.upper(), upper)) {
            errors.add(new FieldErrorDetail(at + ".bands[" + b0.name() + "]", "BaseMismatch", null));
        }
        for (Band b : out) {
            if (b.lower() != null && b.upper() != null && b.lower().compareTo(b.upper()) > 0) {
                errors.add(new FieldErrorDetail(at + ".bands[" + b.name() + "].lower", "Range", null));
            } else if (!b.base() && !within(b, b0)) {
                errors.add(new FieldErrorDetail(at + ".bands[" + b.name() + "]", "Overlap", null));
            }
        }
        return out;
    }

    /** 등급이 기준 등급 안쪽(같거나 더 엄격)인가 */
    static boolean within(Band b, Band base) {
        boolean lowerOk = base.lower() == null || (b.lower() != null && b.lower().compareTo(base.lower()) >= 0);
        boolean upperOk = base.upper() == null || (b.upper() != null && b.upper().compareTo(base.upper()) <= 0);
        return lowerOk && upperOk;
    }

    static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static String canonical(String unit) {
        String u = unit.strip();
        return UNIT_ALIASES.getOrDefault(u, u);
    }

    static BigDecimal number(JsonNode v, String field) {
        if (v == null || !v.isNumber()) {
            throw invalid(field, "Number");
        }
        return v.decimalValue();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString("").strip();
        return s.isEmpty() ? null : s;
    }

    public static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
