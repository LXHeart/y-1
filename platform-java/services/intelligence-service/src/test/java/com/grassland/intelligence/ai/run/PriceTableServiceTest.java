package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PriceTableServiceTest {

    @Test
    void sandboxModelsArePricedAtZero() {
        PriceTableService prices = new PriceTableService();

        assertThat(prices.calculateCost("sandbox-speech-v1", 0, 0, 0, 0)).isZero();
        assertThat(prices.calculateCost("sandbox-embedding-v1", 400, 0, 0, 0)).isZero();
    }

    @Test
    void configuredSpeechAndEmbeddingModelsUseRealPrices() {
        SpeechProviderProperties speech = new SpeechProviderProperties(
                "openai-compatible", "https://api.openai.com/v1", "valid-secret-key-1234", "whisper-1",
                "/audio/transcriptions", Duration.ofSeconds(30), 65_536, 0, 0, 2);
        EmbeddingProviderProperties embedding = new EmbeddingProviderProperties(
                "openai-compatible", "https://api.openai.com/v1", "valid-secret-key-1234", "embed-v1",
                "/embeddings", Duration.ofSeconds(30), 65_536, 256, false, 3);
        PriceTableService prices = new PriceTableService(speech, embedding);

        assertThat(prices.calculateCost("whisper-1", 0, 0, 0, 7)).isEqualTo(14);
        assertThat(prices.calculateCost("embed-v1", 1_001, 0, 0, 0)).isEqualTo(4);
    }
}
