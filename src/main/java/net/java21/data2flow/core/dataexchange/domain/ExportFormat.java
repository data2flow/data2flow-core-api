package net.java21.data2flow.core.dataexchange.domain;

import java.util.Locale;

/** 내보내기 파일 형식(export_jobs.format, API-TSD-20·23). PARQUET은 긴 형식만(NFR-04.02, TSD-07.02) */
public enum ExportFormat {
    CSV("text/csv; charset=UTF-8", "csv"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PARQUET("application/vnd.apache.parquet", "parquet");

    private final String contentType;
    private final String extension;

    ExportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** 대소문자 무시. 모르는 값이면 null */
    public static ExportFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
