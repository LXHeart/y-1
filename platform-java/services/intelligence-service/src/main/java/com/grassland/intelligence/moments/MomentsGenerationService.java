package com.grassland.intelligence.moments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.moments.MomentsGenerationService.Caption;
import com.grassland.intelligence.moments.MomentsGenerationService.MomentsResult;
import com.grassland.intelligence.moments.MomentsGenerationService.OrderSuggestion;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 朋友圈内容生成编排（PRD §4.4 朋友圈图片+文字）。一次多模态（或纯文本）调用产出
 * 结构化 JSON 结果，以非 token SSE 帧（progress/result）下发，镜像 image-analysis 范式。
 *
 * <p>任务模式经 {@link FrozenTextExecutionService} 使用冻结的 AI 配置并内置积分扣退；
 * 独立模式的积分扣退由 controller 编排（comedy 范式）。
 */
@Component
public class MomentsGenerationService {

    static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(180);
    static final int MAX_IMAGES = 9;
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    private final AiCapabilityAdapter ai;
    private final FrozenTextExecutionService frozenText;
    private final ObjectMapper mapper = new ObjectMapper();

    public MomentsGenerationService(AiCapabilityAdapter ai, FrozenTextExecutionService frozenText) {
        this.ai = ai;
        this.frozenText = frozenText;
    }

    /**
     * 校验 base64 素材图（0-9 张）：data: URI 白名单 MIME 或裸 base64（默认 JPEG）、
     * 单张 ≤5MB、magic byte 与声明类型一致；返回可直接进多模态消息的 data URL 列表。
     */
    public List<String> validateAndEncode(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > MAX_IMAGES) {
            throw new IntelligenceException(400, "最多上传 9 张图片");
        }
        List<String> dataUrls = new ArrayList<>(images.size());
        for (String image : images) {
            String mime;
            String payload;
            if (image != null && image.startsWith("data:")) {
                int comma = image.indexOf(',');
                if (comma < 0) {
                    throw new IntelligenceException(400, "图片文件内容与类型不匹配");
                }
                String header = image.substring(5, comma);
                int semicolon = header.indexOf(';');
                mime = semicolon < 0 ? header : header.substring(0, semicolon);
                if (!ALLOWED_MIME.contains(mime)) {
                    throw new IntelligenceException(400, "仅支持 JPG、PNG、WebP 图片");
                }
                payload = image.substring(comma + 1);
            } else {
                mime = "image/jpeg";
                payload = image == null ? "" : image;
            }
            byte[] bytes = decodeBase64(payload);
            if (bytes == null) {
                throw new IntelligenceException(400, "图片文件内容与类型不匹配");
            }
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new IntelligenceException(400, "单张图片不能超过 5 MB");
            }
            if (!matchesSignature(mime, bytes)) {
                throw new IntelligenceException(400, "图片文件内容与类型不匹配");
            }
            dataUrls.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        return List.copyOf(dataUrls);
    }

    /** 独立模式生成 → 事件流（progress/result；上游失败以 onError 信号抛出，由 controller 退款并转 error 帧）。 */
    public Flux<String> generate(List<String> dataUrls, MomentsStyle style, String topic, String feelings) {
        return Flux.defer(() -> Flux.concat(
                Mono.just(progressFrame()),
                ai.completeText(new TextCompletionCommand(
                                List.of(
                                        MomentsPrompts.system(style, dataUrls.size()),
                                        userMessage(dataUrls, topic, feelings)),
                                "朋友圈内容生成失败", GENERATION_TIMEOUT))
                        .map(this::parseResult)
                        .map(MomentsGenerationService::resultFrame)));
    }

    /** 任务模式生成：冻结 AI 配置 + 冻结任务上下文，积分经 AiExecutionService 闭环。 */
    public Flux<String> generateTask(List<String> dataUrls, MomentsStyle style, String topic, String feelings,
                                     MomentsTaskCreationContext.Binding binding, ServerWebExchange exchange) {
        return Flux.defer(() -> Flux.concat(
                Mono.just(progressFrame()),
                frozenText.execute(
                                exchange, binding.snapshotId(),
                                List.of(
                                        binding.promptContext(),
                                        MomentsPrompts.system(style, dataUrls.size()),
                                        userMessage(dataUrls, topic, feelings)),
                                2048, CreditFeature.MOMENTS_GENERATION,
                                completion -> parseResult(completion.content()))
                        .map(MomentsGenerationService::resultFrame)));
    }

    /** 解析模型输出：剥 code fence → JSON → copy 必填，order/captions 容忍缺失。 */
    public MomentsResult parseResult(String content) {
        String stripped = stripCodeFence(content == null ? "" : content).trim();
        JsonNode node;
        try {
            node = mapper.readTree(stripped);
        } catch (Exception e) {
            throw new IntelligenceException(502, "朋友圈内容生成服务返回了无法解析的内容");
        }
        if (!node.isObject()) {
            throw new IntelligenceException(502, "朋友圈内容生成服务返回了无法解析的内容");
        }
        String copy = optionalText(node.get("copy"));
        if (copy == null) {
            throw new IntelligenceException(502, "朋友圈内容生成服务返回了空结果");
        }
        List<OrderSuggestion> order = new ArrayList<>();
        JsonNode orderNode = node.get("imageOrder");
        if (orderNode != null && orderNode.isArray()) {
            for (JsonNode item : orderNode) {
                int index = item.path("index").asInt(0);
                String reason = optionalText(item.get("reason"));
                if (index >= 1) {
                    order.add(new OrderSuggestion(index, reason));
                }
            }
        }
        List<Caption> captions = new ArrayList<>();
        JsonNode captionsNode = node.get("captions");
        if (captionsNode != null && captionsNode.isArray()) {
            for (JsonNode item : captionsNode) {
                int index = item.path("index").asInt(0);
                String text = optionalText(item.get("text"));
                if (index >= 1 && text != null) {
                    captions.add(new Caption(index, text));
                }
            }
        }
        return new MomentsResult(copy, List.copyOf(order), List.copyOf(captions));
    }

    /** 无素材图时发纯文本 user 消息（避免退化的单 text-part 多模态消息）。 */
    private static ChatMessage userMessage(List<String> dataUrls, String topic, String feelings) {
        if (dataUrls.isEmpty()) {
            return ChatMessage.user(MomentsPrompts.user(topic, feelings));
        }
        List<ContentPart> parts = new ArrayList<>();
        dataUrls.forEach(url -> parts.add(ContentPart.image(url)));
        parts.add(ContentPart.text(MomentsPrompts.user(topic, feelings)));
        return ChatMessage.user(parts);
    }

    private static String progressFrame() {
        return "{\"type\":\"progress\",\"message\":\"正在生成朋友圈内容…\"}";
    }

    private static String resultFrame(MomentsResult result) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "result");
        frame.put("copy", result.copy());
        List<Map<String, Object>> order = new ArrayList<>();
        for (OrderSuggestion suggestion : result.imageOrder()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", suggestion.index());
            item.put("reason", suggestion.reason() == null ? "" : suggestion.reason());
            order.add(item);
        }
        frame.put("imageOrder", order);
        List<Map<String, Object>> captions = new ArrayList<>();
        for (Caption caption : result.captions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", caption.index());
            item.put("text", caption.text());
            captions.add(item);
        }
        frame.put("captions", captions);
        try {
            return new ObjectMapper().writeValueAsString(frame);
        } catch (Exception e) {
            throw new IntelligenceException(502, "朋友圈内容生成服务返回了无法解析的内容");
        }
    }

    private static byte[] decodeBase64(String payload) {
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean matchesSignature(String mime, byte[] bytes) {
        return switch (mime) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && (bytes[1] & 0xff) == 0x50
                    && (bytes[2] & 0xff) == 0x4e && (bytes[3] & 0xff) == 0x47 && (bytes[4] & 0xff) == 0x0d
                    && (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a;
            case "image/webp" -> bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF")
                    && ascii(bytes, 8, 12).equals("WEBP");
            default -> false;
        };
    }

    private static String ascii(byte[] bytes, int from, int to) {
        return new String(bytes, from, to - from, StandardCharsets.US_ASCII);
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String stripCodeFence(String text) {
        int start = text.indexOf("```");
        if (start < 0) {
            return text;
        }
        int newline = text.indexOf('\n', start);
        int contentStart = newline < 0 ? start + 3 : newline + 1;
        int end = text.lastIndexOf("```");
        if (end <= contentStart) {
            return text;
        }
        return text.substring(contentStart, end);
    }

    /** 图片顺序建议：数组第 1 位 = 建议最先发布；index 为素材图片原始序号（从 1 开始）。 */
    public record OrderSuggestion(int index, String reason) {}

    /** 单张素材图片配文。 */
    public record Caption(int index, String text) {}

    public record MomentsResult(String copy, List<OrderSuggestion> imageOrder, List<Caption> captions) {
        public MomentsResult {
            imageOrder = imageOrder == null ? List.of() : List.copyOf(imageOrder);
            captions = captions == null ? List.of() : List.copyOf(captions);
        }
    }
}
