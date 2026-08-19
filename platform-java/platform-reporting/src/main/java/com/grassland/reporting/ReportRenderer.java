package com.grassland.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ReportRenderer {
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

    private ReportRenderer() {}

    public static byte[] render(TabularReport report, ReportFormat format) {
        return switch (format) {
            case CSV -> csv(report);
            case XLSX -> xlsx(report);
        };
    }

    static byte[] csv(TabularReport report) {
        StringBuilder out = new StringBuilder();
        appendCsvRow(out, report.headers());
        report.rows().forEach(row -> appendCsvRow(out, row));
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, bytes, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, bytes, UTF8_BOM.length, body.length);
        return bytes;
    }

    static byte[] xlsx(TabularReport report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeSheetName(report.sheetName()));
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int column = 0; column < report.headers().size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(report.headers().get(column));
                cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < report.rows().size(); index++) {
                Row row = sheet.createRow(index + 1);
                List<?> values = report.rows().get(index);
                for (int column = 0; column < values.size(); column++) {
                    writeCell(row.createCell(column), values.get(column));
                }
            }
            for (int column = 0; column < report.headers().size(); column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 18_000));
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("生成 Excel 失败", error);
        }
    }

    private static void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof TemporalAccessor) {
            cell.setCellValue(value.toString());
        } else {
            // Always write user-controlled text as a string cell. POI will never interpret it as a formula.
            cell.setCellValue(value.toString());
        }
    }

    private static void appendCsvRow(StringBuilder out, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(',');
            String value = values.get(index) == null ? "" : values.get(index).toString();
            value = neutralizeFormula(value);
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static String neutralizeFormula(String value) {
        int first = 0;
        while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
        if (first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String safeSheetName(String requested) {
        String value = requested == null || requested.isBlank() ? "Report" : requested;
        value = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return value.substring(0, Math.min(31, value.length()));
    }
}
