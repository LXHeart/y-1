package com.grassland.intelligence.videorecreation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 视频改编上游 JSON 结果标准化（移植 legacy normalizeVideoAdaptationResult）：code fence + snake_case，过滤非法资产，空摘要→502。 */
@Component
public class VideoRecreationAdaptationResultNormalizer {

    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?\\s*```");

    private final ObjectMapper mapper = new ObjectMapper();

    public VideoRecreationAdaptationResultNormalizer() {
    }

    public Map<String, Object> normalize(String content, String runId) {
        JsonNode root = parseObject(content);
        String adaptedSummary = optionalString(root.get("adapted_summary"));
        if (adaptedSummary == null || adaptedSummary.isBlank()) {
            throw new IntelligenceException(502, "视频内容改编服务返回了空结果");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "adaptedTitle", optionalString(root.get("adapted_title")));
        result.put("adaptedSummary", adaptedSummary);
        put(result, "adaptedScript", normalizeScript(root.get("adapted_script")));
        put(result, "adaptedVoiceDescription", optionalString(root.get("adapted_voice_description")));
        put(result, "visualStyle", optionalString(root.get("visual_style")));
        put(result, "tone", optionalString(root.get("tone")));
        result.put("characterSheets", normalizeCharacters(root.get("character_sheets")));
        result.put("sceneCards", normalizeScenes(root.get("scene_cards")));
        result.put("propCards", normalizeProps(root.get("prop_cards")));
        if (runId != null && !runId.isBlank()) result.put("runId", runId);
        return Map.copyOf(result);
    }

    private JsonNode parseObject(String content) {
        if (content == null) throw new IntelligenceException(502, "视频内容改编服务返回了无效数据");
        String stripped = stripFence(content).trim();
        try {
            JsonNode node = mapper.readTree(stripped);
            if (!node.isObject()) throw new IntelligenceException(502, "视频内容改编服务返回了无效数据");
            return node;
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(502, "视频内容改编服务返回了无效数据");
        }
    }

    private String stripFence(String text) {
        Matcher matcher = CODE_FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    private String normalizeScript(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;
        if (raw.isTextual()) {
            String value = raw.asText().trim();
            return value.isEmpty() ? null : value;
        }
        if (!raw.isArray() || raw.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (JsonNode shot : raw) {
            if (!shot.isObject()) continue;
            String number = shot.hasNonNull("shot_number") && shot.get("shot_number").isNumber()
                    ? String.valueOf(shot.get("shot_number").asInt()) : "?";
            String type = optionalString(shot.get("shot_type"));
            String visual = optionalString(shot.get("visual_content"));
            String camera = optionalString(shot.get("camera_movement"));
            String dialogue = optionalString(shot.get("dialogue_narration"));
            String text = optionalString(shot.get("on_screen_text"));
            String duration = shot.hasNonNull("duration_seconds") && shot.get("duration_seconds").isNumber()
                    ? String.valueOf(shot.get("duration_seconds").asInt()) : "";
            String notes = optionalString(shot.get("notes"));
            if (isBlank(visual) && isBlank(dialogue) && isBlank(type)) continue;
            lines.add("镜头 " + number + " | " + (type == null ? "" : type) + " | " + duration + "s");
            if (!isBlank(visual)) lines.add("  画面：" + visual);
            if (!isBlank(camera)) lines.add("  运镜：" + camera);
            if (!isBlank(dialogue) && !"无".equals(dialogue)) lines.add("  台词/旁白：" + dialogue);
            if (!isBlank(text) && !"无".equals(text)) lines.add("  字幕：" + text);
            if (!isBlank(notes)) lines.add("  备注：" + notes);
            lines.add("");
        }
        return lines.size() > 1 ? String.join("\n", lines).trim() : null;
    }

    private List<Map<String, Object>> normalizeCharacters(JsonNode raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null || !raw.isArray()) return result;
        for (JsonNode item : raw) {
            if (!item.isObject()) continue;
            String id = optionalString(item.get("id"));
            String name = optionalString(item.get("name"));
            String description = optionalString(item.get("description"));
            String threeView = optionalString(item.get("three_view_prompt"));
            if (id == null || name == null || description == null || threeView == null) continue;
            result.add(Map.of("id", id, "name", name, "description", description, "threeViewPrompt", threeView));
        }
        return result;
    }

    private List<Map<String, Object>> normalizeScenes(JsonNode raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null || !raw.isArray()) return result;
        for (JsonNode item : raw) {
            if (!item.isObject()) continue;
            String id = optionalString(item.get("id"));
            String description = optionalString(item.get("description"));
            String imagePrompt = optionalString(item.get("image_prompt"));
            if (id == null || description == null || imagePrompt == null) continue;
            String title = optionalString(item.get("title"));
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", id);
            if (title != null) scene.put("title", title);
            scene.put("description", description);
            scene.put("imagePrompt", imagePrompt);
            result.add(Map.copyOf(scene));
        }
        return result;
    }

    private List<Map<String, Object>> normalizeProps(JsonNode raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null || !raw.isArray()) return result;
        for (JsonNode item : raw) {
            if (!item.isObject()) continue;
            String id = optionalString(item.get("id"));
            String name = optionalString(item.get("name"));
            String description = optionalString(item.get("description"));
            String imagePrompt = optionalString(item.get("image_prompt"));
            if (id == null || name == null || description == null || imagePrompt == null) continue;
            result.add(Map.of("id", id, "name", name, "description", description, "imagePrompt", imagePrompt));
        }
        return result;
    }

    private static void put(Map<String, Object> result, String key, String value) {
        if (value != null) result.put(key, value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String optionalString(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
