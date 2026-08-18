package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SpeechTranscriptionRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private SpeechTranscriptionRepository repository;

    @BeforeEach
    void clear() {
        db.sql("DELETE FROM speech_transcription").then().block();
    }

    @Test
    void processing_canStoreProviderResultAndComplete_withOwnerScope() {
        UUID id = UUID.randomUUID();
        SpeechTranscription created = repository.createProcessing(new SpeechTranscription(
                id, UUID.randomUUID(), "acct-a", "org-a", "en", null, 1200L,
                "processing", null, null, null, null, null, null, null, null, null)).block();

        assertThat(created.status()).isEqualTo("processing");
        assertThat(repository.findOwned(id, "acct-b").block()).isNull();
        assertThat(repository.storeProviderResult(id, "hello", "en-US", "qwen", "asr-v1", 3, UUID.randomUUID()).block())
                .isTrue();
        assertThat(repository.markCompleted(id).block()).isTrue();
        assertThat(repository.findOwned(id, "acct-a").block())
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("completed");
                    assertThat(row.transcriptText()).isEqualTo("hello");
                    assertThat(row.completedAt()).isNotNull();
                });
    }

    @Test
    void markFailed_storesOnlyFailureCodeAndClearsTranscript() {
        UUID id = UUID.randomUUID();
        repository.createProcessing(new SpeechTranscription(
                id, UUID.randomUUID(), "acct-a", null, "en", null, 0L,
                "processing", null, null, null, null, null, null, null, null, null)).block();
        repository.storeProviderResult(id, "secret provider output", "en", "qwen", "asr", 1, UUID.randomUUID()).block();

        assertThat(repository.markFailed(id, "provider_timeout").block()).isTrue();
        assertThat(repository.findOwned(id, "acct-a").block())
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("failed");
                    assertThat(row.failureCode()).isEqualTo("provider_timeout");
                    assertThat(row.transcriptText()).isNull();
                    assertThat(row.provider()).isNull();
                    assertThat(row.model()).isNull();
                    assertThat(row.aiRunId()).isNull();
                });
    }

    @Test
    void markFailed_rejectsUnstableFailureCodesWithoutPersistingThem() {
        UUID id = UUID.randomUUID();
        repository.createProcessing(new SpeechTranscription(
                id, UUID.randomUUID(), "acct-a", null, "en", null, 0L,
                "processing", null, null, null, null, null, null, null, null, null)).block();

        for (String invalid : List.of(" ", "provider timeout", "provider: denied", "a".repeat(65))) {
            assertThatThrownBy(() -> repository.markFailed(id, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.findOwned(id, "acct-a").block())
                    .satisfies(row -> {
                        assertThat(row.status()).isEqualTo("processing");
                        assertThat(row.failureCode()).isNull();
                    });
        }

        assertThat(repository.markFailed(id, "unsupported_provider").block()).isTrue();
    }
}
