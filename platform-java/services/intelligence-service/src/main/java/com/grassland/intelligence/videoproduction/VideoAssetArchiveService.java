package com.grassland.intelligence.videoproduction;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.media.MediaLifecycleEvents;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;

/**
 * Copies provider output into private object storage before exposing any result
 * to clients.
 */
@Service
public class VideoAssetArchiveService {
	private static final long MAX_BYTES = 200L * 1024 * 1024;
	private final MediaReferenceRepository mediaRefs;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final VideoGenerationProperties properties;
	private final WebClient client;

	public VideoAssetArchiveService(MediaReferenceRepository mediaRefs,
			ObjectProvider<ObjectStorageAdapter> storageProvider, OutboxRepository outbox,
			TransactionalOperator transactions, VideoGenerationProperties properties) {
		this.mediaRefs = mediaRefs;
		this.storageProvider = storageProvider;
		this.outbox = outbox;
		this.transactions = transactions;
		this.properties = properties;
		this.client = ManagedWebClientFactory
				.builder(VideoAssetArchiveService.class, properties.getRequestTimeout(), (int) MAX_BYTES).build();
	}

	public Mono<String> archive(VideoGenerationJob job, String providerUrl) {
		if (providerUrl == null || providerUrl.isBlank()) {
			return Mono.error(new IllegalStateException("视频 provider 成功响应缺少结果地址"));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IllegalStateException("视频结果归档需要启用对象存储"));
		}
		// One stable media handle per job makes webhook/poll races idempotent.
		UUID mediaId = job.id();
		String key = "media/video_asset/" + mediaId;
		if (properties.getMode().equalsIgnoreCase("sandbox")) {
			return store(job, storage, mediaId, key, new byte[]{0, 0, 0, 8, 'f', 't', 'y', 'p'}, "video/mp4");
		}
		validateProviderUrl(providerUrl);
		return client.get().uri(providerUrl).exchangeToMono(response -> {
			if (!response.statusCode().is2xxSuccessful()) {
				return Mono.error(new IllegalStateException("视频 provider 结果下载失败"));
			}
			long declared = response.headers().contentLength().orElse(-1L);
			if (declared > MAX_BYTES)
				return Mono.error(new IllegalStateException("视频结果大小超出归档限制"));
			return response.bodyToMono(byte[].class).timeout(properties.getRequestTimeout());
		}).flatMap(bytes -> {
			if (bytes.length == 0 || bytes.length > MAX_BYTES) {
				return Mono.error(new IllegalStateException("视频结果大小超出归档限制"));
			}
			String mime = "video/mp4";
			return store(job, storage, mediaId, key, bytes, mime);
		});
	}

	private Mono<String> store(VideoGenerationJob job, ObjectStorageAdapter storage, UUID mediaId, String key,
			byte[] bytes, String mime) {
		return Mono.fromRunnable(() -> storage.putObject(key, bytes, mime)).subscribeOn(Schedulers.boundedElastic())
				.then(transactions.transactional(mediaRefs
						.insert(new MediaReference(mediaId, job.accountId(), job.organizationId(),
								MediaPurpose.VIDEO_ASSET.db(), "video_generation_job", job.id().toString(), key, mime,
								bytes.length, VideoArchiveChecksums.sha256(bytes), "generated", MediaStatus.ACTIVE,
								Instant.now(), null, null))
						.flatMap(active -> outbox.append(MediaLifecycleEvents.activated(active)).thenReturn(active))))
				.thenReturn("/api/media/" + mediaId);
	}

	private void validateProviderUrl(String value) {
		try {
			URI actual = new URI(value);
			URI base = new URI(properties.getBaseUrl());
			if (!("https".equalsIgnoreCase(actual.getScheme()) || "http".equalsIgnoreCase(actual.getScheme()))
					|| !actual.getHost().equalsIgnoreCase(base.getHost()) || actual.getPort() != base.getPort()
					|| (base.getPath() != null && !base.getPath().isBlank()
							&& !actual.getPath().startsWith(base.getPath()))) {
				throw new IllegalStateException("视频 provider 结果地址不在已配置 provider origin 内");
			}
		} catch (URISyntaxException | NullPointerException error) {
			throw new IllegalStateException("视频 provider 结果地址非法", error);
		}
	}

	static final class VideoArchiveChecksums {
		private VideoArchiveChecksums() {
		}
		static String sha256(byte[] bytes) {
			try {
				return java.util.HexFormat.of()
						.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
			} catch (Exception error) {
				throw new IllegalStateException("无法计算视频校验和", error);
			}
		}
	}
}
