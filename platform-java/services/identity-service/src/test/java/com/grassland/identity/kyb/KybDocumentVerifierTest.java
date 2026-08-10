package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class KybDocumentVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KybFieldCrypto crypto = mock(KybFieldCrypto.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private final KybDocumentVerifier verifier = new KybDocumentVerifier(mapper, crypto, clock);

    @Test
    void matchingBusinessLicenseIsAdvisoryPassed() throws Exception {
        KybDocumentAnalysis analysis = new KybDocumentAnalysis(
                1, "business_license", 0.97,
                mapper.readTree("""
                        {"companyName":"草场科技有限公司","unifiedSocialCreditCode":"91310000MA1K123456",
                         "legalRepresentative":"张三","registeredAddress":"上海市",
                         "validFrom":"2020-01-01","validUntil":"长期"}
                        """), "qwen", "qwen-vl");

        KybVerifiedDocument result = verifier.verify(analysis, profile());

        assertThat(result.status()).isEqualTo("passed");
        assertThat(result.safeResultJson()).contains("company_name_match", "legal_representative_match");
    }

    @Test
    void idNumberIsComparedButNeverPersisted() throws Exception {
        when(crypto.matches("encrypted-id", "310101199001011234")).thenReturn(true);
        KybDocumentAnalysis analysis = new KybDocumentAnalysis(
                1, "legal_person_id_front", 0.99,
                mapper.readTree("""
                        {"name":"张三","idNumber":"310101199001011234","side":"front","validUntil":"2030-01-01"}
                        """), "qwen", "qwen-vl");

        KybVerifiedDocument result = verifier.verify(analysis, profile());

        assertThat(result.status()).isEqualTo("passed");
        assertThat(result.safeResultJson()).contains("legal_person_id_match");
        assertThat(result.safeResultJson()).doesNotContain("310101199001011234", "idNumber");
    }

    @Test
    void expiredOrMismatchedDocumentRequiresHumanReview() throws Exception {
        KybDocumentAnalysis analysis = new KybDocumentAnalysis(
                1, "business_license", 0.98,
                mapper.readTree("""
                        {"companyName":"其他公司","unifiedSocialCreditCode":"91310000MA1K123456",
                         "legalRepresentative":"李四","validUntil":"2025-01-01"}
                        """), "qwen", "qwen-vl");

        KybVerifiedDocument result = verifier.verify(analysis, profile());

        assertThat(result.status()).isEqualTo("needs_review");
        assertThat(result.safeResultJson()).contains("needs_review");
    }

    private static MerchantProfile profile() {
        return new MerchantProfile(
                "org", "草场科技有限公司", "91310000MA1K123456", "limited_company",
                "张三", "encrypted-id", 100L, LocalDate.parse("2020-01-01"), "{}",
                "13800000000", "kyb@example.com", "draft", null, null, null, null, null, null);
    }
}

