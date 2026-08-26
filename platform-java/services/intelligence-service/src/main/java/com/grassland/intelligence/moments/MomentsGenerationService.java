package com.grassland.intelligence.moments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
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

    private final FrozenTextExecutionService frozenText;
    private final com.grassland.intelligence.creationlineage.TextCreationLineageService lineage;
    private final ObjectMapper mapper = new ObjectMapper();

    public MomentsGenerationService(FrozenTextExecutionService frozenText,
            com.grassland.intelligence.creationlineage.TextCreationLineageService lineage) {
        this.frozenText = frozenText;
        this.lineage = lineage;
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

    /**
     * 独立模式生成（GL-P3-AI-001 尾巴清偿）：经 {@link FrozenTextExecutionService#executeIndependent}
     * 单环执行（预算闸/ai_run 留痕/积分闭环/失败退款一套机器）。聚合型产出——执行完成后再发 SSE
     * （progress/result 帧），扣费/预算拒绝（402）以 JSON 先于 SSE，与任务模式同契约。
     */
    public Mono<Flux<String>> generateStream(List<String> dataUrls, MomentsStyle style, String topic,
            String feelings, String accountId, String organizationId, ServerWebExchange exchange) {
        return frozenText.executeIndependent(
                        exchange,
                        List.of(
                                MomentsPrompts.system(style, dataUrls.size()),
                                userMessage(dataUrls, topic, feelings)),
                        2048, CreditFeature.MOMENTS_GENERATION,
                        completion -> parseResult(completion.content()))
                .map(trace -> Flux.concat(
                        Mono.just(progressFrame()),
                        Mono.just(resultFrame(trace.value())),
                        // 任务书 #44 登记扩展：朋友圈文案产出落 lineage（run/provider/model 来自执行环）
                        lineage.recordAdvisory(lineageCommand(
                                com.grassland.intelligence.creationlineage.CreationGeneration.Mode.INDEPENDENT,
                                null, trace.runId(), trace, style, topic, feelings,
                                dataUrls.size(), trace.value(), accountId, organizationId))
                                .then(Mono.<String>empty())));
    }

    /** 任务模式生成：冻结 AI 配置 + 冻结任务上下文，积分经 AiExecutionService 闭环。 */
    public Flux<String> generateTask(List<String> dataUrls, MomentsStyle style, String topic, String feelings,
                                     MomentsTaskCreationContext.Binding binding, ServerWebExchange exchange) {
        return Flux.defer(() -> Flux.concat(
                Mono.just(progressFrame()),
                frozenText.executeTraced(
                                exchange, binding.snapshotId(),
                                List.of(
                                        binding.promptContext(),
                                        MomentsPrompts.system(style, dataUrls.size()),
                                        userMessage(dataUrls, topic, feelings)),
                                2048, CreditFeature.MOMENTS_GENERATION,
                                completion -> parseResult(completion.content()))
                        .flatMapMany(trace -> Flux.just(resultFrame(trace.value()))
                                // 任务书 #44 登记扩展：朋友圈文案产出落 lineage（run/provider/model 来自执行环）
                                .concatWith(lineage.recordAdvisory(lineageCommand(
                                        com.grassland.intelligence.creationlineage.CreationGeneration.Mode.TASK,
                                        binding.snapshotId(), trace.runId(), trace, style, topic, feelings,
                                        dataUrls.size(), trace.value(),
                                        binding.snapshot().accountId(), binding.snapshot().organizationId()))
                                        .then(Mono.<String>empty())))));
    }

    /** lineage 命令（任务书 #44）：result 记 copy 全文（朋友圈文案即产出物本体）与配图建议数。 */
    private com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command lineageCommand(
            com.grassland.intelligence.creationlineage.CreationGeneration.Mode mode,
            java.util.UUID snapshotId, java.util.UUID runId,
            com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<MomentsResult> trace,
            MomentsStyle style, String topic, String feelings, int imageCount, MomentsResult result,
            String accountId, String organizationId) {
        String provider = trace == null
                ? com.grassland.intelligence.creationlineage.TextCreationLineageService.INDEPENDENT_PROVIDER
                : trace.provider();
        String model = trace == null ? lineage.independentModel() : trace.model();
        return new com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command(
                com.grassland.intelligence.creationlineage.CreationGeneration.Kind.MOMENTS_COPY,
                mode, snapshotId, runId,
                trace != null && trace.byok()
                        ? com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.BYOK
                        : com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.PLATFORM,
                provider, model, trace == null ? null : trace.platformModelVersion(), null,
                "风格：" + style.key() + "；主题：" + topic + (feelings == null ? "" : "；感受：" + feelings),
                Map.of("style", style.key(), "topic", topic,
                        "feelings", feelings == null ? "" : feelings, "imageCount", imageCount),
                List.of(),
                result == null ? Map.of() : Map.of(
                        "copy", result.copy() == null ? "" : result.copy(),
                        "imageOrderCount", result.imageOrder().size(),
                        "captionCount", result.captions().size()),
                List.of(), accountId, organizationId);
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
