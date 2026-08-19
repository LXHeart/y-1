package com.grassland.intelligence.contentlibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import com.grassland.intelligence.articleimage.GeneratedImage;
import com.grassland.intelligence.articleimage.ImageGenerationClient;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class PublicAssetBatchGenerationServiceTest {

    @Mock private ImageGenerationClient images;
    @Mock private FrozenImageGenerationConfigResolver frozenConfig;
    @Mock private ImageGenerationConfig runtimeConfig;
    @Mock private AiExecutionService executions;
    @Mock private ObjectProvider<ObjectStorageAdapter> storageProvider;
    @Mock private ObjectStorageAdapter storage;
    @Mock private MediaReferenceRepository media;
    @Mock private ContentAssetRepository assets;
    @Mock private OutboxRepository outbox;
    @Mock private TransactionalOperator transactions;

    private PublicAssetBatchGenerationService service;

    @BeforeEach
    void setUp() {
        service = new PublicAssetBatchGenerationService(
                images, frozenConfig, runtimeConfig, executions, storageProvider,
                media, assets, outbox, transactions);
        var config = new FrozenImageGenerationConfigResolver.Config(
                "qwen", "wanx-v1", "pricing-v1", 80, 3, "fingerprint");
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://example.com", "wanx-v1", 3, null);
        var context = new AiExecutionService.ExecutionContext(
                UUID.randomUUID(), null, "reviewer", "image_generation", provider,
                ModelBudgetService.BudgetCheckResult.allowed(null, null, 0, 80),
                UUID.randomUUID(), null, null, false, null, "pricing-v1", 0, 0);
        when(frozenConfig.current()).thenReturn(config);
        when(runtimeConfig.baseUrl()).thenReturn("https://example.com");
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(executions.preparePlatformAsyncExecution(
                anyString(), any(), anyString(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(Mono.just(AiExecutionService.ExecutionResult.allowed(context)));
        when(executions.settleSuccessWithCost(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Mono.just(true));
        when(executions.handleFailure(any(), anyString())).thenReturn(Mono.just(true));
        when(media.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(assets.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void batchIsSequentialPartiallySuccessfulAndPersistsPermanentPendingAssets() {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        when(images.generate(anyString(), anyString()))
                .thenReturn(Mono.just(new GeneratedImage(null, encoded, "one")))
                .thenReturn(Mono.error(new RuntimeException("provider failed")))
                .thenReturn(Mono.just(new GeneratedImage(null, encoded, "three")));

        var result = service.generate("reviewer", new PublicAssetBatchGenerationService.Command(
                PublicAssetBatchGenerationService.Kind.BACKGROUND, "夏日", "清爽", 3,
                Instant.now().plusSeconds(86400))).block();

        assertThat(result.okCount()).isEqualTo(2);
        assertThat(result.items()).extracting(PublicAssetBatchGenerationService.Item::ok)
                .containsExactly(true, false, true);
        assertThat(result.items().get(1).errorReason()).contains("provider failed");
        verify(images, times(3)).generate(anyString(), anyString());
        verify(executions, times(3)).preparePlatformAsyncExecution(
                anyString(), any(), anyString(), any(), any(), any(), anyInt(), anyString());
        verify(storage, times(2)).putObject(anyString(), any(), anyString());

        ArgumentCaptor<MediaReference> mediaRows = ArgumentCaptor.forClass(MediaReference.class);
        verify(media, times(2)).insert(mediaRows.capture());
        assertThat(mediaRows.getAllValues()).allSatisfy(row -> {
            assertThat(row.expiresAt()).isNull();
            assertThat(row.purpose()).isEqualTo("content_asset");
            assertThat(row.source()).isEqualTo("generated");
        });

        ArgumentCaptor<ContentAsset> assetRows = ArgumentCaptor.forClass(ContentAsset.class);
        verify(assets, times(2)).create(assetRows.capture());
        assertThat(assetRows.getAllValues()).allSatisfy(row -> {
            assertThat(row.libraryType()).isEqualTo(LibraryType.PUBLIC);
            assertThat(row.status()).isEqualTo(AssetStatus.PENDING_REVIEW);
            assertThat(row.source()).isEqualTo("platform_ai");
            assertThat(row.licenseScope()).isEqualTo("platform_authorized");
        });
    }
}
