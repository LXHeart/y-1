package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 履约提交文本硬门槛（ADR-D16 D6 登记项落地）：{@code POST /internal/content-safety/submission-check}。
 * 仅 marketplace 服务断言可调（不进 edge RouteManifest，外部不可达）。
 *
 * <p>与 {@link CommentSafetyController} 同姿态：只跑词库层（L1）——提交是低频高敏操作，L1 + 商家人审
 * 截图足够；blocked 语义 = 存在 severity=high 命中，low/medium 命中不拦（advisory，D6）。 一 次调用
 * 检查提交全部自由文本字段（评论文本 / 备注），逐字段返回结论——调用方据此给出字段级 400 文案与
 * advisory 留痕。matched 词不下发（明细够排序与展示，减少敏感词流转）。
 */
@RestController
public class SubmissionSafetyController {

    /** 与 marketplace 提交契约同限（CreateSubmissionRequest.MAX_COMMENT_TEXT / MAX_NOTE_TEXT）。 */
    private static final int MAX_TEXT_CHARS = 500;
    private static final int MAX_FIELDS = 8;

    private final IntelligenceCallerResolver callers;
    private final ContentSafetyService safety;

    public SubmissionSafetyController(IntelligenceCallerResolver callers, ContentSafetyService safety) {
        this.callers = callers;
        this.safety = safety;
    }

    @PostMapping("/internal/content-safety/submission-check")
    public Mono<Map<String, Object>> check(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        Map<String, String> fields = new LinkedHashMap<>();
        collectField(fields, body, "commentText", "comment");
        collectField(fields, body, "note", "note");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("至少提供一个待检查字段");
        }
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("待检查字段过多");
        }
        return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
                .map(caller -> {
                    Map<String, Object> results = new LinkedHashMap<>();
                    String lexiconVersion = null;
                    for (Map.Entry<String, String> field : fields.entrySet()) {
                        SafetyReport report = safety.checkShallow(field.getValue());
                        if (lexiconVersion == null) {
                            lexiconVersion = report.lexiconVersion();
                        }
                        results.put(field.getKey(), fieldBody(report));
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("fields", results);
                    data.put("lexiconVersion", lexiconVersion);
                    return Map.<String, Object>of("success", true, "data", data);
                });
    }

    /** 字段名 → 归一化文本；空白/null 跳过（无该字段即不检查）。超长 → 400（契约错误）。 */
    private static void collectField(Map<String, String> fields, Map<String, Object> body,
            String jsonKey, String fieldName) {
        Object raw = body == null ? null : body.get(jsonKey);
        if (!(raw instanceof String text) || text.isBlank()) {
            return;
        }
        String normalized = text.trim();
        if (normalized.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(jsonKey + " 最长 " + MAX_TEXT_CHARS + " 字");
        }
        fields.put(fieldName, normalized);
    }

    private static Map<String, Object> fieldBody(SafetyReport report) {
        boolean blocked = report.findings().stream()
                .anyMatch(finding -> "high".equalsIgnoreCase(finding.severity()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blocked", blocked);
        body.put("findings", report.findings().size());
        body.put("details", advisoryDetails(report));
        return body;
    }

    private static List<Map<String, Object>> advisoryDetails(SafetyReport report) {
        return report.findings().stream()
                .filter(finding -> !"high".equalsIgnoreCase(finding.severity()))
                .map(finding -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("category", finding.category());
                    detail.put("severity", finding.severity());
                    detail.put("advice", finding.advice());
                    return (Map<String, Object>) detail;
                })
                .toList();
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
