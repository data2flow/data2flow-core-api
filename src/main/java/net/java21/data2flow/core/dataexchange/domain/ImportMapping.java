package net.java21.data2flow.core.dataexchange.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * CSV 가져오기 열 매핑(API-TSD-30 {@code mapping}). 넓은 형식은 {@code metricColumns[{column, metricKey}]}, 긴 형식은
 * {@code metricColumn}·{@code valueColumn}. 기기는 {@code deviceColumn} + {@code deviceKey}(ID·EXTERNAL_ID·NAME) 또는 고정 {@code deviceId}.
 * 시각은 {@code timeFormat}(ISO·EPOCH_S·EPOCH_MS·날짜 패턴)과 {@code tz}(오프셋이 없는 값에 적용).
 */
public record ImportMapping(String timeColumn, String timeFormat, String tz, String deviceColumn, String deviceKey, Long deviceId,
                            List<MetricColumn> metricColumns, String metricColumn, String valueColumn, String delimiter) {

    public record MetricColumn(String column, String metricKey) {
    }

    public ImportMapping {
        timeFormat = timeFormat == null || timeFormat.isBlank() ? "ISO" : timeFormat.strip();
        deviceKey = deviceKey == null || deviceKey.isBlank() ? "EXTERNAL_ID" : deviceKey.strip().toUpperCase(Locale.ROOT);
        metricColumns = metricColumns == null ? List.of() : List.copyOf(metricColumns);
        delimiter = delimiter == null || delimiter.isEmpty() ? "," : delimiter;
    }

    public boolean longFormat() {
        return metricColumns.isEmpty();
    }

    /** 검사: 틀린 항목 이름(없으면 null) */
    public String invalidField() {
        if (timeColumn == null || timeColumn.isBlank()) {
            return "mapping.timeColumn";
        }
        if (deviceId == null && (deviceColumn == null || deviceColumn.isBlank())) {
            return "mapping.deviceColumn";
        }
        if (!List.of("ID", "EXTERNAL_ID", "NAME").contains(deviceKey)) {
            return "mapping.deviceKey";
        }
        if (metricColumns.isEmpty() && (metricColumn == null || metricColumn.isBlank() || valueColumn == null || valueColumn.isBlank())) {
            return "mapping.metricColumns";
        }
        for (MetricColumn m : metricColumns) {
            if (m == null || m.column() == null || m.column().isBlank() || m.metricKey() == null || m.metricKey().isBlank()) {
                return "mapping.metricColumns";
            }
        }
        if (delimiter.length() != 1) {
            return "mapping.delimiter";
        }
        if (tz != null && !tz.isBlank()) {
            try {
                ZoneId.of(tz.strip());
            } catch (DateTimeException ex) {
                return "mapping.tz";
            }
        }
        if (!List.of("ISO", "EPOCH_S", "EPOCH_MS").contains(timeFormat)) {
            try {
                DateTimeFormatter.ofPattern(timeFormat);
            } catch (IllegalArgumentException ex) {
                return "mapping.timeFormat";
            }
        }
        return null;
    }

    /** 필요한 열 이름 */
    public List<String> requiredColumns() {
        java.util.ArrayList<String> cols = new java.util.ArrayList<>();
        cols.add(timeColumn);
        if (deviceId == null) {
            cols.add(deviceColumn);
        }
        if (longFormat()) {
            cols.add(metricColumn);
            cols.add(valueColumn);
        } else {
            metricColumns.forEach(m -> cols.add(m.column()));
        }
        return cols;
    }

    /** 시각 해석. 못 읽으면 DateTimeException */
    public Instant parseTime(String raw, ZoneId defaultZone) {
        String v = raw.strip();
        ZoneId zone = tz == null || tz.isBlank() ? defaultZone : ZoneId.of(tz.strip());
        switch (timeFormat) {
            case "EPOCH_S" -> {
                return Instant.ofEpochSecond(Long.parseLong(v));
            }
            case "EPOCH_MS" -> {
                return Instant.ofEpochMilli(Long.parseLong(v));
            }
            case "ISO" -> {
                try {
                    return OffsetDateTime.parse(v).toInstant();
                } catch (DateTimeException ex) {
                    return LocalDateTime.parse(v.replace(' ', 'T')).atZone(zone).toInstant();
                }
            }
            default -> {
                return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(timeFormat)).atZone(zone).toInstant();
            }
        }
    }
}
