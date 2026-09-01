package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.contentsafety.ContentSafetyService;
import com.grassland.intelligence.contentsafety.SafetyReport;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.credits.CreditFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 结构化分镜生成（任务书 #64 卡3）：LLM NDJSON → 解析校验 → 落 storyboard/shots →
 * SSE 帧序列（meta / shot* / safety）。
 *
 * <p>计费沿用既有执行环（video_production_script 不变，任务模式 frozenText.executeTraced /
 * 独立模式 executeIndependent）；安全检查 = 旁白全拼 + prompt 全拼过完整 check（L1+L2+原创度），
 * 失败降级为空 findings 帧——advisory 不阻断（D6）。lineage 落 video_storyboard（分镜落一），
 * 失败 advisory 不吞分镜结果。
 */
@Service
public class StoryboardService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardService.class);
    /** 10 镜 × ~250 token 的 NDJSON 上限余量。 */
    private static final int MAX_OUTPUT_TOKENS = 3072;

    private final FrozenTextExecutionService frozenText;
    private final VideoTaskCreationContext creationContexts;
    private final VideoStoryboardRepository storyboardRepo;
    private final VideoShotRepository shotRepo;
    private final CreationGenerationRecorder lineage;
    private final ContentSafetyService safety;
    private final ObjectMapper mapper = new ObjectMapper();

    public StoryboardService(FrozenTextExecutionService frozenText, VideoTaskCreationContext creationContexts,
            VideoStoryboardRepository storyboardRepo, VideoShotRepository shotRepo,
            CreationGenerationRecorder lineage, ContentSafetyService safety) {
        this.frozenText = frozenText;
        this.creationContexts = creationContexts;
        this.storyboardRepo = storyboardRepo;
        this.shotRepo = shotRepo;
        this.lineage = lineage;
        this.safety = safety;
    }

    /** 已落库的分镜 + 待发送的 SSE 帧序列（含尾部安全帧）。 */
    public record StoryboardFrames(UUID storyboardId, int targetDurationSeconds, Flux<String> frames) {}

    public Mono<StoryboardFrames> generate(ServerWebExchange exchange, String accountId, String organizationId,
            VideoProductionController.StoryboardRequest request) {
        ChatMessageSystem system = new ChatMessageSystem(
                StoryboardPrompts.system(request.targetDurationSeconds(), request.targetPlatform()),
                StoryboardPrompts.user(request));
        Mono<Executed> executed = request.isTaskMode()
                ? creationContexts
                        .bind(request.contextSnapshotId(), accountId, request.targetPlatform())
                        .flatMap(binding -> frozenText
                                .executeTraced(exchange, request.contextSnapshotId(),
                                        List.of(system.system(), binding.promptContext(), system.user()),
                                        MAX_OUTPUT_TOKENS, CreditFeature.VIDEO_PRODUCTION_SCRIPT,
                                        TextCompletionResult::content)
                                .map(traced -> new Executed(traced, binding.snapshot())))
                : frozenText
                        .executeIndependent(exchange, List.of(system.system(), system.user()),
                                MAX_OUTPUT_TOKENS, CreditFeature.VIDEO_PRODUCTION_SCRIPT,
                                TextCompletionResult::content)
                        .map(traced -> new Executed(traced, null));
        return executed.flatMap(done -> {
            List<StoryboardParser.ParsedShot> shots =
                    StoryboardParser.parse(done.traced().value(), request.images().size());
            String payload = writePayload(request);
            return persist(accountId, organizationId, request, shots, payload)
                    .flatMap(storyboardId -> recordLineage(accountId, organizationId, request, done, shots,
                            storyboardId, payload)
                            .thenReturn(new StoryboardFrames(storyboardId, request.targetDurationSeconds(),
                                    frames(storyboardId, request.targetDurationSeconds(), shots, exchange,
                                            done.snapshot(), request))));
        });
    }

    /** meta 首帧带 storyboardId：卡6 建任务要它，前端在流结束时就持有。 */
    private Flux<String> frames(UUID storyboardId, int targetDurationSeconds,
            List<StoryboardParser.ParsedShot> shots, ServerWebExchange exchange,
            CreationContextSnapshot snapshot, VideoProductionController.StoryboardRequest request) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "meta");
        meta.put("storyboardId", storyboardId.toString());
        meta.put("targetDurationSeconds", targetDurationSeconds);
        Flux<String> head = Flux.concat(
                Mono.just(toJson(meta)),
                Flux.fromIterable(shots).map(shot -> {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("type", "shot");
                    frame.put("shot", shotBody(shot));
                    return toJson(frame);
                }));
        String platform = snapshot == null ? request.targetPlatform() : snapshot.platformId();
        String industry = snapshot == null ? request.industryType()
                : ContentSafetyService.industryFromSnapshot(snapshot);
        StringBuilder checked = new StringBuilder();
        for (StoryboardParser.ParsedShot shot : shots) {
            checked.append(shot.narration()).append('\n');
        }
        for (StoryboardParser.ParsedShot shot : shots) {
            checked.append(shot.prompt()).append('\n');
        }
        Mono<String> safetyFrame = Mono.defer(() -> safety
                .check(exchange, checked.toString(), platform, industry,
                        ContentSafetyService.generationContext(snapshot))
                .map(this::safetyFrame)
                .onErrorResume(error -> Mono.just(safetyFrame(SafetyReport.emptyShallow()))));
        return head.concatWith(safetyFrame);
    }

    private String safetyFrame(SafetyReport report) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "safety");
        frame.put("safety", safety.reportBody(report));
        return toJson(frame);
    }

    private static Map<String, Object> shotBody(StoryboardParser.ParsedShot shot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", shot.seq());
        body.put("visual", shot.visual());
        body.put("narration", shot.narration());
        body.put("plannedSeconds", shot.plannedSeconds());
        body.put("cameraMove", shot.cameraMove());
        body.put("anchorImageIndex", shot.anchorImageIndex());
        body.put("prompt", shot.prompt());
        return body;
    }

    private Mono<UUID> persist(String accountId, String organizationId,
            VideoProductionController.StoryboardRequest request, List<StoryboardParser.ParsedShot> shots,
            String payload) {
        UUID contextSnapshotId = request.isTaskMode() ? request.contextSnapshotId() : null;
        return storyboardRepo
                .create(accountId, organizationId, contextSnapshotId, request.targetDurationSeconds(), payload)
                .flatMap(storyboard -> Flux
                        .fromIterable(shots)
                        .concatMap(shot -> shotRepo.upsert(storyboard.id(), shot.seq(), shot.visual(),
                                shot.narration(), shot.plannedSeconds(), shot.cameraMove(),
                                shot.anchorImageIndex(), shot.prompt()))
                        .then(Mono.just(storyboard.id())));
    }

    /** lineage 是 advisory：失败记日志，不吞分镜（CardSeries 同款姿态）。 */
    private Mono<Void> recordLineage(String accountId, String organizationId,
            VideoProductionController.StoryboardRequest request, Executed done,
            List<StoryboardParser.ParsedShot> shots, UUID storyboardId, String payload) {
        CreationGeneration.Mode mode = request.isTaskMode()
                ? CreationGeneration.Mode.TASK
                : CreationGeneration.Mode.INDEPENDENT;
        Traced<String> traced = done.traced();
        Map<String, Object> result = Map.of(
                "storyboardId", storyboardId.toString(),
                "shotCount", shots.size());
        return lineage
                .record(new CreationGenerationRecorder.Command(
                        CreationGeneration.Kind.VIDEO_STORYBOARD, mode, request.contextSnapshotId(),
                        traced.runId(), CreationGeneration.Resolution.PLATFORM,
                        traced.provider(), traced.model(), traced.platformModelVersion(), null,
                        "视频分镜：" + request.shopName(), Map.of("images", request.images().size()),
                        List.of(), result, List.of(), accountId, organizationId))
                .then()
                .onErrorResume(error -> {
                    log.error("video storyboard lineage 落库失败 owner={}", accountId, error);
                    return Mono.empty();
                });
    }

    private String writePayload(VideoProductionController.StoryboardRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (Exception error) {
            throw new IllegalStateException("分镜请求快照序列化失败", error);
        }
    }

    private String toJson(Map<String, Object> frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (Exception error) {
            throw new IllegalStateException("分镜帧序列化失败", error);
        }
    }

    private record ChatMessageSystem(com.grassland.intelligence.ai.ChatMessage system,
            com.grassland.intelligence.ai.ChatMessage user) {
    }

    private record Executed(Traced<String> traced, CreationContextSnapshot snapshot) {
    }
}
