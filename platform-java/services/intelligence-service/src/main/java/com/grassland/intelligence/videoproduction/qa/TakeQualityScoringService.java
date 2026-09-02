package com.grassland.intelligence.videoproduction.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.ProviderKeyDecryptor;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.media.VideoFrameExtractor;
import com.grassland.intelligence.videoproduction.VideoShot;
import com.grassland.intelligence.videoproduction.VideoShotTakeRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 候选视觉质检评分（任务书 #66 卡D1）：take 归档成功后异步触发——取 take 首帧 + 锚定图 +
 * 该镜 prompt 送 {@code video_qa} 多模态模型评分（0-100 + 标签）。
 *
 * <p>全程 advisory：未配置 video_qa、prepare 被拒、模型输出不可解析、任何 IO 失败 → 仅记日志
 * 不落分、绝不阻断挑选/合成。计费走免费执行环（feature=null 平台资助，照 #64 TTS 分支模板），
 * 零积分流水；结算复用 {@code settleSuccess}（0 成本），禁止手写扣退。
 */
@Service
public class TakeQualityScoringService {

    public static final String CAPABILITY_VIDEO_QA = "video_qa";

    private static final Logger log = LoggerFactory.getLogger(TakeQualityScoringService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final int MAX_TOKENS = 512;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_LABELS = 4;

    /** 评分结果：score 0..100；labels 如「与锚定图差异大」「疑似道具缺失」「画质偏低」。 */
    public record TakeScore(double score, List<String> labels) {}

    private final PlatformModelControlPlaneService controlPlane;
    private final ProviderKeyDecryptor keyDecryptor;
    private final AiExecutionService executions;
    private final TextCompletionClient textCompletion;
    private final VideoShotTakeRepository takes;
    private final VideoFrameExtractor frameExtractor;
    private final ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider;

    public TakeQualityScoringService(PlatformModelControlPlaneService controlPlane,
            ProviderKeyDecryptor keyDecryptor, AiExecutionService executions,
            TextCompletionClient textCompletion, VideoShotTakeRepository takes,
            VideoFrameExtractor frameExtractor,
            ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider) {
        this.controlPlane = controlPlane;
        this.keyDecryptor = keyDecryptor;
        this.executions = executions;
        this.textCompletion = textCompletion;
        this.takes = takes;
        this.frameExtractor = frameExtractor;
        this.storageProvider = storageProvider;
    }

    /** 归档成功后的异步挂钩（脱离 worker 主链，advisory 失败静默）。mediaId 为归档句柄。 */
    public void scoreAsync(UUID takeId, UUID mediaId, VideoShot shot, VideoStoryboard storyboard) {
        Mono.defer(() -> scoreOnce(takeId, mediaId, shot, storyboard))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> { }, error -> log.warn(
                        "take quality scoring skipped takeId={} cause={}", takeId,
                        String.valueOf(error.getMessage())));
    }

    /** 单次评分（同步链，IT 直接驱动断言）。任何降级路径返回 empty。 */
    public Mono<Void> scoreOnce(UUID takeId, UUID mediaId, VideoShot shot,
            VideoStoryboard storyboard) {
        Mono<Optional<ResolvedPlatformModel>> resolved = controlPlane.resolve(CAPABILITY_VIDEO_QA);
        return resolved.flatMap(row -> row.<Mono<ResolvedPlatformModel>>map(Mono::just)
                        .orElseGet(Mono::empty))
                .flatMap(row -> freeExecution(storyboard, row)
                        .flatMap(ctx -> parts(mediaId, shot, storyboard)
                                .flatMap(parts -> call(row, parts)
                                        .flatMap(result -> persist(takeId, ctx, result)))))
                .then();
    }

    // ---------------- 组装与调用 ----------------

    private Mono<AiExecutionService.ExecutionContext> freeExecution(
            VideoStoryboard storyboard, ResolvedPlatformModel row) {
        int estimatedInput = Math.max(1, String.valueOf(storyboard.id()).length()
                + String.valueOf(row.model()).length());
        return executions.prepareExecution(storyboard.accountId(), storyboard.organizationId(),
                        CAPABILITY_VIDEO_QA, null, estimatedInput, 0, true)
                .flatMap(result -> result.allowed() ? Mono.just(result.context()) : Mono.empty());
    }

    private Mono<TextCompletionResult> call(ResolvedPlatformModel row, List<ContentPart> parts) {
        String bearer;
        try {
            bearer = keyDecryptor.decryptIfNeeded(ProviderResolution.platform(row.configId(),
                    row.provider(), row.baseUrl(), row.model(), row.version(), row.maxConcurrency(),
                    row.credentialEncryptedKey(), row.credentialVersion()));
        } catch (RuntimeException decryptFailed) {
            return Mono.empty();
        }
        if (bearer == null && !"sandbox".equalsIgnoreCase(row.provider())) {
            return Mono.empty();
        }
        return textCompletion.completeMessages(row.provider(), row.baseUrl(), bearer, row.model(),
                List.of(ChatMessage.user(parts)), MAX_TOKENS, false, TIMEOUT);
    }

    private Mono<Void> persist(UUID takeId, AiExecutionService.ExecutionContext ctx,
            TextCompletionResult result) {
        TakeScore score = parseScore(result.content());
        Mono<Void> settle = executions.settleSuccess(ctx, result.inputTokens(),
                result.outputTokens(), 0, 0).then().onErrorResume(settleError -> {
                    log.warn("take quality scoring settle failed takeId={}", takeId, settleError);
                    return Mono.empty();
                });
        if (score == null) {
            // 解析失败：放弃评分仅日志（卡面：不把「读不懂」伪装成评分）
            log.info("take quality scoring unparseable takeId={} content={}", takeId,
                    result.content());
            return settle;
        }
        return takes.updateScore(takeId, score.score(), labelsJson(score.labels()))
                .then(settle);
    }

    /** take 首帧 + 锚定图 + 评分 rubric。素材取不到 → 空清单 → 放弃评分。 */
    private Mono<List<ContentPart>> parts(UUID mediaId, VideoShot shot,
            VideoStoryboard storyboard) {
        return Mono.<List<ContentPart>>fromCallable(() -> {
            List<ContentPart> assembled = new ArrayList<>();
            byte[] takeBytes = takeMediaBytes(mediaId);
            if (takeBytes == null) {
                return List.<ContentPart>of();
            }
            List<byte[]> frames = frameExtractor.extract(takeBytes);
            if (frames.isEmpty()) {
                return List.of();
            }
            assembled.add(ContentPart.image("data:image/jpeg;base64,"
                    + BASE64.encodeToString(frames.getFirst())));
            String anchor = anchorDataUrl(shot, storyboard);
            if (anchor != null) {
                assembled.add(ContentPart.image(anchor));
            }
            assembled.add(ContentPart.text(rubric(shot)));
            return List.copyOf(assembled);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] takeMediaBytes(UUID mediaId) {
        if (mediaId == null) {
            return null;
        }
        com.grassland.storage.ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return null;
        }
        try {
            return storage.getObject("media/video_take/" + mediaId);
        } catch (RuntimeException missingOrFailed) {
            return null;
        }
    }

    /** 锚定图 data URL：AI 补图锚定走媒体对象，否则取分镜请求快照第 N 张（1 基，0=纯文生）。 */
    private String anchorDataUrl(VideoShot shot, VideoStoryboard storyboard) {
        try {
            if (shot.isAiAnchored() && shot.anchorMediaId() != null) {
                com.grassland.storage.ObjectStorageAdapter storage = storageProvider.getIfAvailable();
                if (storage == null) {
                    return null;
                }
                byte[] bytes = storage.getObject("media/video_shot_anchor/" + shot.anchorMediaId());
                return bytes == null ? null
                        : "data:image/jpeg;base64," + BASE64.encodeToString(bytes);
            }
            if (shot.anchorImageIndex() < 1 || storyboard.requestPayload() == null) {
                return null;
            }
            JsonNode images = MAPPER.readTree(storyboard.requestPayload()).path("images");
            if (!images.isArray() || shot.anchorImageIndex() > images.size()) {
                return null;
            }
            String value = images.get(shot.anchorImageIndex() - 1).asText();
            return value.startsWith("data:") ? value : null;
        } catch (RuntimeException | java.io.IOException parseFailure) {
            return null;
        }
    }

    private static String rubric(VideoShot shot) {
        return "你是视频候选质检员。对比第一张图（候选片段首帧）与第二张图（该镜头锚定参考图，可能缺失），"
                + "结合镜头描述评估该候选作为成片片段的质量。评估维度：与锚定图画面一致性（主体/道具/场景是否延续）、"
                + "画质（清晰度/曝光/伪影）。只输出 JSON：{\"score\":0-100 整数,"
                + "\"labels\":[\"与锚定图差异大\"|\"疑似道具缺失\"|\"画质偏低\"|\"主体不一致\" 中的 0-4 个]}。"
                + "镜头描述：" + nullSafe(shot.visual()) + "；生成提示：" + nullSafe(shot.prompt());
    }

    // ---------------- 解析 ----------------

    /** score/labels 解析；不可解析或值域外 → null（放弃评分）。 */
    static TakeScore parseScore(String content) {
        try {
            String stripped = stripCodeFence(content == null ? "" : content);
            JsonNode root = MAPPER.readTree(stripped);
            if (!root.has("score")) {
                return null;
            }
            double score = root.path("score").asDouble(Double.NaN);
            if (!Double.isFinite(score) || score < 0 || score > 100) {
                return null;
            }
            List<String> labels = new ArrayList<>();
            JsonNode labelsNode = root.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode node : labelsNode) {
                    String label = node.asText("").trim();
                    if (!label.isEmpty() && labels.size() < MAX_LABELS) {
                        labels.add(label);
                    }
                }
            }
            return new TakeScore(Math.round(score * 10d) / 10d, List.copyOf(labels));
        } catch (RuntimeException | java.io.IOException error) {
            return null;
        }
    }

    static String labelsJson(List<String> labels) {
        try {
            return MAPPER.writeValueAsString(labels);
        } catch (java.io.IOException error) {
            return "[]";
        }
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

}
