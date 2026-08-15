package com.grassland.intelligence.contentlibrary;

import com.grassland.intelligence.creationcontext.MarketplaceCreationContextClient;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Instant;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能素材推荐编排（PRD §4.8「按任务和平台智能推荐素材」）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>任务模式</b>（applicationId + taskId）：经 {@link MarketplaceCreationContextClient} 拉取权威
 *       已接受任务上下文（marketplace 校验参与方匹配），从标题/描述/要求文本提词，平台与内容形式以权威值为准
 *       ——不信任前端传来的任务 JSON。</li>
 *   <li><b>独立模式</b>（platform/contentForm/category/keywords 显式传入）：创作中心自由创作场景。</li>
 * </ul>
 *
 * <p>候选池只含调用者本就可访问的素材——个人库（owner）、被授权商家素材（recommender 显式 grant）、
 * 本组织组织级素材（merchant 身份）、公共库（active 未过期）。推荐只重排不越权：他人个人素材与未授权
 * 商家素材永远不会出现在结果里。门店范围素材对商家侧不入池（无逐店授权检查），推荐官侧经显式 grant 入池。
 */
@Component
public class ContentAssetRecommendationService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final ContentAssetRepository assets;
    private final ContentAssetGrantRepository grants;
    private final MarketplaceCreationContextClient marketplace;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ContentAssetRecommendationService(
            ContentAssetRepository assets,
            ContentAssetGrantRepository grants,
            MarketplaceCreationContextClient marketplace) {
        this(assets, grants, marketplace, Clock.systemUTC());
    }

    ContentAssetRecommendationService(
            ContentAssetRepository assets,
            ContentAssetGrantRepository grants,
            MarketplaceCreationContextClient marketplace,
            Clock clock) {
        this.assets = assets;
        this.grants = grants;
        this.marketplace = marketplace;
        this.clock = clock;
    }

    /** 推荐请求。任务模式要求 applicationId 与 taskId 同时给出；keywords 与任务文本词合并。 */
    public record Request(
            String applicationId, String taskId, String platform, String contentForm,
            AssetCategory category, List<String> keywords, Integer limit) {}

    /** 推荐结果：排序后的打分素材 + 回显实际采用的检索上下文（透明可解释）。 */
    public record Result(List<ContentAssetRecommender.Scored> items, String platform,
                         String contentForm, AssetCategory category, List<String> terms, String sourceTitle) {}

    public Mono<Result> recommend(Caller caller, Request request) {
        if (request == null) {
            return Mono.error(new IntelligenceException(400, "请求无效"));
        }
        boolean taskMode = request.applicationId() != null || request.taskId() != null;
        if (taskMode && (request.applicationId() == null || request.taskId() == null)) {
            return Mono.error(new IntelligenceException(400, "任务模式推荐必须同时提供 applicationId 与 taskId"));
        }
        int limit = request.limit() == null ? DEFAULT_LIMIT
                : Math.max(1, Math.min(request.limit(), MAX_LIMIT));
        Mono<Context> context = taskMode
                ? taskContext(caller, request)
                : Mono.just(standaloneContext(request));
        return context.flatMap(ctx -> candidates(caller)
                .flatMapMany(Flux::fromIterable)
                .map(entry -> ContentAssetRecommender.score(
                        entry.asset(), entry.bucket(), ctx.query()))
                .collectList()
                .map(list -> list.stream().sorted(scoreOrder()).limit(limit).toList())
                .map(items -> new Result(items,
                        ctx.query().platform(), ctx.query().contentForm(),
                        ctx.query().category(), ctx.query().terms(), ctx.title())));
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
                            platform, contentForm, request.category(), List.copyOf(terms), Instant.now(clock)), title);
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
                List.copyOf(terms), Instant.now(clock)), null);
    }

    /** 候选池：个人 +（merchant）本组织组织级 +（recommender）被授权商家 + 公共。桶标识用于库权重。 */
    private Mono<List<Entry>> candidates(Caller caller) {
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
        return pool.collect(LinkedHashMap<UUID, Entry>::new,
                        (byId, entry) -> byId.putIfAbsent(entry.asset().id(), entry))
                .map(byId -> List.copyOf(byId.values()));
    }

    /** 排序：分数降序 → 创建时间降序 → id 降序（确定性，杜绝同分抖动）。 */
    private static Comparator<ContentAssetRecommender.Scored> scoreOrder() {
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

    private record Context(ContentAssetRecommender.Query query, String title) {}
}
