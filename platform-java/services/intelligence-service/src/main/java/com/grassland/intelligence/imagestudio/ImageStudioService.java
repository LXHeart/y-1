package com.grassland.intelligence.imagestudio;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.articleimage.GeneratedImageStore;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 图片编辑台服务（任务书 #43 Stage 1）。
 *
 * <p>抠图全链路：caller 鉴权 → media 归属 + MIME 校验 → 读原图字节 →
 * {@code prepareExecution("image_edit")} 预算闸 → provider 执行 →
 * {@code GeneratedImageStore} 暂存 → 返回短 TTL 读 URL。
 *
 * <p>不设积分键（D3）：与 image_generation 现行口径一致，仅走预算闸。
 */
@Service
public final class ImageStudioService {

    private static final Logger log = LoggerFactory.getLogger(ImageStudioService.class);
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final IntelligenceCallerResolver callers;
    private final MediaReferenceRepository mediaReferences;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final AiExecutionService executions;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final GeneratedImageStore imageStore;
    private final SandboxImageMattingProvider sandboxProvider;
    private final PlatformModelConfig platformDefaults;

    public ImageStudioService(
            IntelligenceCallerResolver callers,
            MediaReferenceRepository mediaReferences,
            ObjectProvider<ObjectStorageAdapter> storageProvider,
            AiExecutionService executions,
            PlatformConcurrencyLimiter concurrencyLimiter,
            GeneratedImageStore imageStore,
            SandboxImageMattingProvider sandboxProvider,
            PlatformModelConfig platformDefaults) {
        this.callers = callers;
        this.mediaReferences = mediaReferences;
        this.storageProvider = storageProvider;
        this.executions = executions;
        this.concurrencyLimiter = concurrencyLimiter;
        this.imageStore = imageStore;
        this.sandboxProvider = sandboxProvider;
        this.platformDefaults = platformDefaults;
    }

    /**
     * 抠图入口：鉴权 → 校验 → 预算闸 → 执行 → 暂存 → 返回 URL。
     *
     * @return {@code {imageUrl: "/api/image-studio/matting-results/{id}"}}
     */
    public Mono<MattingResponse> matting(ServerHttpRequest request, UUID mediaId) {
        return callers.requireUser(request)
                .flatMap(caller -> requireOwnedActiveImage(mediaId, caller.accountId())
                        .flatMap(media -> readImageBytes(media)
                                .flatMap(bytes -> executeMatting(
                                        caller.accountId(), caller.organizationId(), bytes, media.mimeType()))));
    }

    /** 读暂存结果（30min TTL PNG）。 */
    public Mono<GeneratedImageStore.StoredImage> findResult(String id) {
        return imageStore.find(id);
    }

    private Mono<MediaReference> requireOwnedActiveImage(UUID mediaId, String accountId) {
        if (mediaId == null) {
            return Mono.error(new IllegalArgumentException("mediaId 不能为空"));
        }
        Instant now = Instant.now();
        return mediaReferences.findById(mediaId)
                .filter(ref -> accountId.equals(ref.ownerAccountId()))
                .filter(ref -> ref.status() == MediaStatus.ACTIVE && ref.deletedAt() == null)
                .filter(ref -> ref.expiresAt() == null || ref.expiresAt().isAfter(now))
                .filter(ref -> ref.mimeType() != null && IMAGE_MIME_TYPES.contains(ref.mimeType().trim().toLowerCase()))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "图片不存在")));
    }

    private Mono<byte[]> readImageBytes(MediaReference media) {
        return Mono.fromCallable(() -> {
                    ObjectStorageAdapter storage = storageProvider.getIfAvailable();
                    if (storage == null) {
                        throw new IntelligenceException(503, "图片存储暂不可用");
                    }
                    byte[] bytes = storage.getObject(media.objectKey());
                    if (bytes == null || bytes.length < 1 || bytes.length > MAX_IMAGE_BYTES) {
                        throw new IntelligenceException(404, "图片不存在或过大");
                    }
                    return bytes;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<MattingResponse> executeMatting(
            String accountId, String organizationId, byte[] imageBytes, String mimeType) {
        return Mono.usingWhen(
                executions.prepareExecution(
                        accountId, organizationId, "image_edit", null, 0, 0, true),
                prepared -> {
                    if (!prepared.allowed()) {
                        return Mono.error(deniedException(prepared.denialReason()));
                    }
                    return executeWithProvider(imageBytes, mimeType, prepared.context())
                            .flatMap(response -> executions.settleSuccess(
                                            prepared.context(), null, null, 1, 0)
                                    .thenReturn(response));
                },
                ignored -> Mono.empty(),
                (prepared, error) -> finalizeRunFailure(prepared, "matting_failed"),
                prepared -> finalizeRunFailure(prepared, "matting_cancelled"));
    }

    private Mono<MattingResponse> executeWithProvider(
            byte[] imageBytes, String mimeType, AiExecutionService.ExecutionContext context) {
        ImageMattingProvider provider = resolveProvider(context);
        ImageMattingProvider.MattingCommand command =
                new ImageMattingProvider.MattingCommand(imageBytes, mimeType);

        return Mono.usingWhen(
                concurrencyLimiter.acquire(context.provider()),
                lease -> provider.matting(command)
                        .flatMap(this::storeResult),
                PlatformConcurrencyLimiter.Lease::release,
                (lease, error) -> lease.release(),
                PlatformConcurrencyLimiter.Lease::release);
    }

    private ImageMattingProvider resolveProvider(AiExecutionService.ExecutionContext context) {
        String providerName = context.provider().provider();
        if ("sandbox".equalsIgnoreCase(providerName)) {
            return sandboxProvider;
        }
        // 平台模型：baseUrl / model 来自控制面，apiKey 沿用平台默认
        return new OpenAiCompatibleImageMattingProvider(
                context.provider().baseUrl(),
                context.provider().model(),
                platformDefaults.apiKey());
    }

    private Mono<MattingResponse> storeResult(ImageMattingProvider.MattingResult result) {
        String b64 = Base64.getEncoder().encodeToString(result.pngWithAlpha());
        return imageStore.store(b64)
                .map(ref -> new MattingResponse("/api/image-studio/matting-results/" + ref.id()));
    }

    private Mono<Void> finalizeRunFailure(
            AiExecutionService.ExecutionResult prepared, String failureCode) {
        if (!prepared.allowed()) {
            return Mono.empty();
        }
        return Mono.defer(() -> executions.handleFailure(
                                prepared.context(), "image matting failed: " + failureCode)
                        .then())
                .onErrorResume(e -> Mono.empty());
    }

    private static IntelligenceException deniedException(String reason) {
        return switch (reason) {
            case "no_platform_model" ->
                    new IntelligenceException(503, "no_platform_model", "未配置图像编辑模型");
            case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
                    new IntelligenceException(402, reason, "已达预算上限");
            default -> new IntelligenceException(403, "execution_denied", "图像编辑执行被拒绝");
        };
    }

    public record MattingResponse(String imageUrl) {}
}
