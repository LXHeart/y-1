package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM NDJSON 分镜输出解析（任务书 #64 卡3）。
 *
 * <p>逐行解析（§4.1：逐行解析转发），容忍 LLM 对「每行一个 JSON」的常见偏离：代码围栏与空行、
 * 前导/结尾散文行、编号列表前缀（{@code 1. {...}}）、整段 JSON 数组、跨行 pretty-print 对象、
 * {@code {"shots":[...]}} 包裹——2026-09-03 MiniMax-M3 实跑即因偏离炸在逐行 readTree 上，故按
 * 既有防御性姿态扩展（镜头字段校验仍严格）。seq 按到达顺序重编号（LLM 的 seq 不可信）；
 * plannedSeconds 钳制到 4-6（§4.2 硬约束的防御性落地）；anchorImageIndex 超出 [0, imageCount]
 * 直接 400（卡3 验收项：图镜映射校验）。镜头数上限 30（#65 卡1；下界不硬拒——提示词层约束 3-30，
 * LLM 偶发短输出仍可进编辑步手工调整）。
 *
 * <p>可诊断性：跳过的偏离片段与终态失败都会 WARN 留原始输出（截断）——此前解析失败完全静默，
 * ai_run/lineage 均不落正文，线上无法定位是哪种污染。
 */
final class StoryboardParser {

    private static final Logger log = LoggerFactory.getLogger(StoryboardParser.class);

    static final int MIN_SHOT_SECONDS = 4;
    static final int MAX_SHOT_SECONDS = 6;
    /** #65 卡1：镜头上限 10→30（180 秒 ÷ 4 秒/镜）。 */
    static final int MAX_SHOTS = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 编号列表前缀（1. / 2、 / 3)）——LLM 偶发把 NDJSON 行写成编号列表项。 */
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\d{1,3}[.、)）]\\s*");

    /** 诊断日志的原始输出截断上限（max_tokens=8192 之下再截一层，防刷屏）。 */
    private static final int DIAGNOSTIC_CHARS = 2000;

    private StoryboardParser() {}

    record ParsedShot(
            int seq, String visual, String narration, int plannedSeconds,
            String cameraMove, int anchorImageIndex, String prompt) {
    }

    static List<ParsedShot> parse(String content, int imageCount) {
        String stripped = stripCodeFence(content == null ? "" : content.trim());
        List<ParsedShot> shots = new ArrayList<>();
        List<String> tolerated = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : stripped.split("\n")) {
            String line = stripListPrefix(rawLine.trim());
            if (line.isEmpty()) {
                continue;
            }
            char head = line.charAt(0);
            if (buffer.isEmpty()) {
                if (head != '{' && head != '[') {
                    // 前导寒暄/结尾解释等散文行：不可能构成镜头对象，跳过留痕
                    tolerated.add(line);
                    continue;
                }
                buffer.append(line);
            } else if (head == '{' && buffer.charAt(0) == '{') {
                // 累积中的对象始终闭不上，又开了新对象——前段判死丢弃
                tolerated.add(buffer.toString());
                buffer.setLength(0);
                buffer.append(line);
            } else {
                buffer.append('\n').append(line);
            }
            resolve(buffer, shots, imageCount);
        }
        if (!buffer.isEmpty()) {
            JsonNode node = readTree(buffer.toString());
            if (node == null) {
                fail(stripped);
            }
            resolveNode(node, shots, imageCount);
        }
        if (!tolerated.isEmpty()) {
            log.warn("分镜输出容忍 {} 处非 JSON 片段：{}", tolerated.size(), summarize(tolerated));
        }
        if (shots.isEmpty() || shots.size() > MAX_SHOTS) {
            log.warn("分镜镜头数越界 shots={} 原始输出：{}", shots.size(), truncate(stripped));
            throw new IllegalArgumentException("分镜镜头数须在 1-30 之间");
        }
        return List.copyOf(shots);
    }

    /** 缓冲区凑成完整 JSON 值即消费（对象→单镜；数组→逐元素）；不完整则留着继续累积。 */
    private static void resolve(StringBuilder buffer, List<ParsedShot> shots, int imageCount) {
        JsonNode node = readTree(buffer.toString());
        if (node == null) {
            return;
        }
        resolveNode(node, shots, imageCount);
        buffer.setLength(0);
    }

    private static void resolveNode(JsonNode node, List<ParsedShot> shots, int imageCount) {
        if (node.isObject()) {
            JsonNode nested = node.path("shots");
            if (node.path("visual").asText("").trim().isEmpty() && nested.isArray()) {
                // {"shots":[...]} 整体包裹形态：展开内层镜头
                appendShots(nested, shots, imageCount);
                return;
            }
            shots.add(shotOf(node, shots.size() + 1, imageCount));
            return;
        }
        if (node.isArray()) {
            appendShots(node, shots, imageCount);
            return;
        }
        fail(node.toString());
    }

    private static void appendShots(JsonNode array, List<ParsedShot> shots, int imageCount) {
        for (JsonNode element : array) {
            if (!element.isObject()) {
                fail(element.toString());
            }
            shots.add(shotOf(element, shots.size() + 1, imageCount));
        }
    }

    private static ParsedShot shotOf(JsonNode node, int seq, int imageCount) {
        String visual = node.path("visual").asText("").trim();
        if (visual.isEmpty()) {
            log.warn("分镜画面描述为空，原始行：{}", truncate(node.toString()));
            throw new IllegalArgumentException("分镜画面描述不能为空");
        }
        int anchor = node.path("anchorImageIndex").asInt(0);
        if (anchor < 0 || anchor > imageCount) {
            throw new IllegalArgumentException("分镜锚定图序号超出范围");
        }
        return new ParsedShot(
                seq,
                visual,
                node.path("narration").asText("").trim(),
                clamp(node.path("plannedSeconds").asInt(MIN_SHOT_SECONDS)),
                defaultCameraMove(node.path("cameraMove").asText("").trim()),
                anchor,
                node.path("prompt").asText("").trim());
    }

    /** 读不成完整 JSON 值返回 null（跨行对象的中间态），不是失败信号。 */
    private static JsonNode readTree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception error) {
            return null;
        }
    }

    /** 只剥后面真跟着 JSON 值的编号前缀，散文行（「1. 首先我们…」）原样保留进容忍日志。 */
    private static String stripListPrefix(String line) {
        java.util.regex.Matcher matcher = LIST_PREFIX.matcher(line);
        if (matcher.find() && matcher.end() < line.length()) {
            char next = line.charAt(matcher.end());
            if (next == '{' || next == '[') {
                return line.substring(matcher.end());
            }
        }
        return line;
    }

    private static void fail(String content) {
        log.warn("分镜输出包含无法解析的行，原始输出：{}", truncate(content));
        throw new IllegalArgumentException("分镜输出包含无法解析的行");
    }

    private static String truncate(String content) {
        return content.length() <= DIAGNOSTIC_CHARS ? content : content.substring(0, DIAGNOSTIC_CHARS) + "…";
    }

    private static String summarize(List<String> fragments) {
        List<String> capped = new ArrayList<>();
        for (String fragment : fragments) {
            capped.add(fragment.length() <= 60 ? fragment : fragment.substring(0, 60) + "…");
        }
        return String.join(" | ", capped);
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
