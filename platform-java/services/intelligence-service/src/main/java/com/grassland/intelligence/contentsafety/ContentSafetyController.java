package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 手动复查端点（任务书 #34 / ADR-D16 D7）：用户编辑文本后点「重新检查」的入口。
 *
 * <p>{@code POST /api/content-safety/check}（需登录；请求体 ≤50KB；platform 可选做 overlay 预留，
 * v1 不改变结果）。findings 是 advisory（警告 + 建议），词库服务端独占（D9）——响应只回 findings
 * 与版本，不回词表。
 */
@RestController
public class ContentSafetyController {

    /** 请求体字符上限（50KB 级：以字符计，UTF-8 中文约 3 字节/字——16k 中文字已覆盖全部生成产物）。 */
    private static final int MAX_TEXT_CHARS = 16_000;

    private final IntelligenceCallerResolver callers;
    private final ContentSafetyService safety;

    public ContentSafetyController(IntelligenceCallerResolver callers, ContentSafetyService safety) {
        this.callers = callers;
        this.safety = safety;
    }

    @PostMapping("/api/content-safety/check")
    public Mono<Map<String, Object>> check(@RequestBody CheckRequest body, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> Mono.defer(() -> {
                    if (body.text() == null || body.text().isBlank()) {
                        return Mono.just(ok(SafetyReport.emptyShallow()));
                    }
                    if (body.text().length() > MAX_TEXT_CHARS) {
                        return Mono.error(new IntelligenceException(400,
                                "文本超长（上限 " + MAX_TEXT_CHARS + " 字符）"));
                    }
                    return safety.check(
                                    exchange, body.text(), body.platform(), body.industry(),
                                    new OriginalityChecker.Context(
                                            caller.accountId(), body.taskId(), body.applicationId(),
                                            body.platform(), body.contentForm(), "manual"))
                            .map(ContentSafetyController::ok);
                }));
    }

    private static Map<String, Object> ok(SafetyReport report) {
        Map<String, Object> safetyBody = new LinkedHashMap<>();
        safetyBody.put("findings", report.findings().stream()
                .map(f -> {
                    Map<String, Object> finding = new LinkedHashMap<>();
                    finding.put("category", f.category());
                    finding.put("severity", f.severity());
                    finding.put("match", f.match());
                    finding.put("index", f.index());
                    finding.put("advice", f.advice());
                    finding.put("deep", f.deep());
                    return finding;
                }).toList());
        safetyBody.put("lexiconVersion", report.lexiconVersion());
        safetyBody.put("deepCheck", report.deepCheck());
        safetyBody.put("appliedOverlays", report.appliedOverlays());
        return Map.of("success", true, "data", Map.of("safety", safetyBody));
    }

    /** 请求体：text 必填；platform 可选（overlay 预留，v1 不参与判定）。 */
    record CheckRequest(
            String text, String platform, String industry,
            String taskId, String applicationId, String contentForm) {
    }
}
