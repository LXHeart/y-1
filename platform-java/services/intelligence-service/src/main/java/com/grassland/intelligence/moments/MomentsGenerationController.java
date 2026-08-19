package com.grassland.intelligence.moments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 朋友圈内容生成 API（PRD §4.4 朋友圈图片+文字）：{@code POST /api/moments-generation/generate}。
 *
 * <p>SSE 非帧流（镜像 image-analysis）：progress/result/error 判别帧 + [DONE]。校验、鉴权与扣分
 * 都在 SSE headers 之前（400/401/402 JSON）；上游失败在流内先退款再发 error 帧（GL-P0-BILL-002）。
 * 任务模式经 {@link MomentsTaskCreationContext} 绑定冻结上下文，积分由冻结执行闭环。
 */
@RestController
@RequestMapping("/api/moments-generation")
public class MomentsGenerationController {

    static final String ERROR_MESSAGE = "朋友圈内容生成失败";

    private final IntelligenceCallerResolver callers;
    private final MomentsGenerationService service;
    private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;
    private final CreditsClient credits;
    private final MomentsTaskCreationContext contexts;
    private final ObjectMapper mapper = new ObjectMapper();

    public MomentsGenerationController(
            IntelligenceCallerResolver callers, MomentsGenerationService service,
            CreditsClient credits, MomentsTaskCreationContext contexts,
            com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
        this.callers = callers;
        this.service = service;
        this.safety = safety;
        this.credits = credits;
        this.contexts = contexts;
    }

    @PostMapping("/generate")
    public Mono<ResponseEntity<Flux<DataBuffer>>> generate(@RequestBody MomentsRequest body,
                                                           ServerWebExchange exchange) {
        MomentsStyle style = MomentsStyle.fromKey(body.style());
        if (body.isTaskMode()) {
            return callers.requireUser(exchange.getRequest())
                    .flatMap(caller -> contexts.bind(body.contextSnapshotId(), caller.accountId()))
                    .flatMap(binding -> validatedImages(body)
                            .map(dataUrls -> sseEntity(
                                    withSafety(exchange, service.generateTask(dataUrls, style, body.topic(),
                                                    body.feelings(), binding, exchange)
                                            .onErrorResume(e -> Flux.just(errorFrame())), binding.snapshot()),
                                    exchange)))
                    .onErrorMap(error -> error instanceof IntelligenceException
                            ? error : new IntelligenceException(502, ERROR_MESSAGE));
        }
        return validatedImages(body)
                .flatMap(dataUrls -> callers.resolve(exchange.getRequest())
                        .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.MOMENTS_GENERATION))
                        .map(charge -> sseEntity(
                                withSafety(exchange, service.generate(dataUrls, style, body.topic(), body.feelings())
                                        // 上游失败：先退回已扣积分再发 error 帧（GL-P0-BILL-002）
                                        .onErrorResume(e -> credits.refund(charge, "朋友圈内容生成失败自动退回")
                                                .thenMany(Flux.just(errorFrame()))), null),
                                exchange)));
    }

    /** 任务书 #34 D8：朋友圈文案流尾追加安全检查帧（检查文本=result 帧 copy）。 */
    private Flux<String> withSafety(
            ServerWebExchange exchange, Flux<String> frames,
            com.grassland.intelligence.creationcontext.CreationContextSnapshot snapshot) {
        return safety.appendSafetyFrame(exchange, frames,
                com.grassland.intelligence.contentsafety.ContentSafetyService.momentsCopyExtractor(),
                snapshot == null ? "moments" : snapshot.platformId(),
                com.grassland.intelligence.contentsafety.ContentSafetyService.industryFromSnapshot(snapshot),
                com.grassland.intelligence.contentsafety.ContentSafetyService.generationContext(snapshot));
    }

    /** 素材图 base64 解码与 magic 校验留在 boundedElastic（解码 9×5MB 不占事件循环）。 */
    private Mono<List<String>> validatedImages(MomentsRequest body) {
        return Mono.fromCallable(() -> service.validateAndEncode(body.images()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
        Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.set("X-Accel-Buffering", "no");
        headers.setCacheControl("no-cache");
        return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
    }

    private String errorFrame() {
        try {
            return mapper.writeValueAsString(Map.of("type", "error", "error", ERROR_MESSAGE));
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"" + ERROR_MESSAGE + "\"}";
        }
    }

    /**
     * 请求体：{@code topic} 1-500 字必填；{@code style} 四选一必填；{@code feelings} ≤200 字选填；
     * {@code images} base64 素材图 0-9 张（data: URI 白名单 MIME 或裸 base64 默认 JPEG）。
     */
    public record MomentsRequest(
            String topic, String style, String feelings, List<String> images,
            Boolean taskMode, UUID contextSnapshotId) {
        public MomentsRequest {
            topic = topic == null ? "" : topic.trim();
            if (topic.isEmpty() || topic.length() > 500) {
                throw new IllegalArgumentException("主题需为 1-500 字");
            }
            MomentsStyle.fromKey(style);
            style = style.trim();
            feelings = feelings == null || feelings.isBlank() ? null : feelings.trim();
            if (feelings != null && feelings.length() > 200) {
                throw new IllegalArgumentException("补充感受不能超过 200 字");
            }
            if (images != null && images.size() > 9) {
                throw new IllegalArgumentException("最多上传 9 张图片");
            }
            boolean task = Boolean.TRUE.equals(taskMode);
            if (task && contextSnapshotId == null) {
                throw new IllegalArgumentException("任务创作必须绑定创作上下文快照");
            }
            if (!task && contextSnapshotId != null) {
                throw new IllegalArgumentException("独立创作不能绑定任务上下文快照");
            }
            taskMode = task;
        }

        boolean isTaskMode() {
            return Boolean.TRUE.equals(taskMode);
        }
    }
}
