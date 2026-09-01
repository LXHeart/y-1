package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.articleimage.ImageGenerationClient;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.media.StoreMediaModerationService;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 补图首帧（任务书 #65 卡2）：无锚定图镜头的平台资助生图端点实现。
 *
 * <p>计费红线：走 {@code prepareExecution(capability=image_generation, feature=null)} 免费执行环
 * （照 #64 TTS 免费分支模板 / ContentSafetyAiChecker 姿态）——ai_run 留痕、预算闸、并发槽照旧，
 * feature=null 不扣积分，禁止手写扣退。参考图（任务全部用户上传图）经免费 Routed 文本通道
 * 转描述拼进提示词（ArticleImageService.describe 同款：图像 API 不收图，风格一致性以文本承载）。
 *
 * <p>交互语义（第 2 节拍板）：默认关闭、每镜手动触发；重入生成新图替换（旧 media 软删
 * status='deleted' 留审计行）；产物过 {@link StoreMediaModerationService}（purpose=anchor_image，
 * advisory 不阻断）；落 {@code anchor_media_id}/{@code anchor_source='ai'}。
 */
@Service
public class ShotAnchorImageService {

    private static final Logger log = LoggerFactory.getLogger(ShotAnchorImageService.class);
    private static final String CAPABILITY_IMAGE_GENERATION = "image_generation";
    /** 方形锚定图：仓库既有生图调用统一 1024x1024（MiniMax 方言忽略 size），视频侧自适应画幅。 */
    private static final String ANCHOR_SIZE = "1024x1024";
    private static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private final AiExecutionService executions;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final RoutedTextCompletionService routed;
    private final ImageGenerationClient generation;
    private final VideoShotRepository shots;
    private final VideoStoryboardRepository storyboards;
    private final MediaReferenceRepository mediaRefs;
    private final StoreMediaModerationService moderation;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final DatabaseClient db;

    public ShotAnchorImageService(AiExecutionService executions,
            PlatformConcurrencyLimiter concurrencyLimiter, RoutedTextCompletionService routed,
            ImageGenerationClient generation, VideoShotRepository shots,
            VideoStoryboardRepository storyboards, MediaReferenceRepository mediaRefs,
            StoreMediaModerationService moderation, ObjectProvider<ObjectStorageAdapter> storageProvider,
            OutboxRepository outbox, TransactionalOperator transactions, DatabaseClient db) {
        this.executions = executions;
        this.concurrencyLimiter = concurrencyLimiter;
        this.routed = routed;
        this.generation = generation;
        this.shots = shots;
        this.storyboards = storyboards;
        this.mediaRefs = mediaRefs;
        this.moderation = moderation;
        this.storageProvider = storageProvider;
        this.outbox = outbox;
        this.transactions = transactions;
        this.db = db;
    }

    /** 生成结果：媒体句柄 + 落锚后的镜头行。 */
    public record AnchorResult(UUID mediaId, VideoShot shot, MediaReference media) {
    }

    public Mono<AnchorResult> generate(UUID shotId, String accountId) {
        return shots.findByIdForAccount(shotId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "镜头不存在")))
                .flatMap(shot -> storyboards.findById(shot.storyboardId())
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
                        .flatMap(storyboard -> guard(shot, storyboard)
                                .then(Mono.defer(() -> generateForShot(shot, storyboard, accountId)))))
                .flatMap(result -> shots.attachAnchor(result.shot().id(), result.mediaId())
                        .then(softDeletePreviousAnchor(result.shot().anchorMediaId(), result.mediaId()))
                        .then(shots.findById(result.shot().id()))
                        .map(reloaded -> new AnchorResult(result.mediaId(), reloaded, result.media())));
    }

    /** 409 闸：分镜已提交（不在编辑期）/ 镜头仍绑定用户锚定图（未手动解除）。 */
    private Mono<Void> guard(VideoShot shot, VideoStoryboard storyboard) {
        if (storyboard.isCommitted()) {
            return Mono.error(new IntelligenceException(409, "分镜已提交成片，不能生成锚定图"));
        }
        if (!VideoShot.ANCHOR_SOURCE_AI.equals(shot.anchorSource()) && shot.anchorImageIndex() > 0) {
            return Mono.error(new IntelligenceException(409, "该镜头已绑定用户锚定图，先解除（选「无锚定图」）再生成"));
        }
        return Mono.empty();
    }

    private Mono<AnchorResult> generateForShot(VideoShot shot, VideoStoryboard storyboard, String accountId) {
        return describeReferences(storyboard, accountId)
                .flatMap(descriptions -> prepareAndExecute(shot, storyboard, descriptions));
    }

    /**
     * 参考图 → 文本描述（免费 Routed 通道；图像 API 不收图，风格一致性以描述承载——
     * ArticleImageService.describe 同款形态）。描述失败不阻断：空列表退化为纯 visual 提示词。
     */
    private Mono<List<String>> describeReferences(VideoStoryboard storyboard, String accountId) {
        List<String> images = payloadImages(storyboard);
        if (images.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(images)
                .concatMap(image -> describeImage(image, storyboard, accountId))
                .collectList()
                .onErrorResume(error -> {
                    log.warn("anchor reference describe failed storyboardId={}", storyboard.id(), error);
                    return Mono.just(List.of());
                });
    }

    private Mono<String> describeImage(String dataUrl, VideoStoryboard storyboard, String accountId) {
        List<ChatMessage> messages = List.of(ChatMessage.user(List.of(
                ContentPart.text(REFERENCE_DESCRIPTION_PROMPT),
                ContentPart.image(dataUrl))));
        return routed.completeFor(storyboard.accountId(), storyboard.organizationId(), messages, 2048,
                DESCRIBE_TIMEOUT, "锚定图参考描述失败")
                .map(result -> result.content() == null ? "" : result.content().trim())
                .filter(content -> !content.isEmpty());
    }

    private Mono<AnchorResult> prepareAndExecute(VideoShot shot, VideoStoryboard storyboard,
            List<String> descriptions) {
        String prompt = anchorPrompt(shot, descriptions);
        int estimatedInput = prompt.getBytes(StandardCharsets.UTF_8).length;
        return executions
                .prepareExecution(storyboard.accountId(), storyboard.organizationId(),
                        CAPABILITY_IMAGE_GENERATION, null, estimatedInput, 0, 1, 0, true)
                .flatMap(result -> result.allowed()
                        ? executePrepared(shot, storyboard, prompt, result.context())
                        : Mono.error(denied(result.denialReason())));
    }

    private Mono<AnchorResult> executePrepared(VideoShot shot, VideoStoryboard storyboard, String prompt,
            AiExecutionService.ExecutionContext context) {
        ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(
                context.provider().baseUrl(), context.decryptedKey(), context.provider().model(),
                context.provider().provider());
        return Mono.usingWhen(
                Mono.just(context),
                ignored -> Mono.usingWhen(
                        concurrencyLimiter.acquire(context.provider()),
                        lease -> generation.generate(prompt, ANCHOR_SIZE, endpoint)
                                .flatMap(generated -> archive(shot, storyboard, generated.base64())
                                        .flatMap(media -> executions
                                                .settleSuccess(context, estimatedInputOf(prompt), 0, 1, 0)
                                                .thenReturn(new AnchorResult(media.id(), shot, media)))),
                        PlatformConcurrencyLimiter.Lease::release,
                        (lease, error) -> lease.release(),
                        PlatformConcurrencyLimiter.Lease::release)
                        .onErrorResume(error -> executions
                                .handleFailure(context, error.getMessage() == null
                                        ? "anchor image generation failed" : error.getMessage())
                                .then(Mono.error(error))),
                ignored -> Mono.empty(),
                (ignored, error) -> Mono.empty(),
                ignored -> executions.handleCancellation(context).then());
    }

    /** 持久私有归档（无 TTL）：对象 + media 行（anchor_image）+ 生命周期事件 + advisory 送审。 */
    private Mono<MediaReference> archive(VideoShot shot, VideoStoryboard storyboard, String base64) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.error(new IntelligenceException(503, "对象存储未启用"));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException error) {
            return Mono.error(new IntelligenceException(502, "图片生成服务返回了无效图片数据"));
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            return Mono.error(new IntelligenceException(502, "锚定图大小超出归档限制"));
        }
        UUID mediaId = UUID.randomUUID();
        String key = "media/video_shot_anchor/" + mediaId;
        MediaReference reference = new MediaReference(mediaId, storyboard.accountId(),
                storyboard.organizationId(), MediaPurpose.ANCHOR_IMAGE.db(), "video_shot", shot.id().toString(),
                key, "image/png", bytes.length, MediaChecksums.sha256(bytes), "generated",
                MediaStatus.ACTIVE, Instant.now(), null, null);
        return Mono.fromRunnable(() -> storage.putObject(key, bytes, "image/png"))
                .subscribeOn(Schedulers.boundedElastic())
                .then(transactions.transactional(mediaRefs.insert(reference)
                        .flatMap(active -> outbox.append(
                                com.grassland.intelligence.media.MediaLifecycleEvents.activated(active))
                                .thenReturn(active))))
                .doOnNext(active -> moderation.moderateGeneratedAsync(active, bytes));
    }

    /** 重入替换：旧 AI 锚定 media 软删（保留行作审计；对象清理走既有清理策略）。 */
    private Mono<Void> softDeletePreviousAnchor(UUID previousMediaId, UUID newMediaId) {
        if (previousMediaId == null || previousMediaId.equals(newMediaId)) {
            return Mono.empty();
        }
        return db.sql("UPDATE media_reference SET status='deleted',deleted_at=now(),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status='active' AND purpose='anchor_image'")
                .bind("id", previousMediaId.toString())
                .then();
    }

    /** 锚定提示词：visual 为主 + 参考素材描述（风格一致性优先参考图）+ 画幅适配说明。 */
    static String anchorPrompt(VideoShot shot, List<String> descriptions) {
        StringBuilder prompt = new StringBuilder();
        if (descriptions != null && !descriptions.isEmpty()) {
            prompt.append("[参考素材]\n");
            for (int index = 0; index < descriptions.size(); index++) {
                prompt.append("素材").append(index + 1).append(": ").append(descriptions.get(index))
                        .append('\n');
            }
            prompt.append("\n风格、色调、人物与产品特征严格延续以上参考素材，保持同一家店铺的视觉一致性。\n\n");
        }
        prompt.append("[生成要求]\n").append(shot.visual() == null || shot.visual().isBlank()
                ? "生成一张适合作为视频首帧的店铺宣传画面" : shot.visual());
        prompt.append("\n画面作为视频首帧使用：主体明确、构图稳定，不含文字字幕。");
        return prompt.toString();
    }

    /** 分镜请求快照里的素材图列表（data URL 形态；解析失败返回空——退化为纯 visual 提示词）。 */
    private static List<String> payloadImages(VideoStoryboard storyboard) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(storyboard.requestPayload()).path("images");
            if (!node.isArray()) {
                return List.of();
            }
            List<String> images = new ArrayList<>();
            node.forEach(image -> {
                String value = image.asText("");
                if (!value.isEmpty()) {
                    images.add(value.startsWith("data:") ? value : "data:image/jpeg;base64," + value);
                }
            });
            return List.copyOf(images);
        } catch (Exception error) {
            log.warn("anchor payload images parse failed storyboardId={}", storyboard.id(), error);
            return List.of();
        }
    }

    private static int estimatedInputOf(String prompt) {
        return prompt.getBytes(StandardCharsets.UTF_8).length;
    }

    private static IntelligenceException denied(String reason) {
        if ("no_platform_model".equals(reason) || "unpriced_model".equals(reason)) {
            return new IntelligenceException(503, "no_platform_model", "平台未配置图片生成模型，请到治理台配置");
        }
        return switch (reason == null ? "" : reason) {
            case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget",
                    "exceeds_monthly_budget" -> new IntelligenceException(402, "锚定图生成预算不足：" + reason);
            default -> new IntelligenceException(403, "锚定图生成执行被拒绝：" + reason);
        };
    }

    private static final String REFERENCE_DESCRIPTION_PROMPT =
            "请详细描述这张图片的视觉内容，包括：主体、构图、色调、风格、氛围。"
                    + "简洁精准地描述，用于辅助AI生图。不要输出多余说明。";
}
