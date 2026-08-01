package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link BilibiliRecreationResultNormalizer} 单测：内部（无 HTTP 路由）复刻场景能力的结果归一与 runId 语义。 */
@DisplayName("BilibiliRecreationResultNormalizer（内部复刻场景能力）")
class BilibiliRecreationResultNormalizerTest {

    @Test
    @DisplayName("归一有效场景、丢弃空场景，并以 meta runId 兜底")
    @SuppressWarnings("unchecked")
    void normalizesScenesAndUsesMetaRunIdFallback() {
        String content = """
                ```json
                {
                  "scenes": [
                    { "shot_description": "中景，女生吃面", "character_description": "年轻女生", "action_movement": "夹面",
                      "dialogue_voiceover": "好吃", "scene_environment": "暖色面馆" },
                    { "action_movement": "无有效视觉字段" }
                  ],
                  "overall_style": "日系暖色"
                }
                ```""";

        Map<String, Object> result = BilibiliRecreationResultNormalizer.normalize(content, "chatcmpl-recreation");

        assertThat(result).containsEntry("overallStyle", "日系暖色");
        assertThat(result).containsEntry("runId", "chatcmpl-recreation");
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) result.get("scenes");
        assertThat(scenes).hasSize(1);
        assertThat(scenes.getFirst()).containsEntry("shotDescription", "中景，女生吃面");
    }

    @Test
    @DisplayName("上游 run_id 优先于 meta runId")
    void upstreamRunIdWinsOverMetaRunId() {
        String content = """
                { "run_id": "upstream", "scenes": [ { "shot_description": "镜头" } ] }""";

        Map<String, Object> result = BilibiliRecreationResultNormalizer.normalize(content, "meta");

        assertThat(result).containsEntry("runId", "upstream");
    }

    @Test
    @DisplayName("空 scenes → 502")
    void emptyScenesReturn502() {
        assertThatThrownBy(() -> BilibiliRecreationResultNormalizer.normalize("{\"scenes\": []}", null))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(502));
    }
}
