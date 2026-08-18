package com.grassland.intelligence.contentlibrary;

import com.grassland.intelligence.creationcontext.MarketplaceCreationContextClient;
import com.grassland.intelligence.embedding.CosineSimilarity;
import com.grassland.intelligence.embedding.ContentAssetEmbedding;
import com.grassland.intelligence.embedding.ContentAssetEmbeddingRepository;
import com.grassland.intelligence.embedding.EmbeddingExecutionService;
import com.grassland.intelligence.embedding.EmbeddingTextNormalizer;
import com.grassland.intelligence.embedding.SemanticRanker;
import com.grassland.intelligence.embedding.SemanticRanker.Ranked;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Instant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能素材推荐编排（PRD §4.8「按任务和平台智能推荐素材」+ 任务书 #33 语义检索）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>任务模式</b>（applicationId + taskId）：经 {@link MarketplaceCreationContextClient} 拉取权威
 *       已接受任务上下文（marketplace 校验参与方匹配），从标题/描述/要求文本提词，平台与内容形式以权威值为准
 *       ——不信任前端传来的任务 JSON。语义文本缺省也从同一权威语料派生；显式 query 优先。</li>
 *   <li><b>独立模式</b>（platform/contentForm/category/keywords/query 显式传入）：创作中心自由创作场景。
 *       语义检索要求显式 query（trim 后 1-500 字符）。</li>
 * </ul>
 *
 * <p>候选池只含调用者本就可访问的素材——个人库（owner）、被授权商家素材（recommender 显式 grant）、
 * 本组织组织级素材（merchant 身份）、公共库（active 未过期）。推荐只重排不越权：他人个人素材与未授权
 * 商家素材永远不会出现在结果里。<b>授权永远先于向量查询</b>：语义运行只为已授权候选 ID 读取 ready 向量，
 * 语义运行候选上限 500（确定性截断），最终 limit 仍 1-50。
 *
 * <p>语义运行失败（Embedding/模型/预算/Provider 任何错误）在编排边界捕获：整次请求回退为当前纯规则
 * 结果（分数与排序口径与无 query 完全一致），仅附加 {@code semantic.status=fallback} 元数据。
 */
@Component
public class ContentAssetRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ContentAssetRecommendationService.class);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    /** 语义运行候选上限（任务书 #33 §6.4）：只对该数量的已授权 ID 做向量读取与相似度计算。 */
    static final int MAX_SEMANTIC_CANDIDATES = 500;
    static final int MAX_QUERY_LENGTH = 500;

    private final ContentAssetRepository assets;
    private final ContentAssetGrantRepository grants;
    private final MarketplaceCreationContextClient marketplace;
    private final ContentAssetEmbeddingRepository embeddings;
    private final EmbeddingExecutionService embeddingExecution;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ContentAssetRecommendationService(
            ContentAssetRepository assets,
            ContentAssetGrantRepository grants,
            MarketplaceCreationContextClient marketplace,
            ContentAssetEmbeddingRepository embeddings,
            EmbeddingExecutionService embeddingExecution) {
        this(assets, grants, marketplace, embeddings, embeddingExecution, Clock.systemUTC());
    }

    ContentAssetRecommendationService(
            ContentAssetRepository assets,
            ContentAssetGrantRepository grants,
            MarketplaceCreationContextClient marketplace,
            ContentAssetEmbeddingRepository embeddings,
            EmbeddingExecutionService embeddingExecution,
            Clock clock) {
        this.assets = assets;
        this.grants = grants;
        this.marketplace = marketplace;
        this.embeddings = embeddings;
        this.embeddingExecution = embeddingExecution;
        this.clock = clock;
    }

    /** 推荐请求。任务模式要求 applicationId 与 taskId 同时给出；keywords 与任务文本词合并。 */
    public record Request(
            String applicationId, String taskId, String platform, String contentForm,
            AssetCategory category, List<String> keywords, String query, Integer limit) {}

    /** 语义运行元数据：not_requested / applied / fallback；provider 信息仅在 applied 时有意义。 */
    public record SemanticMetadata(
            String status, String provider, String model, boolean sandbox, String message) {
        static SemanticMetadata notRequested() {
            return new SemanticMetadata("not_requested", null, null, false, null);
        }

        static SemanticMetadata applied(String provider, String model, boolean sandbox) {
            return new SemanticMetadata("applied", provider, model, sandbox, null);
        }

        static SemanticMetadata fallback() {
            return new SemanticMetadata("fallback", null, null, false, "语义检索暂不可用，已按规则排序");
        }
    }

    /** 推荐结果：排序后的打分素材 + 回显实际采用的检索上下文（透明可解释）。 */
    public record Result(List<ContentAssetRecommender.Scored> items, String platform,
                         String contentForm, AssetCategory category, List<String> terms, String sourceTitle,
                         SemanticMetadata semantic) {}

    public Mono<Result> recommend(Caller caller, Request request, ServerWebExchange exchange) {
        if (request == null) {
            return Mono.error(new IntelligenceException(400, "请求无效"));
        }
        boolean taskMode = request.applicationId() != null || request.taskId() != null;
        if (taskMode && (request.applicationId() == null || request.taskId() == null)) {
            return Mono.error(new IntelligenceException(400, "任务模式推荐必须同时提供 applicationId 与 taskId"));
        }
        String query = validatedQuery(request.query());
        int limit = request.limit() == null ? DEFAULT_LIMIT
                : Math.max(1, Math.min(request.limit(), MAX_LIMIT));
        Mono<Context> context = taskMode
                ? taskContext(caller, request)
                : Mono.just(standaloneContext(request));
        return context.flatMap(ctx -> {
            String semanticText = semanticText(ctx, query, taskMode);
            return candidates(caller, semanticText != null)
                    .flatMap(entries -> recommendWith(caller, entries, ctx, semanticText, query != null, limit, exchange));
        });
    }

    /** query 校验：提供时 trim 后 1-500 字符；缺省 null。 */
    private static String validatedQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH) {
            throw new IntelligenceException(400, "query 必须是 1-" + MAX_QUERY_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String semanticText(Context ctx, String query, boolean taskMode) {
        if (query != null) {
            return EmbeddingTextNormalizer.normalize(query);
        }
        if (taskMode && ctx.corpus() != null && !ctx.corpus().isBlank()) {
            return EmbeddingTextNormalizer.normalize(ctx.corpus());
        }
        return null;
    }

    private Mono<Result> recommendWith(
            Caller caller, List<Entry> entries, Context ctx, String semanticText,
            boolean explicitQuery, int limit, ServerWebExchange exchange) {
        List<ContentAssetRecommender.Scored> ruleScored = entries.stream()
                .map(entry -> ContentAssetRecommender.score(
                        entry.asset(), entry.bucket(), ctx.query()))
                .toList();
        List<ContentAssetRecommender.Scored> ruleRanked = ruleScored.stream()
                .sorted(ruleOrder()).limit(limit).toList();
        if (semanticText == null) {
            return Mono.just(result(ruleRanked, ctx, SemanticMetadata.notRequested()));
        }
        List<ContentAssetRecommender.Scored> bounded =
                ruleScored.stream().limit(MAX_SEMANTIC_CANDIDATES).toList();
        List<UUID> candidateIds = bounded.stream().map(item -> item.asset().id()).toList();
        return embeddingExecution.embedQuery(exchange, semanticText)
                .flatMap(outcome -> embeddings.findReadyForAssets(candidateIds)
                        .collectList()
                        .map(rows -> semanticResult(bounded, rows, outcome, ctx, limit)))
                .onErrorResume(error -> {
                    // 不记录 query 原文；只记录稳定错误类别。
                    log.warn("semantic recommendation fell back: accountId={}, error={}",
                            caller.accountId(), error.getClass().getSimpleName());
                    return Mono.just(result(ruleRanked, ctx, SemanticMetadata.fallback()));
                });
    }

    /** 语义重排：当前向量 → 60/40 融合；缺/陈旧向量 → 仅规则份额；稳定排序键见 {@link SemanticRanker}。 */
    private static Result semanticResult(
            List<ContentAssetRecommender.Scored> candidates,
            List<ContentAssetEmbedding> rows,
            EmbeddingExecutionService.EmbeddingOutcome outcome,
            Context ctx, int limit) {
        Map<UUID, ContentAssetEmbedding> byAsset = new HashMap<>();
        for (ContentAssetEmbedding row : rows) {
            byAsset.putIfAbsent(row.assetId(), row);
        }
        String modelVersionKey = outcome.provider().modelVersionKey();
        List<ContentAssetRecommender.Scored> rescored = new ArrayList<>(candidates.size());
        for (ContentAssetRecommender.Scored item : candidates) {
            ContentAssetEmbedding row = byAsset.get(item.asset().id());
            ContentAssetEmbedding current = currentRow(row, item, modelVersionKey);
            if (current != null && current.embedding() != null) {
                int semanticScore = SemanticRanker.semanticScore(
                        CosineSimilarity.cosine(outcome.vector(), current.embedding()));
                List<String> reasons = new ArrayList<>(item.reasons());
                reasons.add("语义匹配 " + semanticScore);
                rescored.add(new ContentAssetRecommender.Scored(
                        item.asset(), item.bucket(),
                        SemanticRanker.combine(semanticScore, item.ruleScore()),
                        item.ruleScore(), semanticScore, List.copyOf(reasons)));
            } else {
                rescored.add(new ContentAssetRecommender.Scored(
                        item.asset(), item.bucket(),
                        SemanticRanker.rulesOnlyInSemanticRun(item.ruleScore()),
                        item.ruleScore(), null, item.reasons()));
            }
        }
        List<ContentAssetRecommender.Scored> ranked = rescored.stream()
                .map(item -> new Ranked(item.asset().id(), item.score(), item.ruleScore(),
                        item.asset().updatedAt() == null ? Instant.EPOCH : item.asset().updatedAt()))
                .toList()
                .stream().sorted(SemanticRanker.order())
                .map(ranked0 -> findById(rescored, ranked0.id()))
                .toList();
        return result(ranked.stream().limit(limit).toList(), ctx,
                SemanticMetadata.applied(outcome.provider().provider(), outcome.provider().model(),
                        outcome.sandbox()));
    }

    /** 向量行必须匹配素材当前版本、当前内容哈希与本次查询的模型版本键。 */
    private static ContentAssetEmbedding currentRow(
            ContentAssetEmbedding row, ContentAssetRecommender.Scored item, String modelVersionKey) {
        if (row == null || !"ready".equals(row.status())) {
            return null;
        }
        ContentAsset asset = item.asset();
        if (row.assetVersion() != asset.version()) {
            return null;
        }
        if (!row.contentHash().equals(EmbeddingTextNormalizer.forAsset(asset).contentHash())) {
            return null;
        }
        return modelVersionKey.equals(row.modelVersionKey()) ? row : null;
    }

    private static ContentAssetRecommender.Scored findById(
            List<ContentAssetRecommender.Scored> items, UUID id) {
        return items.stream().filter(item -> item.asset().id().equals(id)).findFirst().orElseThrow();
    }

    private static Result result(
            List<ContentAssetRecommender.Scored> items, Context ctx, SemanticMetadata semantic) {
        return new Result(items, ctx.query().platform(), ctx.query().contentForm(),
                ctx.query().category(), ctx.query().terms(), ctx.title(), semantic);
    }

    private Mono<Context> taskContext(Caller caller, Request request) {
        return marketplace.fetch(request.applicationId(), request.taskId(), caller.accountId())
                .map(authoritative -> {
                    Map<String, Object> task = authoritative.taskContext();
                    String title = text(task.get("title"));
                    String description = text(task.get("description"));
                    StringBuilder corpus = new StringBuilder();
                    if (title != null) {
                        corpus.append(title).append('\n');
                    }
                    if (description != null) {
                        corpus.append(description).append('\n');
                    }
                    appendRequirementValues(task.get("requirements"), corpus);
                    Set<String> terms = new LinkedHashSet<>(ContentAssetRecommender.tokenize(corpus.toString()));
                    for (String keyword : request.keywords() == null ? List.<String>of() : request.keywords()) {
                        if (keyword != null && !keyword.isBlank()) {
                            terms.addAll(ContentAssetRecommender.tokenize(keyword.trim()));
                        }
                    }
                    // 权威任务的平台/内容形式优先，显式参数只在其缺失时兜底。
                    String platform = text(task.get("platform"));
                    if (platform == null || platform.isBlank()) {
                        platform = request.platform();
                    }
                    String contentForm = text(task.get("contentForm"));
                    if (contentForm == null || contentForm.isBlank()) {
                        contentForm = request.contentForm();
                    }
                    return new Context(new ContentAssetRecommender.Query(
                            platform, contentForm, request.category(), List.copyOf(terms), Instant.now(clock)),
                            title, corpus.toString().trim());
                });
    }

    private Context standaloneContext(Request request) {
        Set<String> terms = new LinkedHashSet<>();
        for (String keyword : request.keywords() == null ? List.<String>of() : request.keywords()) {
            if (keyword != null && !keyword.isBlank()) {
                terms.addAll(ContentAssetRecommender.tokenize(keyword.trim()));
            }
        }
        return new Context(new ContentAssetRecommender.Query(
                request.platform(), request.contentForm(), request.category(),
                List.copyOf(terms), Instant.now(clock)), null, null);
    }

    /**
     * 候选池：个人 +（merchant）本组织组织级 +（recommender）被授权商家 + 公共。桶标识用于库权重。
     * 语义运行时确定性截断到 {@value MAX_SEMANTIC_CANDIDATES}（任务书 #33 §6.4）；纯规则模式保持全量。
     */
    private Mono<List<Entry>> candidates(Caller caller, boolean bounded) {
        Flux<Entry> pool = assets.listPersonal(caller.accountId())
                .map(asset -> new Entry(asset, ContentAssetRecommender.Bucket.PERSONAL));
        if (caller.isMerchant() && caller.organizationId() != null) {
            pool = pool.concatWith(assets.listMerchantByOrg(caller.organizationId(), null)
                    .map(asset -> new Entry(asset, ContentAssetRecommender.Bucket.OWN_ORG)));
        }
        if (caller.isRecommender()) {
            pool = pool.concatWith(grants.listGrantedAssets(caller.accountId())
                    .map(asset -> new Entry(asset, ContentAssetRecommender.Bucket.GRANTED_MERCHANT)));
        }
        pool = pool.concatWith(assets.listPublic(null)
                .map(asset -> new Entry(asset, ContentAssetRecommender.Bucket.PUBLIC)));
        Mono<List<Entry>> collected = pool.collect(LinkedHashMap<UUID, Entry>::new,
                        (byId, entry) -> byId.putIfAbsent(entry.asset().id(), entry))
                .map(byId -> List.copyOf(byId.values()));
        return bounded
                ? collected.map(list -> list.stream().limit(MAX_SEMANTIC_CANDIDATES).toList())
                : collected;
    }

    /** 纯规则排序：分数降序 → 创建时间降序 → id 降序（确定性，杜绝同分抖动）。 */
    private static Comparator<ContentAssetRecommender.Scored> ruleOrder() {
        return Comparator.<ContentAssetRecommender.Scored>comparingInt(s -> -s.score())
                .thenComparing(s -> s.asset().createdAt() == null
                        ? Instant.EPOCH : s.asset().createdAt(), Comparator.reverseOrder())
                .thenComparing(s -> s.asset().id(), Comparator.reverseOrder());
    }

    /** requirements 为 jsonb 对象：递归收集字符串叶子值（键是 schema，值才是要求文本）。 */
    private static void appendRequirementValues(Object node, StringBuilder into) {
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                appendRequirementValues(value, into);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                appendRequirementValues(value, into);
            }
        } else if (node instanceof String value && !value.isBlank()) {
            into.append(value).append('\n');
        }
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private record Entry(ContentAsset asset, ContentAssetRecommender.Bucket bucket) {}

    private record Context(ContentAssetRecommender.Query query, String title, String corpus) {}
}
