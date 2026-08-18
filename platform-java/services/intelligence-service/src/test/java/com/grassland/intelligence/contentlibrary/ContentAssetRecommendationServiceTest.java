package com.grassland.intelligence.contentlibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.creationcontext.MarketplaceCreationContextClient;
import com.grassland.intelligence.embedding.ContentAssetEmbedding;
import com.grassland.intelligence.embedding.ContentAssetEmbeddingRepository;
import com.grassland.intelligence.embedding.EmbeddingExecutionService;
import com.grassland.intelligence.embedding.EmbeddingExecutionService.EmbeddingOutcome;
import com.grassland.intelligence.embedding.EmbeddingTextNormalizer;
import com.grassland.intelligence.embedding.SandboxEmbeddingProvider;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #33：语义检索编排——无 query 完全兼容、query 校验、任务模式权威文本派生、
 * 降级回退、缺向量规则份额、500 候选上限与「先授权后向量」安全顺序。
 */
class ContentAssetRecommendationServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-18T00:00:00Z");

    private ContentAssetRepository assets;
    private ContentAssetGrantRepository grants;
    private MarketplaceCreationContextClient marketplace;
    private ContentAssetEmbeddingRepository embeddings;
    private EmbeddingExecutionService execution;
    private ContentAssetRecommendationService service;
    private ServerWebExchange exchange;
    private final SandboxEmbeddingProvider sandbox = new SandboxEmbeddingProvider();

    @BeforeEach
    void setUp() {
        assets = org.mockito.Mockito.mock(ContentAssetRepository.class);
        grants = org.mockito.Mockito.mock(ContentAssetGrantRepository.class);
        marketplace = org.mockito.Mockito.mock(MarketplaceCreationContextClient.class);
        embeddings = org.mockito.Mockito.mock(ContentAssetEmbeddingRepository.class);
        execution = org.mockito.Mockito.mock(EmbeddingExecutionService.class);
        exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        service = new ContentAssetRecommendationService(
                assets, grants, marketplace, embeddings, execution, Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC));
        when(assets.listPublic(null)).thenReturn(Flux.empty());
        when(assets.listPersonal(anyString())).thenReturn(Flux.empty());
        when(embeddings.findReadyForAssets(anyCollection())).thenReturn(Flux.empty());
        when(execution.embedQuery(any(), anyString())).thenAnswer(
                invocation -> Mono.just(outcome(invocation.getArgument(1, String.class))));
    }

    private EmbeddingOutcome outcome(String normalizedText) {
        SandboxEmbeddingProvider.Result result = sandbox.embed(normalizedText).block(Duration.ofSeconds(5));
        return new EmbeddingOutcome(result.vector(),
                com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.platform(
                        UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 1, 4),
                sandbox.algorithmVersion(), UUID.randomUUID(), result.inputTokens(), true);
    }

    private static Caller user(String accountId) {
        return new Caller(accountId, null, null, null, null, null, null, null);
    }

    private static ContentAsset asset(UUID id, String title, List<String> tags, int version) {
        return new ContentAsset(id, UUID.randomUUID(), LibraryType.PERSONAL, AssetCategory.CAMPAIGN,
                "owner", null, title, tags, "image/png", 10L, null, AssetStatus.ACTIVE,
                version, null, null, null, null, null,
                FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(3600), null);
    }

    private void personalPool(ContentAsset... pool) {
        when(assets.listPersonal(anyString()))
                .thenReturn(Flux.fromArray(pool));
    }

    private ContentAssetEmbedding readyRowOf(ContentAsset asset, String normalizedText) {
        String hash = EmbeddingTextNormalizer.hash(normalizedText);
        return new ContentAssetEmbedding(
                UUID.randomUUID(), asset.id(), asset.version(), hash, "ready", "sandbox",
                "sandbox-embedding-v1", "platform:1", sandbox.algorithmVersion(), 256,
                sandbox.embed(normalizedText).block(Duration.ofSeconds(5)).vector(),
                UUID.randomUUID(), null, 0, null, null, null,
                FIXED_NOW, FIXED_NOW, FIXED_NOW);
    }

    private void readyRow(ContentAsset asset, String normalizedText) {
        when(embeddings.findReadyForAssets(anyCollection()))
                .thenReturn(Flux.just(readyRowOf(asset, normalizedText)));
    }    @Test
    void independentModeWithoutQueryPreservesCurrentSemantics() {
        ContentAsset a = asset(UUID.randomUUID(), "开业 海报", List.of(), 1);
        ContentAsset b = asset(UUID.randomUUID(), "宠物 体检", List.of(), 1);
        personalPool(a, b);

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, List.of(), null, null), exchange).block();

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(2);
        assertThat(result.semantic().status()).isEqualTo("not_requested");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.ruleScore()).isEqualTo(item.score());
            assertThat(item.semanticScore()).isNull();
        });
        verify(execution, never()).embedQuery(any(), anyString());
        verify(embeddings, never()).findReadyForAssets(anyCollection());
    }

    @Test
    void queryValidationRejectsBlankAndOversized() {
        ContentAssetRecommendationService.Request blank =
                new ContentAssetRecommendationService.Request(null, null, null, null, null, null, "   ", null);
        assertThatThrownBy(() -> service.recommend(user("acct"), blank, exchange).block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("query");
        String oversized = "字".repeat(501);
        ContentAssetRecommendationService.Request tooLong =
                new ContentAssetRecommendationService.Request(null, null, null, null, null, null, oversized, null);
        assertThatThrownBy(() -> service.recommend(user("acct"), tooLong, exchange).block())
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("query");
    }

    @Test
    void explicitQueryAppliesSemanticScoresAndFusionOrder() {
        ContentAsset match = asset(UUID.randomUUID(), "开业 海报", List.of(), 1);
        ContentAsset other = asset(UUID.randomUUID(), "宠物 体检", List.of(), 1);
        personalPool(match, other);
        when(embeddings.findReadyForAssets(anyCollection())).thenReturn(Flux.just(
                readyRowOf(match, "title: 开业 海报\ncategory: campaign"),
                readyRowOf(other, "title: 宠物 体检\ncategory: campaign")));

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, null, "开业 海报", null), exchange).block();

        assertThat(result.semantic().status()).isEqualTo("applied");
        assertThat(result.semantic().provider()).isEqualTo("sandbox");
        assertThat(result.semantic().model()).isEqualTo("sandbox-embedding-v1");
        assertThat(result.items().get(0).asset().id()).isEqualTo(match.id());
        assertThat(result.items().get(0).semanticScore()).isNotNull();
        assertThat(result.items().get(0).semanticScore())
                .isGreaterThan(result.items().get(1).semanticScore());
        assertThat(result.items().get(0).score()).isEqualTo(
                com.grassland.intelligence.embedding.SemanticRanker.combine(
                        result.items().get(0).semanticScore(), result.items().get(0).ruleScore()));
        assertThat(result.items().get(0).reasons()).anySatisfy(reason ->
                assertThat(reason).contains("语义匹配"));
    }

    @Test
    void missingVectorKeepsRuleShareOnlyDuringSemanticRun() {
        ContentAsset match = asset(UUID.randomUUID(), "开业 海报", List.of(), 1);
        ContentAsset unindexed = asset(UUID.randomUUID(), "宠物 体检", List.of(), 1);
        personalPool(match, unindexed);
        ContentAssetEmbedding matchRow = new ContentAssetEmbedding(
                UUID.randomUUID(), match.id(), 1,
                EmbeddingTextNormalizer.hash("title: 开业 海报\ncategory: campaign"),
                "ready", "sandbox", "sandbox-embedding-v1", "platform:1",
                sandbox.algorithmVersion(), 256,
                sandbox.embed("title: 开业 海报\ncategory: campaign").block(Duration.ofSeconds(5)).vector(),
                UUID.randomUUID(), null, 0, null, null, null,
                FIXED_NOW, FIXED_NOW, FIXED_NOW);
        when(embeddings.findReadyForAssets(anyCollection())).thenReturn(Flux.just(matchRow));

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, null, "开业 海报", null), exchange).block();

        assertThat(result.semantic().status()).isEqualTo("applied");
        var unindexedItem = result.items().stream()
                .filter(item -> item.asset().id().equals(unindexed.id())).findFirst().orElseThrow();
        assertThat(unindexedItem.semanticScore()).isNull();
        assertThat(unindexedItem.score()).isEqualTo(
                com.grassland.intelligence.embedding.SemanticRanker.rulesOnlyInSemanticRun(
                        unindexedItem.ruleScore()));
    }

    @Test
    void taskModeDerivesSemanticTextFromAuthoritativePayload() {
        ContentAsset match = asset(UUID.randomUUID(), "开业 大促", List.of(), 1);
        personalPool(match);
        readyRow(match, "title: 开业 大促\ncategory: campaign");
        UUID applicationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(marketplace.fetch(eq(applicationId.toString()), eq(taskId.toString()), anyString()))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(
                        java.util.Map.of(
                                "title", "开业 大促 宣传",
                                "description", "门店 开业",
                                "requirements", java.util.Map.of()),
                        null, null)));
        when(assets.listPersonal(anyString())).thenReturn(Flux.just(match));

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        applicationId.toString(), taskId.toString(), null, null, null, null, null, null),
                exchange).block();

        assertThat(result.semantic().status()).isEqualTo("applied");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).semanticScore()).isNotNull();
        verify(execution).embedQuery(any(), eq("开业 大促 宣传 门店 开业"));
    }

    @Test
    void explicitQueryOverridesDerivedTaskText() {
        ContentAsset match = asset(UUID.randomUUID(), "开业 大促", List.of(), 1);
        personalPool(match);
        readyRow(match, "title: 开业 大促\ncategory: campaign");
        UUID applicationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(marketplace.fetch(eq(applicationId.toString()), eq(taskId.toString()), anyString()))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(
                        java.util.Map.of("title", "完全 无关 任务", "requirements", java.util.Map.of()), null, null)));

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        applicationId.toString(), taskId.toString(), null, null, null, null, "开业 大促", null),
                exchange).block();

        assertThat(result.semantic().status()).isEqualTo("applied");
        verify(execution).embedQuery(any(), eq("开业 大促"));
    }

    @Test
    void providerFailureFallsBackToUntouchedRuleOrder() {
        ContentAsset a = asset(UUID.randomUUID(), "开业 海报", List.of(), 1);
        ContentAsset b = asset(UUID.randomUUID(), "宠物 体检", List.of(), 1);
        personalPool(a, b);
        org.mockito.Mockito.doReturn(Mono.error(
                        new IntelligenceException(503, "no_platform_model", "平台未配置Embedding模型")))
                .when(execution).embedQuery(any(), anyString());

        ContentAssetRecommendationService.Result result = service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, null, "开业 海报", null), exchange).block();

        assertThat(result.semantic().status()).isEqualTo("fallback");
        assertThat(result.semantic().message()).isNotBlank();
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.score()).isEqualTo(item.ruleScore());
            assertThat(item.semanticScore()).isNull();
        });
        List<Integer> ruleOnlyScores = result.items().stream()
                .map(ContentAssetRecommender.Scored::score).collect(Collectors.toList());
        assertThat(ruleOnlyScores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void semanticRunBoundsCandidatesAndAuthorizationPrecedesVectorLookup() {
        List<ContentAsset> pool = java.util.stream.IntStream.range(0, 600)
                .mapToObj(i -> asset(UUID.randomUUID(), "素材 " + i, List.of(), 1))
                .collect(Collectors.toList());
        personalPool(pool.toArray(ContentAsset[]::new));
        when(embeddings.findReadyForAssets(anyCollection())).thenReturn(Flux.empty()).thenReturn(Flux.empty());

        service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, null, "素材", null), exchange).block();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(embeddings).findReadyForAssets(captor.capture());
        assertThat(captor.getValue()).hasSize(500);
        assertThat(captor.getValue()).allSatisfy(id -> assertThat(pool).anyMatch(asset -> asset.id().equals(id)));
        // 无 query 时不受 500 截断影响（保持既有语义）。
        service.recommend(user("acct"),
                new ContentAssetRecommendationService.Request(
                        null, null, null, null, null, null, null, null), exchange).block();
        verify(assets, org.mockito.Mockito.times(2)).listPersonal(anyString());
    }
}
