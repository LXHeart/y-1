package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 字幕 cues（任务书 #64 卡5 / §4.4）：旁白按标点切 ≤20 字块，时长按字数比例分布。
 * 输出 JSON 文本落 {@code video_shot_audio.cues}，卡8 烧硬字幕 + 导出 SRT 都读它。
 */
final class TtsCues {

    static final int MAX_CUE_CHARS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TtsCues() {}

    record Cue(String text, long startMs, long endMs) {}

    static List<Cue> build(String narration, long durationMs) {
        List<String> chunks = split(narration);
        if (chunks.isEmpty() || durationMs <= 0) {
            return List.of();
        }
        int totalChars = chunks.stream().mapToInt(String::length).sum();
        List<Cue> cues = new ArrayList<>();
        long elapsedChars = 0;
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            long start = Math.round(elapsedChars * durationMs / (double) totalChars);
            elapsedChars += chunk.length();
            long end = index == chunks.size() - 1
                    ? durationMs
                    : Math.round(elapsedChars * durationMs / (double) totalChars);
            cues.add(new Cue(chunk, start, Math.max(start, end)));
        }
        return cues;
    }

    static String toJson(List<Cue> cues) {
        try {
            List<Object> raw = new ArrayList<>();
            for (Cue cue : cues) {
                raw.add(java.util.Map.of("text", cue.text(), "startMs", cue.startMs(),
                        "endMs", cue.endMs()));
            }
            return MAPPER.writeValueAsString(raw);
        } catch (Exception error) {
            throw new IllegalStateException("cues 序列化失败", error);
        }
    }

    /** 按标点切分（。！？；，、换行/空白），无标点长句每 20 字硬切。 */
    static List<String> split(String narration) {
        List<String> chunks = new ArrayList<>();
        if (narration == null) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < narration.length(); index++) {
            char ch = narration.charAt(index);
            if (Character.isWhitespace(ch) || isPunctuation(ch)) {
                appendChunk(chunks, current);
                continue;
            }
            current.append(ch);
            if (current.length() >= MAX_CUE_CHARS) {
                appendChunk(chunks, current);
            }
        }
        appendChunk(chunks, current);
        return chunks;
    }

    private static boolean isPunctuation(char ch) {
        return "。！？；，、.!?,;".indexOf(ch) >= 0;
    }

    private static void appendChunk(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
