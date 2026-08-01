package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BilibiliAnalysisResultNormalizer} 单测（草场 Slice 13 Stage 5）。覆盖 snake/camel 双态、代码块围栏剥离、
 * video_script 数组→多行、characters/props 缺失时按场景/字幕关键词回填线索、非法内容 502。
 */
@DisplayName("BilibiliAnalysisResultNormalizer（移植 legacy normalizeVideoAnalysisResult）")
class BilibiliAnalysisResultNormalizerTest {

    @Test
    @DisplayName("snake_case 6 字段 + runId 全量归一")
    void normalizesSnakeCaseFields() {
        String content = """
                {
                  "video_captions": "[00:01] 旁白",
                  "video_script": "原始脚本",
                  "characters_description": "一位女性博主",
                  "voice_description": "清亮",
                  "props_description": "一个白瓷大碗",
                  "scene_description": "面馆",
                  "run_id": "r1"
                }""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, "meta-run");

        assertThat(result).containsEntry("videoCaptions", "[00:01] 旁白");
        assertThat(result).containsEntry("videoScript", "原始脚本");
        assertThat(result).containsEntry("charactersDescription", "一位女性博主");
        assertThat(result).containsEntry("voiceDescription", "清亮");
        assertThat(result).containsEntry("propsDescription", "一个白瓷大碗");
        assertThat(result).containsEntry("sceneDescription", "面馆");
        // record.run_id 优先于 meta runId。
        assertThat(result).containsEntry("runId", "r1");
    }

    @Test
    @DisplayName("camelCase 别名 + meta runId 兜底")
    void normalizesCamelCaseAndUsesMetaRunId() {
        String content = """
                { "videoCaptions": "c", "videoScript": "s", "charactersDescription": "ch",
                  "voiceDescription": "v", "propsDescription": "p", "sceneDescription": "sc" }""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, "meta-run");

        assertThat(result).containsEntry("runId", "meta-run");
        assertThat(result).containsEntry("sceneDescription", "sc");
    }

    @Test
    @DisplayName("```json 代码块围栏被剥离")
    void stripsCodeFence() {
        String content = """
                ```json
                { "video_captions": "x", "scene_description": "s" }
                ```""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, null);

        assertThat(result).containsEntry("videoCaptions", "x");
    }

    @Test
    @DisplayName("video_script 数组 → 多行格式化（含镜头号/时长/画面）")
    void formatsScriptArrayAsMultiline() {
        String content = """
                { "scene_description": "s",
                  "video_script": [
                    { "shot_number": 1, "shot_type": "中景", "visual_content": "女生吃面",
                      "camera_movement": "固定", "dialogue_narration": "无", "on_screen_text": "无",
                      "duration_seconds": 5, "notes": "暖色" }
                  ] }""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, null);

        String script = (String) result.get("videoScript");
        assertThat(script).contains("镜头 1 | 中景 | 5s");
        assertThat(script).contains("画面：女生吃面");
        assertThat(script).contains("运镜：固定");
        assertThat(script).contains("备注：暖色");
        // dialogue/on_screen_text 为 "无" 时不输出对应行。
        assertThat(script).doesNotContain("台词/旁白");
        assertThat(script).doesNotContain("字幕");
    }

    @Test
    @DisplayName("缺失 charactersDescription → 按场景/字幕人物关键词回填线索")
    void fillsCharacterHintsWhenMissing() {
        String content = """
                { "video_captions": "一位女生在吃面",
                  "scene_description": "店内环境", "voice_description": "v" }""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, null);

        assertThat(result).containsEntry("charactersDescription", "可见出镜人物线索：一位女生在吃面");
    }

    @Test
    @DisplayName("缺失 propsDescription → 按场景道具关键词回填线索")
    void fillsPropsHintsWhenMissing() {
        String content = """
                { "scene_description": "桌上有一个杯子和一双筷子", "voice_description": "v" }""";

        Map<String, Object> result = BilibiliAnalysisResultNormalizer.normalize(content, null);

        String props = (String) result.get("propsDescription");
        assertThat(props).contains("可见道具/物件：杯子");
        assertThat(props).contains("可见道具/物件：筷子");
    }

    @Test
    @DisplayName("非 JSON 内容 → 502")
    void invalidJsonReturns502() {
        assertThatThrownBy(() -> BilibiliAnalysisResultNormalizer.normalize("not json", null))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
    }

    @Test
    @DisplayName("非对象 JSON（数组）→ 502")
    void nonObjectJsonReturns502() {
        assertThatThrownBy(() -> BilibiliAnalysisResultNormalizer.normalize("[1,2,3]", null))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
    }
}
