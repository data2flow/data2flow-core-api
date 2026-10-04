package net.java21.data2flow.core.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * 단위 체계 변환 표시(DEV-04.04, API-DEV-57). 저장값은 원래 단위(℃) 그대로 두고 표시할 때만 바꾼다. 22.0℃ → 71.6℉.
 * 내보내기 파일은 저장 단위와 표시 단위를 함께 적는다({@link #exportUnitLabel}). 온도 외 단위는 v1에서 바꾸지 않는다.
 */
public final class UnitConversion {

    public static final String CELSIUS = "C";
    public static final String FAHRENHEIT = "F";

    private UnitConversion() {
    }

    /** 조직 unit_system(METRIC·IMPERIAL) ↔ 온도 단위(C·F) */
    public static String temperatureUnitOf(String unitSystem) {
        return "IMPERIAL".equalsIgnoreCase(unitSystem) ? FAHRENHEIT : CELSIUS;
    }

    public static String unitSystemOf(String temperatureUnit) {
        return FAHRENHEIT.equalsIgnoreCase(temperatureUnit) ? "IMPERIAL" : "METRIC";
    }

    /** 섭씨 단위 표기인가(℃·°C·C·celsius) */
    public static boolean isCelsius(String unit) {
        if (unit == null) {
            return false;
        }
        String u = unit.strip().toLowerCase(Locale.ROOT);
        return u.equals("℃") || u.equals("°c") || u.equals("c") || u.equals("celsius") || u.equals("degc");
    }

    /** 표시 단위 기호. 온도가 아니면 저장 단위 그대로 */
    public static String displayUnit(String storedUnit, String temperatureUnit) {
        return isCelsius(storedUnit) && FAHRENHEIT.equalsIgnoreCase(temperatureUnit) ? "℉" : storedUnit;
    }

    /** 표시 값(℉면 v×9/5+32, 소수 첫째 자리 반올림). 온도가 아니거나 C면 그대로 */
    public static Optional<BigDecimal> toDisplay(BigDecimal value, String storedUnit, String temperatureUnit) {
        if (value == null) {
            return Optional.empty();
        }
        if (isCelsius(storedUnit) && FAHRENHEIT.equalsIgnoreCase(temperatureUnit)) {
            return Optional.of(value.multiply(BigDecimal.valueOf(9)).divide(BigDecimal.valueOf(5), 6, RoundingMode.HALF_UP)
                    .add(BigDecimal.valueOf(32)).setScale(1, RoundingMode.HALF_UP));
        }
        return Optional.of(value);
    }

    /** 내보내기 머리글의 단위 표기: 저장 단위와 표시 단위가 다르면 "℉ (저장: ℃)" */
    public static String exportUnitLabel(String storedUnit, String temperatureUnit) {
        String display = displayUnit(storedUnit, temperatureUnit);
        if (storedUnit == null) {
            return "";
        }
        return display.equals(storedUnit) ? storedUnit : display + " (stored: " + storedUnit + ")";
    }
}
