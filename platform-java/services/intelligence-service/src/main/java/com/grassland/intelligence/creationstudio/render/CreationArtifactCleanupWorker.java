package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationstudio.visual.VisualArtifact;
import com.grassland.intelligence.creationstudio.visual.VisualArtifactRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-09：成品/导出清理规则基础。
 *
 * <ul>
 * <li>候选 artifact 到期 90 天清理（§7.4）：先核对 anchor 依赖与草稿采用（result_asset_ids），
 * 依赖媒体不删，只清可删除副本；</li>
 * <li>导出文件 7 天生命周期由 C101-18 接入同一 worker。</li>
 * </ul>
 *
 * <p>
 * 清理只删导出/候选副本对象与行——已有 media 与 AI run 不随导出过期删除。
 */
@Component
public class CreationArtifactCleanupWorker {

	private static final Logger log = LoggerFactory.getLogger(CreationArtifactCleanupWorker.class);
	private static final int BATCH = 50;

	private final VisualArtifactRepository artifacts;
	private final MediaReferenceRepository mediaRefs;
	private final ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider;

	public CreationArtifactCleanupWorker(VisualArtifactRepository artifacts, MediaReferenceRepository mediaRefs,
			ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider) {
		this.artifacts = artifacts;
		this.mediaRefs = mediaRefs;
		this.storageProvider = storageProvider;
	}

	@Scheduled(fixedDelayString = "${creation.studio.artifact-cleanup-interval-ms:3600000}")
	public void cleanupExpiredCandidates() {
		cleanupOnce().subscribeOn(Schedulers.boundedElastic()).subscribe();
	}

	/** 单轮清理（调度与测试共用）。 */
	public Mono<Void> cleanupOnce() {
		OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
		return artifacts.findExpirableCandidates(cutoff, BATCH).concatMap(this::deleteCandidate).then()
				.doOnError(error -> log.warn("visual artifact cleanup failed", error));
	}

	private Mono<Void> deleteCandidate(VisualArtifact candidate) {
		var storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.empty();
		}
		return mediaRefs.findById(candidate.deliveryMediaId()).flatMap(media -> {
			// 对象与 media 行一起清（候选副本）；被 anchor/采用的行根本不会出现在扫描结果里
			return Mono.fromRunnable(() -> storage.deleteObject(media.objectKey()))
					.subscribeOn(Schedulers.boundedElastic())
					.then(mediaRefs.claimDelete(candidate.deliveryMediaId(), candidate.ownerAccountId()))
					.flatMap(claimed -> claimed != null
							? mediaRefs.completeDelete(candidate.deliveryMediaId()).then()
							: Mono.empty())
					.then(artifacts.delete(candidate.id()));
		}).then().onErrorResume(error -> {
			log.warn("visual artifact cleanup item failed: artifact={}", candidate.id(), error);
			return Mono.empty();
		});
	}
}
