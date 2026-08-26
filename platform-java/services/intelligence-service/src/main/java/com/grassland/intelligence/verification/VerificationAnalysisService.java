package com.grassland.intelligence.verification;

import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 履约 AI 视觉核验编排（草场 Slice 11 Verification Stage 3）。
 *
 * <p>marketplace 以服务断言调 {@link VerificationController}，传入待核验附件 media id 列表 + 任务上下文。
 * 本服务 intelligence 内部自读附件字节（{@link MediaReferenceRepository#findById} 过滤 purpose=engagement_attachment
 * + active + 未过期 → {@link ObjectStorageAdapter#getObject} → base64 data URI），逐张调
 * 经 {@link RoutedTextCompletionService#completePlatformOnly} 平台内置模型视觉判断，归一为 per-media tri-state。
 *
 * <p>provider guard：仅 Qwen 支持视觉核验（镜像 {@code VideoRecreationAdaptationService}），非 qwen→400。
 * 错误隔离：单张附件的存储/解析/AI 失败（含上游超时）一律降级为该附件 {@code inconclusive}，不拖垮整次核验；
 * 故路由层透传的上游失败文案不会泄露——
 * 既因本服务自定义 failureMessage（镜像 {@code ImageAnalysisService} 经 completeText 传入），又因所有错误被
 * onErrorResume 吞为 inconclusive。
 *
 * <p>装配门控：与 {@code MediaController} / {@code MediaCleanup} 同，仅在 {@code object-storage.enabled=true}
 * 时装配——本服务注入 {@link ObjectStorageAdapter}（禁用存储时无该 bean，不门控会断上下文）。
 */
@Service
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class VerificationAnalysisService {

    static final String STATUS_PASSED = "passed";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_INCONCLUSIVE = "inconclusive";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final String UNSUPPORTED = "当前核验服务不支持AI视觉核验，请切换到 Qwen 后重试";
    private static final String AI_FAILURE_MESSAGE = "履约核验AI判断失败，请稍后重试";
    private static final String SERVICE_ATTACHMENT_PURPOSE = MediaPurpose.ENGAGEMENT_ATTACHMENT.db();

    private final RoutedTextCompletionService routed;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectStorageAdapter storage;
    private final VerificationResultNormalizer normalizer;
    private final Duration timeout;
    private final String provider;

    public VerificationAnalysisService(
            RoutedTextCompletionService routed,
            MediaReferenceRepository mediaRefs,
            ObjectStorageAdapter storage,
            VerificationResultNormalizer normalizer,
            Environment environment) {
        this.routed = routed;
        this.mediaRefs = mediaRefs;
        this.storage = storage;
        this.normalizer = normalizer;
        this.provider = environment.getProperty("ai.verification.provider", "qwen");
        long timeoutMs = environment.getProperty(
                "ai.verification.timeout-ms", Long.class, DEFAULT_TIMEOUT.toMillis());
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
    }

    /**
     * 逐张核验附件并聚合。provider 非 qwen → 400（部署配置错误，marketplace 据此把 ai_visual 降为 inconclusive）。
     * 不会对单张附件失败抛出——每张都产出 tri-state 结果。
     */
    public Mono<VerificationAnalysis> analyze(VerificationAnalysisRequest request) {
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.error(new IntelligenceException(400, UNSUPPORTED));
        }
        String prompt = request.interactionMode()
                ? VerificationPrompts.buildInteraction(request.taskTitle(), request.taskDescription(),
                        request.platform(), request.targetUrl(), request.actionType(), request.platformHandle(),
                        request.commentText())
                : VerificationPrompts.build(
                        request.taskTitle(), request.taskDescription(), request.platform());
        return Flux.fromIterable(request.mediaIds())
                .concatMap(mediaId -> verifyOne(mediaId, prompt))
                .collectList()
                .map(VerificationAnalysisService::aggregate);
    }

    /**
     * 核验单张附件。过滤不符附件（purpose/active/过期）或对象不可读 → {@code inconclusive "附件不可用"}；
     * AI 调用/归一失败（含上游超时）→ {@code inconclusive "AI 核验暂不可用"}。
     */
    private Mono<MediaVerificationResult> verifyOne(UUID mediaId, String prompt) {
        return serviceAttachment(mediaId)
                .flatMap(ref -> readBytes(ref)
                        .onErrorResume(error -> Mono.empty())
                        .map(bytes -> dataUri(ref.mimeType(), bytes))
                        .flatMap(dataUri -> judge(mediaId, dataUri, prompt)))
                .onErrorResume(error -> Mono.just(inconclusive(mediaId, "AI 核验暂不可用")))
                .switchIfEmpty(Mono.just(inconclusive(mediaId, "附件不可用")));
    }

    /** purpose=engagement_attachment + active + 未过期；不符/不存在均 empty（→ 附件不可用）。 */
    private Mono<MediaReference> serviceAttachment(UUID id) {
        return mediaRefs.findById(id)
                .filter(ref -> SERVICE_ATTACHMENT_PURPOSE.equals(ref.purpose()))
                .filter(ref -> ref.status() == MediaStatus.ACTIVE)
                .filter(ref -> !isExpired(ref));
    }

    private Mono<byte[]> readBytes(MediaReference ref) {
        return Mono.fromCallable(() -> storage.getObject(ref.objectKey()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<MediaVerificationResult> judge(UUID mediaId, String dataUri, String prompt) {
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.image(dataUri));
        parts.add(ContentPart.text(prompt));
        return routed.completePlatformOnly(
                List.of(ChatMessage.user(parts)), 2048, timeout, AI_FAILURE_MESSAGE)
                .map(completion -> normalizer.normalize(completion.content()))
                .map(verdict -> new MediaVerificationResult(mediaId, verdict.status(), verdict.detail()));
    }

    /** failed > inconclusive > passed：任一 failed→failed；否则任一 inconclusive→inconclusive；全 passed→passed。 */
    private static VerificationAnalysis aggregate(List<MediaVerificationResult> results) {
        String status = STATUS_PASSED;
        for (MediaVerificationResult result : results) {
            if (STATUS_FAILED.equals(result.status())) {
                status = STATUS_FAILED;
                break;
            }
            if (STATUS_INCONCLUSIVE.equals(result.status())) {
                status = STATUS_INCONCLUSIVE;
            }
        }
        return new VerificationAnalysis(status, results);
    }

    private static MediaVerificationResult inconclusive(UUID mediaId, String detail) {
        return new MediaVerificationResult(mediaId, STATUS_INCONCLUSIVE, detail);
    }

    private static String dataUri(String mimeType, byte[] bytes) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean isExpired(MediaReference ref) {
        return ref.expiresAt() != null && ref.expiresAt().isBefore(Instant.now());
    }
}
