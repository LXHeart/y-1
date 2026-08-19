package com.grassland.reporting;

import java.util.Locale;

public enum ReportFormat {
    CSV("csv", "text/csv;charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String extension;
    private final String mediaType;

    ReportFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public static ReportFormat parse(String value) {
        String normalized = value == null ? "csv" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "csv" -> CSV;
            case "xlsx", "excel" -> XLSX;
            default -> throw new IllegalArgumentException("format 仅支持 csv/xlsx");
        };
    }
}
