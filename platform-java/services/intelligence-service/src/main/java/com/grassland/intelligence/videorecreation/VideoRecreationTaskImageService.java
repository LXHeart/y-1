package com.grassland.intelligence.videorecreation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.articleimage.TaskImageGenerationService;
import com.grassland.intelligence.articleimage.TaskImageGenerationService.GeneratedImageWithTrace;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Task-bound video recreation images with one lineage row per HTTP generation call. */
@Service
public class VideoRecreationTaskImageService {

    private final TaskImageGenerationService images;
    private final CreationGenerationRecorder lineage;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public VideoRecreationTaskImageService(
            TaskImageGenerationService images, CreationGenerationRecorder lineage) {
        this.images = images;
        this.lineage = lineage;
    }

    public Mono<GeneratedImageResponse> generateAsset(
            Asset asset, String visualStyle, String size,
            VideoRecreationTaskCreationContext.Binding binding) {
        String prompt = VideoRecreationPrompts.buildAssetImagePrompt(asset, visualStyle);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assetType", assetType(asset));
        input.put("visualStyle", visualStyle);
        input.put("size", size);
        input.put("asset", value(asset));
        return generateOne(prompt, size, binding)
                .flatMap(trace -> record(CreationGeneration.Kind.ASSET_IMAGE,
                                List.of(prompt), input, List.of(trace), size, binding)
                        .thenReturn(trace.response()));
    }

    public Mono<List<GeneratedImageResponse>> generateAssets(
            List<Asset> assets, String visualStyle, String size,
            VideoRecreationTaskCreationContext.Binding binding) {
        List<String> prompts = assets.stream()
                .map(asset -> VideoRecreationPrompts.buildAssetImagePrompt(asset, visualStyle)).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assetType", assets.isEmpty() ? null : assetType(assets.getFirst()));
        input.put("visualStyle", visualStyle);
        input.put("size", size);
        input.put("assets", assets.stream().map(this::value).toList());
        return generateMany(prompts, size, binding)
                .flatMap(traces -> record(CreationGeneration.Kind.ASSET_IMAGE,
                                prompts, input, traces, size, binding)
                        .thenReturn(traces.stream().map(GeneratedImageWithTrace::response).toList()));
    }

    public Mono<GeneratedImageResponse> generateScene(
            VideoScene scene, String overallStyle, String size,
            VideoRecreationTaskCreationContext.Binding binding) {
        String prompt = VideoRecreationPrompts.buildSceneImagePrompt(scene, overallStyle);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("overallStyle", overallStyle);
        input.put("size", size);
        input.put("scene", value(scene));
        return generateOne(prompt, size, binding)
                .flatMap(trace -> record(CreationGeneration.Kind.SCENE_IMAGE,
                                List.of(prompt), input, List.of(trace), size, binding)
                        .thenReturn(trace.response()));
    }

    public Mono<List<GeneratedImageResponse>> generateScenes(
            List<VideoScene> scenes, String overallStyle, String size,
            VideoRecreationTaskCreationContext.Binding binding) {
        List<String> prompts = scenes.stream()
                .map(scene -> VideoRecreationPrompts.buildSceneImagePrompt(scene, overallStyle)).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("overallStyle", overallStyle);
        input.put("size", size);
        input.put("scenes", scenes.stream().map(this::value).toList());
        return generateMany(prompts, size, binding)
                .flatMap(traces -> record(CreationGeneration.Kind.SCENE_IMAGE,
                                prompts, input, traces, size, binding)
                        .thenReturn(traces.stream().map(GeneratedImageWithTrace::response).toList()));
    }

    private Mono<GeneratedImageWithTrace> generateOne(
            String prompt, String size, VideoRecreationTaskCreationContext.Binding binding) {
        return images.generateForBoundContextTraced(
                new ArticleImageService.GenerateCommand(prompt, size, List.of()),
                binding.snapshot(), binding.promptContext(), MediaPurpose.VIDEO_ASSET);
    }

    private Mono<List<GeneratedImageWithTrace>> generateMany(
            List<String> prompts, String size, VideoRecreationTaskCreationContext.Binding binding) {
        return Flux.fromIterable(prompts).concatMap(prompt -> generateOne(prompt, size, binding)).collectList();
    }

    private Mono<CreationGeneration> record(
            CreationGeneration.Kind kind, List<String> prompts, Map<String, Object> input,
            List<GeneratedImageWithTrace> traces, String size,
            VideoRecreationTaskCreationContext.Binding binding) {
        GeneratedImageWithTrace first = traces.getFirst();
        VideoRecreationImageService.ImageResult result = VideoRecreationImageService.imageResult(
                traces.stream().map(GeneratedImageWithTrace::response).toList(), size);
        return lineage.record(new CreationGenerationRecorder.Command(
                kind, CreationGeneration.Mode.TASK, binding.snapshot().id(), first.aiRunId(),
                CreationGeneration.Resolution.PLATFORM, first.provider(), first.model(),
                first.platformModelVersion(), null, String.join("\n\n---\n\n", prompts), input,
                List.of(), Map.of("images", result.images()), result.mediaIds(),
                binding.snapshot().accountId(), binding.snapshot().organizationId()));
    }

    private Map<String, Object> value(Object object) {
        return mapper.convertValue(object, new TypeReference<Map<String, Object>>() {});
    }

    private static String assetType(Asset asset) {
        return switch (asset) {
            case Asset.CharacterAsset ignored -> "character-three-view";
            case Asset.SceneAsset ignored -> "scene";
            case Asset.PropAsset ignored -> "prop";
        };
    }
}
