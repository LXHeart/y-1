package com.grassland.intelligence.media;

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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Hypit Output 归档到既有媒体管线的适配器（任务书 #107-2 C107-10 / W10 JI/media）。
 *
 * <p>
 * 幂等契约（T10-4）：mediaId 确定性派生自 outputId，key = 前缀+mediaId； 已存在同 key 行 → 直接返回现状（同
 * mediaId、同 key、单条 media/outbox 关联）； PUT 成功后 DB 故障重跑 → putObject 幂等覆盖同
 * key，insert 补齐，仍单行。
 */
@Component
public class HypitMediaArchiveAdapter {

	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final MediaReferenceRepository mediaRefs;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public HypitMediaArchiveAdapter(ObjectProvider<ObjectStorageAdapter> storageProvider,
			MediaReferenceRepository mediaRefs, OutboxRepository outbox, TransactionalOperator transactions) {
		this.storageProvider = storageProvider;
		this.mediaRefs = mediaRefs;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	/** 确定性 mediaId：同 outputId 永远映射到同一 media 行。 */
	public static UUID deterministicMediaId(UUID outputId) {
		return UUID.nameUUIDFromBytes(
				("hypit.output-archive:" + outputId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	public Mono<String> storeArchiveBytes(UUID outputId, String ownerAccountId, String organizationId, byte[] bytes,
			String mime) {
		UUID mediaId = deterministicMediaId(outputId);
		String key = "hypit/output/" + mediaId;
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IllegalStateException("Hypit 输出归档需要启用对象存储"));
		}
		return mediaRefs.findByObjectKey(key).map(existing -> existing.id().toString()).switchIfEmpty(Mono.defer(() -> {
			MediaPurpose purpose = mime != null && mime.startsWith("video/")
					? MediaPurpose.VIDEO_MASTER
					: MediaPurpose.CONTENT_ASSET;
			return Mono.fromRunnable(() -> storage.putObject(key, bytes, mime)).subscribeOn(Schedulers.boundedElastic())
					.then(transactions.transactional(mediaRefs
							.insert(new MediaReference(mediaId, ownerAccountId, organizationId, purpose.db(),
									"hypit_output", outputId.toString(), key, mime, bytes.length, checksum(bytes),
									"generated", MediaStatus.ACTIVE, Instant.now(), null, null))
							.flatMap(active -> outbox.append(MediaLifecycleEvents.activated(active))
									.thenReturn(active))))
					.map(active -> active.id().toString());
		}));
	}

	private static String checksum(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception error) {
			throw new IllegalStateException("无法计算归档校验和", error);
		}
	}
}
