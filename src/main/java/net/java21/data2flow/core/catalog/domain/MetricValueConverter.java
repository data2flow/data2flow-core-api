package net.java21.data2flow.core.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 측정 항목 정의대로 값을 다루는 규칙(DEV-04.01, TC-DEV-127). 저장은 항상 숫자다(domain-model §2.8).
 * <ul>
 *   <li>{@link #toNumber}: 값 형식별 숫자 변환. ENUM은 매핑(예: {@code {"open":1,"close":0}} → "open"은 1, AT-DEV-10.3),
 *       BOOLEAN은 true=1·false=0</li>
 *   <li>{@link #round}: 표시 소수 자릿수 반올림(HALF_UP)</li>
 *   <li>{@link #aggregate}: 다운샘플링 대표값(AVG·SUM·MAX·MIN·LAST·COUNT, TSD)</li>
 * </ul>
 * pipeline의 수신 변환(ING-02.06)과 같은 규칙이고, core는 미리 보기·내보내기 표시에 쓴다.
 */
public final class MetricValueConverter {

    private MetricValueConverter() {
    }

    /** 숫자로 바꿀 수 없으면 빈 값 */
    public static OptionalDouble toNumber(String valueType, Map<String, Double> enumMap, Object raw) {
        if (raw == null) {
            return OptionalDouble.empty();
        }
        if ("ENUM".equals(valueType) && enumMap != null && raw instanceof String s) {
            Double mapped = enumMap.get(s);
            if (mapped == null) {
                mapped = enumMap.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(s.strip()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (mapped != null) {
                return OptionalDouble.of(mapped);
            }
        }
        if (raw instanceof Boolean b) {
            return OptionalDouble.of(b ? 1 : 0);
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
        }
        if (raw instanceof String s) {
            String text = s.strip().toLowerCase(Locale.ROOT);
            if ("BOOLEAN".equals(valueType) && (text.equals("true") || text.equals("false"))) {
                return OptionalDouble.of(text.equals("true") ? 1 : 0);
            }
            try {
                double d = Double.parseDouble(text);
                return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
            } catch (NumberFormatException ex) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    /** 소수 자릿수(0~6) 반올림 */
    public static double round(double value, int precision) {
        int digits = Math.max(0, Math.min(6, precision));
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    /** 집계 방식별 대표값. 값이 없으면 빈 값(COUNT는 0) */
    public static OptionalDouble aggregate(String aggregation, List<Double> values) {
        if ("COUNT".equals(aggregation)) {
            return OptionalDouble.of(values.size());
        }
        if (values.isEmpty()) {
            return OptionalDouble.empty();
        }
        return switch (aggregation) {
            case "SUM" -> OptionalDouble.of(values.stream().mapToDouble(Double::doubleValue).sum());
            case "MAX" -> values.stream().mapToDouble(Double::doubleValue).max();
            case "MIN" -> values.stream().mapToDouble(Double::doubleValue).min();
            case "LAST" -> OptionalDouble.of(values.get(values.size() - 1));
            case "AVG" -> values.stream().mapToDouble(Double::doubleValue).average();
            default -> throw new IllegalArgumentException("모르는 집계 방식: " + aggregation);
        };
    }
}
