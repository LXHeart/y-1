package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * 视频制作脚本生成（草场 intelligence Slice 4）——首个多模态业务模块。
 * 脚本 SSE 与 provider-neutral 异步视频任务入口。
 */
@RestController
public class VideoProductionController {

    private static final Set<String> INDUSTRY_TYPES = Set.of("餐饮", "零售", "美业", "健身", "教育培训", "其他");
    private static final Set<String> VIDEO_STYLES = Set.of("烟火纪实", "治愈清新", "高级暗调", "数字人口播", "复古胶片");
    private static final String ERROR_MESSAGE = "视频脚本生成失败";

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;
    private final VideoGenerationService video;
    private final VideoGenerationProperties videoProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoProductionController(
            IntelligenceCallerResolver callers,
            AiCapabilityAdapter ai,
            CreditsClient credits, VideoGenerationService video, VideoGenerationProperties videoProperties) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
        this.video = video;
        this.videoProperties = videoProperties;
    }

    @PostMapping("/api/video-production/generate-video")
    public Mono<ResponseEntity<Map<String,Object>>> generateVideo(@RequestBody VideoGenerationService.VideoRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest()).flatMap(c -> video.create(c.accountId(), c.organizationId(), body))
                .map(job -> ResponseEntity.accepted().body(envelope(job)));
    }

    @GetMapping("/api/video-production/jobs/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> getVideo(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest()).flatMap(c -> video.get(id, c.accountId()))
                .map(job -> ResponseEntity.ok(envelope(job))).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/video-production/jobs")
    public Mono<ResponseEntity<List<Map<String,Object>>>> listVideo(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest()).flatMapMany(c -> video.list(c.accountId()).map(VideoProductionController::snapshot)).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/api/video-production/jobs/{id}/cancel")
    public Mono<ResponseEntity<Map<String,Object>>> cancelVideo(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest()).flatMap(c -> video.cancel(id, c.accountId())).map(ok -> ok ? ResponseEntity.ok(Map.of("success", true)) : ResponseEntity.notFound().build());
    }

    @GetMapping("/api/video-production/capabilities")
    public ResponseEntity<Map<String,Object>> capabilities() {
        return ResponseEntity.ok(Map.of("provider", videoProperties.getMode(), "model", videoProperties.getModel(), "available", videoProperties.available(), "reason", videoProperties.unavailableReason() == null ? "" : videoProperties.unavailableReason()));
    }
    private static Map<String,Object> envelope(VideoGenerationJob j) { return Map.of("success", true, "data", snapshot(j)); }
    private static Map<String,Object> snapshot(VideoGenerationJob j) {
        Map<String,Object> m = new LinkedHashMap<>(); m.put("id", j.id()); m.put("status", j.status()); m.put("progress", j.progress()); m.put("provider", j.provider()); m.put("model", j.model()); m.put("resultUrl", j.resultUrl()); m.put("actualDurationSeconds", j.actualDurationSeconds()); m.put("actualCostCents", j.actualCostCents()); m.put("errorMessage", j.errorMessage()); return m;
    }

    @PostMapping("/api/video-production/generate-script")
    public Mono<ResponseEntity<Flux<DataBuffer>>> generateScript(
            @RequestBody ScriptRequest body,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.VIDEO_PRODUCTION_SCRIPT))
                .map(charge -> {
                    Flux<String> payloads = ai.startTextRun(new TextRunCommand(List.of(
                                    VideoScriptPrompts.system(body.videoStyle(), body.industryType()),
                                    VideoScriptPrompts.user(body))))
                            .map(chunk -> frame(Map.of("content", chunk.content())))
                            // 上游失败：先退回已扣积分再发 error 帧（GL-P0-BILL-002）
                            .onErrorResume(error -> credits.refund(charge, "视频脚本生成失败自动退回")
                                    .thenMany(Flux.just(frame(Map.of("error", ERROR_MESSAGE)))));
                    Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.TEXT_EVENT_STREAM);
                    headers.set("X-Accel-Buffering", "no");
                    headers.setCacheControl("no-cache");
                    return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
                });
    }

    private String frame(Map<String, String> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (Exception error) {
            return "{\"error\":\"" + ERROR_MESSAGE + "\"}";
        }
    }

    /** 请求校验镜像 legacy zod：images 1-9，店铺/可选字段长度限制，行业/风格白名单。 */
    public record ScriptRequest(
            List<String> images,
            String shopName,
            String industryType,
            String shopAddress,
            String shopDescription,
            String videoStyle,
            String customPrompt) {

        public ScriptRequest {
            images = images == null ? List.of() : List.copyOf(images);
            shopName = trimmed(shopName);
            industryType = trimmed(industryType);
            shopAddress = optionalTrimmed(shopAddress);
            shopDescription = optionalTrimmed(shopDescription);
            videoStyle = trimmed(videoStyle);
            customPrompt = optionalTrimmed(customPrompt);

            if (images.isEmpty() || images.size() > 9 || images.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("请上传 1-9 张有效图片");
            }
            if (shopName.isEmpty() || shopName.length() > 100) {
                throw new IllegalArgumentException("店铺名称需为 1-100 字");
            }
            if (!INDUSTRY_TYPES.contains(industryType)) {
                throw new IllegalArgumentException("请选择行业类型");
            }
            if (shopAddress != null && shopAddress.length() > 200) {
                throw new IllegalArgumentException("店铺地址最多 200 字");
            }
            if (shopDescription != null && shopDescription.length() > 500) {
                throw new IllegalArgumentException("店铺描述最多 500 字");
            }
            if (!VIDEO_STYLES.contains(videoStyle)) {
                throw new IllegalArgumentException("请选择视频风格");
            }
            if (customPrompt != null && customPrompt.length() > 500) {
                throw new IllegalArgumentException("用户要求最多 500 字");
            }
        }

        private static String trimmed(String value) {
            return value == null ? "" : value.trim();
        }

        private static String optionalTrimmed(String value) {
            if (value == null) {
                return null;
            }
            String result = value.trim();
            return result.isEmpty() ? null : result;
        }
    }
}
