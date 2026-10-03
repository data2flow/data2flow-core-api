package net.java21.data2flow.core.space.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 시맨틱 표준 어휘(DEV-13.01, BR-DEV-33): Brick 1.3 물리량·장비 클래스 중 공간 환경 센서·설비에 쓰는 것과 Haystack 4 대응 이름.
 * 목록에 없는 값은 {@code custom:} 접두사로만 쓸 수 있다. 대소문자는 무시하고 표준 표기로 저장한다.
 * 틀린 값에는 편집 거리로 가장 가까운 후보를 알려 준다(AT-DEV-23.3, 예: Tempurature → Temperature).
 * (참고: Brick Schema, Project Haystack)
 */
public final class SemanticVocabulary {

    public static final String CUSTOM_PREFIX = "custom:";

    /** 점 유형(API·DB 값) */
    public static final Set<String> POINT_TYPES = Set.of("MEASUREMENT", "CONTROL", "STATUS", "SETPOINT");

    private static final Map<String, String> QUANTITIES = index(List.of(
            "Temperature", "Air_Temperature", "Zone_Air_Temperature", "Outside_Air_Temperature", "Supply_Air_Temperature",
            "Return_Air_Temperature", "Water_Temperature", "Surface_Temperature", "Dewpoint",
            "Humidity", "Relative_Humidity", "Zone_Air_Humidity", "Outside_Air_Humidity", "Absolute_Humidity",
            "CO2", "CO2_Concentration", "Zone_Air_CO2", "CO", "CO_Concentration", "NO2", "NO2_Concentration", "Ozone",
            "Ozone_Concentration", "Radon", "Radon_Concentration", "Formaldehyde", "TVOC", "TVOC_Concentration",
            "PM1", "PM1_Concentration", "PM2.5", "PM2.5_Concentration", "PM10", "PM10_Concentration", "Air_Quality",
            "Illuminance", "Luminance", "Daylight", "Occupancy", "Occupancy_Count", "Occupancy_Percentage", "Motion",
            "Presence", "Pressure", "Air_Pressure", "Atmospheric_Pressure", "Static_Pressure", "Differential_Pressure",
            "Sound_Pressure_Level", "Noise", "Power", "Active_Power", "Reactive_Power", "Apparent_Power", "Energy",
            "Active_Energy", "Reactive_Energy", "Voltage", "Current", "Electric_Current", "Frequency", "Power_Factor",
            "Flow", "Air_Flow", "Water_Flow", "Volume", "Water_Volume", "Gas_Volume", "Level", "Water_Level",
            "Position", "Speed", "Fan_Speed", "Wind_Speed", "Wind_Direction", "Precipitation", "Rainfall",
            "Solar_Irradiance", "Battery_Level", "Battery_Voltage", "Signal_Strength", "Contact", "Door_Position",
            "Window_Position", "Leak", "Smoke", "Vibration", "Tilt", "On_Off", "Mode", "Run_Status", "Alarm", "Fault",
            "Setpoint", "Temperature_Setpoint", "Humidity_Setpoint", "CO2_Setpoint", "Speed_Setpoint", "Count", "Distance",
            "Weight", "Soil_Moisture", "Water_Usage", "Gas_Usage"));

    private static final Map<String, String> EQUIP_CLASSES = index(List.of(
            "Equipment", "Sensor", "Zone_Air_Sensor", "Environment_Sensor", "Air_Quality_Sensor", "Temperature_Sensor",
            "Air_Temperature_Sensor", "Humidity_Sensor", "CO2_Sensor", "TVOC_Sensor", "PM_Sensor", "Occupancy_Sensor",
            "Motion_Sensor", "People_Counter", "Illuminance_Sensor", "Pressure_Sensor", "Sound_Sensor", "Door_Sensor",
            "Contact_Sensor", "Leak_Sensor", "Smoke_Detector", "Vibration_Sensor", "Weather_Station", "Meter",
            "Electrical_Meter", "Power_Meter", "Energy_Meter", "Water_Meter", "Gas_Meter", "HVAC_Equipment", "AHU",
            "Air_Handling_Unit", "VAV", "FCU", "Fan_Coil_Unit", "Terminal_Unit", "Air_Conditioner", "Heat_Pump", "Boiler",
            "Chiller", "Cooling_Tower", "Fan", "Exhaust_Fan", "Ventilation_Equipment", "Air_Purifier", "Humidifier",
            "Dehumidifier", "Pump", "Damper", "Valve", "Thermostat", "Controller", "Lighting_Equipment", "Lighting",
            "Luminaire", "Switch", "Relay", "Smart_Plug", "Outlet", "Shade_Equipment", "Blind", "Door", "Window",
            "Gateway", "Camera", "Battery", "PV_Panel", "Inverter", "Elevator", "Water_Heater"));

    private SemanticVocabulary() {
    }

    private static Map<String, String> index(List<String> names) {
        return names.stream().collect(Collectors.toMap(n -> n.toLowerCase(Locale.ROOT), n -> n, (a, b) -> a, TreeMap::new));
    }

    /** 표준 표기의 물리량(custom:은 그대로). 모르면 비어 있다 */
    public static Optional<String> quantity(String raw) {
        return resolve(raw, QUANTITIES);
    }

    /** 표준 표기의 장비 클래스(custom:은 그대로). 모르면 비어 있다 */
    public static Optional<String> equipClass(String raw) {
        return resolve(raw, EQUIP_CLASSES);
    }

    public static String suggestQuantity(String raw) {
        return suggest(raw, QUANTITIES);
    }

    public static String suggestEquipClass(String raw) {
        return suggest(raw, EQUIP_CLASSES);
    }

    private static Optional<String> resolve(String raw, Map<String, String> vocabulary) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.toLowerCase(Locale.ROOT).startsWith(CUSTOM_PREFIX)) {
            String rest = value.substring(CUSTOM_PREFIX.length());
            return rest.matches("[A-Za-z0-9_.-]{1,72}") ? Optional.of(CUSTOM_PREFIX + rest) : Optional.empty();
        }
        return Optional.ofNullable(vocabulary.get(value.toLowerCase(Locale.ROOT)));
    }

    private static String suggest(String raw, Map<String, String> vocabulary) {
        String needle = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return vocabulary.entrySet().stream()
                .min(Comparator.comparingInt((Map.Entry<String, String> e) -> distance(needle, e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue).orElse("custom:…");
    }

    /** 레벤슈타인 거리 */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
