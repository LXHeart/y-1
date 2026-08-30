package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.AiProviderKey;
import com.grassland.intelligence.ai.byok.AiProviderKeyRepository;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.contentlibrary.AssetCategory;
import com.grassland.intelligence.contentlibrary.AssetStatus;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.contentlibrary.LibraryType;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.FrozenVideoGenerationConfigResolver;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CreationContextServiceTest {
    private MarketplaceCreationContextClient marketplace;
    private ContentAssetRepository assets;
    private CreationContextSnapshotRepository snapshots;
    private AiProviderKeyRepository keys;
    private PlatformModelControlPlaneService models;
    private FrozenVideoGenerationConfigResolver videoGenerationConfig;
    private FrozenImageGenerationConfigResolver imageGenerationConfig;
    private CreationContextService service;

    @BeforeEach
    void setUp() {
        marketplace = mock(MarketplaceCreationContextClient.class);
        assets = mock(ContentAssetRepository.class);
        snapshots = mock(CreationContextSnapshotRepository.class);
        keys = mock(AiProviderKeyRepository.class);
        models = mock(PlatformModelControlPlaneService.class);
        videoGenerationConfig = mock(FrozenVideoGenerationConfigResolver.class);
        imageGenerationConfig = mock(FrozenImageGenerationConfigResolver.class);
        service = new CreationContextService(
                marketplace, assets, snapshots, keys, models,
                videoGenerationConfig, imageGenerationConfig);
        // 任务书 #58：平台图像段快照=控制面行 + 静态价目（无密钥/端点/指纹）
        when(imageGenerationConfig.platformSnapshot()).thenReturn(Mono.just(new java.util.LinkedHashMap<>(Map.of(
                "provider", "qwen", "model", "wanx-v1",
                "pricingVersion", "image-config-v1", "unitPriceCents", 80,
                "platformModelVersion", 1))));
        when(snapshots.findByKey(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Test
    void freezesAuthoritativeTaskRulesMaterialsAndByokMetadata() {
        UUID second = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        Map<String, Object> task = Map.of(
                "taskId", "task-1", "applicationId", "app-1", "taskVersion", 4,
                "platform", "小红书", "contentForm", "图文", "title", "接受时标题");
        when(marketplace.fetch("app-1", "task-1", "account-1"))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(task, "org-1", java.util.Map.of())));
        when(assets.findForCreation(List.of(second, first), "account-1", "org-1"))
                .thenReturn(Flux.just(asset(first, "first"), asset(second, "second")));
        when(keys.findByPersonalAndCapability("account-1", "text"))
                .thenReturn(Mono.just(new AiProviderKey(
                        UUID.randomUUID(), null, "account-1", "text", "qwen",
                        "https://provider.internal.example", "qwen-plus", "ciphertext", "v7",
                        "sk-***last", true, Instant.now(), Instant.now())));
        when(snapshots.create(any())).thenAnswer(invocation -> {
            CreationContextSnapshot snapshot = invocation.getArgument(0);
            return Mono.just(new CreationContextSnapshot(
                    UUID.randomUUID(), snapshot.accountId(), snapshot.organizationId(), snapshot.taskId(),
                    snapshot.applicationId(), snapshot.taskVersion(), snapshot.platformId(), snapshot.contentFormId(),
                    snapshot.taskSnapshot(), snapshot.platformRulesSnapshot(), snapshot.materialSnapshot(),
                    snapshot.aiConfigSnapshot(), snapshot.storeBrandingSnapshot(), Instant.now()));
        });

        CreationContextSnapshot result = service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-1", "app-1", 4, "xiaohongshu", "graphic",
                        List.of(second.toString(), first.toString()))).block();

        assertThat(result.platformRulesSnapshot())
                .containsEntry("version", PlatformCreationRuleCatalog.VERSION)
                .containsEntry("minChars", 50)
                .containsEntry("requiresCreatorConfirmation", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frozenMaterials =
                (List<Map<String, Object>>) result.materialSnapshot().get("items");
        assertThat(frozenMaterials).extracting(item -> item.get("assetId"))
                .containsExactly(second.toString(), first.toString());
        assertThat(result.aiConfigSnapshot())
                .containsEntry("resolutionType", "BYOK")
                .containsEntry("provider", "qwen")
                .containsEntry("model", "qwen-plus")
                .containsEntry("keyVersion", "v7")
                .containsEntry("maskedHint", "sk-***last")
                .doesNotContainKeys("encryptedKey", "apiKey", "baseUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageConfig =
                (Map<String, Object>) result.aiConfigSnapshot().get("imageGeneration");
        assertThat(imageConfig)
                .containsEntry("provider", "qwen")
                .containsEntry("model", "wanx-v1")
                .doesNotContainKeys("apiKey", "baseUrl", "runtimeFingerprint");
    }

    @Test
    void freezesVideoAndImageProviderMetadataForVideoTasks() {
        Map<String, Object> task = Map.of(
                "taskId", "task-video", "applicationId", "app-video", "taskVersion", 2,
                "platform", "douyin", "contentForm", "video");
        when(marketplace.fetch("app-video", "task-video", "account-1"))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(task, "org-1", java.util.Map.of())));
        when(assets.findForCreation(List.of(), "account-1", "org-1")).thenReturn(Flux.empty());
        when(keys.findByPersonalAndCapability("account-1", "text"))
                .thenReturn(Mono.just(new AiProviderKey(
                        UUID.randomUUID(), null, "account-1", "text", "qwen",
                        "https://provider.internal.example", "qwen-plus", "ciphertext", "v7",
                        "sk-***last", true, Instant.now(), Instant.now())));
        Map<String, Object> videoConfig = Map.of(
                "provider", "sandbox", "model", "sandbox-video-v1",
                "pricingVersion", "video-config-v1", "unitPriceCents", 1,
                "platformModelVersion", 1, "maxConcurrency", 4,
                "maxDurationSeconds", 10, "runtimeFingerprint", "a".repeat(64));
        when(videoGenerationConfig.snapshot()).thenReturn(videoConfig);
        when(snapshots.create(any())).thenAnswer(invocation -> {
            CreationContextSnapshot snapshot = invocation.getArgument(0);
            return Mono.just(new CreationContextSnapshot(
                    UUID.randomUUID(), snapshot.accountId(), snapshot.organizationId(), snapshot.taskId(),
                    snapshot.applicationId(), snapshot.taskVersion(), snapshot.platformId(), snapshot.contentFormId(),
                    snapshot.taskSnapshot(), snapshot.platformRulesSnapshot(), snapshot.materialSnapshot(),
                    snapshot.aiConfigSnapshot(), snapshot.storeBrandingSnapshot(), Instant.now()));
        });

        CreationContextSnapshot result = service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-video", "app-video", 2, "douyin", "video", List.of())).block();

        assertThat(result.aiConfigSnapshot()).containsEntry("videoGeneration", videoConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> imageConfig =
                (Map<String, Object>) result.aiConfigSnapshot().get("imageGeneration");
        assertThat(imageConfig)
                .containsEntry("provider", "qwen")
                .containsEntry("model", "wanx-v1")
                .doesNotContainKeys("apiKey", "baseUrl", "runtimeFingerprint");
        verify(videoGenerationConfig).snapshot();
        verify(imageGenerationConfig).platformSnapshot();
    }

    @Test
    void returnsExistingSnapshotWithoutRefetchingMutableDependencies() {
        CreationContextSnapshot existing = new CreationContextSnapshot(
                UUID.randomUUID(), "account-1", "org-1", "task-1", "app-1", 4,
                "xiaohongshu", "graphic", Map.of("title", "frozen"), Map.of(), Map.of(), Map.of(), Map.of(), Instant.now());
        when(snapshots.findByKey("account-1", "app-1", 4, "xiaohongshu", "graphic"))
                .thenReturn(Mono.just(existing));

        CreationContextSnapshot result = service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-1", "app-1", 4, "xiaohongshu", "graphic", List.of())).block();

        assertThat(result.id()).isEqualTo(existing.id());
        verify(marketplace, never()).fetch(anyString(), anyString(), anyString());
        verify(assets, never()).findForCreation(any(), anyString(), anyString());
    }

    /** 任务书 #24：storeBranding 首次创建即冻结；二次创建同幂等键返回同一快照（不可变回归）。 */
    @Test
    void freezesStoreBrandingAndSecondCreateReturnsSameSnapshot() {
        Map<String, Object> task = Map.of(
                "taskId", "task-9", "applicationId", "app-9", "taskVersion", 2,
                "platform", "xiaohongshu", "contentForm", "graphic");
        Map<String, Object> branding = Map.of(
                "storeName", "旗舰店", "brandTone", "温暖亲切",
                "mustEmphasize", List.of("锅底现熬"), "forbiddenPhrases", List.of("最好吃"));
        when(marketplace.fetch("app-9", "task-9", "account-1"))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(
                        task, "org-1", branding)));
        when(assets.findForCreation(List.of(), "account-1", "org-1")).thenReturn(Flux.empty());
        when(keys.findByPersonalAndCapability("account-1", "text"))
                .thenReturn(Mono.just(new AiProviderKey(
                        UUID.randomUUID(), null, "account-1", "text", "qwen",
                        "https://provider.internal.example", "qwen-plus", "ciphertext", "v7",
                        "sk-***last", true, Instant.now(), Instant.now())));
        when(snapshots.create(any())).thenAnswer(invocation -> {
            CreationContextSnapshot snapshot = invocation.getArgument(0);
            return Mono.just(new CreationContextSnapshot(
                    UUID.randomUUID(), snapshot.accountId(), snapshot.organizationId(), snapshot.taskId(),
                    snapshot.applicationId(), snapshot.taskVersion(), snapshot.platformId(), snapshot.contentFormId(),
                    snapshot.taskSnapshot(), snapshot.platformRulesSnapshot(), snapshot.materialSnapshot(),
                    snapshot.aiConfigSnapshot(), snapshot.storeBrandingSnapshot(), Instant.now()));
        });

        CreationContextSnapshot first = service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-9", "app-9", 2, "xiaohongshu", "graphic", List.of())).block();
        assertThat(first).isNotNull();
        assertThat(first.storeBrandingSnapshot())
                .containsEntry("storeName", "旗舰店")
                .containsEntry("brandTone", "温暖亲切")
                .containsEntry("mustEmphasize", List.of("锅底现熬"))
                .containsEntry("forbiddenPhrases", List.of("最好吃"));

        // 二次创建（同幂等键）→ 命中既有快照，不重拉权威上下文（首次创建即不可变）。
        when(snapshots.findByKey("account-1", "app-9", 2, "xiaohongshu", "graphic"))
                .thenReturn(Mono.just(first));
        CreationContextSnapshot second = service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-9", "app-9", 2, "xiaohongshu", "graphic", List.of())).block();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.storeBrandingSnapshot()).isEqualTo(first.storeBrandingSnapshot());
        verify(marketplace, times(1)).fetch(anyString(), anyString(), anyString());
        verify(snapshots, times(1)).create(any());
    }

    @Test
    void rejectsUnauthorizedOrExpiredMaterialSet() {
        UUID requested = UUID.randomUUID();
        Map<String, Object> task = Map.of(
                "taskId", "task-1", "applicationId", "app-1", "taskVersion", 4,
                "platform", "xiaohongshu", "contentForm", "graphic");
        when(marketplace.fetch("app-1", "task-1", "account-1"))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(task, "org-1", java.util.Map.of())));
        when(assets.findForCreation(List.of(requested), "account-1", "org-1"))
                .thenReturn(Flux.empty());

        assertThatThrownBy(() -> service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-1", "app-1", 4, "xiaohongshu", "graphic",
                        List.of(requested.toString()))).block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("无权使用、过期或已失效");
        verify(snapshots, never()).create(any());
    }

    @Test
    void rejectsTaskVersionAndPlatformMismatch() {
        Map<String, Object> task = Map.of(
                "taskId", "task-1", "applicationId", "app-1", "taskVersion", 5,
                "platform", "douyin", "contentForm", "video");
        when(marketplace.fetch("app-1", "task-1", "account-1"))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(task, "org-1", java.util.Map.of())));

        assertThatThrownBy(() -> service.create("account-1",
                new CreationContextService.CreateCreationContextRequest(
                        "task-1", "app-1", 4, "xiaohongshu", "graphic", List.of())).block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("任务版本已变化");
    }

    private ContentAsset asset(UUID id, String title) {
        return new ContentAsset(
                id, UUID.randomUUID(), LibraryType.PERSONAL, AssetCategory.COPY, "account-1", null,
                title, List.of("task"), "text/plain", 64L, Instant.parse("2027-01-01T00:00:00Z"),
                AssetStatus.ACTIVE, 2, null, null, null, null, null, Instant.now(), Instant.now(), null);
    }
}
