package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Criterion;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Boundary;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 점수 경계 고르기(BR-CMP-09, TC-CMP-031). 항목마다 적용된 프로필의 유효 기준 → 공간 목표 환경 → 시스템 기본(CO2 1000, PM2.5 35,
 * 온도 20~26, 습도 40~60) 순으로 쓴다. 프로필이 준 기준이 하나라도 있으면 basis=PROFILE, 아니면 DEFAULT다.
 * 조직 자체 기준 템플릿은 조직이 "기본 프로필"을 고르는 설정이 아직 없어 건너뛴다(목표 환경이 그 자리를 맡는다).
 */
public final class DefaultCriteria {

    public static final Map<String, Boundary> SYSTEM;

    static {
        Map<String, Boundary> m = new LinkedHashMap<>();
        m.put("co2", new Boundary(null, new BigDecimal("1000")));
        m.put("pm2_5", new Boundary(null, new BigDecimal("35")));
        m.put("temperature", new Boundary(new BigDecimal("20"), new BigDecimal("26")));
        m.put("humidity", new Boundary(new BigDecimal("40"), new BigDecimal("60")));
        SYSTEM = Collections.unmodifiableMap(m);
    }

    public enum Basis {
        PROFILE, DEFAULT
    }

    /** {@code boundaries}는 항목 키 → 경계. 유효 기준도 시스템 기본도 없는 항목은 빠진다 */
    public record Resolved(Map<String, Boundary> boundaries, Basis basis) {
    }

    private DefaultCriteria() {
    }

    public static Resolved resolve(List<Criterion> criteria, Iterable<String> metricKeys) {
        Map<String, Criterion> byMetric = new LinkedHashMap<>();
        boolean fromProfile = false;
        for (Criterion c : criteria) {
            byMetric.put(c.metricKey(), c);
            fromProfile |= !c.fromTarget();
        }
        Map<String, Boundary> out = new LinkedHashMap<>();
        for (String key : metricKeys) {
            Criterion c = byMetric.get(key);
            if (c != null) {
                out.put(key, new Boundary(c.lower() == null ? null : c.lower().value(), c.upper() == null ? null : c.upper().value()));
            } else if (SYSTEM.containsKey(key)) {
                out.put(key, SYSTEM.get(key));
            }
        }
        return new Resolved(Collections.unmodifiableMap(out), fromProfile ? Basis.PROFILE : Basis.DEFAULT);
    }
}
