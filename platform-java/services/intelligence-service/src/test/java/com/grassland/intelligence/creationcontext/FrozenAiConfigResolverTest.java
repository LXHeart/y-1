package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.AiProviderKey;
import com.grassland.intelligence.ai.byok.AiProviderKeyRepository;
import com.grassland.intelligence.ai.controlplane.PlatformModelConfigRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class FrozenAiConfigResolverTest {

    @Mock
    CreationContextSnapshotRepository snapshots;
    @Mock
    AiProviderKeyRepository keys;
    @Mock
    PlatformModelConfigRepository platformModels;
    @InjectMocks
    FrozenAiConfigResolver resolver;

    @Test
    void frozenByokResolutionPreservesKeyVersion() {
        UUID snapshotId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-08-18T00:00:00Z");
        CreationContextSnapshot snapshot = new CreationContextSnapshot(
                snapshotId, "acct-1", "org-1", "task-1", "app-1", 1,
                "xiaohongshu", "graphic", Map.of(), Map.of(), Map.of(),
                Map.of("resolutionType", "BYOK", "configId", keyId.toString(),
                        "provider", "openai-compatible", "model", "frozen-model",
                        "keyVersion", "v7", "configUpdatedAt", updatedAt.toString()),
                Map.of(), updatedAt);
        AiProviderKey key = new AiProviderKey(
                keyId, null, "acct-1", "text", "openai-compatible", "https://api.example.com",
                "frozen-model", "ciphertext", "v7", "sk-***", true, updatedAt, updatedAt);
        when(snapshots.findById(snapshotId)).thenReturn(Mono.just(snapshot));
        when(keys.findPersonalByIdAndOwner(keyId, "acct-1")).thenReturn(Mono.just(key));

        FrozenAiConfigResolver.ResolvedSnapshot resolved = resolver.resolve(snapshotId, "acct-1", "text").block();

        assertThat(resolved.provider().modelVersionKey()).isEqualTo("byok:v7");
    }
}
