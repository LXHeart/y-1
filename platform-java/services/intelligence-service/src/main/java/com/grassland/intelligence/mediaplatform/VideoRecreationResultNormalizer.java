package com.grassland.intelligence.mediaplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频复刻（分镜场景）结果归一（移植 legacy {@code providers/types.ts} 的
 * {@code normalizeVideoRecreationResult} + {@code normalizeScene}）。
 *
 * <p>Qwen content → 剥代码块围栏 + JSON → {@code scenes} 数组（必须非空）逐项归一：每场景至少
 * {@code shot_description}/{@code character_description}/{@code scene_environment} 之一非空才保留，
 * 其余字段缺失回空串。输出 {@code {scenes:[...], overallStyle?, runId?}}。空场景 → 502。
 *
 * <p>抖音 / Bilibili 复刻分析共用（两平台提示词与归一规则同源，见 {@link VideoAnalysisPrompts#recreation()}）。
 */
public final class VideoRecreationResultNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern CODE_FENCE =
            java.util.regex.Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?\\s*```");

    private VideoRecreationResultNormalizer() {}

    public static Map<String, Object> normalize(String content, String runId) {
        JsonNode record = parseContentObject(content);
        JsonNode rawScenes = record.get("scenes");
        if (rawScenes == null || !rawScenes.isArray() || rawScenes.isEmpty()) {
            throw new IntelligenceException(502, "视频复刻分析服务返回了空场景列表");
        }
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (JsonNode item : rawScenes) {
            Map<String, Object> scene = normalizeScene(item);
            if (scene != null) {
                scenes.add(scene);
            }
        }
        if (scenes.isEmpty()) {
            throw new IntelligenceException(502, "视频复刻分析服务返回了空场景列表");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenes", scenes);
        String overallStyle = readOptional(record.get("overall_style"));
        if (overallStyle != null) {
            result.put("overallStyle", overallStyle);
        }
        String resolvedRunId = firstDefinedString(record, "run_id", "runId");
        if (resolvedRunId == null && runId != null && !runId.isBlank()) {
            resolvedRunId = runId.trim();
        }
        if (resolvedRunId != null) {
            result.put("runId", resolvedRunId);
        }
        return result;
    }

    private static Map<String, Object> normalizeScene(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        String shotDescription = readOptional(value.get("shot_description"));
        String characterDescription = readOptional(value.get("character_description"));
        String sceneEnvironment = readOptional(value.get("scene_environment"));
        if (shotDescription == null && characterDescription == null && sceneEnvironment == null) {
            return null;
        }
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("shotDescription", shotDescription == null ? "" : shotDescription);
        scene.put("characterDescription", characterDescription == null ? "" : characterDescription);
        scene.put("actionMovement", orEmpty(readOptional(value.get("action_movement"))));
        scene.put("dialogueVoiceover", orEmpty(readOptional(value.get("dialogue_voiceover"))));
        scene.put("sceneEnvironment", sceneEnvironment == null ? "" : sceneEnvironment);
        return scene;
    }

    private static JsonNode parseContentObject(String content) {
        String stripped = stripCodeFence(content).trim();
        JsonNode root;
        try {
            root = MAPPER.readTree(stripped);
        } catch (Exception error) {
            throw new IntelligenceException(502, "Qwen 返回了无法解析的内容");
        }
        if (!root.isObject()) {
            throw new IntelligenceException(502, "视频复刻分析服务返回了无效数据");
        }
        return root;
    }

    private static String stripCodeFence(String text) {
        java.util.regex.Matcher matcher = CODE_FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    private static String readOptional(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstDefinedString(JsonNode record, String... fields) {
        for (String field : fields) {
            JsonNode node = record.get(field);
            if (node != null && node.isTextual()) {
                String text = node.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }
}
