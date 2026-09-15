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

	/** 推进一批待物删对象；返回本批处理数（0=无可推进，如存储未装配）。 */
	public Mono<Long> advance(UUID manifestId) {
		AtomicLong handled = new AtomicLong();
		return repository.findPendingObjects(manifestId, BATCH)
				.concatMap(object -> handle(manifestId, object).doOnSuccess(ignored -> handled.incrementAndGet()))
				.then(Mono.fromSupplier(handled::get));
	}

	private Mono<Void> handle(UUID manifestId, PersonalDataErasureRepository.ErasureObject object) {
		if (object.kind().equals("wechat_token_cache")) {
			return invalidateTokenCache(manifestId, object);
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
			work = deleteStorageObject(storage, object.objectKey())
					.then(repository.markObjectDeleted(manifestId, object.objectKeyHash())).then();
		}
		return work.onErrorResume(error -> {
			log.warn("object cleanup failed: manifest={} kind={} error={}", manifestId, object.kind(),
					error.getClass().getSimpleName());
			return repository.bumpObjectAttempt(manifestId, object.objectKeyHash(), MAX_OBJECT_ATTEMPTS).then();
		});
	}

	/**
	 * 媒体对象：KYB/证据租约或共享素材挂载 → retained+原因（行保持 deleting 供租约到期后的 GC 接管）； 可删 → 配额
	 * exactly-once 释放 → 物删对象 → 删除审计；media 行已不存在 → 直接按已删除收口。
	 */
	private Mono<Void> deleteMediaObject(UUID manifestId, PersonalDataErasureRepository.ErasureObject object,
			ObjectStorageAdapter storage) {
		return repository.mediaIdByObjectKey(object.objectKey()).flatMap(mediaId -> repository
				.mediaRetentionReason(mediaId)
				.flatMap(reason -> repository.markObjectRetained(manifestId, object.objectKeyHash(), reason))
				.switchIfEmpty(Mono.defer(() -> mediaRefs.releaseQuota(mediaId)
						.then(deleteStorageObject(storage, object.objectKey())).then(mediaRefs.completeDelete(mediaId))
						.then(repository.markObjectDeleted(manifestId, object.objectKeyHash()))))
				.then()).switchIfEmpty(
						Mono.defer(() -> repository.markObjectDeleted(manifestId, object.objectKeyHash()).then()));
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
