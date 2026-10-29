package net.java21.data2flow.core.energy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 에너지 설정 규칙(ENE-01.01·01.02) 중 저장소와 관계없는 계산 */
public final class EnergyRules {

    public static final Set<String> METER_METRICS = Set.of("energy", "power");
    public static final Set<String> BILL_SPACE_TYPES = Set.of("SITE", "BUILDING");
    public static final int MAX_POWER_W = 10_000_000;

    private EnergyRules() {
    }

    /** 기간 끝: 같은 범위의 시작일 순 목록에서 다음 행 시작 전날. 마지막 행은 끝이 없다(null) */
    public static LocalDate validTo(List<LocalDate> sortedStarts, LocalDate start) {
        for (LocalDate s : sortedStarts) {
            if (s.isAfter(start)) {
                return s.minusDays(1);
            }
        }
        return null;
    }

    /** 고지서와 측정 합계의 차이 비율 (고지서 − 측정) / 고지서, 소수 넷째 자리. 측정이 없거나 고지서가 0이면 null(AT-ENE-02.2) */
    public static BigDecimal diffRatio(BigDecimal billKwh, BigDecimal meteredKwh) {
        if (billKwh == null || meteredKwh == null || billKwh.signum() == 0) {
            return null;
        }
        return billKwh.subtract(meteredKwh).divide(billKwh, 4, RoundingMode.HALF_UP);
    }

    /**
     * 상위 계량기 겹침(BR-ENE-02, UI-ENE-02): 하위 합계를 포함하는 계량기({@code coversDescendants})끼리 한쪽이 다른 쪽의 조상(같은 공간 포함)이면
     * 하위 공간 사용량을 두 번 더하게 되므로 막는다. 경로는 {@code /1/2/3/} 모양이다
     */
    public static boolean nested(String path, String otherPath) {
        return path.startsWith(otherPath) || otherPath.startsWith(path);
    }

    /** 측정 항목에서 누적형 기본값: {@code energy}는 누적 kWh, {@code power}는 순간 W(BR-ENE-03) */
    public static boolean defaultCumulative(String metricKey) {
        return "energy".equals(metricKey);
    }
}
