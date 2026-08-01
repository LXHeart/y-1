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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 视频制作脚本生成（草场 intelligence Slice 4）——首个多模态业务模块。
 * 路径沿用 legacy {@code POST /api/video-production/generate-script}，前端零改动；
 * {@code generate-video} 仍留 legacy stub，edge-bff 仅精确切本端点。
 */
@RestController
public class VideoProductionController {

    private static final Set<String> INDUSTRY_TYPES = Set.of("餐饮", "零售", "美业", "健身", "教育培训", "其他");
    private static final Set<String> VIDEO_STYLES = Set.of("烟火纪实", "治愈清新", "高级暗调", "数字人口播", "复古胶片");
    private static final String ERROR_MESSAGE = "视频脚本生成失败";

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoProductionController(
            IntelligenceCallerResolver callers,
            AiCapabilityAdapter ai,
            CreditsClient credits) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
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
