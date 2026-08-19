package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ContentSafetyLexiconTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentSafetyLexiconRepository repository;
    private ContentSafetyLexicon lexicons;

    @BeforeEach
    void setUp() {
        repository = mock(ContentSafetyLexiconRepository.class);
        lexicons = new ContentSafetyLexicon(repository, mock(TransactionalOperator.class));
    }

    @Test
    void createDraftMapsOnlyPostgresUniqueViolationToConflict() {
        R2dbcException sql = mock(R2dbcException.class);
        when(sql.getSqlState()).thenReturn("23505");
        when(repository.createDraft("lexicon-v2", payload().toString(), "admin"))
                .thenReturn(Mono.error(new RuntimeException("wrapped", sql)));

        StepVerifier.create(lexicons.createDraft("lexicon-v2", payload(), "admin"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOfSatisfying(IntelligenceException.class,
                                value -> assertThat(value.status()).isEqualTo(409)))
                .verify();
    }

    @Test
    void createDraftPreservesUnrelatedDatabaseFailure() {
        when(repository.createDraft("lexicon-v2", payload().toString(), "admin"))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(lexicons.createDraft("lexicon-v2", payload(), "admin"))
                .expectErrorMatches(error -> error instanceof RuntimeException
                        && "db down".equals(error.getMessage()))
                .verify();
    }

    @Test
    void overlaysSupportPlatformIndustryAliasAndMissingOverlayCompatibility() throws Exception {
        var withOverlays = ContentSafetyLexicon.parse(payload(), "lexicon-v2");
        var result = ContentSafetyChecker.check(
                withOverlays, "这里包含平台词和行业词", "DOUYIN", "美食");
        assertThat(result.appliedOverlays()).containsExactly("douyin", "food");
        assertThat(result.findings()).extracting(SafetyReport.Finding::category)
                .contains("platform_overlay", "industry_overlay");

        JsonNode legacy = mapper.readTree("""
                {"version":"lexicon-v3","categories":[{"id":"base","severity":"low",
                "advice":"改写","phrases":["基础词"],"patterns":[]}]}
                """);
        var legacyResult = ContentSafetyChecker.check(
                ContentSafetyLexicon.parse(legacy, "lexicon-v3"), "基础词", "douyin", "美食");
        assertThat(legacyResult.appliedOverlays()).isEmpty();
        assertThat(legacyResult.findings()).extracting(SafetyReport.Finding::category)
                .containsExactly("base");
    }

    private JsonNode payload() {
        try {
            return mapper.readTree("""
                    {"version":"lexicon-v2","categories":[{"id":"base","severity":"low",
                    "advice":"改写","phrases":["基础词"],"patterns":[]}],"overlays":{
                    "platforms":{"douyin":["平台词"]},"industries":{"food":["行业词"]},
                    "industryAliases":{"美食":"food"}}}
                    """);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
