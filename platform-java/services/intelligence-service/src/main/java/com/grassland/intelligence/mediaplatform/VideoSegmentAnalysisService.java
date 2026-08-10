package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class VideoSegmentAnalysisService {
    private final AiCapabilityAdapter ai;
    private final String publicOrigin;

    public VideoSegmentAnalysisService(AiCapabilityAdapter ai, Environment environment) {
        this.ai = ai;
        this.publicOrigin = environment.getProperty("app.public-backend-origin", "").replaceFirst("/$", "");
    }

    public Mono<Map<String, Object>> analyze(String platform, List<String> artifactIds, Duration timeout) {
        if (publicOrigin.isBlank()) return Mono.error(new IllegalStateException("PUBLIC_BACKEND_ORIGIN 未配置"));
        return Flux.fromIterable(artifactIds)
                .concatMap(id -> ai.completeMultimodalMeta(List.of(
                                ContentPart.video(publicOrigin + "/api/" + platform + "/analysis-media/" + id),
                                ContentPart.text(VideoAnalysisPrompts.analysis())), timeout)
                        .map(meta -> VideoAnalysisResultNormalizer.normalize(meta.content(), meta.runId())))
                .collectList().map(VideoSegmentAnalysisService::merge);
    }

    static Map<String, Object> merge(List<Map<String, Object>> segments) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("videoCaptions", "videoScript", "charactersDescription", "voiceDescription",
                "propsDescription", "sceneDescription")) {
            String value = segments.stream().map(item -> item.get(field)).filter(String.class::isInstance)
                    .map(String.class::cast).filter(text -> !text.isBlank()).distinct().reduce((a, b) -> a + "\n" + b).orElse(null);
            if (value != null) result.put(field, value);
        }
        segments.stream().map(item -> item.get("runId")).filter(String.class::isInstance).findFirst()
                .ifPresent(runId -> result.put("runId", runId));
        return result;
    }
}
