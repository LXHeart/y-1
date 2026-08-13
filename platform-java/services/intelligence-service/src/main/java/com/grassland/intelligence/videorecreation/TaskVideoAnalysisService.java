package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.mediaplatform.VideoAnalysisPrompts;
import com.grassland.intelligence.mediaplatform.VideoAnalysisResultNormalizer;
import com.grassland.intelligence.mediaplatform.VideoSegmentAnalysisService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Runs short or segmented video analysis with one frozen task snapshot and one AI run. */
@Service
public class TaskVideoAnalysisService {
    private final VideoRecreationTaskCreationContext creationContexts;
    private final FrozenTextExecutionService frozenText;
    private final VideoSegmentAnalysisService segments;

    public TaskVideoAnalysisService(
            VideoRecreationTaskCreationContext creationContexts,
            FrozenTextExecutionService frozenText,
            VideoSegmentAnalysisService segments) {
        this.creationContexts = creationContexts;
        this.frozenText = frozenText;
        this.segments = segments;
    }

    public Mono<Map<String, Object>> analyzeShort(
            String publicVideoUrl,
            String accountId,
            VideoRecreationTaskRequest task,
            ServerWebExchange exchange) {
        return creationContexts.bind(task.contextSnapshotId(), accountId, task.targetPlatform())
                .flatMap(binding -> frozenText.execute(
                        exchange, binding.snapshot().id(), List.of(
                                binding.promptContext(),
                                ChatMessage.user(List.of(
                                        ContentPart.video(publicVideoUrl),
                                        ContentPart.text(VideoAnalysisPrompts.analysis())))),
                        4096, CreditFeature.VIDEO_ANALYSIS,
                        completion -> VideoAnalysisResultNormalizer.normalize(
                                completion.content(), null)));
    }

    public Mono<Map<String, Object>> analyzeSegments(
            String sourcePlatform,
            List<String> artifactIds,
            String accountId,
            VideoRecreationTaskRequest task,
            ServerWebExchange exchange) {
        return creationContexts.bind(task.contextSnapshotId(), accountId, task.targetPlatform())
                .flatMap(binding -> segments.analyzeTask(
                        sourcePlatform, artifactIds, exchange,
                        binding.snapshot().id(), binding.promptContext()));
    }
}
