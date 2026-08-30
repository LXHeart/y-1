package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService.Traced;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 视频改编出图编排（草场 intelligence Slice 9）。镜像 legacy {@code video-recreation-image.service.ts}：
 * 构建 prompt 后单张持久化委托 {@link ArticleImageService}（{@code purpose=VIDEO_ASSET}，复用 Slice 8 媒体存储与
 * {@code /api/article-generation/generated-images/{id}} 读路径），批量顺序生成。
 *
 * <p>批量用 {@code concatMap} 顺序保序、逐项生成；任一项失败 → 整 {@link Mono} 出错（丢弃已完成前缀，错误信封由
 * 全局 handler 返回），与 legacy「单张抛错即整请求失败」一致。legacy 的「客户端 abort 返回已完成前缀」在
 * 非流式 WebFlux 下无可观测效果（响应一次性缓冲在末尾写出，客户端断连即响应式取消、停止后续 provider 调用即可），
 * 故不在此合成部分响应——忠实目标是「断连即停止后续生图」。
 */
@Service
public class VideoRecreationImageService {

    private final CreationGenerationRecorder lineage;
    private final IndependentImageGenerationService independentImages;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public VideoRecreationImageService(
            CreationGenerationRecorder lineage,
            IndependentImageGenerationService independentImages) {
        this.lineage = lineage;
        this.independentImages = independentImages;
    }

    public Mono<GeneratedImageResponse> generateAsset(
            Asset asset, String visualStyle, String size, MediaOwner owner) {
        String prompt = VideoRecreationPrompts.buildAssetImagePrompt(asset, visualStyle);
        return generate(prompt, size, owner)
                .flatMap(traced -> record(
                                CreationGeneration.Kind.ASSET_IMAGE, List.of(prompt),
                                assetSummary(asset, visualStyle, size), List.of(traced.response()), traced, owner)
                        .thenReturn(traced.response()));
    }

    public Mono<List<GeneratedImageResponse>> generateAllAssets(
            List<Asset> assets, String visualStyle, String size, MediaOwner owner) {
        List<String> prompts = assets.stream()
                .map(asset -> VideoRecreationPrompts.buildAssetImagePrompt(asset, visualStyle)).toList();
        return generateBatch(prompts, owner, size)
                .flatMap(traces -> record(
                                CreationGeneration.Kind.ASSET_IMAGE, prompts,
                                assetBatchSummary(assets, visualStyle, size), traces, owner)
                        .thenReturn(responses(traces)));
    }

    public Mono<GeneratedImageResponse> generateScene(
            VideoScene scene, String overallStyle, String size, MediaOwner owner) {
        String prompt = VideoRecreationPrompts.buildSceneImagePrompt(scene, overallStyle);
        return generate(prompt, size, owner)
                .flatMap(traced -> record(
                                CreationGeneration.Kind.SCENE_IMAGE, List.of(prompt),
                                sceneSummary(scene, overallStyle, size), List.of(traced.response()), traced, owner)
                        .thenReturn(traced.response()));
    }

    public Mono<List<GeneratedImageResponse>> generateAllScenes(
            List<VideoScene> scenes, String overallStyle, String size, MediaOwner owner) {
        List<String> prompts = scenes.stream()
                .map(scene -> VideoRecreationPrompts.buildSceneImagePrompt(scene, overallStyle)).toList();
        return generateBatch(prompts, owner, size)
                .flatMap(traces -> record(
                                CreationGeneration.Kind.SCENE_IMAGE, prompts,
                                sceneBatchSummary(scenes, overallStyle, size), traces, owner)
                        .thenReturn(responses(traces)));
    }

    /**
     * 任务书 #58 决策 G：静态 env 生图端点已删——出图统一走 {@link IndependentImageGenerationService}
     * （BYOK &gt; 控制面 image_generation 行；预算闸 + ai_run 留痕，feature=null 不扣积分，保持免费语义）。
     */
    private Mono<Traced> generate(String prompt, String size, MediaOwner owner) {
        return independentImages.generate(
                new ArticleImageService.GenerateCommand(prompt, size, List.of()),
                owner.accountId(), owner.organizationId(), MediaPurpose.VIDEO_ASSET);
    }

    private Mono<List<Traced>> generateBatch(List<String> prompts, MediaOwner owner, String size) {
        return Flux.fromIterable(prompts)
                .concatMap(prompt -> generate(prompt, size, owner))
                .collectList();
    }

    private static List<GeneratedImageResponse> responses(List<Traced> traces) {
        return traces.stream().map(Traced::response).toList();
    }

    private Mono<CreationGeneration> record(
            CreationGeneration.Kind kind, List<String> prompts, Map<String, Object> input,
            List<Traced> traces, MediaOwner owner) {
        if (lineage == null || traces.isEmpty()) return Mono.empty();
        return record(kind, prompts, input, responses(traces), traces.getFirst(), owner);
    }

    /** provider/model/runId 回填本次生成的真实路由（#58：静态配置兜底已删）。 */
    private Mono<CreationGeneration> record(
            CreationGeneration.Kind kind, List<String> prompts, Map<String, Object> input,
            List<GeneratedImageResponse> responses, Traced traced, MediaOwner owner) {
        if (lineage == null) return Mono.empty();
        ImageResult result = imageResult(responses, String.valueOf(input.get("size")));
        return lineage.record(new CreationGenerationRecorder.Command(
                kind, CreationGeneration.Mode.INDEPENDENT, null, traced.aiRunId(),
                CreationGeneration.Resolution.PLATFORM, traced.provider(), traced.model(),
                null, null, String.join("\n\n---\n\n", prompts),
                input, List.of(), Map.of("images", result.images()), result.mediaIds(),
                owner.accountId(), owner.organizationId()));
    }

    private Map<String, Object> assetSummary(Asset asset, String visualStyle, String size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetType", assetType(asset));
        result.put("visualStyle", visualStyle);
        result.put("size", size);
        result.put("asset", mapper.convertValue(asset, new TypeReference<Map<String, Object>>() {}));
        return result;
    }

    private Map<String, Object> assetBatchSummary(List<Asset> assets, String visualStyle, String size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetType", assets.isEmpty() ? null : assetType(assets.getFirst()));
        result.put("visualStyle", visualStyle);
        result.put("size", size);
        result.put("assets", assets.stream().map(asset -> mapper.convertValue(
                asset, new TypeReference<Map<String, Object>>() {})).toList());
        return result;
    }

    private Map<String, Object> sceneSummary(VideoScene scene, String overallStyle, String size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallStyle", overallStyle);
        result.put("size", size);
        result.put("scene", mapper.convertValue(scene, new TypeReference<Map<String, Object>>() {}));
        return result;
    }

    private Map<String, Object> sceneBatchSummary(List<VideoScene> scenes, String overallStyle, String size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallStyle", overallStyle);
        result.put("size", size);
        result.put("scenes", scenes.stream().map(scene -> mapper.convertValue(
                scene, new TypeReference<Map<String, Object>>() {})).toList());
        return result;
    }

    static ImageResult imageResult(List<GeneratedImageResponse> responses, String size) {
        List<Map<String, Object>> images = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (GeneratedImageResponse response : responses) {
            UUID mediaId = mediaId(response.imageUrl());
            if (mediaId != null) ids.add(mediaId);
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("mediaId", mediaId);
            image.put("imageUrl", response.imageUrl());
            image.put("size", size);
            image.put("revisedPrompt", response.revisedPrompt());
            images.add(image);
        }
        return new ImageResult(List.copyOf(images), List.copyOf(ids));
    }

    private static UUID mediaId(String imageUrl) {
        if (imageUrl == null) return null;
        int slash = imageUrl.lastIndexOf('/');
        String value = slash >= 0 ? imageUrl.substring(slash + 1) : imageUrl;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String assetType(Asset asset) {
        return switch (asset) {
            case Asset.CharacterAsset ignored -> "character-three-view";
            case Asset.SceneAsset ignored -> "scene";
            case Asset.PropAsset ignored -> "prop";
        };
    }

    record ImageResult(List<Map<String, Object>> images, List<UUID> mediaIds) {}
}
