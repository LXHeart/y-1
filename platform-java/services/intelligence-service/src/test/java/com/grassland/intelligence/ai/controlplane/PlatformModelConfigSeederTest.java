package com.grassland.intelligence.ai.controlplane;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.UUID;
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
}
