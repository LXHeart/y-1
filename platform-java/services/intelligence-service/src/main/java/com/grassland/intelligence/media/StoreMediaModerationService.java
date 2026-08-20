package com.grassland.intelligence.media;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 门店公开媒体多模态审核（缺口清偿之五，#42 D9 登记）：store_media 用途图片在 confirm
 * 服务端字节可得时送视觉模型审一次，结论落 {@code store_media_moderation}。
 *
 * <p>姿态对齐内容安全 ADR-D16 D6 advisory：审核模型未配置/调用失败/输出不可解析为 verdict
 * 语义时<b>不阻断上传与公开展示</b>——无行=未审；显式 {@code blocked} 才被公开端点过滤。
 * 输出不可解析不伪装成通过：记 {@code review}（待人工复核）+ 说明性 finding。
 * 视频本期不送审（无帧抽取设施），维持无行状态。
 */
@Component
public class StoreMediaModerationService {

    private static final Logger log = LoggerFactory.getLogger(StoreMediaModerationService.class);
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    private final AiCapabilityAdapter ai;
    private final StoreMediaModerationRepository moderation;
    private final String provider;
    private final Duration timeout;

    public StoreMediaModerationService(
            AiCapabilityAdapter ai,
            StoreMediaModerationRepository moderation,
            Environment environment) {
        this.ai = ai;
        this.moderation = moderation;
        this.provider = environment.getProperty("ai.store-media-moderation.provider", "qwen");
        long timeoutMs = environment.getProperty("ai.store-media-moderation.timeout-ms", Long.class, 30_000L);
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 120_000)));
    }

    /** 审核结论：status ∈ pass/review/blocked（blocked = 公开展示拦截）。 */
    public record Verdict(String status, List<Finding> findings, String model, String runId) {
        public record Finding(String category, String severity, String advice) {}
    }

    /**
     * confirm 后审核一次（已有结论不重跑）。{@code bytes} 为惰性对象字节拉取——仅在确实要送审
     * （store_media 图片且无既有结论）时才订阅。返回落库后的行；未审（视频/未配置/失败）返回 empty。
     */
    public Mono<StoreMediaModerationRepository.ModerationRow> moderateOnce(
            MediaReference ref, Mono<byte[]> bytes) {
        if (!"store_media".equals(ref.purpose()) || !isSupportedImage(ref.mimeType())) {
            return Mono.empty();
        }
        return moderation.exists(ref.id()).flatMap(exists -> exists
                ? moderation.find(ref.id())
                : bytes.flatMap(value -> runModeration(ref, value)));
    }

    private Mono<StoreMediaModerationRepository.ModerationRow> runModeration(MediaReference ref, byte[] bytes) {
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.empty();
        }
        String dataUrl = "data:" + ref.mimeType() + ";base64," + BASE64.encodeToString(bytes);
        List<ContentPart> parts = List.of(
                ContentPart.image(dataUrl),
                ContentPart.text(rubric()));
        return ai.completeMultimodalMeta(parts, timeout)
                .map(meta -> parseVerdict(meta.content(), meta.runId()))
                .onErrorResume(error -> {
                    log.warn("store media moderation unavailable mediaId={}", ref.id(), error);
                    return Mono.empty();
                })
                .flatMap(verdict -> persist(ref, verdict));
    }

    private Mono<StoreMediaModerationRepository.ModerationRow> persist(
            MediaReference ref, Verdict verdict) {
        String findingsJson = findingsJson(verdict.findings());
        return moderation.upsert(new StoreMediaModerationRepository.ModerationRow(
                ref.id(), verdict.status(), findingsJson, verdict.model(), verdict.runId(), Instant.now()));
    }

    private static boolean isSupportedImage(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png", "image/jpeg", "image/webp" -> true;
            default -> false;
        };
    }

    /** verdict JSON 解析；不可解析 → review + 说明 finding（不把「读不懂」伪装成通过）。 */
    static Verdict parseVerdict(String content, String runId) {
        try {
            String stripped = stripCodeFence(content == null ? "" : content);
            var root = MAPPER.readTree(stripped);
            String verdict = root.path("verdict").asText("").trim().toLowerCase(Locale.ROOT);
            if (!verdict.equals("pass") && !verdict.equals("review") && !verdict.equals("blocked")) {
                return unparseable(runId);
            }
            List<Verdict.Finding> findings = new ArrayList<>();
            var findingsNode = root.path("findings");
            if (findingsNode.isArray()) {
                for (var node : findingsNode) {
                    String category = node.path("category").asText("").trim();
                    if (category.isEmpty()) {
                        continue;
                    }
                    findings.add(new Verdict.Finding(
                            category,
                            node.path("severity").asText("medium"),
                            node.path("advice").asText("")));
                }
            }
            return new Verdict(verdict, List.copyOf(findings), null, runId);
        } catch (Exception error) {
            log.warn("store media moderation output unparseable", error);
            return unparseable(runId);
        }
    }

    private static Verdict unparseable(String runId) {
        return new Verdict("review",
                List.of(new Verdict.Finding("unparseable", "medium", "审核模型输出不可解析，转人工复核")),
                null, runId);
    }

    private static String findingsJson(List<Verdict.Finding> findings) {
        try {
            var array = MAPPER.createArrayNode();
            for (Verdict.Finding finding : findings) {
                var node = array.addObject();
                node.put("category", finding.category());
                node.put("severity", finding.severity());
                node.put("advice", finding.advice());
            }
            return MAPPER.writeValueAsString(array);
        } catch (Exception error) {
            return "[]";
        }
    }

    private static String stripCodeFence(String raw) {
        String stripped = raw.trim();
        if (stripped.startsWith("```")) {
            int start = stripped.indexOf('\n');
            int end = stripped.lastIndexOf("```");
            if (start >= 0 && end > start) {
                stripped = stripped.substring(start + 1, end).trim();
            }
        }
        return stripped;
    }

    static String rubric() {
        return """
                你是门店公开媒体内容安全审核员。门店会在公开页面展示这张图片（门面/环境/菜单/宣传图），\
                检查画面是否存在以下问题（仅凭画面明确证据判定，不确定不算）：
                - pornographic：色情低俗内容
                - violence：暴力血腥内容
                - illegal_goods：违禁品或违法经营暗示
                - misleading：画面文字含广告法极限词/虚假宣传（如「全网最好」「第一」）
                - minor_safety：未成年人不当呈现
                - diversion：站外导流（明显二维码/联系方式招揽私域流量）
                verdict 判定：无问题=pass；仅轻微/存疑=review；明确违规=blocked。仅返回 JSON：
                {"verdict":"pass|review|blocked","findings":[{"category":"...","severity":"high|medium|low",\
                "advice":"20 字以内说明"}]}""";
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
