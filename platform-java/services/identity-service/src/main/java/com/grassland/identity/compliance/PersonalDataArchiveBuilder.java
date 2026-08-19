package com.grassland.identity.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

@Component
public class PersonalDataArchiveBuilder {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public byte[] build(String identityJson, List<Map<String, Object>> financialRecords) {
        try {
            JsonNode identity = mapper.readTree(identityJson);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                write(zip, "personal-data.json", mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(identity));
                write(zip, "financial-records.csv", financialCsv(financialRecords));
                write(zip, "README.txt", ("Grassland personal data export\n"
                        + "personal-data.json: profile data held by Identity.\n"
                        + "financial-records.csv: income and expenditure facts held by Finance.\n"
                        + "Immutable evidence, security logs and internal audit records are excluded.\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("failed to build personal data archive", error);
        }
    }

    private static byte[] financialCsv(List<Map<String, Object>> records) {
        StringBuilder csv = new StringBuilder("\uFEFFid,type,direction,amount_cents,fee_cents,status,reference,memo,occurred_at\r\n");
        for (Map<String, Object> record : records == null ? List.<Map<String, Object>>of() : records) {
            csv.append(cell(record.get("id"), true)).append(',')
                    .append(cell(record.get("type"), true)).append(',')
                    .append(cell(record.get("direction"), true)).append(',')
                    .append(cell(record.get("amountCents"), false)).append(',')
                    .append(cell(record.get("feeCents"), false)).append(',')
                    .append(cell(record.get("status"), true)).append(',')
                    .append(cell(record.get("reference"), true)).append(',')
                    .append(cell(record.get("memo"), true)).append(',')
                    .append(cell(record.get("occurredAt"), true)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(Object value, boolean protectFormula) {
        String text = value == null ? "" : String.valueOf(value);
        if (protectFormula && !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) throws java.io.IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
