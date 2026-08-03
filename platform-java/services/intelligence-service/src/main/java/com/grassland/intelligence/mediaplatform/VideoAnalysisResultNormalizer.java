package com.grassland.intelligence.mediaplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频内容提取结果归一——各媒体平台（Bilibili / Douyin）Java 分析路径共用（移植 legacy
 * {@code providers/types.ts} 的 {@code normalizeVideoAnalysisResult} + {@code completeVideoAnalysisResult} +
 * {@code extractCharacterHints} + {@code extractPropsHints} + {@code normalizeAdaptedScript} +
 * {@code stripMarkdownCodeFence}）。
 *
 * <p>Qwen 返回 {@code choices[0].message.content}（可能裹 ```json 代码块）→ 剥围栏 → JSON 解析 →
 * 取 6 字段（snake/camel 双态）+ runId → {@code charactersDescription}/{@code propsDescription} 缺失时按
 * 场景/字幕关键词回填线索（逐字对齐 legacy）。{@code video_script} 可能是字符串或分镜数组，数组态格式化为多行。
 * 输出 {@code null} 字段不放入 map（前端读取语义 = undefined，与 legacy 一致）。
 */
public final class VideoAnalysisResultNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?\\s*```");

    private static final Pattern NO_PERSON = Pattern.compile("没有人物|无人出镜|未见人物|没人出镜");
    private static final Pattern PERSON_HINT = Pattern.compile(
            "人物|角色|女生|男生|女人|男人|小孩|博主|主持人|店员|顾客|女孩|男孩|女性|男性");

    private static final List<String> PROP_HINT_KEYWORDS = List.of(
            "笔记本电脑", "咖啡机", "高脚凳", "木桌", "桌子", "椅子", "马克杯", "杯子", "盘子", "筷子", "勺子",
            "手机", "电脑", "相机", "背包", "台灯", "柜台", "麦克风", "耳机", "屏幕", "键盘", "产品", "设备");

    private VideoAnalysisResultNormalizer() {}

    /** 解析 Qwen content（剥代码块围栏 + JSON）→ 6 字段归一 map；非法 → 502。null 字段省略。 */
    public static Map<String, Object> normalize(String content, String runId) {
        JsonNode record = parseContentObject(content);

        String videoCaptions = firstDefinedString(record, "video_captions", "videoCaptions");
        String videoScript = normalizeAdaptedScript(firstRaw(record, "video_script", "videoScript"));
        String charactersDescription = firstDefinedString(record, "characters_description", "charactersDescription");
        String voiceDescription = firstDefinedString(record, "voice_description", "voiceDescription");
        String propsDescription = firstDefinedString(record, "props_description", "propsDescription");
        String sceneDescription = firstDefinedString(record, "scene_description", "sceneDescription");
        String resolvedRunId = firstDefinedString(record, "run_id", "runId");
        if (resolvedRunId == null && runId != null && !runId.isBlank()) {
            resolvedRunId = runId.trim();
        }

        // completeVideoAnalysisResult：缺失时按场景/字幕关键词回填线索（逐字对齐 legacy）。
        if (charactersDescription == null) {
            charactersDescription = extractCharacterHints(sceneDescription, videoCaptions);
        }
        if (propsDescription == null) {
            propsDescription = extractPropsHints(sceneDescription);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "videoCaptions", videoCaptions);
        putIfPresent(result, "videoScript", videoScript);
        putIfPresent(result, "charactersDescription", charactersDescription);
        putIfPresent(result, "voiceDescription", voiceDescription);
        putIfPresent(result, "propsDescription", propsDescription);
        putIfPresent(result, "sceneDescription", sceneDescription);
        putIfPresent(result, "runId", resolvedRunId);
        return result;
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
            throw new IntelligenceException(502, "视频内容提取服务返回了无效数据");
        }
        return root;
    }

    private static String stripCodeFence(String text) {
        Matcher matcher = CODE_FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    /** video_script 可能是字符串或分镜数组；数组态格式化为多行（对齐 legacy normalizeAdaptedScript）。 */
    private static String normalizeAdaptedScript(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isTextual()) {
            String text = raw.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (!raw.isArray() || raw.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode shot : raw) {
            if (!shot.isObject()) {
                continue;
            }
            String number = shot.path("shot_number").isNumber() ? String.valueOf(shot.path("shot_number").asInt()) : "?";
            String type = shot.path("shot_type").asText("");
            String visual = shot.path("visual_content").asText("");
            String camera = shot.path("camera_movement").asText("");
            String dialogue = shot.path("dialogue_narration").asText("");
            String onScreenText = shot.path("on_screen_text").asText("");
            String duration = shot.path("duration_seconds").isNumber() ? String.valueOf(shot.path("duration_seconds").asInt()) : "";
            String notes = shot.path("notes").asText("");

            if (visual.isBlank() && dialogue.isBlank() && type.isBlank()) {
                continue;
            }
            lines.add(String.format("镜头 %s | %s | %ss", number, type, duration));
            if (!visual.isBlank()) lines.add("  画面：" + visual);
            if (!camera.isBlank()) lines.add("  运镜：" + camera);
            if (!dialogue.isBlank() && !"无".equals(dialogue)) lines.add("  台词/旁白：" + dialogue);
            if (!onScreenText.isBlank() && !"无".equals(onScreenText)) lines.add("  字幕：" + onScreenText);
            if (!notes.isBlank()) lines.add("  备注：" + notes);
            lines.add("");
        }
        // lines 末尾必有空串（至少一个 "" 分隔）；>1 元素才视为有效（对齐 legacy lines.length > 1）。
        return lines.size() > 1 ? String.join("\n", lines).trim() : null;
    }

    private static String extractCharacterHints(String sceneDescription, String videoCaptions) {
        List<String> lines = new ArrayList<>();
        splitLines(sceneDescription, lines);
        splitLines(videoCaptions, lines);
        List<String> matched = new ArrayList<>();
        for (String line : lines) {
            if (NO_PERSON.matcher(line).find()) {
                continue;
            }
            if (PERSON_HINT.matcher(line).find()) {
                matched.add("可见出镜人物线索：" + line);
            }
        }
        return matched.isEmpty() ? null : joinUniqueLines(matched);
    }

    private static String extractPropsHints(String sceneDescription) {
        List<String> lines = new ArrayList<>();
        splitLines(sceneDescription, lines);
        List<String> matchedTokens = new ArrayList<>();
        for (String line : lines) {
            // 命中关键词按长度降序，长词优先吞并短词（对齐 legacy sort by length desc + includes 去重）。
            List<String> hits = new ArrayList<>();
            for (String keyword : PROP_HINT_KEYWORDS) {
                if (line.contains(keyword)) {
                    hits.add(keyword);
                }
            }
            hits.sort((a, b) -> Integer.compare(b.length(), a.length()));
            List<String> selected = new ArrayList<>();
            for (String keyword : hits) {
                boolean subsumed = false;
                for (String existing : selected) {
                    if (existing.contains(keyword)) {
                        subsumed = true;
                        break;
                    }
                }
                if (subsumed) {
                    continue;
                }
                selected.add(keyword);
                matchedTokens.add("可见道具/物件：" + keyword);
            }
        }
        return matchedTokens.isEmpty() ? null : joinUniqueLines(matchedTokens);
    }

    private static String joinUniqueLines(List<String> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (String item : items) {
            if (seen.add(item)) {
                lines.add(item);
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static void splitLines(String value, List<String> out) {
        if (value == null) {
            return;
        }
        for (String line : value.split("\\n+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
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

    /** 取原始节点（snake 优先，回退 camel），用于 {@link #normalizeAdaptedScript}（需保留数组形态）。 */
    private static JsonNode firstRaw(JsonNode record, String... fields) {
        for (String field : fields) {
            JsonNode node = record.get(field);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
