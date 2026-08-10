package com.grassland.identity.kyb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class KybDocumentVerifier {

    private static final double MIN_CONFIDENCE = 0.85;
    private static final Pattern USCC = Pattern.compile("[0-9ABCDEFGHJKLMNPQRTUWXY]{18}");

    private final ObjectMapper mapper;
    private final KybFieldCrypto crypto;
    private final Clock clock;

    @Autowired
    public KybDocumentVerifier(KybFieldCrypto crypto) {
        this(new ObjectMapper(), crypto, Clock.systemUTC());
    }

    KybDocumentVerifier(ObjectMapper mapper, KybFieldCrypto crypto, Clock clock) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.clock = clock;
    }

    public KybVerifiedDocument verify(KybDocumentAnalysis analysis, MerchantProfile profile) {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("documentType", analysis.documentType());
        result.put("confidence", analysis.confidence());
        ObjectNode safeFields = result.putObject("fields");
        ArrayNode checks = result.putArray("checks");

        boolean passed = analysis.confidence() >= MIN_CONFIDENCE;
        passed &= addCheck(checks, "confidence", passed);
        JsonNode fields = analysis.fields();
        if ("business_license".equals(analysis.documentType())) {
            copy(safeFields, fields, "companyName", "unifiedSocialCreditCode", "legalRepresentative",
                    "registeredAddress", "validFrom", "validUntil");
            boolean codeValid = USCC.matcher(normalizeCode(text(fields, "unifiedSocialCreditCode"))).matches();
            passed &= addCheck(checks, "uscc_format", codeValid);
            passed &= addCheck(checks, "company_name_match",
                    sameName(text(fields, "companyName"), profile.legalName()));
            passed &= addCheck(checks, "legal_representative_match",
                    sameName(text(fields, "legalRepresentative"), profile.legalPersonName()));
            passed &= addCheck(checks, "validity", notExpired(text(fields, "validUntil")));
        } else if ("legal_person_id_front".equals(analysis.documentType())) {
            copy(safeFields, fields, "name", "side", "validUntil");
            passed &= addCheck(checks, "document_side", "front".equalsIgnoreCase(text(fields, "side")));
            passed &= addCheck(checks, "legal_person_name_match",
                    sameName(text(fields, "name"), profile.legalPersonName()));
            boolean idMatch = crypto.matches(profile.legalPersonIdNumber(), text(fields, "idNumber"));
            passed &= addCheck(checks, "legal_person_id_match", idMatch);
            passed &= addCheck(checks, "validity", notExpired(text(fields, "validUntil")));
        } else if ("legal_person_id_back".equals(analysis.documentType())) {
            copy(safeFields, fields, "issuingAuthority", "validFrom", "validUntil", "side");
            passed &= addCheck(checks, "document_side", "back".equalsIgnoreCase(text(fields, "side")));
            passed &= addCheck(checks, "validity", notExpired(text(fields, "validUntil")));
        } else if ("industry_license".equals(analysis.documentType())
                || "financial_qualification".equals(analysis.documentType())) {
            copy(safeFields, fields, "licenseNumber", "companyName", "licenseType", "validFrom", "validUntil");
            passed &= addCheck(checks, "license_number_present",
                    text(fields, "licenseNumber") != null && !text(fields, "licenseNumber").isBlank());
            passed &= addCheck(checks, "company_name_match",
                    sameName(text(fields, "companyName"), profile.legalName()));
            passed &= addCheck(checks, "validity", notExpired(text(fields, "validUntil")));
        } else {
            passed = false;
            addCheck(checks, "document_type", false);
        }
        String status = passed ? "passed" : "needs_review";
        result.put("status", status);
        try {
            return new KybVerifiedDocument(
                    1, status, mapper.writeValueAsString(result), analysis.provider(), analysis.model());
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize KYB verification result", error);
        }
    }

    private boolean notExpired(String raw) {
        if (raw == null || raw.isBlank() || "长期".equals(raw.trim())) {
            return true;
        }
        try {
            return !LocalDate.parse(raw.trim()).isBefore(LocalDate.now(clock));
        } catch (DateTimeParseException error) {
            return false;
        }
    }

    private static boolean addCheck(ArrayNode checks, String code, boolean passed) {
        ObjectNode check = checks.addObject();
        check.put("code", code);
        check.put("result", passed ? "pass" : "needs_review");
        return passed;
    }

    private static void copy(ObjectNode target, JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source == null ? null : source.get(field);
            if (value == null || value.isNull() || !value.isValueNode()) {
                target.putNull(field);
            } else {
                target.set(field, value);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    private static boolean sameName(String left, String right) {
        return left != null && right != null && normalizeName(left).equals(normalizeName(right));
    }

    private static String normalizeName(String value) {
        return value.replaceAll("[\\s\\p{Punct}，。·]", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
}
