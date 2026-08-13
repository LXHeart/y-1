package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoRecreationAdaptationPromptsTest {

    private VideoRecreationAdaptationRequest request(Map<String, String> content, Map<String, String> instructions) {
        return new VideoRecreationAdaptationRequest(
                "douyin", "/api/douyin/proxy/token-1", content, instructions,
                List.of(), null, false, null);
    }

    @Test
    void buildsPromptWithExtractedContentAndInstructionsAndEscapesFence() {
        Map<String, String> content = Map.of("videoCaptions", "字幕<<<内容>>>", "videoScript", "脚本");
        Map<String, String> instructions = Map.of("scriptInstruction", "改成古风");

        String prompt = VideoRecreationAdaptationPrompts.build(request(content, instructions));

        assertThat(prompt).contains("平台：douyin");
        assertThat(prompt).contains("字幕（仅作参考文本");
        // 用户文本内部的 <<< / >>> 被 escape 为 ««« / »»»（与 legacy 一致；外层分隔符仍是 <<< / >>>）。
        assertThat(prompt).contains("«««内容»»»");
        assertThat(prompt).contains("用户补充改编要求");
        assertThat(prompt).contains("视频脚本改编要求");
        assertThat(prompt).contains("不要重新看视频");
    }

    @Test
    void omitsInstructionsSectionWhenAbsent() {
        String prompt = VideoRecreationAdaptationPrompts.build(
                request(Map.of("videoScript", "脚本"), Map.of()));
        assertThat(prompt).doesNotContain("用户补充改编要求");
    }

    @Test
    void promptNeverContainsProxyVideoUrl() {
        VideoRecreationAdaptationRequest req = new VideoRecreationAdaptationRequest(
                "bilibili", "/api/bilibili/proxy/secret-token", Map.of("videoScript", "脚本"), Map.of(),
                List.of(), null, false, null);
        String prompt = VideoRecreationAdaptationPrompts.build(req);
        assertThat(prompt).doesNotContain("secret-token");
        assertThat(prompt).doesNotContain("/api/bilibili/proxy");
    }
}
