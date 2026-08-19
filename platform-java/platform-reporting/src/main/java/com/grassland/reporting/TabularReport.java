package com.grassland.reporting;

import java.util.List;

public record TabularReport(String sheetName, List<String> headers, List<List<?>> rows) {
    public static final int MAX_ROWS = 10_000;

    public TabularReport {
        headers = List.copyOf(headers);
        rows = rows.stream().<List<?>>map(List::copyOf).toList();
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("报表至少需要一列");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("单次导出最多 " + MAX_ROWS + " 行");
        }
        int columnCount = headers.size();
        if (rows.stream().anyMatch(row -> row.size() != columnCount)) {
            throw new IllegalArgumentException("报表行列数不一致");
        }
    }
}
