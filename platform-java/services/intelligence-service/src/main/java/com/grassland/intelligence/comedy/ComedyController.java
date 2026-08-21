package com.grassland.intelligence.comedy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 脱口秀文稿生成（草场 intelligence Slice 2）——第一个迁入的业务模块。结构与冒烟端点同构，多两件事：
 * <ol>
 *   <li>流式前扣 1 积分（{@link CreditFeature#COMEDY_GENERATION}，镜像 legacy {@code requireCredit}，
 *       不足→402 JSON，不发 SSE 字节）；</li>
 *   <li>请求校验 + 李继刚风格 prompt（{@link ComedyPrompts}，忠实移植 legacy）。</li>
 * </ol>
 *
 * <p>路径沿用 legacy {@code /api/comedy-generation/generate-script}，edge-bff 按前缀路由 → 前端零改动。
 * 上游流式中途异常降级为 {@code data: {"error":"..."}\n\n} 帧（与 legacy 一致；随后 [DONE] 收尾）。
 */
@RestController
public class ComedyController {

    static final String ERROR_MESSAGE = "脱口秀文稿生成失败";

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;
    private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;
    private final FrozenTextExecutionService frozenText;
    private final ComedyTaskCreationContext creationContexts;
    private final com.grassland.intelligence.creationlineage.TextCreationLineageService lineage;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComedyController(
            IntelligenceCallerResolver callers, AiCapabilityAdapter ai, CreditsClient credits,
            FrozenTextExecutionService frozenText, ComedyTaskCreationContext creationContexts,
            com.grassland.intelligence.contentsafety.ContentSafetyService safety,
            com.grassland.intelligence.creationlineage.TextCreationLineageService lineage) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
        this.safety = safety;
        this.frozenText = frozenText;
        this.creationContexts = creationContexts;
        this.lineage = lineage;
    }

    @PostMapping("/api/comedy-generation/generate-script")
    public Mono<ResponseEntity<Flux<DataBuffer>>> generate(@RequestBody ComedyRequest body, ServerWebExchange exchange) {
        if (body.isTaskMode()) {
            return callers.requireUser(exchange.getRequest())
                    .flatMap(caller -> creationContexts.bind(
                            body.contextSnapshotId(), caller.accountId(), body.targetPlatform()))
                    .flatMap(binding -> frozenText.executeTraced(
                            exchange, body.contextSnapshotId(), List.of(
                                    ComedyPrompts.system(body.duration()),
                                    binding.promptContext(),
                                    ComedyPrompts.user(body.topic())),
                            2048, CreditFeature.COMEDY_GENERATION,
                            completion -> completion.content())
                            .map(trace -> Map.entry(binding, trace)))
                    .map(bound -> {
                        ComedyTaskCreationContext.Binding binding = bound.getKey();
                        FrozenTextExecutionService.Traced<String> trace = bound.getValue();
                        Flux<String> frames = Flux.just(frame(Map.of("content", trace.value())))
                                // 任务书 #44 登记扩展：脚本产出落 lineage（SSE 尾部，失败不破坏内容流）
                                .concatWith(Mono.defer(() -> lineage.recordAdvisory(
                                        lineageCommand(body, trace.value(), binding.snapshot().accountId(),
                                                binding.snapshot().organizationId(),
                                                com.grassland.intelligence.creationlineage.CreationGeneration.Mode.TASK,
                                                body.contextSnapshotId(), trace))
                                        .then(Mono.<String>empty())));
                        return sseEntity(withSafety(exchange, frames, binding.snapshot(), body.targetPlatform()),
                                exchange);
                    })
                    .onErrorMap(error -> error instanceof IntelligenceException
                            ? error : new IntelligenceException(502, ERROR_MESSAGE));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.COMEDY_GENERATION)
                        .map(charge -> Map.entry(caller, charge)))
                .map(entry -> {
                    com.grassland.intelligence.security.IntelligenceCallerResolver.Caller caller = entry.getKey();
                    StringBuilder accumulated = new StringBuilder();
                    Flux<String> payloads = ai.startTextRun(new TextRunCommand(List.of(
                            ComedyPrompts.system(body.duration()),
                            ComedyPrompts.user(body.topic()))))
                            .map(chunk -> frame(Map.of("content", chunk.content())))
                            .doOnNext(item -> {
                                String text = com.grassland.intelligence.contentsafety.ContentSafetyService
                                        .contentFieldExtractor().apply(item);
                                if (text != null) {
                                    accumulated.append(text);
                                }
                            })
                            // 上游失败：先退回已扣积分再发 error 帧（GL-P0-BILL-002）
                            .onErrorResume(e -> credits.refund(entry.getValue(), "脱口秀文稿生成失败自动退回")
                                    .thenMany(Flux.just(frame(Map.of("error", ERROR_MESSAGE)))))
                            // 任务书 #44 登记扩展：脚本产出落 lineage
                            .concatWith(Mono.defer(() -> lineage.recordAdvisory(
                                    lineageCommand(body, accumulated.toString(), caller.accountId(),
                                            caller.organizationId(),
                                            com.grassland.intelligence.creationlineage.CreationGeneration.Mode.INDEPENDENT,
                                            null, null))
                                    .then(Mono.<String>empty())));
                    return sseEntity(withSafety(exchange, payloads, null, body.targetPlatform()), exchange);
                });
    }

    /** lineage 命令：input=题材/时长/平台，result 只记长度（脚本文本不整段落库）。 */
    private com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command lineageCommand(
            ComedyRequest body, String content, String accountId, String organizationId,
            com.grassland.intelligence.creationlineage.CreationGeneration.Mode mode, UUID snapshotId,
            FrozenTextExecutionService.Traced<String> trace) {
        String provider = trace == null
                ? com.grassland.intelligence.creationlineage.TextCreationLineageService.INDEPENDENT_PROVIDER
                : trace.provider();
        String model = trace == null ? lineage.independentModel() : trace.model();
        return new com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command(
                com.grassland.intelligence.creationlineage.CreationGeneration.Kind.COMEDY_SCRIPT,
                mode, snapshotId, trace == null ? null : trace.runId(),
                com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.PLATFORM,
                provider, model, trace == null ? null : trace.platformModelVersion(), null,
                "题材：" + body.topic() + "；时长：" + body.duration() + " 秒",
                Map.of("topic", body.topic(), "durationSeconds", body.duration(),
                        "platform", body.targetPlatform() == null ? "" : body.targetPlatform()),
                List.of(),
                Map.of("contentLength", content == null ? 0 : content.length()),
                List.of(), accountId, organizationId);
    }

    /** 任务书 #34 D8：喜剧脚本流尾追加安全检查帧（脚本=长文本，L2 已配置时深检）。 */
    private Flux<String> withSafety(
            ServerWebExchange exchange, Flux<String> frames,
            com.grassland.intelligence.creationcontext.CreationContextSnapshot snapshot,
            String requestedPlatform) {
        return safety.appendSafetyFrame(exchange, frames,
                com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor(),
                snapshot == null ? requestedPlatform : snapshot.platformId(),
                com.grassland.intelligence.contentsafety.ContentSafetyService.industryFromSnapshot(snapshot),
                com.grassland.intelligence.contentsafety.ContentSafetyService.generationContext(snapshot));
    }

    private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
        Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.set("X-Accel-Buffering", "no");
        headers.setCacheControl("no-cache");
        return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
    }

    private String frame(Map<String, String> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (Exception e) {
            return "{\"error\":\"" + ERROR_MESSAGE + "\"}";
        }
    }

    /** 请求体：{@code topic} 1-200 字（trim 后），{@code duration} 30-300 秒、默认 60（镜像 legacy zod schema）。 */
    public record ComedyRequest(
            String topic,
            Integer duration,
            String targetPlatform,
            Boolean taskMode,
            UUID contextSnapshotId) {
        public ComedyRequest {
            topic = topic == null ? "" : topic.trim();
            targetPlatform = optionalTrimmed(targetPlatform);
            if (topic.isEmpty() || topic.length() > 200) {
                throw new IllegalArgumentException("题材需为 1-200 字");
            }
            if (duration == null) {
                duration = 60;
            }
            if (duration < 30 || duration > 300) {
                throw new IllegalArgumentException("时长需为 30-300 秒");
            }
            if (Boolean.TRUE.equals(taskMode) && targetPlatform == null) {
                throw new IllegalArgumentException("任务创作必须指定目标平台");
            }
            if (!Boolean.TRUE.equals(taskMode) && contextSnapshotId != null) {
                throw new IllegalArgumentException("独立创作不能绑定任务上下文快照");
            }
        }

        boolean isTaskMode() {
            return Boolean.TRUE.equals(taskMode);
        }

        private static String optionalTrimmed(String value) {
            if (value == null) return null;
            String result = value.trim();
            return result.isEmpty() ? null : result;
        }
    }
}
