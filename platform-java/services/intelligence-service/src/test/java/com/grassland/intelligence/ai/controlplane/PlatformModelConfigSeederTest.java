package com.grassland.intelligence.ai.controlplane;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.time.Duration;
import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PlatformModelConfigSeederTest {

    @Mock
    PlatformModelConfigRepository repository;

    @Mock
    com.grassland.intelligence.ai.PlatformModelConfig envDefaults;

    @Mock
    TransactionalOperator transactions;

    @Test
    void seedsOnlyMissingVoiceAndRetrievalCapabilities() throws Exception {
        when(repository.count()).thenReturn(Mono.just(1L));
        when(repository.findCurrent("voice", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.empty());
        when(repository.findCurrent("retrieval", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.empty());
        when(repository.create(any(PlatformModelConfig.class), eq("system")))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(transactions.transactional(org.mockito.ArgumentMatchers.<Mono<UUID>>any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PlatformModelConfigSeeder seeder = new PlatformModelConfigSeeder(repository, envDefaults, transactions);

        seeder.run(new DefaultApplicationArguments(new String[0]));

        verify(repository).create(argThat(model -> model.capability().equals("voice")
                && model.provider().equals("sandbox")
                && model.model().equals("sandbox-speech-v1")), eq("system"));
        verify(repository).create(argThat(model -> model.capability().equals("retrieval")
                && model.provider().equals("sandbox")
                && model.model().equals("sandbox-embedding-v1")), eq("system"));
    }

    @Test
    void seedsRetrievalWhenVoiceAlreadyHasPrimaryConfig() throws Exception {
        when(repository.count()).thenReturn(Mono.just(1L));
        when(repository.findCurrent("voice", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.just(new PlatformModelConfig(
                        null, "voice", PlatformModelConfig.ROLE_PRIMARY, "sandbox", "existing",
                        "https://sandbox.invalid", null, PlatformModelConfig.HEALTH_HEALTHY,
                        true, 1, "system", null, null)));
        when(repository.findCurrent("retrieval", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.empty());
        when(repository.create(any(PlatformModelConfig.class), eq("system")))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(transactions.transactional(org.mockito.ArgumentMatchers.<Mono<UUID>>any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        new PlatformModelConfigSeeder(repository, envDefaults, transactions)
                .run(new DefaultApplicationArguments(new String[0]));

        verify(repository, never()).create(argThat(model -> model.capability().equals("voice")), eq("system"));
        verify(repository).create(argThat(model -> model.capability().equals("retrieval")), eq("system"));
    }

    @Test
    void upgradesBuiltInSandboxSeedsWhenRealProvidersAreConfigured() throws Exception {
        when(repository.count()).thenReturn(Mono.just(1L));
        PlatformModelConfig sandboxVoice = new PlatformModelConfig(
                UUID.randomUUID(), "voice", PlatformModelConfig.ROLE_PRIMARY,
                "sandbox", "sandbox-speech-v1", "https://sandbox.invalid", null,
                PlatformModelConfig.HEALTH_HEALTHY, true, 1, "system", null, null);
        when(repository.findCurrent("voice", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.just(sandboxVoice));
        when(repository.findCurrent("retrieval", PlatformModelConfig.ROLE_PRIMARY))
                .thenReturn(Mono.empty());
        when(repository.revise(eq("voice"), eq(PlatformModelConfig.ROLE_PRIMARY),
                any(PlatformModelConfig.class), eq("system")))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(2)));
        when(repository.create(any(PlatformModelConfig.class), eq("system")))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(transactions.transactional(org.mockito.ArgumentMatchers.<Mono<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SpeechProviderProperties speech = new SpeechProviderProperties(
                "openai-compatible", "https://api.openai.com/v1", "valid-secret-key-1234", "whisper-1",
                "/audio/transcriptions", Duration.ofSeconds(30), 65_536, 0, 0, 1);
        EmbeddingProviderProperties embedding = new EmbeddingProviderProperties(
                "openai-compatible", "https://api.openai.com/v1", "valid-secret-key-1234", "embed-v1",
                "/embeddings", Duration.ofSeconds(30), 65_536, 256, false, 1);

        new PlatformModelConfigSeeder(repository, envDefaults, transactions, speech, embedding)
                .run(new DefaultApplicationArguments(new String[0]));

        verify(repository).revise(eq("voice"), eq(PlatformModelConfig.ROLE_PRIMARY),
                argThat(model -> model.provider().equals("openai-compatible")
                        && model.model().equals("whisper-1")), eq("system"));
        verify(repository).create(argThat(model -> model.capability().equals("retrieval")
                && model.provider().equals("openai-compatible")
                && model.model().equals("embed-v1")), eq("system"));
    }
}
