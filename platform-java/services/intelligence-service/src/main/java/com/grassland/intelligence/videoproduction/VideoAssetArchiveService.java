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
	private final com.grassland.intelligence.media.StoreMediaModerationService moderation;
	private final com.grassland.intelligence.ai.controlplane.TrustedOriginService trustedOrigins;
	private final WebClient client;

	public VideoAssetArchiveService(MediaReferenceRepository mediaRefs,
			ObjectProvider<ObjectStorageAdapter> storageProvider, OutboxRepository outbox,
			TransactionalOperator transactions, VideoGenerationProperties properties,
			com.grassland.intelligence.media.StoreMediaModerationService moderation,
			com.grassland.intelligence.ai.controlplane.TrustedOriginService trustedOrigins) {
		this.mediaRefs = mediaRefs;
		this.storageProvider = storageProvider;
		this.outbox = outbox;
		this.transactions = transactions;
		this.properties = properties;
		this.moderation = moderation;
		this.trustedOrigins = trustedOrigins;
		this.client = ManagedWebClientFactory
				.builder(VideoAssetArchiveService.class, properties.getRequestTimeout(), (int) MAX_BYTES).build();
	}

	public Mono<String> archive(VideoGenerationJob job, String providerUrl) {
		// 旧链 baseUrl 来自 properties（env 已删；sandbox 恒可用，vendor 行会先被冻结配置漂移拦截）
		return archiveGenerated(job.accountId(), job.organizationId(), job.id(), properties.getBaseUrl(), providerUrl,
				MediaPurpose.VIDEO_ASSET, "video_generation_job", job.id(), "media/video_asset/",
				properties.getMode().equalsIgnoreCase("sandbox"));
	}

	/**
	 * 字节直存归档（任务书 #64 卡8）：沙箱 take 真实 mp4（SandboxMedia lavfi 产物）不经下载， 直接落私有存储 +
	 * media_reference + activated + advisory 送审。
	 */
	public Mono<String> archiveGeneratedBytes(String accountId, String organizationId, UUID mediaId, byte[] bytes,
			MediaPurpose purpose, String domainType, UUID domainId, String keyPrefix) {
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
			return Mono.error(new IllegalStateException("视频结果大小超出归档限制"));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IllegalStateException("视频结果归档需要启用对象存储"));
		}
		return store(accountId, organizationId, storage, mediaId, keyPrefix + mediaId, bytes, "video/mp4", purpose,
				domainType, domainId);
	}

	/**
	 * 通用私有归档（任务书 #64 卡6 take / 卡8 成片复用）：下载（≤200MB、同 origin 校验）→ 私有对象存储 +
	 * media_reference + activated 事件 → 异步 advisory 送审。 一个稳定 media 句柄（调用方给确定性 id）让
	 * webhook/轮询竞态幂等。
	 *
	 * @param sandboxProvider
	 *            true = sandbox://占位符，落 8 字节 ftyp 存根（本地确定性全链用）
	 */
	public Mono<String> archiveGenerated(String accountId, String organizationId, UUID mediaId, String providerBaseUrl,
			String providerUrl, MediaPurpose purpose, String domainType, UUID domainId, String keyPrefix,
			boolean sandboxProvider) {
		if (providerUrl == null || providerUrl.isBlank()) {
			return Mono.error(new IllegalStateException("视频 provider 成功响应缺少结果地址"));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IllegalStateException("视频结果归档需要启用对象存储"));
		}
		String key = keyPrefix + mediaId;
		if (sandboxProvider) {
			return store(accountId, organizationId, storage, mediaId, key, new byte[]{0, 0, 0, 8, 'f', 't', 'y', 'p'},
					"video/mp4", purpose, domainType, domainId);
		}
		validateProviderUrl(providerUrl, providerBaseUrl);
		// 签名 URL 以 URI 直传：uri(String) 模板再编码会毁掉 OSS/CDN 签名（同 TtsWorker）
		return client.get().uri(URI.create(providerUrl)).exchangeToMono(response -> {
			if (!response.statusCode().is2xxSuccessful()) {
				return Mono
						.error(new IllegalStateException("视频 provider 结果下载失败: HTTP " + response.statusCode().value()));
			}
			long declared = response.headers().contentLength().orElse(-1L);
			if (declared > MAX_BYTES)
				return Mono.error(new IllegalStateException("视频结果大小超出归档限制"));
			return response.bodyToMono(byte[].class).timeout(properties.getRequestTimeout());
		}).flatMap(bytes -> {
			if (bytes.length == 0 || bytes.length > MAX_BYTES) {
				return Mono.error(new IllegalStateException("视频结果大小超出归档限制"));
			}
			return store(accountId, organizationId, storage, mediaId, key, bytes, "video/mp4", purpose, domainType,
					domainId);
		});
	}

	private Mono<String> store(String accountId, String organizationId, ObjectStorageAdapter storage, UUID mediaId,
			String key, byte[] bytes, String mime, MediaPurpose purpose, String domainType, UUID domainId) {
		return Mono.fromRunnable(() -> storage.putObject(key, bytes, mime)).subscribeOn(Schedulers.boundedElastic())
				.then(transactions.transactional(mediaRefs
						.insert(new MediaReference(mediaId, accountId, organizationId, purpose.db(), domainType,
								domainId.toString(), key, mime, bytes.length, VideoArchiveChecksums.sha256(bytes),
								"generated", MediaStatus.ACTIVE, Instant.now(), null, null))
						.flatMap(active -> outbox.append(MediaLifecycleEvents.activated(active)).thenReturn(active))))
				// AI 生成结果多模态审核（任务书 #45 登记）：异步 advisory 送审，失败静默不影响归档/结算。
				.doOnNext(active -> moderation.moderateGeneratedAsync(active, bytes))
				.thenReturn("/api/media/" + mediaId);
	}

	private void validateProviderUrl(String value, String baseUrl) {
		try {
			URI actual = new URI(value);
			URI base = new URI(baseUrl);
			if (!ProviderOriginGuard.isHttpScheme(actual.getScheme())
					|| !ProviderOriginGuard.allowed(actual, base, trustedOrigins.enabledOrigins())
					|| actual.getPort() != base.getPort() || (base.getPath() != null && !base.getPath().isBlank()
							&& !actual.getPath().startsWith(base.getPath()))) {
				throw new IllegalStateException(
						"视频 provider 结果地址不在已配置 provider origin 内: " + ProviderOriginGuard.originOf(actual));
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
