package com.grassland.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportRendererTest {
    private final TabularReport report = new TabularReport("经营/报表", List.of("名称", "金额"), List.of(
            List.of("=HYPERLINK(\"https://example.test\")", 12),
            List.of(" 普通,\"文本\"", -3)));

    @Test
    void csvHasBomQuotesFieldsAndNeutralizesFormulaInjection() {
        byte[] rendered = ReportRenderer.render(report, ReportFormat.CSV);
        assertThat(rendered).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        String text = new String(rendered, 3, rendered.length - 3, StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=HYPERLINK(\"\"https://example.test\"\")\"")
                .contains("\" 普通,\"\"文本\"\"\"");
    }

    @Test
    void xlsxIsReadableAndNeverCreatesFormulaCellsFromText() throws Exception {
        byte[] rendered = ReportRenderer.render(report, ReportFormat.XLSX);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("经营_报表");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    void rejectsMoreThanTenThousandRows() {
        List<List<?>> rows = java.util.stream.IntStream.rangeClosed(0, TabularReport.MAX_ROWS)
                .<List<?>>mapToObj(index -> List.of(index)).toList();
        assertThatThrownBy(() -> new TabularReport("Rows", List.of("id"), rows))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
