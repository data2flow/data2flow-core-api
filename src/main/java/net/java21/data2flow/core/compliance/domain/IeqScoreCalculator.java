package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종합 실내 환경 점수(CMP-01.05, BR-CMP-08, TC-CMP-028~030). 공개 계산식이라 같은 입력이면 늘 같은 결과다.
 *
 * <ul>
 *   <li>상한형(상한 U만): 값 ≤ 0.5U → 5, ≤ U → 4, 초과율 (값−U)/U ≤ 25% → 3, ≤ 50% → 2, 그 밖 → 1</li>
 *   <li>범위형(하한 L·상한 U): 범위 안이면 가까운 경계까지 여유가 폭의 25% 이상 → 5, 아니면 4. 범위 밖이면 벗어난 양 ÷ 폭으로 3·2·1</li>
 *   <li>하한형(하한 L만, 문서에 없어 상한형을 뒤집었다): 값 ≥ 1.5L → 5, ≥ L → 4, 부족률 (L−값)/L로 3·2·1</li>
 *   <li>점수 = round(100 × Σ(w·(i−1)/4) / Σw). 최근 10분 데이터가 없는 항목은 빼고(지수 null), 남은 항목이 없으면 점수 null</li>
 *   <li>감점 1순위는 w·(5−i)가 가장 큰 항목(같으면 입력 순서 앞). 감점이 없으면 null</li>
 *   <li>등급은 문서에 없어 정했다: GOOD ≥ 80, MODERATE ≥ 60, POOR ≥ 40, BAD 그 밖</li>
 * </ul>
 */
public final class IeqScoreCalculator {

    public static final String FORMULA_VERSION = "IEQ-1";
    /** 기본 가중치(BR-CMP-08). 순서가 화면·입력 순서다 */
    public static final Map<String, BigDecimal> DEFAULT_WEIGHTS;

    static {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("co2", new BigDecimal("1.0"));
        w.put("pm2_5", new BigDecimal("1.0"));
        w.put("pm10", new BigDecimal("0.8"));
        w.put("tvoc", new BigDecimal("0.8"));
        w.put("temperature", new BigDecimal("0.7"));
        w.put("humidity", new BigDecimal("0.5"));
        DEFAULT_WEIGHTS = Collections.unmodifiableMap(w);
    }

    public enum Grade {
        GOOD(80, 100), MODERATE(60, 79), POOR(40, 59), BAD(0, 39);

        public final int min;
        public final int max;

        Grade(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public static Grade of(Integer score) {
            if (score == null) {
                return null;
            }
            for (Grade g : values()) {
                if (score >= g.min) {
                    return g;
                }
            }
            return BAD;
        }
    }

    private IeqScoreCalculator() {
    }

    /** 지수 계산 경계. null 쪽은 제한이 없다 */
    public record Boundary(BigDecimal lower, BigDecimal upper) {
    }

    /** 항목 입력. {@code avg5m}이 null이면 최근 10분 데이터가 없다 */
    public record Input(String metricKey, Double avg5m, double weight, Boundary boundary) {
    }

    /** {@code indices}는 입력 순서, 뺀 항목은 null 값 */
    public record Score(Integer score, Grade grade, Map<String, Integer> indices, String topPenaltyMetric, Double topPenaltyValue,
                        List<String> excludedMetrics) {

        public boolean partial() {
            return !excludedMetrics.isEmpty();
        }
    }

    public static Score calculate(List<Input> inputs) {
        Map<String, Integer> indices = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        double weighted = 0;
        double weights = 0;
        String top = null;
        Double topValue = null;
        double topPenalty = 0;
        for (Input in : inputs) {
            if (in.weight() <= 0) {
                continue;
            }
            if (in.avg5m() == null) {
                indices.put(in.metricKey(), null);
                excluded.add(in.metricKey());
                continue;
            }
            int i = index(in.avg5m(), in.boundary());
            indices.put(in.metricKey(), i);
            weighted += in.weight() * (i - 1) / 4.0;
            weights += in.weight();
            double penalty = in.weight() * (5 - i);
            if (penalty > topPenalty) {
                topPenalty = penalty;
                top = in.metricKey();
                topValue = in.avg5m();
            }
        }
        Integer score = weights == 0 ? null : (int) Math.round(100 * weighted / weights);
        return new Score(score, Grade.of(score), Collections.unmodifiableMap(indices), top, topValue, List.copyOf(excluded));
    }

    public static int index(double v, Boundary b) {
        BigDecimal lower = b.lower();
        BigDecimal upper = b.upper();
        if (lower != null && upper != null) {
            double l = lower.doubleValue();
            double u = upper.doubleValue();
            double width = u - l;
            if (width <= 0) {
                return v >= l && v <= u ? 4 : 1;
            }
            if (v >= l && v <= u) {
                return Math.min(v - l, u - v) / width >= 0.25 ? 5 : 4;
            }
            return outside((v < l ? l - v : v - u) / width);
        }
        if (upper != null) {
            double u = upper.doubleValue();
            if (v <= u) {
                return v <= 0.5 * u ? 5 : 4;
            }
            return u <= 0 ? 1 : outside((v - u) / u);
        }
        if (lower != null) {
            double l = lower.doubleValue();
            if (v >= l) {
                return v >= 1.5 * l ? 5 : 4;
            }
            return l <= 0 ? 1 : outside((l - v) / l);
        }
        return 5;
    }

    private static int outside(double ratio) {
        if (ratio <= 0.25) {
            return 3;
        }
        return ratio <= 0.5 ? 2 : 1;
    }
}
