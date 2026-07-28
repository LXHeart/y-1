package com.grassland.intelligence.imageanalysis;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 图片评价风格偏好业务（草场 intelligence Slice 6）。逐字移植 legacy {@code image-review-style.service.ts}：
 * load/save（覆盖）/optimize（LLM 合并，不落库）/saveFromEdits（LLM 总结→去重合并→落库）。
 *
 * <p>LLM 错误映射对齐 legacy：saveFromEdits 透传具体消息（{@code 风格总结失败}/{@code LLM 返回了空内容}），
 * optimize 统一降级为 {@code 风格偏好优化失败}（与 legacy controller 的固定 500 一致）。
 */
@Component
public class StylePreferencesService {

    static final Duration STYLE_LLM_TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_PREFERENCES = 100;

    private final StylePreferencesRepository repo;
    private final AiCapabilityAdapter ai;

    public StylePreferencesService(StylePreferencesRepository repo, AiCapabilityAdapter ai) {
        this.repo = repo;
        this.ai = ai;
    }

    public Mono<List<String>> loadPreferences(String accountId) {
        return repo.load(accountId);
    }

    public Mono<List<String>> savePreferences(String accountId, List<String> preferences) {
        return repo.save(accountId, preferences);
    }

    /** 生成注入用：读偏好→构建附录串（空→""，镜像 legacy {@code buildStylePreferenceAppendix}）。 */
    public Mono<String> styleAppendixFor(String accountId) {
        if (accountId == null) {
            return Mono.just("");
        }
        return repo.load(accountId).map(ImageAnalysisPrompts::buildStylePreferenceAppendix);
    }

    /** LLM 合并近义规则（不落库），cap 100。镜像 legacy {@code optimizeStylePreferences}。 */
    public Mono<List<String>> optimizePreferences(List<String> preferences) {
        String prompt = ImageAnalysisPrompts.buildStyleOptimizePrompt(preferences);
        return complete(prompt, "风格偏好优化失败")
                .map(StylePreferencesService::parseRules)
                .map(rules -> cap(rules, MAX_PREFERENCES))
                .onErrorMap(StylePreferencesService::toOptimizeFailure);
    }

    /** 从原/编辑快照总结风格差异→合并入既有偏好→落库。原===编辑→不调 LLM，直接返回既有。 */
    public Mono<List<String>> saveFromEdits(String accountId, StyleSnapshot original, StyleSnapshot edited) {
        if (sameSnapshot(original, edited)) {
            return repo.load(accountId);
        }
        String prompt = ImageAnalysisPrompts.buildStyleSummaryPrompt(
                ImageAnalysisPrompts.prettyJson(original), ImageAnalysisPrompts.prettyJson(edited));
        return complete(prompt, "风格总结失败")
                .map(StylePreferencesService::parseRules)
                .flatMap(newRules -> repo.load(accountId).map(existing -> mergePreserving(existing, newRules)))
                .flatMap(merged -> repo.save(accountId, cap(merged, MAX_PREFERENCES)));
    }

    private Mono<String> complete(String prompt, String failureMessage) {
        return ai.completeText(new TextCompletionCommand(
                List.of(ChatMessage.user(prompt)), failureMessage, STYLE_LLM_TIMEOUT));
    }

    /** 解析 LLM 文本为规则列表（strip 行首 bullet/编号，过滤空行）。镜像 legacy summarize 解析。 */
    static List<String> parseRules(String content) {
        if (content == null || content.isBlank()) {
            throw new IntelligenceException(500, "LLM 返回了空内容");
        }
        List<String> rules = new ArrayList<>();
        content.trim().lines().forEach(line -> {
            String stripped = line.replaceFirst("^[-•*\\d.)\\s]+", "").trim();
            if (!stripped.isEmpty()) {
                rules.add(stripped);
            }
        });
        return List.copyOf(rules);
    }

    /** 合并：保留既有顺序，追加新规则（exact-string 去重），整体 cap。镜像 legacy saveFromEdits 合并。 */
    static List<String> mergePreserving(List<String> existing, List<String> newRules) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
        for (String rule : newRules) {
            if (merged.size() >= MAX_PREFERENCES) {
                break;
            }
            merged.add(rule);
        }
        return List.copyOf(merged);
    }

    static List<String> cap(List<String> rules, int limit) {
        return rules.size() <= limit ? rules : List.copyOf(rules.subList(0, limit));
    }

    private static boolean sameSnapshot(StyleSnapshot a, StyleSnapshot b) {
        return ImageAnalysisPrompts.prettyJson(a).equals(ImageAnalysisPrompts.prettyJson(b));
    }

    private static Throwable toOptimizeFailure(Throwable error) {
        return new IntelligenceException(500, "风格偏好优化失败");
    }

    /** 风格快照（镜像 legacy {@code ImageReviewSnapshot}）。 */
    public record StyleSnapshot(String review, String title, List<String> tags) {}
}
