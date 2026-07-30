package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoRecreationAdaptationResultNormalizerTest {

    private final VideoRecreationAdaptationResultNormalizer normalizer =
            new VideoRecreationAdaptationResultNormalizer();

    @Test
    void normalizesSnakeCaseAndFiltersInvalidAssets() {
        String content = """
                {
                  "adapted_title": "标题",
                  "adapted_summary": "改编摘要",
                  "adapted_script": [{"shot_number": 1, "shot_type": "中景", "visual_content": "画面", "duration_seconds": 5}],
                  "adapted_voice_description": "音色",
                  "visual_style": "电影感",
                  "tone": "温暖",
                  "character_sheets": [
                    {"id": "c1", "name": "人物", "description": "描述", "three_view_prompt": "三视图"},
                    {"id": "bad"}
                  ],
                  "scene_cards": [{"id": "s1", "title": "场景", "description": "描述", "image_prompt": "图"}],
                  "prop_cards": []
                }
                """;

        Map<String, Object> result = normalizer.normalize(content, "run-1");

        assertThat(result.get("adaptedTitle")).isEqualTo("标题");
        assertThat(result.get("adaptedSummary")).isEqualTo("改编摘要");
        assertThat((String) result.get("adaptedScript")).contains("镜头 1 | 中景 | 5s").contains("画面：画面");
        assertThat(((List<?>) result.get("characterSheets"))).hasSize(1);
        assertThat(((List<?>) result.get("sceneCards"))).hasSize(1);
        assertThat(((List<?>) result.get("propCards"))).isEmpty();
        assertThat(result.get("runId")).isEqualTo("run-1");
    }

    @Test
    void stripsMarkdownCodeFence() {
        String content = "```json\n{\"adapted_summary\": \"摘要\"}\n```";
        Map<String, Object> result = normalizer.normalize(content, null);
        assertThat(result.get("adaptedSummary")).isEqualTo("摘要");
        assertThat(result).doesNotContainKey("runId");
    }

    @Test
    void emptySummaryThrows502() {
        assertThatThrownBy(() -> normalizer.normalize("{\"adapted_summary\": \"\"}", null))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
    }

    @Test
    void invalidJsonThrows502() {
        assertThatThrownBy(() -> normalizer.normalize("not json", null))
                .isInstanceOf(IntelligenceException.class);
    }
}
