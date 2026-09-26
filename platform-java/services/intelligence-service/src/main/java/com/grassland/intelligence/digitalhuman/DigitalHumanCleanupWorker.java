package com.grassland.intelligence.digitalhuman;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 数字人派生清理 worker（任务书 #105G C105G-02 / 共享契约 K05 dh_cleanup、K09、K13.5、§9.1）。
 *
 * <p>
 * 驱动 {@code dh_cleanup} 登记行到终态：<b>avatar</b>
 * 族（avatar/avatar_object/avatar_external）委托
 * {@link DigitalHumanAvatarService#processCleanup}（runtime INTERNAL13 +
 * 第三方远端删除与查询）； {@code recording_object} 由本类经
 * INTERNAL13（kind=recording）删除，complete 才落 deleted。行级退避 1/5/30 分钟、上限
 * {@value #MAX_ROW_ATTEMPTS} 次：超限保持 failed 并告警（不再自动重试）， 注销 verify 按 failed
 * 计残留——<b>远端未确认不得报 complete</b>。
 *
 * <p>
 * 只推进登记行状态；不删除 DB 业务行（批次删除归 PersonalDataErasureService 的 dh_* kinds， 顺序上必须在本
 * worker 收口之后）。late 供应商结果经账号生命周期核验：目标行已随注销删除时写入 0 行，不复活（账号墓碑阻断）。
 */
@Component
public class DigitalHumanCleanupWorker {

	private static final Logger log = LoggerFactory.getLogger(DigitalHumanCleanupWorker.class);

	/** 行级重试上限（任务书 §11 步骤 3：1/5/30 分钟、上限 10 次，超限保 failed 并告警）。 */
	static final int MAX_ROW_ATTEMPTS = 10;

	/** 注销驱动单轮最多推进的资源数（有界；大数据靠多次调用/周期驱动收敛）。 */
	private static final int ERASURE_RESOURCE_LIMIT = 200;

	private final DatabaseClient db;
	private final DigitalHumanMediaRepository media;
	private final DigitalHumanAvatarService avatars;
	private final DigitalHumanRenderService renders;
	private final RuntimePort runtime;
	private final org.springframework.data.redis.core.ReactiveStringRedisTemplate redis;
	private final boolean enabled;
	private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

	public DigitalHumanCleanupWorker(DatabaseClient db, DigitalHumanMediaRepository media,
			DigitalHumanAvatarService avatars, DigitalHumanRenderService renders,
			ObjectProvider<RuntimePort> runtimePorts, @Value("${dh.runtime.base-url:}") String runtimeBaseUrl,
			org.springframework.data.redis.core.ReactiveStringRedisTemplate redis,
			@Value("${digital-human.cleanup.enabled:true}") boolean enabled) {
		this.db = db;
		this.media = media;
		this.avatars = avatars;
		this.renders = renders;
		this.runtime = runtimePorts.getIfAvailable(() -> defaultRuntimePort(runtimeBaseUrl));
		this.redis = redis;
		this.enabled = enabled;
	}

	/**
	 * 调度入口（任务书 #105fix-1 C105X-01）：30s 周期（可配）兜底推进全局 due 行；enabled=false 或
	 * 上一轮未结束（running CAS）时首行返回。照 {@code PersonalDataErasureWorker} 既有范式。
	 */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${digital-human.cleanup.poll-interval-ms:30000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> log.warn("dh cleanup worker cycle failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	Mono<Void> runOnce() {
		return advanceDue(100).then();
	}

	// ---------- runtime 删除端口（INTERNAL13；生产=WebClient，IT=受控 fake） ----------

	/** INTERNAL13 删除回执（complete=false 时 remainingHandles 非空——不报假零）。 */
	public record ResourceDeletion(boolean complete, List<String> remainingHandles) {
	}

	/** runtime 资源删除端口（kind=avatar|recording；可替换 transport）。 */
	public interface RuntimePort {

		Mono<ResourceDeletion> deleteResource(UUID resourceId, String kind, int revision);
	}

	static RuntimePort defaultRuntimePort(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return (resourceId, kind, revision) -> Mono.error(new IllegalStateException("dh runtime 未配置"));
		}
		var client = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(baseUrl).build();
		return (resourceId, kind, revision) -> client.post().uri("/internal/v1/resources/{id}/delete", resourceId)
				.bodyValue(Map.of("commandId", UUID.randomUUID().toString(), "payloadHash",
						"cleanup-" + kind + "-" + resourceId, "kind", kind, "revision", revision))
				.retrieve().bodyToMono(ResourceDeletion.class).timeout(Duration.ofSeconds(10));
	}

	// ---------- 残留与推进 ----------

	/** 注销核对口径：pending/deleting/failed 全部计残留（unknown/failed 不计 0）。 */
	public record CleanupResidue(long pending, long deleting, long failed) {

		public boolean clean() {
			return pending == 0 && deleting == 0 && failed == 0;
		}

		public long total() {
			return pending + deleting + failed;
		}
	}

	/** 逐状态计数（仅统计，不写）。 */
	public Mono<CleanupResidue> residue(String accountId) {
		return db.sql("""
				SELECT count(*) FILTER (WHERE state = 'pending') AS pending,
				       count(*) FILTER (WHERE state = 'deleting') AS deleting,
				       count(*) FILTER (WHERE state = 'failed') AS failed
				  FROM dh_cleanup WHERE owner_account_id = :a
				""").bind("a", accountId)
				.map(row -> new CleanupResidue(nullSafe(row.get("pending", Long.class)),
						nullSafe(row.get("deleting", Long.class)), nullSafe(row.get("failed", Long.class))))
				.one().defaultIfEmpty(new CleanupResidue(0, 0, 0));
	}

	/**
	 * 注销路径驱动：先为「从未撤销但持有外部句柄」的形象补登 avatar_external（幂等），再逐 due 资源推进，
	 * 最后对仍未确认的外部句柄做供应商状态核对（§9.1 幂等恢复）。返回本轮实际推进的资源数 （0=无可推进——残留仍非 0
	 * 时为真失败，调用方不得当成功）。仅用于 active 账号（冻结/清理期 触发器拒绝 dh_cleanup 新 INSERT——清理期走
	 * {@link #advanceForErasure}）。
	 */
	public Mono<Long> advanceForOwner(String accountId) {
		return registerMissingExternalCleanup(accountId)
				.then(dueResources(accountId, ERASURE_RESOURCE_LIMIT)
						.concatMap(resource -> advanceResource(accountId, resource).thenReturn(1L)).count())
				.flatMap(processed -> reconcileFailedExternal(accountId).thenReturn(processed));
	}

	/** 清理期驱动结果：processed=due 行推进数；unconfirmedExternal=未获远端确认的外部句柄数。 */
	public record ErasureAdvance(long processed, long unconfirmedExternal) {
	}

	/**
	 * 清理期（erasing）驱动：不新增 dh_cleanup 登记（触发器冻结期拒绝 INSERT；重试账本由
	 * personal_data_erasure_step 步骤承担）——① 推进既有 due 行；② 对「持有外部句柄但无终态 avatar_external
	 * 登记覆盖」的形象直接删除并查询核对（K14.3 确认语义，ACTIVE/UNKNOWN 计 unconfirmed，步骤按其失败重试——远端未确认不得报
	 * complete）。
	 */
	public Mono<ErasureAdvance> advanceForErasure(String accountId) {
		return dueResources(accountId, ERASURE_RESOURCE_LIMIT)
				.concatMap(resource -> advanceResource(accountId, resource).thenReturn(1L)).count()
				.flatMap(processed -> reconcileFailedExternal(accountId).then(driveUnregisteredExternal(accountId))
						.map(unconfirmed -> new ErasureAdvance(processed, unconfirmed)));
	}

	/** 无登记覆盖的外部句柄直接删除+查询：confirmed/查询已删 → 0；ACTIVE/UNKNOWN → 1。 */
	private Mono<Long> driveUnregisteredExternal(String accountId) {
		return db.sql("""
				SELECT a.id::text, a.provider_resource_refs FROM dh_avatar a
				 WHERE a.owner_account_id = :a AND a.provider_resource_refs IS NOT NULL
				   AND NOT EXISTS (SELECT 1 FROM dh_cleanup c WHERE c.resource_kind = 'avatar_external'
				                  AND c.resource_id = a.id AND c.state IN ('deleted', 'retained'))
				""").bind("a", accountId)
				.map(row -> Map.entry(row.get("id", String.class), row.get("provider_resource_refs", String.class)))
				.all().collectList().flatMap(rows -> {
					Mono<Long> chain = Mono.just(0L);
					for (var row : rows) {
						String external = DigitalHumanAvatarService.readProviderRef(row.getValue());
						if (external == null || external.isBlank()) {
							continue;
						}
						chain = chain.flatMap(unconfirmed -> renders.deleteRemoteAvatar(external)
								.flatMap(deletion -> deletion.confirmed()
										? Mono.just(0L)
										: renders.queryRemoteAvatar(external).map(
												state -> state == DigitalHumanRenderService.RemoteResourceState.DELETED
														? 0L
														: 1L))
								.onErrorResume(error -> Mono.just(1L)).map(extra -> unconfirmed + extra));
					}
					return chain;
				});
	}

	/** 一个待清理资源（同 kind+resourceId 的多行登记一并收口）。 */
	private static final class DueResource {

		private final String resourceKind;

		private final UUID resourceId;

		private final List<String> rowIds = new ArrayList<>();

		private final int attempts;

		private DueResource(String resourceKind, UUID resourceId, int attempts) {
			this.resourceKind = resourceKind;
			this.resourceId = resourceId;
			this.attempts = attempts;
		}
	}

	/** due 资源（行级退避已由登记行 next_attempt_at 承载；超限行不选入）。 */
	private Flux<DueResource> dueResources(String accountId, int limit) {
		return db.sql("""
				SELECT id::text, resource_kind, resource_id::text, attempts FROM dh_cleanup
				 WHERE owner_account_id = :a AND state IN ('pending', 'deleting', 'failed')
				   AND (next_attempt_at IS NULL OR next_attempt_at <= now())
				   AND attempts < :maxAttempts
				 ORDER BY created_at LIMIT :n
				""").bind("a", accountId).bind("maxAttempts", MAX_ROW_ATTEMPTS).bind("n", limit)
				.map(row -> java.util.Arrays.<Object>asList(row.get("id", String.class),
						row.get("resource_kind", String.class), row.get("resource_id", String.class),
						row.get("attempts", Integer.class)))
				.all().collectList().flatMapMany(rows -> {
					Map<String, DueResource> byResource = new LinkedHashMap<>();
					for (List<Object> row : rows) {
						String kind = (String) row.get(1);
						UUID resourceId = UUID.fromString((String) row.get(2));
						int attempts = row.get(3) == null ? 0 : (Integer) row.get(3);
						// avatar/avatar_object/avatar_external 同形象一并收口（processCleanup 本就整批处理，
						// 分组重复触发会重复出站远端删除）。
						String groupKey = (kind.startsWith("avatar") ? "avatar" : kind) + ":" + resourceId;
						DueResource resource = byResource.computeIfAbsent(groupKey,
								key -> new DueResource(kind, resourceId, attempts));
						resource.rowIds.add((String) row.get(0));
					}
					return Flux.fromIterable(byResource.values());
				});
	}

	private Mono<Void> advanceResource(String accountId, DueResource resource) {
		if (resource.resourceKind.startsWith("avatar")) {
			// avatar/avatar_object/avatar_external 同批收口（AvatarService
			// 内含远端删除+查询+finishDeletion）。
			return avatars.processCleanup(resource.resourceId).subscribeOn(Schedulers.boundedElastic())
					.onErrorResume(error -> {
						log.warn("dh avatar cleanup advance failed: avatar={} error={}", resource.resourceId,
								error.getClass().getSimpleName());
						return Mono.empty();
					});
		}
		if ("recording_object".equals(resource.resourceKind)) {
			return runtime.deleteResource(resource.resourceId, "recording", 1)
					.flatMap(deletion -> deletion.complete()
							? media.markCleanupDeleted(resource.rowIds)
							: media.markCleanupFailed(resource.rowIds, "dh_cleanup_incomplete",
									nextAttemptAt(resource.attempts)))
					.onErrorResume(error -> media.markCleanupFailed(resource.rowIds, errorCode(error),
							nextAttemptAt(resource.attempts)))
					.then();
		}
		// 未知 kind 不猜（K05：归属未知保留并失败）。
		log.warn("dh_cleanup unknown resource_kind kept failed: kind={} resource={} owner={}", resource.resourceKind,
				resource.resourceId, accountId);
		return media.markCleanupFailed(resource.rowIds, "unknown_resource_kind", null).then();
	}

	/** 1/5/30 分钟退避（按既有 attempts；超限行已被 due 查询排除）。 */
	private static Instant nextAttemptAt(int attempts) {
		Duration backoff = attempts <= 0
				? Duration.ofMinutes(1)
				: attempts == 1 ? Duration.ofMinutes(5) : Duration.ofMinutes(30);
		return Instant.now().plus(backoff);
	}

	private static String errorCode(Throwable error) {
		String name = error.getClass().getSimpleName();
		return name.length() > 64 ? name.substring(0, 64) : name;
	}

	/** 为持有外部句柄但尚未登记的形象补登 avatar_external（注销前未撤销的形象同样必须远端回收）。 */
	private Mono<Void> registerMissingExternalCleanup(String accountId) {
		return db
				.sql("SELECT id::text, provider_resource_refs FROM dh_avatar"
						+ " WHERE owner_account_id = :a AND provider_resource_refs IS NOT NULL")
				.bind("a", accountId)
				.map(row -> Map.entry(row.get("id", String.class), row.get("provider_resource_refs", String.class)))
				.all().collectList().flatMap(rows -> {
					Mono<Void> chain = Mono.empty();
					for (var row : rows) {
						String external = DigitalHumanAvatarService.readProviderRef(row.getValue());
						if (external != null && !external.isBlank()) {
							chain = chain.then(media.registerCleanup(accountId, "avatar_external",
									UUID.fromString(row.getKey()), external, "account_erasure"));
						}
					}
					return chain;
				});
	}

	// ---------- 易失键清除（C105G-02：Redis buffer/preview 纳入 ack，不只删 Postgres）
	// ----------

	/**
	 * 行删除前收集并删除 owner 的易失键：会话内容缓冲（dh:buffer:{session}:{epoch}）与预览音频
	 * （dh:preview:{id}）。键式与 {@link DigitalHumanContentBuffer}/预览模块的既有约定一致；无键时零
	 * 出站。Redis 不可达时有界超时+告警降级（行删除后键不可读、TTL ≤会话+终止 20 分钟兜底——K05）， 不因易失端点无限期阻塞正文清理。
	 */
	public Mono<Long> eraseVolatileKeys(String accountId) {
		Mono<List<String>> buffers = db
				.sql("SELECT id::text, content_epoch FROM dh_session WHERE owner_account_id = :a").bind("a", accountId)
				.map(row -> "dh:buffer:" + row.get("id", String.class) + ":" + row.get("content_epoch", Long.class))
				.all().collectList().defaultIfEmpty(List.of());
		Mono<List<String>> previews = db.sql("SELECT id::text FROM dh_preview WHERE owner_account_id = :a")
				.bind("a", accountId).map(row -> "dh:preview:" + row.get("id", String.class)).all().collectList()
				.defaultIfEmpty(List.of());
		return Mono.zip(buffers, previews).flatMap(tuple -> {
			List<String> keys = new ArrayList<>(tuple.getT1());
			keys.addAll(tuple.getT2());
			return keys.isEmpty()
					? Mono.just(0L)
					: redis.delete(keys.toArray(String[]::new)).timeout(Duration.ofSeconds(3)).onErrorResume(error -> {
						log.warn("dh volatile key erase degraded (TTL backstop): owner={} keys={}", accountId,
								keys.size());
						return Mono.just(0L);
					});
		});
	}

	/**
	 * §9.1 幂等恢复：未确认删除的 avatar_external 行按当前供应商状态核对——远端已删除 → 收口 deleted；
	 * ACTIVE/UNKNOWN 保持 failed（下一 due 重试或按残留阻塞 verify，不猜已删）。
	 */
	private Mono<Void> reconcileFailedExternal(String accountId) {
		return db
				.sql("SELECT id::text, object_ref FROM dh_cleanup WHERE owner_account_id = :a"
						+ " AND resource_kind = 'avatar_external' AND state = 'failed' AND object_ref IS NOT NULL")
				.bind("a", accountId)
				.map(row -> Map.entry(row.get("id", String.class), row.get("object_ref", String.class))).all()
				.collectList().flatMap(rows -> {
					Mono<Void> chain = Mono.empty();
					for (var row : rows) {
						chain = chain.then(renders.queryRemoteAvatar(row.getValue()).flatMap(state -> {
							if (state == DigitalHumanRenderService.RemoteResourceState.DELETED) {
								log.info("dh avatar external recovered by provider query: owner={} ref={}", accountId,
										row.getValue());
								return media.markCleanupDeleted(List.of(row.getKey())).then();
							}
							return Mono.empty();
						}).onErrorResume(error -> Mono.empty()));
					}
					return chain;
				});
	}

	// ---------- 周期兜底驱动（无账号上下文；供调度/运维入口复用） ----------

	/**
	 * 全局 due 行驱动一轮（仅 active 账号——冻结/清理期不新增登记，见 {@link #advanceForErasure}）： 返回处理过的账号
	 * id（去重）。
	 */
	public Flux<String> advanceDue(int limit) {
		return db.sql("""
				SELECT DISTINCT c.owner_account_id FROM dh_cleanup c
				 LEFT JOIN intelligence_account_lifecycle g ON g.account_id = c.owner_account_id
				 WHERE c.state IN ('pending', 'deleting', 'failed')
				   AND (c.next_attempt_at IS NULL OR c.next_attempt_at <= now())
				   AND c.attempts < :maxAttempts
				   AND (g.state IS NULL OR g.state = 'active')
				 ORDER BY c.owner_account_id LIMIT :n
				""").bind("maxAttempts", MAX_ROW_ATTEMPTS).bind("n", Math.max(1, limit))
				.map(row -> row.get("owner_account_id", String.class)).all()
				.concatMap(account -> advanceForOwner(account).thenReturn(account));
	}

	/** 告警口径：超限 failed 行计数（供指标/治理台读取；本 worker 只记录不自动重试）。 */
	public Mono<Long> countExhaustedRows() {
		return db.sql("SELECT count(*) AS n FROM dh_cleanup WHERE state = 'failed' AND attempts >= :maxAttempts")
				.bind("maxAttempts", MAX_ROW_ATTEMPTS).map(row -> nullSafe(row.get("n", Long.class))).one()
				.defaultIfEmpty(0L);
	}

	private static long nullSafe(Long value) {
		return value == null ? 0L : value;
	}
}
