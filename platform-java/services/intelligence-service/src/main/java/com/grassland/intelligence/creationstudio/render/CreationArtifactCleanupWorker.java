package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationstudio.visual.VisualArtifact;
import com.grassland.intelligence.creationstudio.visual.VisualArtifactRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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
	private final CreationExportService exports;
	private final CreationExportRepository exportRows;
	private final org.springframework.r2dbc.core.DatabaseClient db;
	private final org.springframework.transaction.reactive.TransactionalOperator transactions;

	public CreationArtifactCleanupWorker(VisualArtifactRepository artifacts, MediaReferenceRepository mediaRefs,
			ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storageProvider, CreationExportService exports,
			CreationExportRepository exportRows, org.springframework.r2dbc.core.DatabaseClient db,
			org.springframework.transaction.reactive.TransactionalOperator transactions) {
		this.artifacts = artifacts;
		this.mediaRefs = mediaRefs;
		this.storageProvider = storageProvider;
		this.exports = exports;
		this.exportRows = exportRows;
		this.db = db;
		this.transactions = transactions;
	}

	@Scheduled(fixedDelayString = "${creation.studio.artifact-cleanup-interval-ms:3600000}")
	public void cleanupExpiredCandidates() {
		cleanupOnce().subscribeOn(Schedulers.boundedElastic()).subscribe();
	}

	/** 单轮清理（调度与测试共用）：候选 artifact 90 天 + 导出产物 7 天（C101-18）。 */
	public Mono<Void> cleanupOnce() {
		OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
		Mono<Void> candidates = artifacts.findExpirableCandidates(cutoff, BATCH).concatMap(this::deleteCandidate)
				.then();
		Mono<Void> expiredExports = exports.expiredExports().concatMap(this::deleteExportRow).then();
		return Mono.when(candidates, expiredExports)
				.doOnError(error -> log.warn("visual artifact cleanup failed", error));
	}

	/** 导出 7 天生命周期：先删对象（manifest 记录的 objectKey）再删行；对象删失败仅告警不阻塞行清理重试。 */
	private Mono<Void> deleteExportRow(CreationExportRepository.ExportRow row) {
		var storage = storageProvider.getIfAvailable();
		Mono<Void> deleteObject = Mono.empty();
		if (storage != null && row.manifestJson() != null) {
			Map<String, Object> manifest = com.grassland.intelligence.creationstudio.plan.PlanJson
					.readJson(row.manifestJson());
			if (manifest.get("objectKey") instanceof String objectKey) {
				deleteObject = Mono.fromRunnable(() -> storage.deleteObject(objectKey))
						.subscribeOn(Schedulers.boundedElastic()).then();
			}
		}
		return deleteObject.then(exports.deleteExport(row)).onErrorResume(error -> {
			log.warn("export row cleanup failed: export={}", row.id(), error);
			return Mono.empty();
		}).then();
	}

	private Mono<Void> deleteCandidate(VisualArtifact candidate) {
		var storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.empty();
		}
		return db.sql("SELECT id FROM creation_draft WHERE id=:id FOR UPDATE").bind("id", candidate.draftId()).then()
				.then(db.sql("SELECT id FROM creation_visual_plan WHERE id=:id FOR UPDATE")
						.bind("id", candidate.planId()).then())
				.then(artifacts.isExpirable(candidate.id(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(90)))
				.filter(Boolean::booleanValue)
				.flatMap(ignored -> mediaRefs.claimDelete(candidate.deliveryMediaId(), candidate.ownerAccountId())
						.switchIfEmpty(mediaRefs.findById(candidate.deliveryMediaId())
								.filter(media -> candidate.ownerAccountId().equals(media.ownerAccountId())
										&& media.status() == com.grassland.intelligence.media.MediaStatus.DELETING)))
				.as(transactions::transactional)
				.flatMap(media -> Mono.fromRunnable(() -> storage.deleteObject(media.objectKey()))
						.subscribeOn(Schedulers.boundedElastic())
						.then(mediaRefs.completeDelete(candidate.deliveryMediaId()))
						.then(artifacts.delete(candidate.id())))
				.then().onErrorResume(error -> {
					log.warn("visual artifact cleanup item failed: artifact={}", candidate.id(), error);
					return Mono.empty();
				});
	}
}
