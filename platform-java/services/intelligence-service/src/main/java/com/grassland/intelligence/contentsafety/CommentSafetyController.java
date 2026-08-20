package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 评论类互动的 L1 词库审核内部端点（缺口清偿之九）：{@code POST /internal/content-safety/comment-check}。
 * 仅 marketplace 服务断言可调（不进 edge RouteManifest，外部不可达）。
 *
 * <p>只跑词库层（{@link ContentSafetyChecker#checkCached}）——提交是低频高敏操作，L1 + 商家人审
 * 截图足够；LLM 深检留给生成流。blocked 语义：存在 severity=high 命中；low/medium 命中不拦
 * （advisory，ADR-D16 D6）。advisory 明细（category/severity/advice）随行返回——marketplace
 * 据此落人工复核队列（之九遗留清偿）；matched 词不下发（明细够排序与展示，减少敏感词流转）。
 */
@RestController
public class CommentSafetyController {

    /** 与 marketplace 提交契约同限（CreateSubmissionRequest.MAX_COMMENT_TEXT）。 */
    private static final int MAX_TEXT_CHARS = 500;

    private final IntelligenceCallerResolver callers;
    private final ContentSafetyService safety;

    public CommentSafetyController(IntelligenceCallerResolver callers, ContentSafetyService safety) {
        this.callers = callers;
        this.safety = safety;
    }

    @PostMapping("/internal/content-safety/comment-check")
    public Mono<Map<String, Object>> check(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        Object raw = body == null ? null : body.get("text");
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        String normalized = text.trim();
        if (normalized.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("text 最长 " + MAX_TEXT_CHARS + " 字");
        }
        return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
                .map(caller -> {
                    SafetyReport report = safety.checkShallow(normalized);
                    boolean blocked = report.findings().stream()
                            .anyMatch(finding -> "high".equalsIgnoreCase(finding.severity()));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("blocked", blocked);
                    data.put("findings", report.findings().size());
                    data.put("lexiconVersion", report.lexiconVersion());
                    data.put("details", report.findings().stream()
                            .map(finding -> {
                                Map<String, Object> detail = new LinkedHashMap<>();
                                detail.put("category", finding.category());
                                detail.put("severity", finding.severity());
                                detail.put("advice", finding.advice());
                                return (Map<String, Object>) detail;
                            })
                            .toList());
                    return Map.<String, Object>of("success", true, "data", data);
                });
    }

    /** text 契约错误自含映射 400（内部端点不依赖全局 advice 的完整装配）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 鉴权/断言失败自含映射（401/403 语义随 IntelligenceException.status）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.grassland.intelligence.security.IntelligenceException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleIntelligence(
            com.grassland.intelligence.security.IntelligenceException error) {
        return org.springframework.http.ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }
}
