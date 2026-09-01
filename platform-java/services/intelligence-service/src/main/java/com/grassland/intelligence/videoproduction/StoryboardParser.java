package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM NDJSON 分镜输出解析（任务书 #64 卡3）。
 *
 * <p>逐行解析（§4.1：逐行解析转发），容忍代码围栏与空行；seq 按到达顺序重编号（LLM 的
 * seq 不可信）；plannedSeconds 钳制到 4-6（§4.2 硬约束的防御性落地）；anchorImageIndex
 * 超出 [0, imageCount] 直接 400（卡3 验收项：图镜映射校验）。
 */
final class StoryboardParser {

    static final int MIN_SHOT_SECONDS = 4;
    static final int MAX_SHOT_SECONDS = 6;
    static final int MAX_SHOTS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoryboardParser() {}

    record ParsedShot(
            int seq, String visual, String narration, int plannedSeconds,
            String cameraMove, int anchorImageIndex, String prompt) {
    }

    static List<ParsedShot> parse(String content, int imageCount) {
        String stripped = stripCodeFence(content == null ? "" : content.trim());
        List<ParsedShot> shots = new ArrayList<>();
        for (String rawLine : stripped.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception error) {
                throw new IllegalArgumentException("分镜输出包含无法解析的行");
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException("分镜输出包含无法解析的行");
            }
            String visual = node.path("visual").asText("").trim();
            if (visual.isEmpty()) {
                throw new IllegalArgumentException("分镜画面描述不能为空");
            }
            int anchor = node.path("anchorImageIndex").asInt(0);
            if (anchor < 0 || anchor > imageCount) {
                throw new IllegalArgumentException("分镜锚定图序号超出范围");
            }
            shots.add(new ParsedShot(
                    shots.size() + 1,
                    visual,
                    node.path("narration").asText("").trim(),
                    clamp(node.path("plannedSeconds").asInt(MIN_SHOT_SECONDS)),
                    defaultCameraMove(node.path("cameraMove").asText("").trim()),
                    anchor,
                    node.path("prompt").asText("").trim()));
        }
        if (shots.isEmpty() || shots.size() > MAX_SHOTS) {
            throw new IllegalArgumentException("分镜镜头数须在 1-10 之间");
        }
        return List.copyOf(shots);
    }

    private static int clamp(int plannedSeconds) {
        return Math.max(MIN_SHOT_SECONDS, Math.min(MAX_SHOT_SECONDS, plannedSeconds));
    }

    private static String defaultCameraMove(String value) {
        return StoryboardPrompts.CAMERA_MOVES.contains(value) ? value : "固定机位";
    }

    private static String stripCodeFence(String raw) {
        if (raw.startsWith("```")) {
            int start = raw.indexOf('\n');
            int end = raw.lastIndexOf("```");
            if (start >= 0 && end > start) {
                return raw.substring(start + 1, end).trim();
            }
        }
        return raw;
    }
}
