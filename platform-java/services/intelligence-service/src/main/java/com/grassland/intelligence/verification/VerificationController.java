package com.grassland.intelligence.verification;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 履约 AI 视觉核验服务间断点（草场 Slice 11 Verification Stage 3）。
 *
 * <p>{@code POST /api/verification/analyze} 仅 marketplace 服务 principal 可调（镜像
 * {@code MediaController.serviceMetadata} 的 service gate）——商家触发履约核验时，marketplace 作为
 * 履约权威，以服务断言跨服务提交待核验附件 media id 列表 + 任务上下文，本服务内部自读附件字节做 Qwen 视觉判断。
 * 不对浏览器/终端用户开放（用户/非 marketplace 服务断言 → 403；缺断言 → 401）。
 *
 * <p>装配门控同 {@code MediaController}：{@code object-storage.enabled=true} 时才装配（依赖
 * {@link com.grassland.storage.ObjectStorageAdapter}）。
 */
@RestController
@RequestMapping("/api/verification")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class VerificationController {

    private static final int MAX_MEDIA = 20;
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_PLATFORM = 64;

    private final IntelligenceCallerResolver callers;
    private final VerificationAnalysisService analysis;

    public VerificationController(IntelligenceCallerResolver callers, VerificationAnalysisService analysis) {
        this.callers = callers;
        this.analysis = analysis;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> analyze(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> analysis.analyze(parse(body)))
                .map(VerificationController::success);
    }

    private static VerificationAnalysisRequest parse(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("核验请求不能为空");
        }
        List<String> rawIds = requireStringList(body, "mediaIds");
        if (rawIds.isEmpty() || rawIds.size() > MAX_MEDIA) {
            throw new IllegalArgumentException("mediaIds 数量需为 1-" + MAX_MEDIA);
        }
        List<UUID> mediaIds = new ArrayList<>(rawIds.size());
        for (String raw : rawIds) {
            try {
                mediaIds.add(UUID.fromString(raw.trim()));
            } catch (Exception ignored) {
                throw new IllegalArgumentException("mediaIds 含无效 id");
            }
        }
        String taskTitle = requireString(body, "taskTitle", MAX_TITLE, "taskTitle");
        String taskDescription = optionalString(body, "taskDescription", MAX_DESCRIPTION);
        String platform = optionalString(body, "platform", MAX_PLATFORM);
        return new VerificationAnalysisRequest(List.copyOf(mediaIds), taskTitle, taskDescription, platform);
    }

    @SuppressWarnings("unchecked")
    private static List<String> requireStringList(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("请求参数无效");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("请求参数无效");
            }
            result.add(string.trim());
        }
        return result;
    }

    private static String requireString(Map<String, Object> body, String field, int max, String label) {
        Object value = body.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("请求参数无效");
        }
        String trimmed = string.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + " 过长");
        }
        return trimmed;
    }

    private static String optionalString(Map<String, Object> body, String field, int max) {
        if (!body.containsKey(field)) {
            return null;
        }
        Object value = body.get(field);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("请求参数无效");
        }
        String trimmed = string.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("请求参数过长");
        }
        return trimmed;
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }
}
