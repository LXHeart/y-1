package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.media.StoreMediaModerationService;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 视频归档送审钩子（任务书 #45 登记的视频侧闭环，此前仅图片侧有
 * {@code GeneratedMediaModerationGateTest}）：归档成功（对象存储 + media_reference +
 * outbox activated）后，以归档行（purpose=video_asset）与归档字节异步送审——advisory，不阻塞归档返回。
 */
@DisplayName("视频归档 → 多模态审核异步钩子")
class VideoAssetArchiveModerationGateTest {

	private final MediaReferenceRepository mediaRefs = mock(MediaReferenceRepository.class);
	private final OutboxRepository outbox = mock(OutboxRepository.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<ObjectStorageAdapter> storageProvider = mock(ObjectProvider.class);
	private final ObjectStorageAdapter storage = mock(ObjectStorageAdapter.class);
	private final StoreMediaModerationService moderation = mock(StoreMediaModerationService.class);
	private final TransactionalOperator transactions = mock(TransactionalOperator.class);
	private final com.grassland.intelligence.ai.controlplane.TrustedOriginService trustedOrigins = mock(
			com.grassland.intelligence.ai.controlplane.TrustedOriginService.class);

	private VideoAssetArchiveService service;

	@BeforeEach
	void setUp() {
		when(storageProvider.getIfAvailable()).thenReturn(storage);
		// 事务壳直通：单测只验证归档→送审编排，不落真库
		when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
		// 产物 origin 校验：sandbox 分支不校验，空受信集即 fail-closed 语义
		when(trustedOrigins.enabledOrigins()).thenReturn(java.util.Set.of());
		VideoGenerationProperties properties = new VideoGenerationProperties();
		properties.setMode("sandbox");
		properties.setRequestTimeout(Duration.ofSeconds(5));
		service = new VideoAssetArchiveService(mediaRefs, storageProvider, outbox, transactions, properties, moderation,
				trustedOrigins);
	}

	@Test
	void sandboxArchiveTriggersAsyncModerationWithArchivedReferenceAndBytes() {
		VideoGenerationJob job = job();
		when(mediaRefs.insert(any(MediaReference.class)))
				.thenAnswer(inv -> Mono.just(inv.getArgument(0, MediaReference.class)));
		when(outbox.append(any())).thenReturn(Mono.empty());

		String result = service.archive(job, "https://provider.example/out.mp4").block();

		assertThat(result).isEqualTo("/api/media/" + job.id());
		verify(storage).putObject(any(), any(), any());
		verify(outbox).append(any());

		// 送审钩子：归档行（video_asset / generated / ACTIVE）+ 归档字节，异步不阻塞返回
		ArgumentCaptor<MediaReference> mediaCaptor = ArgumentCaptor.forClass(MediaReference.class);
		ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
		verify(moderation, timeout(1000)).moderateGeneratedAsync(mediaCaptor.capture(), bytesCaptor.capture());
		assertThat(mediaCaptor.getValue().purpose()).isEqualTo("video_asset");
		assertThat(mediaCaptor.getValue().domainType()).isEqualTo("video_generation_job");
		assertThat(mediaCaptor.getValue().status()).isEqualTo(MediaStatus.ACTIVE);
		// sandbox 归档字节是 ftyp 探针（非 provider 下载）
		assertThat(bytesCaptor.getValue()).containsExactly(new byte[]{0, 0, 0, 8, 'f', 't', 'y', 'p'});
	}

	@Test
	void moderationHookReceivesReferenceOnlyAfterPersistSucceeds() {
		VideoGenerationJob job = job();
		when(mediaRefs.insert(any(MediaReference.class)))
				.thenReturn(Mono.error(new IllegalStateException("media_reference 落库失败")));

		// 归档链在 insert 失败处断掉——不送审、不返回媒体句柄
		service.archive(job, "https://provider.example/out.mp4").onErrorResume(error -> Mono.empty()).block();
		verify(moderation, org.mockito.Mockito.never()).moderateGeneratedAsync(any(), any());
	}

	private static VideoGenerationJob job() {
		return new VideoGenerationJob(UUID.randomUUID(), "acct-1", "org-1", "idem-1", null, null, "sandbox",
				"sandbox-video-v1", null, "succeeded", 100, null, "https://provider.example/out.mp4", 5, null, null,
				null, 0, 0, null, null, null, null, 0, null, 0, null, null, null);
	}
}
