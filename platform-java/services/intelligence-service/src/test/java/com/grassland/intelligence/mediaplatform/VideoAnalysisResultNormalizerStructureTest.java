package com.grassland.intelligence.mediaplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #66 E1：参考结构派生单测——shotStructure（durationSeconds/purpose 归类）与
 * hookAtSeconds（首个 hook 镜头起始偏移）从既有 video_script 数组映射；缺字段/非法值静默跳过。
 */
@DisplayName("VideoAnalysisResultNormalizer 参考结构派生")
class VideoAnalysisResultNormalizerStructureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("video_script 数组 → shotStructure 派生 + hook 位置=前序时长累加")
    void derivesShotStructureAndHookOffset() throws Exception {
        var script = MAPPER.readTree("""
                [
                  {"shot_number":1,"shot_type":"hook 开场","duration_seconds":5},
                  {"shot_number":2,"shot_type":"产品介绍","duration_seconds":4},
                  {"shot_number":3,"shot_type":"结尾引导","duration_seconds":6}
                ]
                """);

        List<Map<String, Object>> structure = VideoAnalysisResultNormalizer.shotStructureOf(script);
        assertThat(structure).hasSize(3);
        assertThat(structure.get(0)).containsEntry("purpose", "hook");
        assertThat(structure.get(1)).containsEntry("purpose", "point");
        assertThat(structure.get(2)).containsEntry("purpose", "cta");
        assertThat(structure.get(0)).containsEntry("durationSeconds", 5.0);

        assertThat(VideoAnalysisResultNormalizer.hookAtSecondsOf(script)).isEqualTo(0.0);

        var hookNotFirst = MAPPER.readTree("""
                [
                  {"shot_number":1,"shot_type":"产品介绍","duration_seconds":3},
                  {"shot_number":2,"shot_type":"钩子镜头","duration_seconds":5}
                ]
                """);
        assertThat(VideoAnalysisResultNormalizer.hookAtSecondsOf(hookNotFirst)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("无 hook 镜头/缺 duration_seconds/非数组 → 派生缺席")
    void absentOrInvalidInputsDeriveNothing() throws Exception {
        var noHook = MAPPER.readTree(
                "[{\"shot_number\":1,\"shot_type\":\"介绍\",\"duration_seconds\":5}]");
        assertThat(VideoAnalysisResultNormalizer.hookAtSecondsOf(noHook)).isNull();

        var missingDuration = MAPPER.readTree(
                "[{\"shot_number\":1,\"shot_type\":\"hook\"}]");
        assertThat(VideoAnalysisResultNormalizer.shotStructureOf(missingDuration)).isNull();

        assertThat(VideoAnalysisResultNormalizer.shotStructureOf(MAPPER.readTree("\"文本\"")))
                .isNull();
    }

    @Test
    @DisplayName("normalize 输出 schema 追加 shotStructure/hookAtSeconds（只加不改，缺省省略）")
    void normalizeAppendsStructureFields() {
        String content = """
                ```json
                {"video_captions":"字幕","video_script":[
                  {"shot_number":1,"shot_type":"hook 开场","duration_seconds":5},
                  {"shot_number":2,"shot_type":"转场过渡","duration_seconds":4}]}
                ```
                """;
        Map<String, Object> result = VideoAnalysisResultNormalizer.normalize(content, null);
        assertThat(result).containsKeys("videoCaptions", "shotStructure", "hookAtSeconds");
        assertThat(result.get("hookAtSeconds")).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structure = (List<Map<String, Object>>) result.get("shotStructure");
        assertThat(structure.get(1)).containsEntry("purpose", "transition");
    }
}
