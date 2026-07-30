package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link VideoRecreationImageService} 批量顺序/失败/取消语义单元测试（草场 Slice 9）。 */
@ExtendWith(MockitoExtension.class)
class VideoRecreationImageServiceTest {

    private static final MediaOwner OWNER = new MediaOwner("acct-1", "org-1");

    @Mock
    private ArticleImageService articleImages;

    private VideoRecreationImageService service;

    @BeforeEach
    void setUp() {
        service = new VideoRecreationImageService(articleImages);
    }

    @Test
    void singleAssetDelegatesToArticleImagesWithVideoAssetPurpose() {
        Asset.CharacterAsset asset = new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1");
        GeneratedImageResponse response = new GeneratedImageResponse("/api/article-generation/generated-images/x", "p");
        when(articleImages.generate(any(), any(), eq(MediaPurpose.VIDEO_ASSET))).thenReturn(Mono.just(response));

        GeneratedImageResponse result = service.generateAsset(asset, null, "1024x1792", OWNER).block();

        assertThat(result).isEqualTo(response);
        verify(articleImages).generate(any(), eq(OWNER), eq(MediaPurpose.VIDEO_ASSET));
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
        when(articleImages.generate(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0, ArticleImageService.GenerateCommand.class).prompt();
            if (prompt.contains("名1")) return Mono.just(r1);
            if (prompt.contains("名2")) return Mono.just(r2);
            if (prompt.contains("名3")) return Mono.just(r3);
            return Mono.<GeneratedImageResponse>empty();
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
        when(articleImages.generate(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0, ArticleImageService.GenerateCommand.class).prompt();
            if (prompt.contains("名2")) return Mono.<GeneratedImageResponse>error(new RuntimeException("生成失败"));
            return Mono.just(r1);
        });

        // collectList 在 inner 出错时直接 error，绝不发出已完成前缀——忠实复刻「单张抛错即整请求失败」。
        StepVerifier.create(service.generateAllAssets(assets, null, "1024x1792", OWNER))
                .verifyError(RuntimeException.class);
        // 第三项绝不应开始（顺序生成 + 第二项出错即终止）。
        ArgumentCaptor<ArticleImageService.GenerateCommand> captor =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(articleImages, times(2)).generate(captor.capture(), any(), any());
        assertThat(captor.getAllValues()).noneMatch(cmd -> cmd.prompt().contains("名3"));
    }

    @Test
    void batchStopsFurtherGenerationWhenDownstreamCancels() {
        List<Asset> assets = List.of(
                new Asset.CharacterAsset("id1", "名1", "描述1", "三视图1"),
                new Asset.CharacterAsset("id2", "名2", "描述2", "三视图2"),
                new Asset.CharacterAsset("id3", "名3", "描述3", "三视图3"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        when(articleImages.generate(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0, ArticleImageService.GenerateCommand.class).prompt();
            if (prompt.contains("名2")) return Mono.<GeneratedImageResponse>never();
            return Mono.just(r1);
        });

        StepVerifier.create(service.generateAllAssets(assets, null, "1024x1792", OWNER))
                .expectSubscription()
                .thenAwait(java.time.Duration.ofMillis(100))
                .thenCancel()
                .verify();
        // 取消后第三项绝不应开始——忠实复刻「断连即停止后续生图」。
        ArgumentCaptor<ArticleImageService.GenerateCommand> captor =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(articleImages, atMost(2)).generate(captor.capture(), any(), any());
        assertThat(captor.getAllValues()).noneMatch(cmd -> cmd.prompt().contains("名3"));
    }

    @Test
    void batchScenesCollectResultsInOrder() {
        List<VideoScene> scenes = List.of(
                new VideoScene("镜头1", "角色1", "", "", "环境1"),
                new VideoScene("镜头2", "角色2", "", "", "环境2"));
        GeneratedImageResponse r1 = new GeneratedImageResponse("url1", null);
        GeneratedImageResponse r2 = new GeneratedImageResponse("url2", null);
        when(articleImages.generate(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0, ArticleImageService.GenerateCommand.class).prompt();
            if (prompt.contains("镜头1")) return Mono.just(r1);
            if (prompt.contains("镜头2")) return Mono.just(r2);
            return Mono.<GeneratedImageResponse>empty();
        });

        StepVerifier.create(service.generateAllScenes(scenes, null, "1024x1792", OWNER))
                .expectNext(List.of(r1, r2))
                .verifyComplete();
    }
}
