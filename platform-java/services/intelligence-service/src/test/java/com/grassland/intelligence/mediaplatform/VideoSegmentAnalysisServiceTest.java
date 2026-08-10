package com.grassland.intelligence.mediaplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoSegmentAnalysisServiceTest {
    @Test
    void mergesSegmentsInOrderAndDeduplicatesRepeatedFields() {
        Map<String, Object> merged = VideoSegmentAnalysisService.merge(List.of(
                Map.of("videoCaptions", "第一段", "voiceDescription", "男声", "runId", "run-1"),
                Map.of("videoCaptions", "第二段", "voiceDescription", "男声", "sceneDescription", "室外")));

        assertThat(merged).containsEntry("videoCaptions", "第一段\n第二段")
                .containsEntry("voiceDescription", "男声")
                .containsEntry("sceneDescription", "室外")
                .containsEntry("runId", "run-1");
    }
}
