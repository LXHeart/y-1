package com.grassland.intelligence.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService.Routed;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KybDocumentAnalysisServiceTest {

    private final RoutedTextCompletionService ai = mock(RoutedTextCompletionService.class);
    private final MediaReferenceRepository refs = mock(MediaReferenceRepository.class);
    private final ObjectStorageAdapter storage = mock(ObjectStorageAdapter.class);
    private final KybDocumentAnalysisService service = new KybDocumentAnalysisService(
            ai, refs, storage, 5000);

    private static final String KYB_COMPLETION =
            "{\"documentType\":\"business_license\",\"confidence\":0.96,"
                    + "\"fields\":{\"companyName\":\"草场科技有限公司\"}}";

    private static Mono<com.grassland.intelligence.ai.run.TextCompletionResult> completion(String content) {
        return Mono.just(new com.grassland.intelligence.ai.run.TextCompletionResult(content, 1, 1, null));
    }

    @Test
    void readsOnlyTenantScopedKybEvidence() {
        UUID id = UUID.randomUUID();
        when(refs.findById(id)).thenReturn(Mono.just(reference(id, "org-a", "merchant_kyb", "org-a")));
        when(storage.getObject("kyb/object.png")).thenReturn(new byte[]{1, 2, 3});
        // 决策 I：KYB 走治理域固定平台路由，Result 回填路由解析的真实 provider/model
        Routed routed = new Routed(ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://qwen.example/v1", "qwen-vl", 7, null), "bearer-key");
        when(ai.resolvePlatform()).thenReturn(Mono.just(routed));
        when(ai.completeWith(any(), any(), anyInt(), any(), any()))
                .thenReturn(completion(KYB_COMPLETION));

        KybDocumentAnalysisService.Result result = service.analyze(id, "org-a", "business_license").block();

        assertThat(result).isNotNull();
        assertThat(result.documentType()).isEqualTo("business_license");
        assertThat(result.fields().path("companyName").asText()).isEqualTo("草场科技有限公司");
        assertThat(result.provider()).isEqualTo("qwen");
        assertThat(result.model()).isEqualTo("qwen-vl");
    }

    @Test
    void rejectsCrossTenantBeforeObjectRead() {
        UUID id = UUID.randomUUID();
        when(refs.findById(id)).thenReturn(Mono.just(reference(id, "org-b", "merchant_kyb", "org-b")));

        assertThatThrownBy(() -> service.analyze(id, "org-a", "business_license").block())
                .isInstanceOf(IntelligenceException.class)
                .satisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(404));
        verify(storage, never()).getObject(any());
        verify(ai, never()).resolvePlatform();
    }

    @Test
    void rejectsWrongDomainBeforeObjectRead() {
        UUID id = UUID.randomUUID();
        when(refs.findById(id)).thenReturn(Mono.just(reference(id, "org-a", "other", "org-a")));

        assertThatThrownBy(() -> service.analyze(id, "org-a", "business_license").block())
                .isInstanceOf(IntelligenceException.class);
        verify(storage, never()).getObject(any());
    }

    private static MediaReference reference(UUID id, String org, String domainType, String domainId) {
        return new MediaReference(
                id, UUID.randomUUID().toString(), org, "merchant_kyb", domainType, domainId,
                "kyb/object.png", null, "image/png", 3, "abc", "upload",
                MediaStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(600), null);
    }
}
