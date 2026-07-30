package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.List;
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

    private final ArticleImageService articleImages;

    public VideoRecreationImageService(ArticleImageService articleImages) {
        this.articleImages = articleImages;
    }

    public Mono<GeneratedImageResponse> generateAsset(
            Asset asset, String visualStyle, String size, MediaOwner owner) {
        String prompt = VideoRecreationPrompts.buildAssetImagePrompt(asset, visualStyle);
        return articleImages.generate(
                new ArticleImageService.GenerateCommand(prompt, size, List.of()), owner, MediaPurpose.VIDEO_ASSET);
    }

    public Mono<List<GeneratedImageResponse>> generateAllAssets(
            List<Asset> assets, String visualStyle, String size, MediaOwner owner) {
        return Flux.fromIterable(assets)
                .concatMap(asset -> generateAsset(asset, visualStyle, size, owner))
                .collectList();
    }

    public Mono<GeneratedImageResponse> generateScene(
            VideoScene scene, String overallStyle, String size, MediaOwner owner) {
        String prompt = VideoRecreationPrompts.buildSceneImagePrompt(scene, overallStyle);
        return articleImages.generate(
                new ArticleImageService.GenerateCommand(prompt, size, List.of()), owner, MediaPurpose.VIDEO_ASSET);
    }

    public Mono<List<GeneratedImageResponse>> generateAllScenes(
            List<VideoScene> scenes, String overallStyle, String size, MediaOwner owner) {
        return Flux.fromIterable(scenes)
                .concatMap(scene -> generateScene(scene, overallStyle, size, owner))
                .collectList();
    }
}
