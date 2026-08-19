package com.grassland.intelligence.videostudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 视频工坊端点（任务书 #43 Stage 1）。
 *
 * <p>当前仅 BGM 节奏建议：{@code POST /api/video-studio/bgm-advice}。
 * 扣 1 积分（{@link CreditFeature#VIDEO_STUDIO_BGM}），聚合流式输出后解析 JSON，
 * 失败→502 + 退款（GL-P0-BILL-002）。
 */
@RestController
@RequestMapping("/api/video-studio")
public final class VideoStudioController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;

    public VideoStudioController(
            IntelligenceCallerResolver callers, AiCapabilityAdapter ai, CreditsClient credits) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
    }

    @PostMapping("/bgm-advice")
    public Mono<ResponseEntity<Map<String, Object>>> bgmAdvice(
            @RequestBody BgmAdviceRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.VIDEO_STUDIO_BGM))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(List.of(
                                ChatMessage.system(BgmAdvicePrompts.SYSTEM),
                                ChatMessage.user(BgmAdvicePrompts.user(
                                        body.platform(), body.contentForm(), body.topic(),
                                        body.durationSeconds(), body.moodHint())))))
                        .map(chunk -> chunk.content())
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(raw -> parseBgmResult(stripCodeFence(raw)))
                        .onErrorResume(error -> credits.refund(charge, "BGM 建议生成失败自动退回")
                                .then(Mono.error(sanitize(error)))))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    /** 上游任意失败收敛为 502 固定中文文案（不外泄内部异常细节），已带状态的域错误原样透传。 */
    private static Throwable sanitize(Throwable error) {
        if (error instanceof IntelligenceException) {
            return error;
        }
        return new IntelligenceException(502, "建议生成失败，请稍后重试");
    }

    // ---- JSON 解析 ----

    private static Map<String, Object> parseBgmResult(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IntelligenceException(502, "建议生成失败，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();

        // moodDirection
        JsonNode mood = root.path("moodDirection");
        Map<String, String> moodDirection = new LinkedHashMap<>();
        moodDirection.put("label", mood.path("label").asText(""));
        moodDirection.put("reason", mood.path("reason").asText(""));
        moodDirection.put("referenceStyle", mood.path("referenceStyle").asText(""));
        result.put("moodDirection", moodDirection);

        // rhythm: 2..6 项
        JsonNode rhythmNode = root.path("rhythm");
        List<Map<String, Object>> rhythm = new ArrayList<>();
        if (rhythmNode.isArray()) {
            int limit = Math.min(rhythmNode.size(), 6);
            for (int i = 0; i < limit; i++) {
                JsonNode item = rhythmNode.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timeRange", item.path("timeRange").asText(""));
                entry.put("intensity", Math.max(1, Math.min(5, item.path("intensity").asInt(3))));
                entry.put("suggestion", item.path("suggestion").asText(""));
                rhythm.add(entry);
            }
        }
        result.put("rhythm", rhythm);

        // syncPoints: 0..8 项
        JsonNode syncNode = root.path("syncPoints");
        List<Map<String, Object>> syncPoints = new ArrayList<>();
        if (syncNode.isArray()) {
            int limit = Math.min(syncNode.size(), 8);
            for (int i = 0; i < limit; i++) {
                JsonNode item = syncNode.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("atSeconds", item.path("atSeconds").asDouble(0));
                entry.put("suggestion", item.path("suggestion").asText(""));
                syncPoints.add(entry);
            }
        }
        result.put("syncPoints", syncPoints);

        // cautions
        JsonNode cautionsNode = root.path("cautions");
        List<String> cautions = new ArrayList<>();
        if (cautionsNode.isArray()) {
            for (JsonNode c : cautionsNode) {
                cautions.add(c.asText(""));
            }
        }
        result.put("cautions", cautions);

        return result;
    }

    private static String stripCodeFence(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    // ---- 请求体 ----

    public record BgmAdviceRequest(
            String platform, String contentForm, String topic,
            Integer durationSeconds, String moodHint) {
        public BgmAdviceRequest {
            platform = platform == null ? "douyin" : platform.trim();
            contentForm = contentForm == null ? "口播" : contentForm.trim();
            topic = topic == null ? "" : topic.trim();
            if (!BgmAdvicePrompts.PLATFORMS.contains(platform)) {
                throw new IllegalArgumentException("平台无效");
            }
            if (!BgmAdvicePrompts.CONTENT_FORMS.contains(contentForm)) {
                throw new IllegalArgumentException("内容形式无效");
            }
            if (topic.isEmpty() || topic.length() > 200) {
                throw new IllegalArgumentException("主题需为 1-200 字");
            }
            if (durationSeconds == null) {
                durationSeconds = 60;
            }
            if (durationSeconds < 5 || durationSeconds > 600) {
                throw new IllegalArgumentException("时长需为 5-600 秒");
            }
            if (moodHint != null && moodHint.length() > 100) {
                throw new IllegalArgumentException("情绪倾向过长");
            }
        }
    }
}
