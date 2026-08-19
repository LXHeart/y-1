package com.grassland.identity.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class PersonalDataArchiveBuilderTest {

    private final PersonalDataArchiveBuilder builder = new PersonalDataArchiveBuilder();

    @Test
    void buildsExpectedArchiveWithoutLeakingCsvFormulas() throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "entry-1");
        record.put("type", "wallet_income");
        record.put("direction", "income");
        record.put("amountCents", 1200);
        record.put("feeCents", 25);
        record.put("status", "completed");
        record.put("reference", "=HYPERLINK(\"https://example.test\")");
        record.put("memo", "+SUM(1,1)\r\nquoted \"memo\"");
        record.put("occurredAt", "2026-08-19T10:00:00Z");

        Map<String, byte[]> entries = unzip(builder.build(
                "{\"account\":{\"email\":\"person@example.test\"}}", List.of(record)));

        assertThat(entries).containsOnlyKeys(
                "personal-data.json", "financial-records.csv", "README.txt");
        assertThat(new String(entries.get("personal-data.json"), StandardCharsets.UTF_8))
                .contains("person@example.test");
        String csv = new String(entries.get("financial-records.csv"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFid,type,direction,amount_cents");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://example.test\"\")\"");
        assertThat(csv).contains("\"'+SUM(1,1)\r\nquoted \"\"memo\"\"\"");
    }

    @Test
    void writesHeaderForAnEmptyFinancialHistory() throws Exception {
        Map<String, byte[]> entries = unzip(builder.build("{\"account\":{}}", null));

        assertThat(new String(entries.get("financial-records.csv"), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFid,type,direction,amount_cents,fee_cents,status,reference,memo,occurred_at\r\n");
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
