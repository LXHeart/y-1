package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

/**
 * AI 生成结果多模态审核钩子（任务书 #45 登记）：受审 purpose gate 与异步送审——
 * {@code video_asset}/{@code article_generated} 进审、私有素材明确排除、异步失败静默。
 */
@DisplayName("生成媒体多模态审核 gate 与异步钩子")
class GeneratedMediaModerationGateTest {

    private static final byte[] PNG = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};

    private final RoutedTextCompletionService ai = mock(RoutedTextCompletionService.class);
    private final StoreMediaModerationRepository moderation = mock(StoreMediaModerationRepository.class);
    private final VideoFrameExtractor frameExtractor = mock(VideoFrameExtractor.class);

    private StoreMediaModerationService service;

    @BeforeEach
    void setUp() {
        service = new StoreMediaModerationService(ai, moderation, frameExtractor, new MockEnvironment());
    }

    @Test
    void generatedPurposesPassTheGateAndReachTheModel() {
        when(moderation.exists(any())).thenReturn(Mono.just(false));
        when(ai.completePlatformOnly(any(), anyInt(), any(), any())).thenReturn(Mono.error(new IllegalStateException("模型不可用")));

        assertThat(service.moderateOnce(media("article_generated", "image/png"), Mono.just(PNG)).blockOptional())
                .as("模型失败 advisory 降级为未审（empty），但已越过 purpose gate")
                .isEmpty();

        assertThat(service.moderateOnce(media("video_asset", "image/png"), Mono.just(PNG)).blockOptional())
                .isEmpty();
        verify(ai, timeout(1000).times(2)).completePlatformOnly(any(), anyInt(), any(), any());
    }

    @Test
    void privateUploadPurposesStayExcluded() {
        assertThat(service.moderateOnce(media("user_upload", "image/png"), Mono.just(PNG)).block())
                .isNull();
        assertThat(service.moderateOnce(media("content_asset", "image/png"), Mono.just(PNG)).block())
                .isNull();
        verify(ai, never()).completePlatformOnly(any(), anyInt(), any(), any());
        verify(moderation, never()).exists(any());
    }

    @Test
    void moderateGeneratedAsyncPersistsVerdictOffTheCallingThread() {
        when(moderation.exists(any())).thenReturn(Mono.just(false));
        when(frameExtractor.extract(any())).thenReturn(List.of(new byte[] {1, 2, 3}));
        when(ai.completePlatformOnly(any(), anyInt(), any(), any())).thenReturn(Mono.just(
                new TextCompletionResult("{\"verdict\":\"pass\",\"findings\":[]}", 1, 1, "run-g1")));

        service.moderateGeneratedAsync(media("video_asset", "video/mp4"), "mp4-bytes".getBytes());

        verify(moderation, timeout(2000)).upsert(any());
        verify(ai, timeout(2000)).completePlatformOnly(any(), anyInt(), any(), any());
    }

    @Test
    void existingVerdictShortCircuitsAsyncHook() {
        var existing = new StoreMediaModerationRepository.ModerationRow(
                UUID.randomUUID(), "pass", "[]", null, null, Instant.now());
        when(moderation.exists(any())).thenReturn(Mono.just(true));
        when(moderation.find(any())).thenReturn(Mono.just(existing));

        service.moderateGeneratedAsync(media("article_generated", "image/png"), PNG);

        verify(ai, never()).completePlatformOnly(any(), anyInt(), any(), any());
    }

    private static MediaReference media(String purpose, String mimeType) {
        return new MediaReference(UUID.randomUUID(), "acct-1", "org-1", purpose, null, null,
                "media/x/" + purpose, mimeType, PNG.length, "checksum", "generated", MediaStatus.ACTIVE,
                Instant.now(), null, null);
    }
}
