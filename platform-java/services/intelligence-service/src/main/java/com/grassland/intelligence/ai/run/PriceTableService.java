package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 价目表服务（GL-P3-AI-001 Phase 4；V52 起改为 DB 版本化）。
 *
 * <p>单价来自 {@code price_table_version} + {@code price_table_model}，治理台可自助调价，
 * 不再需要改 Java + 重建镜像。缓存口径照 {@code ContentSafetyLexicon}：active 版本 60 秒 TTL，
 * 激活后由 admin 端点显式 {@link #invalidate()}。
 *
 * <p><b>版本语义（V52 修正的既有缺陷）</b>：{@link #priceFor(String, String)} 按 Run 冻结的
 * {@code priceTableVersion} 查表，而非一律用当前 active。此前 {@code calculateCost} 用 {@code getCurrent()}、
 * {@code getVersion} 零调用方——只有一张 v1 时无差别，但一旦能调价就会「按新价结算旧 Run」。
 * retired 版本不可变，故永久缓存。
 *
 * <p><b>兜底</b>：DB 不可达或表未播种时回落内置硬编码表（与 V52 之前逐值一致），
 * 避免地基故障让所有平台档调用 503。
 */
@Service
public class PriceTableService {

    private static final Logger log = LoggerFactory.getLogger(PriceTableService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration DB_TIMEOUT = Duration.ofSeconds(20);
    static final String FALLBACK_LABEL = "v1";

    private final PriceTableRepository repository;
    private final PriceTable fallback;
    /** label → 表。active 受 TTL 约束；retired/draft 不可变，读进来就一直留着。 */
    private final Map<String, PriceTable> byLabel = new ConcurrentHashMap<>();
    private volatile PriceTable cachedActive;
    private volatile Instant cacheExpiresAt = Instant.EPOCH;

    /** 无参构造供单测直接用内置表（不碰 DB）。 */
    public PriceTableService() {
        this(null);
    }

    @Autowired
    public PriceTableService(PriceTableRepository repository) {
        this.repository = repository;
        this.fallback = new PriceTable(FALLBACK_LABEL, buildDefaultPrices());
        this.cachedActive = fallback;
        this.byLabel.put(FALLBACK_LABEL, fallback);
    }

    /**
     * 表空时把内置价目播成 v1/active——否则升级后所有平台档调用立刻 503（估价拿不到单价）。
     * 播种失败只记日志：内置兜底表仍在内存里，服务照常可用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (repository == null) {
            return;
        }
        try {
            repository.countVersions()
                    .flatMap(count -> count == 0 ? seedFallbackAsActive() : Mono.just(true))
                    .then(reloadActive())
                    .block(DB_TIMEOUT);
        } catch (RuntimeException error) {
            log.warn("price table seed/load unavailable; using bundled fallback prices", error);
            cachedActive = fallback;
            cacheExpiresAt = Instant.EPOCH;
        }
    }

    private Mono<Boolean> seedFallbackAsActive() {
        List<PriceTableRepository.ModelPriceRow> rows = new ArrayList<>();
        fallback.models().forEach((modelId, price) -> rows.add(new PriceTableRepository.ModelPriceRow(
                modelId, price.capability(), price.provider(), price.centsPer1kInputTokens(),
                price.centsPer1kOutputTokens(), price.centsPerImage(), price.centsPerSecond())));
        return repository.createVersion(FALLBACK_LABEL, "active", "V52 从硬编码价目迁移", "system")
                .flatMap(versionId -> repository.replaceModels(versionId, rows).thenReturn(true));
    }

    /**
     * 回源**全部**版本（不只 active）：结算要按 Run 冻结的 label 查历史版本，而查表不能阻塞，
     * 所以历史版本必须提前全部驻留内存。价目表数据量很小（版本数 × 模型数），全量缓存代价可忽略。
     */
    private Mono<PriceTable> reloadActive() {
        return repository.findAllVersions()
                .concatMap(version -> repository.findModelsByVersion(version.id())
                        .collectList()
                        .map(models -> Map.entry(version, toPriceTable(version.label(), models))))
                .collectList()
                .map(entries -> {
                    PriceTable active = null;
                    for (Map.Entry<PriceTableRepository.VersionRow, PriceTable> entry : entries) {
                        byLabel.put(entry.getValue().version(), entry.getValue());
                        if ("active".equals(entry.getKey().status())) {
                            active = entry.getValue();
                        }
                    }
                    if (active != null) {
                        cachedActive = active;
                    }
                    cacheExpiresAt = Instant.now().plus(CACHE_TTL);
                    return cachedActive;
                })
                .defaultIfEmpty(fallback);
    }

    private static PriceTable toPriceTable(String label, List<PriceTableRepository.ModelPriceRow> rows) {
        Map<String, PriceTable.ModelPrice> prices = new HashMap<>();
        for (PriceTableRepository.ModelPriceRow row : rows) {
            prices.put(row.modelId(), new PriceTable.ModelPrice(
                    row.capability(), row.provider(), row.centsPer1kInputTokens(),
                    row.centsPer1kOutputTokens(), row.centsPerImage(), row.centsPerSecond()));
        }
        return new PriceTable(label, prices);
    }

    /**
     * 激活/改价后由 admin 端点调用。
     *
     * <p>置过期 + **立即在 boundedElastic 上后台回源**：admin 端点本身也在事件循环上，
     * 不能在此 block；只置过期则要等下一次 {@code @Scheduled} 才生效，激活后短时间内仍按旧价估。
     * 订阅是 fire-and-forget，失败由定时刷新兜住。
     */
    public void invalidate() {
        cacheExpiresAt = Instant.EPOCH;
        if (repository == null) {
            return;
        }
        reloadActive()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(table -> log.info("price table cache refreshed after admin change; active={}",
                                table.version()),
                        error -> log.warn("post-activation price table refresh failed", error));
    }

    /**
     * 当前生效价目表。<b>纯内存读，绝不阻塞</b>。
     *
     * <p>本方法在 WebFlux 请求链上被调用（{@code AiExecutionService.prepareExecution}），
     * 而那是 Netty 事件循环线程——在其上 {@code block()} 会直接抛
     * {@code IllegalStateException: block() are blocking, which is not supported in thread reactor-tcp-nio-*}。
     * 回源一律交给 {@link #refreshActive()}（{@code @Scheduled}，跑在独立线程）与
     * {@link #invalidate()} 触发的后台刷新。
     */
    public PriceTable getCurrent() {
        return cachedActive;
    }

    /**
     * 定时回源当前 active。TTL 到点才真查库，避免每秒打库。
     *
     * <p>跑在 Spring 调度线程而非事件循环，故这里 {@code block} 是安全的。
     */
    @Scheduled(fixedDelayString = "${ai.price-table.refresh-interval-ms:15000}")
    public void refreshActive() {
        if (repository == null || Instant.now().isBefore(cacheExpiresAt)) {
            return;
        }
        try {
            reloadActive().block(DB_TIMEOUT);
        } catch (RuntimeException error) {
            // 不推进 TTL：下一轮继续尝试，避免故障期一直用陈旧表却不再重试
            log.warn("price table refresh failed; serving last known table {}", cachedActive.version(), error);
        }
    }

    /**
     * 按 label 取价目表；未知 label 返回 {@code null}（调用方决定如何处置）。
     *
     * <p>历史版本不可变，故读一次就永久缓存。刻意**不**在未知 label 时回落当前 active——
     * 那正是「按新价结算旧 Run」这个 bug 的成因。
     */
    public PriceTable getVersion(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        // 纯内存读：本方法也在事件循环上被调用（结算路径），不能 block。
        // 全部版本在启动与每次刷新时载入 byLabel；历史版本不可变，故一直有效。
        return byLabel.get(label);
    }

    /**
     * 取某 Run 该用的单价：{@code priceTableVersion} 非空按它查，否则用当前 active。
     *
     * @throws IllegalArgumentException 版本或模型未定价（调用方据此 503 或记 0）
     */
    public PriceTable.ModelPrice priceFor(String priceTableVersion, String modelId) {
        PriceTable table = priceTableVersion == null || priceTableVersion.isBlank()
                ? getCurrent()
                : getVersion(priceTableVersion);
        if (table == null) {
            throw new IllegalArgumentException("Unknown price table version: " + priceTableVersion);
        }
        PriceTable.ModelPrice price = table.getPrice(modelId);
        if (price == null) {
            throw new IllegalArgumentException("Unknown model: " + modelId);
        }
        return price;
    }

    /** True only when every metered dimension for the model is explicitly free. */
    public boolean isZeroPricedModel(String modelId) {
        PriceTable.ModelPrice price = priceFor(null, modelId);
        return price.centsPer1kInputTokens() == 0
                && price.centsPer1kOutputTokens() == 0
                && price.centsPerImage() == 0
                && price.centsPerSecond() == 0;
    }

    /** 计算成本（按实际用量）。{@code priceTableVersion} 为 Run 冻结的版本。 */
    public int calculateCost(
            String priceTableVersion,
            String modelId,
            int inputTokens,
            int outputTokens,
            int imagesGenerated,
            int videoSeconds) {

        PriceTable.ModelPrice price = priceFor(priceTableVersion, modelId);
        int total = 0;
        total += price.calculateTextCost(inputTokens, outputTokens);
        total += price.calculateImageCost(imagesGenerated);
        total += price.calculateVideoCost(videoSeconds);
        return total;
    }

    /** 估算成本（用于预算预留）——估价发生在 Run 起始，一律按当前 active。 */
    public int estimateCost(
            String modelId,
            int estimatedTokens,
            int estimatedImages,
            int estimatedSeconds) {

        return priceFor(null, modelId).estimateCost(estimatedTokens, estimatedImages, estimatedSeconds);
    }

    /** 当前生效版本的 label，供 Run 起始冻结。 */
    public String currentVersionLabel() {
        return getCurrent().version();
    }

    /**
     * 兜底价目（无 DB 版本时）。任务书 #58 起 speech/embedding 真实模型的价目不再从 env 派生——
     * 生产真实模型须经价目表 admin CRUD 配置，未配价即 unpriced_model 拒绝（fail-closed）。
     */
    private Map<String, PriceTable.ModelPrice> buildDefaultPrices() {
        Map<String, PriceTable.ModelPrice> prices = new HashMap<>();

        // Qwen 通义千问系列
        prices.put("qwen-turbo", new PriceTable.ModelPrice(
            "text", "qwen",
            1,   // 0.01 元/1k tokens
            2,   // 0.02 元/1k tokens
            80,  // 0.8 元/张（图片）
            10   // 0.1 元/秒（视频）
        ));
        prices.put("qwen-plus", new PriceTable.ModelPrice(
            "text", "qwen",
            3,   // 0.03 元/1k tokens
            6,   // 0.06 元/1k tokens
            200, // 2 元/张
            30   // 0.3 元/秒
        ));
        prices.put("qwen-max", new PriceTable.ModelPrice(
            "text", "qwen",
            10,  // 0.1 元/1k tokens
            20,  // 0.2 元/1k tokens
            500, // 5 元/张
            100  // 1 元/秒
        ));

        // 图片生成专用模型
        prices.put("wanx-v1", new PriceTable.ModelPrice(
            "image_generation", "qwen",
            1, 2, 80, 0   // 图片生成模型，视频单价为 0
        ));

        // OpenAI 兼容系列（参考价格，实际由用户 BYOK）
        prices.put("gpt-3.5-turbo", new PriceTable.ModelPrice(
            "text", "openai-compatible",
            2,   // 0.02 元/1k tokens
            4,   // 0.04 元/1k tokens
            0,   // 不支持图片
            0    // 不支持视频
        ));
        prices.put("gpt-4", new PriceTable.ModelPrice(
            "text", "openai-compatible",
            30,  // 0.3 元/1k tokens
            60,  // 0.6 元/1k tokens
            0,
            0
        ));

        prices.put("sandbox-speech-v1", new PriceTable.ModelPrice(
            "voice", "sandbox",
            0, 0, 0, 0
        ));
        prices.put("sandbox-embedding-v1", new PriceTable.ModelPrice(
            "retrieval", "sandbox",
            0, 0, 0, 0
        ));
        prices.put("sandbox-matting-v1", new PriceTable.ModelPrice(
            "image_edit", "sandbox",
            0, 0, 0, 0
        ));
        // 视频管线（任务书 #64）：sandbox 视频按秒计价 1 分（与旧 ai.video-generation.unit-price-cents
        // 默认值一致）；sandbox 配音走 feature=null 免费分支，全 0。
        prices.put("sandbox-video-v1", new PriceTable.ModelPrice(
            "video_generation", "sandbox",
            0, 0, 0, 1
        ));
        prices.put("sandbox-tts-v1", new PriceTable.ModelPrice(
            "video_tts", "sandbox",
            0, 0, 0, 0
        ));

        return prices;
    }
}
