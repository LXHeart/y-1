package com.grassland.intelligence.videoproduction;

import java.util.ArrayList;
import java.util.List;

/**
 * SRT 字幕构建（任务书 #64 卡8，P4：硬字幕烧录 + SRT 下载二合一）。
 * 时间轴来自各镜音频起止累计 + cues；未配音镜头不产生字幕。
 */
final class SrtBuilder {

    private SrtBuilder() {}

    record Cue(String text, long startMs, long endMs) {}

    /** 全片 SRT 文本：输入已按镜序展开的绝对时间轴 cues。 */
    static String buildSrt(List<Cue> absoluteCues) {
        StringBuilder srt = new StringBuilder();
        int index = 1;
        for (Cue cue : absoluteCues) {
            if (cue.text() == null || cue.text().isBlank()) {
                continue;
            }
            srt.append(index++).append('\n')
                    .append(timestamp(cue.startMs())).append(" --> ").append(timestamp(cue.endMs()))
                    .append('\n')
                    .append(cue.text().trim()).append('\n')
                    .append('\n');
        }
        return srt.toString();
    }

    /** cues JSON → 镜内 cues；无 cues（渠道未给时间戳）时按 §4.4 算法现切。 */
    static List<Cue> parseCues(String cuesJson, String narration) {
        if (cuesJson != null && !cuesJson.isBlank()) {
            try {
                List<Cue> parsed = new ArrayList<>();
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(cuesJson)
                        .forEach(node -> parsed.add(new Cue(
                                node.path("text").asText(""),
                                node.path("startMs").asLong(0),
                                node.path("endMs").asLong(0))));
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 坏 cues 落回现切
            }
        }
        long durationMs = narration == null ? 0 : SandboxTtsProvider.durationMsFor(narration);
        List<TtsCues.Cue> fallback = TtsCues.build(narration, Math.max(durationMs, 1));
        return fallback.stream()
                .map(cue -> new Cue(cue.text(), cue.startMs(), cue.endMs()))
                .toList();
    }

    static String timestamp(long millis) {
        long clamped = Math.max(0, millis);
        long hours = clamped / 3_600_000;
        long minutes = (clamped % 3_600_000) / 60_000;
        long seconds = (clamped % 60_000) / 1000;
        long millisPart = clamped % 1000;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millisPart);
    }
}
