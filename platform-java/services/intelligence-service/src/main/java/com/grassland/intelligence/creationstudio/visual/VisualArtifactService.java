package com.grassland.intelligence.creationstudio.visual;

import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-09（§6.7 D10）：交付画幅衍生与可靠 artifact。
 *
 * <p>
 * 读取原图（media 行 + 对象存储，本地未启用对象存储时经 GeneratedImageStore 兜底）→ 等比补边到 targetAspect →
 * 交付图写独立对象与 media 行（候选 90 天）→ artifact 行登记（attempt 幂等）。 衍生失败保留原图与已结算
 * run——只重做确定性图像处理，不再调用供应商。
 */
@Service
public class VisualArtifactService {

	private static final Logger log = LoggerFactory.getLogger(VisualArtifactService.class);
	/** 候选保留 90 天（§7.4）；已采用沿用永久策略（采用后由 C101-12 覆盖 expires）。 */
	private static final long CANDIDATE_TTL_DAYS = 90;
	private static final String DELIVERY_KEY_PREFIX = "creation-visual/";

	private static final Map<String, int[]> ASPECTS = Map.of("3:4", new int[]{1080, 1440}, "9:16",
			new int[]{1080, 1920}, "1:1", new int[]{1080, 1080}, "16:9", new int[]{1920, 1080}, "2.35:1",
			new int[]{1410, 600});

	private final VisualArtifactRepository artifacts;
	private final MediaReferenceRepository mediaRefs;
	private final CreationImageProcessor processor;
	private final ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider;
	private final ObjectProvider<com.grassland.intelligence.articleimage.GeneratedImageStore> generatedStoreProvider;

	public VisualArtifactService(VisualArtifactRepository artifacts, MediaReferenceRepository mediaRefs,
			CreationImageProcessor processor,
			ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider,
			ObjectProvider<com.grassland.intelligence.articleimage.GeneratedImageStore> generatedStoreProvider) {
		this.artifacts = artifacts;
		this.mediaRefs = mediaRefs;
		this.processor = processor;
		this.storageProvider = storageProvider;
		this.generatedStoreProvider = generatedStoreProvider;
	}

	public record RegisterCommand(UUID artifactId, String ownerAccountId, UUID draftId, UUID planId, int planRevision,
			String itemId, UUID attemptId, UUID runId, UUID originalMediaId, String targetAspect, String paletteId,
			UUID anchorArtifactId) {
	}

	/** 衍生并登记（attempt 幂等：同 attempt 重复调用读已存在 artifact，不重复生成）。 */
	public Mono<VisualArtifact> register(RegisterCommand command) {
		return artifacts.findByAttempt(command.attemptId()).switchIfEmpty(Mono.defer(() -> deriveAndInsert(command)));
	}

	public Mono<VisualArtifact> loadOwned(UUID artifactId, String ownerAccountId) {
		return artifacts.findByIdAndOwner(artifactId, ownerAccountId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "成品不存在")));
	}

	private Mono<VisualArtifact> deriveAndInsert(RegisterCommand command) {
		int[] dimension = ASPECTS.get(command.targetAspect());
		if (dimension == null) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "目标画幅不合法"));
		}
		String padHex = com.grassland.intelligence.creationstudio.CreationVisualPresetCatalog
				.paletteBackgroundHex(command.paletteId());
		return readOriginalBytes(command.originalMediaId(), command.ownerAccountId())
				.flatMap(bytes -> processor.derivePadded(bytes, dimension[0], dimension[1], padHex))
				.flatMap(deliveryBytes -> storeDelivery(command, deliveryBytes, dimension));
	}

	/** 原图读取：对象存储优先（media 行 objectKey），本地卷兜底（GeneratedImageStore）。 */
	private Mono<byte[]> readOriginalBytes(UUID mediaId, String ownerAccountId) {
		return mediaRefs.findById(mediaId).filter(ref -> ownerAccountId.equals(ref.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "原图媒体不存在")))
				.flatMap(ref -> {
					var storage = storageProvider.getIfAvailable();
					if (storage != null) {
						return Mono.fromCallable(() -> storage.getObject(ref.objectKey()))
								.subscribeOn(Schedulers.boundedElastic());
					}
					var store = generatedStoreProvider.getIfAvailable();
					if (store != null) {
						return store.find(ref.id().toString()).map(stored -> stored.bytes());
					}
					return Mono
							.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储未配置，无法读取原图"));
				});
	}

	private Mono<VisualArtifact> storeDelivery(RegisterCommand command, byte[] deliveryBytes, int[] dimension) {
		String objectKey = DELIVERY_KEY_PREFIX + command.attemptId() + ".png";
		var storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "持久化需要启用对象存储"));
		}
		UUID deliveryMediaId = UUID.nameUUIDFromBytes(
				("visual-delivery:" + command.attemptId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		MediaReference media = new MediaReference(deliveryMediaId, command.ownerAccountId(), null,
				com.grassland.intelligence.media.MediaPurpose.CARD_SERIES.db(), null, null, objectKey, "image/png",
				deliveryBytes.length, MediaChecksums.sha256(deliveryBytes), "generated", MediaStatus.ACTIVE, null,
				Instant.now().plus(java.time.Duration.ofDays(CANDIDATE_TTL_DAYS)), null);
		return Mono.fromRunnable(() -> storage.putObject(objectKey, deliveryBytes, "image/png"))
				.subscribeOn(Schedulers.boundedElastic()).then(mediaRefs.insert(media))
				.then(artifacts.insertOrGet(new VisualArtifact(command.artifactId(), command.ownerAccountId(),
						command.draftId(), command.planId(), command.planRevision(), command.itemId(),
						command.attemptId(), command.runId(), command.originalMediaId(), deliveryMediaId,
						command.targetAspect(), dimension[0], dimension[1], MediaChecksums.sha256(deliveryBytes),
						command.anchorArtifactId(), OffsetDateTime.now(ZoneOffset.UTC))))
				.doOnError(error -> log.warn("visual artifact derive failed: attempt={}", command.attemptId(), error));
	}
}
