package com.grassland.intelligence.compliance;

import com.grassland.intelligence.creationstudio.wechat.WechatTokenService;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 注销对象物删（任务书 #103 C103-10 / §7.4）：按 manifest 精确 key 删除，不做桶级扫描。
 *
 * <p>
 * 分类处理：媒体对象先查 KYB/证据租约与共享素材挂载（保留项记 retained+原因，不提前物删），可删项 复用
 * {@link MediaReferenceRepository} 的配额 exactly-once 释放与删除审计；导出产物/上传暂存/公众号派生
 * 直接物删；公众号 token 缓存按连接+版本显式失效（无 Redis = 无缓存可失效）。对象删除幂等（key 已不存在视为已删除）； 存储故障保持
 * pending 重试，超限转 failed。存储适配器未装配（如 IT/未启对象存储）时跳过存储类对象——绝不假装已物删。
 */
@Component
public class PersonalDataObjectCleanup {

	private static final Logger log = LoggerFactory.getLogger(PersonalDataObjectCleanup.class);
	private static final int BATCH = 100;
	private static final int MAX_OBJECT_ATTEMPTS = 8;

	private final PersonalDataErasureRepository repository;
	private final MediaReferenceRepository mediaRefs;
	private final WechatTokenService tokens;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;

	public PersonalDataObjectCleanup(PersonalDataErasureRepository repository, MediaReferenceRepository mediaRefs,
			WechatTokenService tokens, ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.repository = repository;
		this.mediaRefs = mediaRefs;
		this.tokens = tokens;
		this.storageProvider = storageProvider;
	}

	/**
	 * 推进一批待物删对象；返回本批处理数（0=无可推进，如存储未装配）。
	 *
	 * <p>
	 * 归属冲突前置门闸（#106 D01）：任何对象物删/配额释放/token 失效之前核对
	 * {@link PersonalDataErasureRepository#conflictsByKind}——冲突则
	 * manifest=needs_review 且返回 0， 不删除任何字节（已有对象清理入口不能绕过批次门闸）。
	 */
	public Mono<Long> advance(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.flatMap(manifest -> repository.conflictsByKind(manifest.accountId()).flatMap(conflicts -> {
					if (conflicts.isEmpty()) {
						return advanceBatch(manifestId);
					}
					log.warn("object cleanup withheld by ownership conflicts: manifest={} kinds={}", manifestId,
							conflicts);
					return repository.setManifestState(manifestId, "needs_review").thenReturn(0L);
				})).defaultIfEmpty(0L);
	}

	private Mono<Long> advanceBatch(UUID manifestId) {
		AtomicLong handled = new AtomicLong();
		return repository.findPendingObjects(manifestId, BATCH)
				.concatMap(object -> handle(manifestId, object).doOnSuccess(ignored -> handled.incrementAndGet()))
				.then(Mono.fromSupplier(handled::get));
	}

	private Mono<Void> handle(UUID manifestId, PersonalDataErasureRepository.ErasureObject object) {
		if (object.kind().equals("wechat_token_cache")) {
			return invalidateTokenCache(manifestId, object);
		}
		// 坏条目不猜（#104 TC104-02-05）：空/空白 key、未登记 kind 直接 failed+诊断，不触存储。
		if (object.objectKey() == null || object.objectKey().isBlank()
				|| !KNOWN_STORAGE_KINDS.contains(object.kind())) {
			return repository.markObjectFailed(manifestId, object.objectKeyHash(), "invalid_object_entry").then();
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			// 无对象存储（未装配/IT）：保持 pending，不伪装完成；装配恢复后由 worker/重试推进。
			return Mono.empty();
		}
		Mono<Void> work;
		if (object.kind().equals("media_object")) {
			work = deleteMediaObject(manifestId, object, storage);
		} else {
			work = deleteDerivedOrExportObject(manifestId, object, storage);
		}
		return work.onErrorResume(error -> {
			log.warn("object cleanup failed: manifest={} kind={} error={}", manifestId, object.kind(),
					error.getClass().getSimpleName());
			return repository.bumpObjectAttempt(manifestId, object.objectKeyHash(), MAX_OBJECT_ATTEMPTS).then();
		});
	}

	/** 登记过的存储对象 kind（#104 §7.2：其余 kind 属坏登记，不猜）。 */
	private static final java.util.Set<String> KNOWN_STORAGE_KINDS = java.util.Set.of("media_object", "upload_staging",
			"wechat_derived", "export_artifact");

	/**
	 * 派生/导出/暂存对象：物删前按当前引用重验（#104 §7.2——旧 pending/failed manifest 不信任旧登记即等于可删）。 仍被保留
	 * sync 的映射或保留导出行引用 → retained+organization_project_reference；暂存 key 经媒体保留原因核对；
	 * 引用证据已缺失时只有 带 scope_verified 登记标记（新作用域已证明个人）才物删，旧登记保持字节并 failed 待人工判定。
	 */
	private Mono<Void> deleteDerivedOrExportObject(UUID manifestId, PersonalDataErasureRepository.ErasureObject object,
			ObjectStorageAdapter storage) {
		Mono<Boolean> retained;
		if (object.kind().equals("wechat_derived")) {
			retained = repository.derivedKeyRetainedBySync(object.objectKey());
		} else if (object.kind().equals("export_artifact")) {
			retained = repository.exportKeyRetained(object.objectKey());
		} else if (object.kind().equals("upload_staging")) {
			retained = repository.mediaIdByUploadKey(object.objectKey()).flatMap(repository::mediaRetentionReason)
					.hasElement();
		} else {
			retained = Mono.just(false);
		}
		return retained.flatMap(isRetained -> isRetained
				? repository.markObjectRetained(manifestId, object.objectKeyHash(), "organization_project_reference")
						.then()
				: provenanceVerified(object)
						? deleteStorageObject(storage, object.objectKey())
								.then(repository.markObjectDeleted(manifestId, object.objectKeyHash())).then()
						: repository.markObjectFailed(manifestId, object.objectKeyHash(), "unverifiable_provenance")
								.then());
	}

	private static boolean provenanceVerified(PersonalDataErasureRepository.ErasureObject object) {
		return "scope_verified".equals(object.provenance());
	}

	/**
	 * 媒体对象：KYB/证据租约或共享/组织引用 → retained+原因（行保持 deleting 供租约到期后的 GC 接管）； 旧清理部分执行（行
	 * deleting 且配额已释放而字节仍在）→ failed+诊断、保字节待单独修复（§7.2）； 可删 → 先物删字节再释放配额（该顺序保证
	 * 「deleting+已释放」组合只可能来自旧流程）；media 行已不存在 → 新登记（scope_verified）按残留 key 幂等物删，
	 * 旧登记不猜 failed。
	 */
	private Mono<Void> deleteMediaObject(UUID manifestId, PersonalDataErasureRepository.ErasureObject object,
			ObjectStorageAdapter storage) {
		return repository.mediaIdByObjectKey(object.objectKey()).flatMap(mediaId -> repository
				.mediaRetentionReason(mediaId)
				.flatMap(reason -> repository.markObjectRetained(manifestId, object.objectKeyHash(), reason))
				.switchIfEmpty(Mono.defer(() -> repository.mediaQuotaReleasedWhileDeleting(mediaId)
						.flatMap(partial -> partial
								? repository.markObjectFailed(manifestId, object.objectKeyHash(),
										"prior_partial_cleanup")
								: deleteStorageObject(storage, object.objectKey()).then(mediaRefs.releaseQuota(mediaId))
										.then(mediaRefs.completeDelete(mediaId))
										.then(repository.markObjectDeleted(manifestId, object.objectKeyHash())))))
				.thenReturn(true))
				.switchIfEmpty(Mono.defer(() -> provenanceVerified(object)
						? deleteStorageObject(storage, object.objectKey())
								.then(repository.markObjectDeleted(manifestId, object.objectKeyHash())).thenReturn(true)
						: repository.markObjectFailed(manifestId, object.objectKeyHash(), "unverifiable_provenance")
								.thenReturn(true)))
				.then();
	}

	private Mono<Void> deleteStorageObject(ObjectStorageAdapter storage, String key) {
		return Mono.fromRunnable(() -> storage.deleteObject(key)).subscribeOn(Schedulers.boundedElastic()).then();
	}

	private Mono<Void> invalidateTokenCache(UUID manifestId, PersonalDataErasureRepository.ErasureObject object) {
		// object_key = "{connectionId}:v{version}"（登记时的连接行；行已在 DB 阶段删除，key 自带定位）。
		String[] parts = object.objectKey().split(":v", 2);
		try {
			UUID connectionId = UUID.fromString(parts[0]);
			long version = Long.parseLong(parts[1]);
			return tokens.invalidateAccount(connectionId, version)
					.then(repository.markObjectDeleted(manifestId, object.objectKeyHash())).then();
		} catch (RuntimeException error) {
			return repository.markObjectRetained(manifestId, object.objectKeyHash(), "unparseable_token_key").then();
		}
	}
}
