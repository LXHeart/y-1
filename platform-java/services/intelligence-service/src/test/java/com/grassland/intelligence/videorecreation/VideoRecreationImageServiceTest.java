package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService.Traced;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link VideoRecreationImageService} 批量顺序/失败/取消语义单元测试（草场 Slice 9）。
 * 任务书 #58 决策 G 起出图统一经 {@link IndependentImageGenerationService}（预算闸 + ai_run 留痕）。
 */
@ExtendWith(MockitoExtension.class)
class VideoRecreationImageServiceTest {

    private static final MediaOwner OWNER = new MediaOwner("acct-1", "org-1");

    @Mock
    private IndependentImageGenerationService independentImages;

    private VideoRecreationImageService service;

    @BeforeEach
    void setUp() {
        service = new VideoRecreationImageService(null, independentImages);
    }

    private static Traced traced(GeneratedImageResponse response) {
        return new Traced(response, UUID.randomUUID(), "qwen", "wanx-v1");
    }

    @Test
    void singleAssetDelegatesToIndependentGenerationWithVideoAssetPurpose() {
        Asset.CharacterAsset asset = new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1");
        GeneratedImageResponse response = new GeneratedImageResponse("/api/article-generation/generated-images/x", "p");
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenReturn(Mono.just(traced(response)));

        GeneratedImageResponse result = service.generateAsset(asset, null, "1024x1792", OWNER).block();

        assertThat(result).isEqualTo(response);
        verify(independentImages).generate(any(), any(), any(), eqPurpose());
    }

    private static MediaPurpose eqPurpose() {
        return org.mockito.ArgumentMatchers.eq(MediaPurpose.VIDEO_ASSET);
    }

    @Test
    void lineageFailurePreventsSuccessfulGenerationResponse() {
        CreationGenerationRecorder lineage = org.mockito.Mockito.mock(CreationGenerationRecorder.class);
        VideoRecreationImageService traced =
                new VideoRecreationImageService(lineage, independentImages);
        Asset.CharacterAsset asset = new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1");
        String mediaId = UUID.randomUUID().toString();
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenReturn(Mono.just(
                traced(new GeneratedImageResponse("/api/article-generation/generated-images/" + mediaId, "p"))));
        when(lineage.record(any())).thenReturn(Mono.error(new RuntimeException("lineage db down")));

        StepVerifier.create(traced.generateAsset(asset, null, "1024x1792", OWNER))
                .expectErrorMatches(error -> "lineage db down".equals(error.getMessage()))
                .verify();
        verify(lineage).record(any());
    }

    @Test
    void batchAssetsCollectResultsInOrder() {
        List<Asset> assets = List.of(
                new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1"),
                new Asset.CharacterAsset("id2", "名2", "描述2", "三视图2"),
                new Asset.CharacterAsset("id3", "名3", "描述3", "三视图3"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        GeneratedImageResponse r2 = new GeneratedImageResponse("url2", null);
        GeneratedImageResponse r3 = new GeneratedImageResponse("url3", null);
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenAnswer(inv -> {
            ArticleImageService.GenerateCommand command = inv.getArgument(0);
            if (command.prompt().contains("名1")) return Mono.just(traced(r1));
            if (command.prompt().contains("名2")) return Mono.just(traced(r2));
            if (command.prompt().contains("名3")) return Mono.just(traced(r3));
            return Mono.<Traced>empty();
        });

        StepVerifier.create(service.generateAllAssets(assets, null, "1024x1792", OWNER))
                .expectNext(List.of(r1, r2, r3))
                .verifyComplete();
    }

    @Test
    void batchAssetErrorDiscardsCompletedPrefix() {
        List<Asset> assets = List.of(
                new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1"),
                new Asset.CharacterAsset("id2", "名2", "描述2", "三视图2"),
                new Asset.CharacterAsset("id3", "名3", "描述3", "三视图3"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenAnswer(inv -> {
            ArticleImageService.GenerateCommand command = inv.getArgument(0);
            if (command.prompt().contains("名2")) return Mono.<Traced>error(new RuntimeException("生成失败"));
            return Mono.just(traced(r1));
        });

        // collectList 在 inner 出错时直接 error，绝不发出已完成前缀——忠实复刻「单张抛错即整请求失败」。
        StepVerifier.create(service.generateAllAssets(assets, null, "1024x1792", OWNER))
                .verifyError(RuntimeException.class);
        // 第三项绝不应开始（顺序生成 + 第二项出错即终止）。
        ArgumentCaptor<ArticleImageService.GenerateCommand> captor =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(independentImages, times(2)).generate(captor.capture(), any(), any(), eqPurpose());
        assertThat(captor.getAllValues()).noneMatch(cmd -> cmd.prompt().contains("名3"));
    }

    @Test
    void batchStopsFurtherGenerationWhenDownstreamCancels() {
        List<Asset> assets = List.of(
                new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1"),
                new Asset.CharacterAsset("id2", "名2", "描述2", "三视图2"),
                new Asset.CharacterAsset("id3", "名3", "描述3", "三视图3"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenAnswer(inv -> {
            ArticleImageService.GenerateCommand command = inv.getArgument(0);
            if (command.prompt().contains("名2")) return Mono.<Traced>never();
            return Mono.just(traced(r1));
        });

        StepVerifier.create(service.generateAllAssets(assets, null, "1024x1792", OWNER))
                .expectSubscription()
                .thenAwait(java.time.Duration.ofMillis(100))
                .thenCancel()
                .verify();
        // 取消后第三项绝不应开始——忠实复刻「断连即停止后续生图」。
        ArgumentCaptor<ArticleImageService.GenerateCommand> captor =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(independentImages, atMost(2)).generate(captor.capture(), any(), any(), eqPurpose());
        assertThat(captor.getAllValues()).noneMatch(cmd -> cmd.prompt().contains("名3"));
    }

    @Test
    void batchScenesCollectResultsInOrder() {
        List<VideoScene> scenes = List.of(
                new VideoScene("镜头1", "角色1", "", "", "环境1"),
                new VideoScene("镜头2", "角色2", "", "", "环境2"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        GeneratedImageResponse r2 = new GeneratedImageResponse("url2", null);
        when(independentImages.generate(any(), any(), any(), eqPurpose())).thenAnswer(inv -> {
            ArticleImageService.GenerateCommand command = inv.getArgument(0);
            if (command.prompt().contains("镜头1")) return Mono.just(traced(r1));
            if (command.prompt().contains("镜头2")) return Mono.just(traced(r2));
            return Mono.<Traced>empty();
        });

        StepVerifier.create(service.generateAllScenes(scenes, null, "1024x1792", OWNER))
                .expectNext(List.of(r1, r2))
                .verifyComplete();
    }
}
