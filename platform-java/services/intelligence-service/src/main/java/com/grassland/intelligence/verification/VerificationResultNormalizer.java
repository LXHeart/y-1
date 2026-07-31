package com.grassland.intelligence.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 履约核验上游 JSON 结果标准化（草场 Slice 11 Verification Stage 3）。镜像
 * {@code ImageAnalysisService.parseResult} 与 {@code VideoRecreationAdaptationResultNormalizer}：
 * 剥 code fence → readTree → 校验为对象 → 提取 status（tri-state，可空 detail）→ 非法 → 502。
 *
 * <p>502 由调用方 {@link VerificationAnalysisService} 捕获后转为该附件的 {@code inconclusive}，
 * 故上游返回畸形内容不会拖垮整次核验。
 */
@Component
public class VerificationResultNormalizer {

    private static final Set<String> PASSED = Set.of("passed", "pass");
    private static final Set<String> FAILED = Set.of("failed", "fail");
    private static final Set<String> INCONCLUSIVE = Set.of("inconclusive", "unknown", "unclear");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析上游返回为 tri-state 判决；非对象 / 缺 status / status 词表外 → 502。 */
    public VerificationVerdict normalize(String content) {
        JsonNode root = parseObject(content);
        String rawStatus = optionalText(root.get("status"));
        if (rawStatus == null) {
            throw new IntelligenceException(502, "履约核验服务返回了空结果");
        }
        String status = classify(rawStatus);
        if (status == null) {
            throw new IntelligenceException(502, "履约核验服务返回了无效数据");
        }
        return new VerificationVerdict(status, optionalText(root.get("detail")));
    }

    private JsonNode parseObject(String content) {
        String stripped = stripCodeFence(content == null ? "" : content).trim();
        try {
            JsonNode node = mapper.readTree(stripped);
            if (!node.isObject()) {
                throw new IntelligenceException(502, "履约核验服务返回了无效数据");
            }
            return node;
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(502, "履约核验服务返回了无法解析的内容");
        }
    }

    private static String classify(String rawStatus) {
        String normalized = rawStatus.toLowerCase(Locale.ROOT);
        if (PASSED.contains(normalized)) {
            return "passed";
        }
        if (FAILED.contains(normalized)) {
            return "failed";
        }
        if (INCONCLUSIVE.contains(normalized)) {
            return "inconclusive";
        }
        return null;
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

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** 单张附件的 AI 判决（tri-state + 可空理由）。 */
    public record VerificationVerdict(String status, String detail) {
    }
}
