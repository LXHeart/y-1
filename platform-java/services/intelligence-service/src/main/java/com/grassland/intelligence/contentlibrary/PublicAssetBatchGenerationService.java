package com.grassland.intelligence.contentlibrary;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import com.grassland.intelligence.articleimage.GeneratedImage;
import com.grassland.intelligence.articleimage.ImageGenerationClient;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Generates permanent public image assets, one isolated budgeted run per requested item. */
@Service
public class PublicAssetBatchGenerationService {

    private final ImageGenerationClient images;
    private final FrozenImageGenerationConfigResolver frozenConfig;
    private final ImageGenerationConfig runtimeConfig;
    private final AiExecutionService executions;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final MediaReferenceRepository media;
    private final ContentAssetRepository assets;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public PublicAssetBatchGenerationService(
            ImageGenerationClient images,
            FrozenImageGenerationConfigResolver frozenConfig,
            ImageGenerationConfig runtimeConfig,
            AiExecutionService executions,
            ObjectProvider<ObjectStorageAdapter> storageProvider,
            MediaReferenceRepository media,
            ContentAssetRepository assets,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.images = images;
        this.frozenConfig = frozenConfig;
        this.runtimeConfig = runtimeConfig;
        this.executions = executions;
        this.storageProvider = storageProvider;
        this.media = media;
        this.assets = assets;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public Mono<BatchResult> generate(String accountId, Command command) {
        return Flux.range(1, command.count())
                .concatMap(index -> generateOne(accountId, command, index)
                        .map(asset -> Item.success(index, asset.id()))
                        .onErrorResume(error -> Mono.just(Item.failure(index, publicReason(error)))))
                .collectList()
                .map(items -> new BatchResult(items,
                        (int) items.stream().filter(Item::ok).count()));
    }

    private Mono<ContentAsset> generateOne(String accountId, Command command, int index) {
        FrozenImageGenerationConfigResolver.Config config = frozenConfig.current();
        ProviderResolution provider = ProviderResolution.platform(
                null, config.provider(), runtimeConfig.baseUrl(), config.model(),
                config.platformModelVersion(), null);
        UUID operationId = UUID.randomUUID();
        return executions.preparePlatformAsyncExecution(
                        accountId, null, "image_generation", null, provider, operationId,
                        config.unitPriceCents(), config.pricingVersion())
                .flatMap(result -> result.allowed()
                        ? executePrepared(accountId, command, index, config, result.context())
                        : Mono.error(denied(result.denialReason())));
    }

    private Mono<ContentAsset> executePrepared(
            String accountId, Command command, int index,
            FrozenImageGenerationConfigResolver.Config config,
            AiExecutionService.ExecutionContext context) {
        return Mono.usingWhen(
                Mono.just(context),
                ignored -> images.generate(prompt(command), "1024x1024")
                        .flatMap(generated -> persist(accountId, command, index, generated))
                        .flatMap(asset -> executions.settleSuccessWithCost(
                                        context, config.unitPriceCents(), 0, 0, 1, 0)
                                .thenReturn(asset)),
                ignored -> Mono.empty(),
                (ignored, error) -> executions.handleFailure(context, publicReason(error)).then(),
                ignored -> executions.handleCancellation(context).then());
    }

    private Mono<ContentAsset> persist(
            String accountId, Command command, int index, GeneratedImage generated) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) return Mono.error(new IntelligenceException(503, "公共素材生成需要启用对象存储"));
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(generated.base64());
        } catch (Exception error) {
            return Mono.error(new IntelligenceException(502, "图片生成服务返回了无效图片数据"));
        }
        UUID mediaId = UUID.randomUUID();
        String objectKey = "media/content_asset/" + mediaId;
        MediaReference reference = new MediaReference(
                mediaId, accountId, null, MediaPurpose.CONTENT_ASSET.db(),
                "public_asset_generation", null, objectKey, "image/png", bytes.length,
                MediaChecksums.sha256(bytes), "generated", MediaStatus.ACTIVE,
                null, null, null);
        ContentAsset asset = new ContentAsset(
                UUID.randomUUID(), mediaId, LibraryType.PUBLIC, category(command.kind()),
                accountId, null, title(command, index), tags(command), "image/png", (long) bytes.length,
                command.validUntil(), AssetStatus.PENDING_REVIEW, 1,
                "platform_ai", "platform_authorized", null, null, null,
                null, null, null);
        return Mono.fromRunnable(() -> storage.putObject(objectKey, bytes, "image/png"))
                .subscribeOn(Schedulers.boundedElastic())
                .then(transactions.transactional(media.insert(reference)
                        .then(assets.create(asset))
                        .flatMap(created -> outbox.append(ContentAssetController.assetEvent(
                                        "ContentAssetSubmittedForReview", created, accountId, null, null))
                                .thenReturn(created))));
    }

    private static String prompt(Command command) {
        String subject = switch (command.kind()) {
            case ICON -> "单个清晰图标，居中构图，边缘干净，适合界面与海报叠加";
            case DECORATION -> "单个装饰元素，主体完整，留出安全边距，适合内容排版叠加";
            case BACKGROUND -> "无文字背景图，层次清楚，主体区域留出文案空间";
            case MOOD -> "氛围场景图，光线与色彩具有明确情绪，画面不含文字和水印";
        };
        String style = command.style() == null ? "简洁、现代、可商用" : command.style();
        return "生成公共内容素材。类型要求：" + subject + "。主题：" + command.theme()
                + "。视觉风格：" + style + "。不得出现品牌标识、文字、水印或受版权保护的角色。";
    }

    private static String title(Command command, int index) {
        return command.theme() + "·" + command.kind().label() + "·" + index;
    }

    private static List<String> tags(Command command) {
        return command.style() == null
                ? List.of(command.theme(), command.kind().db())
                : List.of(command.theme(), command.kind().db(), command.style());
    }

    private static AssetCategory category(Kind kind) {
        return kind == Kind.BACKGROUND || kind == Kind.MOOD
                ? AssetCategory.SCENE : AssetCategory.OTHER;
    }

    private static IntelligenceException denied(String reason) {
        return switch (reason) {
            case "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
                    new IntelligenceException(402, "图片生成预算不足：" + reason);
            default -> new IntelligenceException(403, "图片生成执行被拒绝：" + reason);
        };
    }

    private static String publicReason(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "生成失败，请稍后重试" : message;
    }

    public record Command(
            Kind kind, String theme, String style, int count, Instant validUntil) {}

    public enum Kind {
        ICON("icon", "图标"), DECORATION("decoration", "装饰"),
        BACKGROUND("background", "背景"), MOOD("mood", "氛围图");
        private final String db;
        private final String label;
        Kind(String db, String label) { this.db = db; this.label = label; }
        public String db() { return db; }
        public String label() { return label; }
        public static Kind parse(String value) {
            if (value != null) for (Kind kind : values()) if (kind.db.equals(value.trim())) return kind;
            throw new IntelligenceException(400, "公共素材类型无效");
        }
    }

    public record Item(int index, boolean ok, UUID assetId, String errorReason) {
        static Item success(int index, UUID assetId) { return new Item(index, true, assetId, null); }
        static Item failure(int index, String error) { return new Item(index, false, null, error); }
    }
    public record BatchResult(List<Item> items, int okCount) {}
}
