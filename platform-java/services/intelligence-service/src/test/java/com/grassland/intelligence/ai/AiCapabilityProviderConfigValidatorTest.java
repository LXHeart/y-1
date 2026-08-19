package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiCapabilityProviderConfigValidatorTest {

    @Test
    void acceptsSandboxDefaults() {
        AiCapabilityProviderConfigValidator.validateSpeech(new SpeechProviderProperties(
                "sandbox", "https://sandbox.invalid", "", "sandbox-speech-v1",
                "/audio/transcriptions", Duration.ofSeconds(30), 65_536, 0, 0, 0), false);
        AiCapabilityProviderConfigValidator.validateEmbedding(new EmbeddingProviderProperties(
                "sandbox", "https://sandbox.invalid", "", "sandbox-embedding-v1",
                "/embeddings", Duration.ofSeconds(30), 65_536, 256, false, 0), false);
    }

    @Test
    void realProviderRequiresHttpsSecretAndBoundedDimensions() {
        assertThatThrownBy(() -> AiCapabilityProviderConfigValidator.validateSpeech(
                new SpeechProviderProperties(
                        "openai-compatible", "http://example.com", "short", "whisper-1",
                        "/audio/transcriptions", Duration.ofSeconds(30), 65_536, 0, 0, 1), false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AiCapabilityProviderConfigValidator.validateEmbedding(
                new EmbeddingProviderProperties(
                        "openai-compatible", "https://api.openai.com/v1", "valid-secret-key-1234", "embedding",
                        "/embeddings", Duration.ofSeconds(30), 65_536, 5000, false, 1), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void rejectsProviderPathTraversal() {
        assertThatThrownBy(() -> AiCapabilityProviderConfigValidator.validateEmbedding(
                new EmbeddingProviderProperties(
                        "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "valid-secret-key-1234", "embedding", "/../embeddings",
                        Duration.ofSeconds(30), 65_536, 256, false, 1), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path");
    }
}
