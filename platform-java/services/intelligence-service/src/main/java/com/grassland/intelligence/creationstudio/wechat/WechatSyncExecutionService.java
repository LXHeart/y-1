package com.grassland.intelligence.creationstudio.wechat;

import static com.grassland.intelligence.creationstudio.wechat.WechatSyncPreparationService.value;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.StudioCommandStore;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.creationstudio.render.CreationRenderService;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountRepository.AccountRow;
import com.grassland.intelligence.creationstudio.wechat.WechatApiClient.WechatArticle;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.MediaMappingRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.SyncRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService.Candidate;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService.CandidateResult;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 任务书 #103 C103-21：公众号草稿同步「执行」服务——自 {@link WechatDraftSyncService} 按职责原样搬移：
 * workflow 推进（preparing→uploading→submitting→verifying；悬置 submitting 一律 unknown
 * 禁止盲重发， draft_add_done 唯一派发与 token 有界刷新语义不变，复用注入的同一 WechatApiClient 不重复创建）、
 * 候选搜索/用户核实/取消。逻辑零变更；facade 以委托保持既有 API。
 */
@Service
public class WechatSyncExecutionService {

	private static final Duration TOTAL_BUDGET = Duration.ofMinutes(10);
	private static final int CONTENT_IMAGE_MAX_BYTES = 1024 * 1024;
	private static final int COVER_IMAGE_MAX_BYTES = 2 * 1024 * 1024;
	private static final int CANDIDATE_PAGE_SIZE = 20;
	private static final int CANDIDATE_MAX_ITEMS = 100;
	private static final Duration CANDIDATE_DEADLINE = Duration.ofSeconds(20);
	private static final Duration CANDIDATE_CACHE_TTL = Duration.ofSeconds(30);
	private static final List<String> ACTIVE_STATES = List.of("preparing", "uploading", "submitting", "verifying");

	private final WechatDraftSyncRepository syncs;
	private final WechatAccountRepository accounts;
	private final WechatTokenService tokens;
	private final WechatApiClient wechat;
	private final CreationDraftRepository drafts;
	private final CreationImageProcessor images;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final ObjectProvider<EnvelopeEncryption> cryptoProvider;
	private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
	private final WechatProperties properties;
	private final WechatDraftWorkflowStarter starter;
	private final StudioCommandStore commands;
	private final CreationRenderService renderer;
	private final WechatSyncPreparationService preparation;

	public WechatSyncExecutionService(WechatDraftSyncRepository syncs, WechatAccountRepository accounts,
			WechatTokenService tokens, WechatApiClient wechat, CreationDraftRepository drafts,
			CreationImageProcessor images, ObjectProvider<ObjectStorageAdapter> storageProvider,
			ObjectProvider<EnvelopeEncryption> cryptoProvider,
			ObjectProvider<ReactiveStringRedisTemplate> redisProvider, WechatProperties properties,
			WechatDraftWorkflowStarter starter, StudioCommandStore commands, CreationRenderService renderer,
			WechatSyncPreparationService preparation) {
		this.syncs = syncs;
		this.accounts = accounts;
		this.tokens = tokens;
		this.wechat = wechat;
		this.drafts = drafts;
		this.images = images;
		this.storageProvider = storageProvider;
		this.cryptoProvider = cryptoProvider;
		this.redisProvider = redisProvider;
		this.properties = properties;
		this.starter = starter;
		this.commands = commands;
		this.renderer = renderer;
		this.preparation = preparation;
	}

	// ---- workflow 推进（activity 调用；行是真相源） ----

	/** 返回 true=终态（workflow 收口）。先过总时限预算（10min），每步重验连接/凭据版本。 */
	public Mono<Boolean> advance(UUID syncId) {
		return syncs.findById(syncId).flatMap(row -> {
			if (!ACTIVE_STATES.contains(row.state())) {
				return completeDispatch(row);
			}
			if (!properties.isWorkerEnabled() && !List.of("submitting", "verifying").contains(row.state()))
				return Mono.just(false);
			if (row.createdAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minus(TOTAL_BUDGET))) {
				// 派发后超时结果不确定 → unknown；派发前超时确定未创建 → failed
				boolean dispatched = row.draftAddDone() || "submitting".equals(row.state())
						|| "verifying".equals(row.state());
				Mono<SyncRow> outcome = dispatched
						? syncs.markUnknown(row.id(), "STUDIO_TIMEOUT")
						: syncs.markFailed(row.id(), "STUDIO_TIMEOUT");
				return outcome.thenReturn(true);
			}
			return switch (row.state()) {
				case "preparing" -> advancePreparing(row);
				case "uploading" -> advanceUploading(row);
				case "submitting" -> advanceSubmitting(row);
				case "verifying" -> advanceVerifying(row);
				default -> Mono.just(true);
			};
		}).defaultIfEmpty(true);
	}

	private Mono<Boolean> completeDispatch(SyncRow row) {
		if (!"completed".equals(row.dispatchState())) {
			return syncs.markDispatch(row.id().toString(), "completed").thenReturn(true);
		}
		return Mono.just(true);
	}

	private Mono<Boolean> advancePreparing(SyncRow row) {
		return accountForWrite(row).flatMap(account -> {
			Map<String, Object> payload = PlanJson.readJson(row.payloadJson());
			List<Mono<MediaMappingRow>> inserts = new ArrayList<>();
			if (payload.get("cover") instanceof Map<?, ?> cover) {
				inserts.add(mapping(row, String.valueOf(cover.get("mediaRefId")), "cover", 0,
						String.valueOf(cover.get("contentHash"))));
			}
			if (payload.get("images") instanceof List<?> list) {
				int ordinal = 0;
				for (Object item : list) {
					if (item instanceof Map<?, ?> image) {
						inserts.add(mapping(row, String.valueOf(image.get("mediaRefId")), "content", ++ordinal,
								String.valueOf(image.get("contentHash"))));
					}
				}
			}
			return Mono.when(inserts).then(syncs.casState(row.id(), "preparing", "uploading", null)).thenReturn(false);
		}).onErrorResume(IntelligenceException.class, error -> markFailedFrom(row, error));
		// 瞬态错误（DB/存储抖动）原样抛出：activity 层按瞬态重试，行仍是真相源
	}

	private Mono<MediaMappingRow> mapping(SyncRow row, String mediaRefId, String purpose, int ordinal,
			String contentHash) {
		return syncs.insertOrGetMapping(row.id(), row.ownerAccountId(), row.accountId(), row.accountVersion(),
				UUID.fromString(mediaRefId), purpose, ordinal, contentHash);
	}

	private Mono<Boolean> advanceUploading(SyncRow row) {
		return accountForWrite(row)
				.flatMap(account -> syncs.mappingsOfSync(row.id()).collectList().flatMap(mappings -> {
					MediaMappingRow pending = mappings.stream().filter(item -> !"uploaded".equals(item.state()))
							.findFirst().orElse(null);
					if (pending != null) {
						return uploadOne(row, account, pending).thenReturn(false)
								.onErrorResume(IntelligenceException.class, error -> markFailedFrom(row, error))
								.onErrorResume(WechatApiClient.WechatApiException.class,
										error -> businessWechatFailure(error)
												? syncs.markFailed(row.id(), "WECHAT_" + error.code()).thenReturn(true)
												: Mono.just(false));
					}
					// 全部就绪：再次核验权限/凭据版本 → 持久 submitting → 同一次推进完成唯一 draft/add
					return accountForWrite(row)
							.flatMap(rechecked -> syncs.casState(row.id(), "uploading", "submitting", null))
							.flatMap(marked -> dispatchDraftAdd(marked));
				})).onErrorResume(IntelligenceException.class, error -> markFailedFrom(row, error));
	}

	/** 单张上传：媒体权限复查 → 压缩阶梯 → 上传（token 失效刷新一次；瞬态重试 2 次）→ 衍生图落存核对。 */
	private Mono<MediaMappingRow> uploadOne(SyncRow row, AccountRow account, MediaMappingRow mapping) {
		return authorizedSnapshot(row)
				.then(syncs.cachedUpload(mapping)).flatMap(cached -> syncs.markMappingUploaded(mapping.id(),
						cached.mediaId(), cached.mediaUrl(), cached.derivedObjectKey()))
				.switchIfEmpty(Mono.defer(() -> uploadNew(row, account, mapping)));
	}

	private Mono<MediaMappingRow> uploadNew(SyncRow row, AccountRow account, MediaMappingRow mapping) {
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储不可用"));
		}
		return Mono.defer(() -> syncs.bumpMappingAttempts(mapping.id()))
				.switchIfEmpty(
						Mono.error(new IntelligenceException(503, "STUDIO_PROVIDER_FAILED", "图片上传已达重试上限，请核对连接后重新发起同步")))
				.then(authorizedSnapshot(row))
				.flatMap(prepared -> Mono.justOrEmpty(prepared.media().stream()
						.filter(item -> item.media().id().equals(mapping.mediaRefId())).findFirst()))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "图片引用不再可用")))
				.flatMap(item -> images.bounded(() -> storage.getObject(item.media().objectKey())))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "图片文件已不可用")))
				.flatMap(bytes -> {
					if (bytes == null || bytes.length == 0) {
						return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "媒体对象缺失，同步中止"));
					}
					if (!mapping.contentHash().equals(MediaChecksums.sha256(bytes)))
						return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "图片内容校验失败"));
					int limit = "cover".equals(mapping.purpose()) ? COVER_IMAGE_MAX_BYTES : CONTENT_IMAGE_MAX_BYTES;
					return images.deriveForWechat(bytes, limit, "#ffffff");
				})
				.flatMap(derived -> withTokenRefreshing(account,
						token -> "cover".equals(mapping.purpose())
								? wechat.uploadCoverMaterial(token, derived.bytes(),
										"cover." + ("image/png".equals(derived.contentType()) ? "png" : "jpg"))
								: wechat.uploadContentImage(token, derived.bytes(),
										"image." + ("image/png".equals(derived.contentType()) ? "png" : "jpg")))
						.flatMap(uploaded -> {
							String objectKey = "creation-wechat/" + row.id() + "/" + mapping.id()
									+ ("image/png".equals(derived.contentType()) ? ".png" : ".jpg");
							return images.bounded(() -> {
								storage.putObject(objectKey, derived.bytes(), derived.contentType());
								return true;
							}).then(syncs.markMappingUploaded(mapping.id(), uploaded.mediaId(), uploaded.url(),
									objectKey));
						}))
				// 上传/只读最多重试 2 次（1s/2s）；业务错误（IntelligenceException/数字 errcode）不重试
				.retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).filter(WechatSyncExecutionService::transientError));
	}

	/** 悬置提交标记（崩溃/重放）：不得再次派发 → unknown 待核实（TC101-103）。 */
	private Mono<Boolean> advanceSubmitting(SyncRow row) {
		if (!row.draftAddDone()) {
			return syncs.markUnknown(row.id(), "STUDIO_UNKNOWN_OUTCOME").thenReturn(true);
		}
		return completeDispatch(row);
	}

	/** 唯一 draft/add 派发：业务错误 → failed；超时/断线/5xx 无 errcode/解析失败 → unknown（不自动重试）。 */
	private Mono<Boolean> dispatchDraftAdd(SyncRow marked) {
		return Mono.defer(() -> {
			Map<String, Object> payload = PlanJson.readJson(marked.payloadJson());
			return syncs.mappingsOfSync(marked.id()).collectList().flatMap(mappings -> {
				String coverMediaId = mappings.stream()
						.filter(item -> "cover".equals(item.purpose()) && item.mediaId() != null)
						.map(MediaMappingRow::mediaId).findFirst().orElse(null);
				if (coverMediaId == null) {
					return syncs.markFailed(marked.id(), "STUDIO_MEDIA_UNAVAILABLE").thenReturn(true);
				}
				String content = replaceImageUrls(String.valueOf(payload.get("contentHtml")), mappings);
				var article = new WechatArticle(String.valueOf(payload.get("title")), content, coverMediaId,
						payload.get("summary") == null ? null : String.valueOf(payload.get("summary")),
						payload.get("author") == null ? null : String.valueOf(payload.get("author")),
						payload.get("contentSourceUrl") == null
								? null
								: String.valueOf(payload.get("contentSourceUrl")),
						((Number) payload.getOrDefault("needOpenComment", 0)).intValue(),
						((Number) payload.getOrDefault("onlyFansCanComment", 0)).intValue());
				return accounts.findById(marked.accountId()).filter(
						account -> "active".equals(account.state()) && account.version() == marked.accountVersion())
						.flatMap(account -> withTokenRefreshing(account, token -> wechat.addDraft(token, article))
								.flatMap(mediaId -> syncs.markSubmitted(marked.id(), mediaId).thenReturn(false))
								.onErrorResume(WechatApiClient.WechatApiException.class,
										error -> businessWechatFailure(error)
												? syncs.markFailed(marked.id(), "WECHAT_" + error.code())
														.thenReturn(true)
												: syncs.markUnknown(marked.id(), "STUDIO_UNKNOWN_OUTCOME")
														.thenReturn(true)))
						.switchIfEmpty(Mono.defer(() -> syncs.markFailed(marked.id(), "STUDIO_CHANNEL_ACCOUNT_INVALID")
								.thenReturn(true)));
			});
		})
				// draft/add 超时/断线/5xx 无 errcode/解析失败：结果未知，绝不自动重发（TC101-098）
				.onErrorResume(
						error -> !(error instanceof WechatApiClient.WechatApiException)
								&& !(error instanceof IntelligenceException),
						error -> syncs.markUnknown(marked.id(), "STUDIO_UNKNOWN_OUTCOME").thenReturn(true))
				.onErrorResume(IntelligenceException.class, error -> markFailedFrom(marked, error));
	}

	/** 回读核实（只读——可用当前重新验证的凭据；比对失败不置 succeeded，TC101-097）。 */
	private Mono<Boolean> advanceVerifying(SyncRow row) {
		return accounts.findById(row.accountId()).filter(account -> "active".equals(account.state())).flatMap(
				account -> withTokenRefreshing(account, token -> wechat.getDraft(token, row.externalDraftMediaId()))
						// 只读重试 2 次（瞬态）；微信业务错误不重试
						.retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
								.filter(WechatSyncExecutionService::transientError))
						.flatMap(fetched -> syncs.mappingsOfSync(row.id()).collectList().flatMap(mappings -> {
							if (compare(row, fetched, mappings)) {
								return syncs.markSucceeded(row.id(), row.externalDraftMediaId()).thenReturn(true);
							}
							return syncs.markFailed(row.id(), "STUDIO_CHANNEL_CONTENT_MISMATCH").thenReturn(true);
						})).onErrorResume(WechatApiClient.WechatApiException.class,
								error -> businessWechatFailure(error)
										? syncs.markFailed(row.id(), "WECHAT_" + error.code()).thenReturn(true)
										: Mono.just(false)))
				// 连接已失效且尚未完成核实的派发：结果不确定 → unknown（待凭据修复后核实）
				.switchIfEmpty(Mono
						.defer(() -> syncs.markUnknown(row.id(), "STUDIO_CHANNEL_ACCOUNT_INVALID").thenReturn(true)));
	}

	// ---- API101-29 候选 / API101-30 核实 / API101-31 取消 ----

	/** 候选搜索（只在用户点击时执行）：20/页、最多 100 条、总截止 20s；Redis 缓存 30s（同 sync+版本）。 */
	public Mono<CandidateResult> candidates(Caller caller, UUID syncId) {
		return preparation.get(caller, syncId).flatMap(row -> {
			if (!"unknown".equals(row.state())) {
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "仅结果未知的同步需要核实候选"));
			}
			ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
			if (redis == null) {
				return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "公众号渠道缓存依赖不可用"));
			}
			String cacheKey = "creation:wechat:candidates:" + row.id() + ":v" + row.version();
			return redis.opsForValue().get(cacheKey)
					.flatMap(
							cached -> Mono.just(candidateResultOf(PlanJson.readJson(cached))))
					.switchIfEmpty(
							Mono.defer(
									() -> searchCandidates(row)
											.flatMap(result -> redis.opsForValue()
													.set(cacheKey, PlanJson.json(candidateJson(result)),
															CANDIDATE_CACHE_TTL)
													.thenReturn(result))))
					// Redis 运行时故障 → 渠道 503（§6.8 fail-closed）
					.onErrorResume(error -> !(error instanceof IntelligenceException), error -> Mono
							.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "公众号渠道缓存依赖不可用")));
		});
	}

	/** 候选匹配＝正文规范化文本一致（不能只看标题，§6.8）；标题只作辨认展示。 */

	private Mono<CandidateResult> searchCandidates(SyncRow row) {
		long deadline = System.nanoTime() + CANDIDATE_DEADLINE.toNanos();
		return accounts.findById(row.accountId()).filter(account -> "active".equals(account.state()))
				.switchIfEmpty(
						Mono.error(new IntelligenceException(422, "STUDIO_CHANNEL_ACCOUNT_INVALID", "请先校验连接后再核实草稿")))
				.flatMap(account -> syncs.mappingsOfSync(row.id()).collectList()
						.flatMap(mappings -> withTokenRefreshing(account,
								token -> collectPages(token, 0, new ArrayList<>(), row, mappings, deadline))));
	}

	private Mono<CandidateResult> collectPages(String token, int offset, List<Candidate> collected, SyncRow row,
			List<MediaMappingRow> mappings, long deadline) {
		long remaining = deadline - System.nanoTime();
		if (collected.size() >= CANDIDATE_MAX_ITEMS || remaining <= 0)
			return Mono.just(new CandidateResult(List.copyOf(collected), collected.size(), true));
		return wechat.batchGetDrafts(token, offset, CANDIDATE_PAGE_SIZE)
				.timeout(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(5).toNanos()))).flatMap(page -> {
					for (var item : page.items()) {
						if (collected.size() >= CANDIDATE_MAX_ITEMS)
							break;
						var article = new WechatApiClient.DraftArticle(item.title(), item.content(), item.digest(),
								item.thumbMediaId(), null, null);
						collected.add(new Candidate(item.mediaId(), item.title(), item.updatedAt(),
								compare(row, article, mappings)));
					}
					int nextOffset = offset + page.items().size();
					if (page.items().isEmpty() || nextOffset >= page.totalItem())
						return Mono.just(new CandidateResult(List.copyOf(collected), collected.size(), false));
					return collectPages(token, nextOffset, collected, row, mappings, deadline);
				}).onErrorResume(java.util.concurrent.TimeoutException.class,
						error -> Mono.just(new CandidateResult(List.copyOf(collected), collected.size(), true)));
	}

	/** 用户核实（API101-30）：draft/get 回读比对——一致才 succeeded；绝不触发 draft/add。 */
	public Mono<SyncRow> reconcile(Caller caller, UUID syncId, UUID requestId, int expectedVersion,
			String externalDraftMediaId) {
		return syncCommand(caller, syncId, requestId, expectedVersion, "reconcile", externalDraftMediaId,
				() -> reconcileNew(caller, syncId, expectedVersion, externalDraftMediaId));
	}

	private Mono<SyncRow> reconcileNew(Caller caller, UUID syncId, int expectedVersion, String externalDraftMediaId) {
		return syncs.lockByIdAndOwner(syncId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "同步不存在"))).flatMap(row -> {
					if (row.version() != expectedVersion) {
						return Mono
								.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "同步记录版本已变化，请刷新后重试"));
					}
					if (!List.of("unknown", "failed").contains(row.state())) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "当前状态无需核实"));
					}
					return accounts.findById(row.accountId()).filter(account -> "active".equals(account.state()))
							.switchIfEmpty(Mono.error(new IntelligenceException(422, "STUDIO_CHANNEL_ACCOUNT_INVALID",
									"连接未验证或已失效，请先在连接设置中校验")))
							.flatMap(account -> withTokenRefreshing(account,
									token -> wechat.getDraft(token, externalDraftMediaId))
									.flatMap(fetched -> syncs.mappingsOfSync(row.id()).collectList()
											.flatMap(mappings -> {
												if (compare(row, fetched, mappings)) {
													return syncs.markSucceeded(row.id(), externalDraftMediaId);
												}
												// 不一致：不置 succeeded，错误经响应透出（行保持原态可再核实）
												return Mono.error(new IntelligenceException(409,
														"STUDIO_CHANNEL_CONTENT_MISMATCH", "外部草稿内容与快照不一致"));
											})));
				});
	}

	/** 取消（API101-31）：submitting 后无法证明取消 → unknown；早期态 CAS 取消。 */
	public Mono<SyncRow> cancel(Caller caller, UUID syncId, UUID requestId, int expectedVersion) {
		return syncCommand(caller, syncId, requestId, expectedVersion, "cancel", "",
				() -> cancelNew(caller, syncId, expectedVersion));
	}

	private Mono<SyncRow> syncCommand(Caller caller, UUID id, UUID request, int version, String kind, String externalId,
			java.util.function.Supplier<Mono<SyncRow>> action) {
		String hash = PlanJson.sha256(PlanJson.json(Map.of("id", id, "version", version, "externalId", externalId)));
		return preparation.get(caller, id)
				.then(commands.execute(caller.accountId(), "wechat-sync-" + kind, request, hash,
						() -> action.get().map(row -> new StudioCommandStore.Result(row.id(), row.version(), Map.of())),
						resource -> preparation.get(caller, resource)))
				.flatMap(result -> preparation.get(caller, result.resourceId()));
	}

	private Mono<SyncRow> cancelNew(Caller caller, UUID syncId, int expectedVersion) {
		return syncs.lockByIdAndOwner(syncId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "同步不存在"))).flatMap(row -> {
					if (row.version() != expectedVersion) {
						return Mono
								.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "同步记录版本已变化，请刷新后重试"));
					}
					switch (row.state()) {
						case "preparing", "uploading" :
							return syncs.casState(row.id(), row.state(), "cancelled", null)
									.doOnNext(cancelled -> starter.signalCancel(row.id(), "user-cancel"))
									.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT",
											"同步记录版本已变化，请刷新后重试")));
						case "submitting" :
							return syncs.markUnknown(row.id(), "STUDIO_UNKNOWN_OUTCOME");
						case "verifying" :
							return syncs.markUnknown(row.id(), "STUDIO_UNKNOWN_OUTCOME");
						default :
							return Mono.just(row);
					}
				});
	}

	// ---- 共用工具 ----

	/** 写路径连接门禁：状态 active 且版本与冻结一致（TC101-102：撤权/轮换后不提交）。 */
	private Mono<AccountRow> accountForWrite(SyncRow row) {
		return authorizedSnapshot(row).then(accounts.findById(row.accountId()))
				.filter(account -> "active".equals(account.state()) && account.version() == row.accountVersion())
				.switchIfEmpty(Mono
						.error(new IntelligenceException(422, "STUDIO_CHANNEL_ACCOUNT_INVALID", "连接已断开或凭据已变更，未提交草稿")));
	}

	private Mono<CreationRenderService.PreparedDocument> authorizedSnapshot(SyncRow row) {
		return drafts.findById(row.draftId())
				.filter(draft -> row.ownerAccountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿已删除")))
				.flatMap(draft -> renderer.prepare(new Caller(row.ownerAccountId(), null, null, draft.organizationId(),
						null, "user", row.ownerAccountId(), "user"), row.draftId(), row.draftVersion()))
				.flatMap(prepared -> prepared.unavailable().isEmpty()
						? Mono.just(prepared)
						: Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "图片授权已失效")));
	}

	private <T> Mono<T> withToken(AccountRow account, Function<String, Mono<T>> call) {
		EnvelopeEncryption crypto = cryptoProvider.getIfAvailable();
		if (crypto == null || account.encryptedSecret() == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "加密依赖不可用"));
		}
		return tokens.token(account, crypto.decrypt(account.encryptedSecret())).flatMap(call);
	}

	/** 确定的 token 失效（40001/42001/40014，请求被拒未创建）：失效缓存后刷新重试一次（§6.8 有界）。 */
	private <T> Mono<T> withTokenRefreshing(AccountRow account, Function<String, Mono<T>> call) {
		return withToken(account, call).onErrorResume(WechatApiClient.WechatApiException.class, error -> {
			if (!error.tokenInvalid()) {
				return Mono.error(error);
			}
			return tokens.invalidate(account).then(withToken(account, call));
		});
	}

	/** 数字 errcode = 微信业务错误（确定失败）；network/解析类 = 传输不确定。 */
	static boolean businessWechatFailure(WechatApiClient.WechatApiException error) {
		return error.code().matches("\\d+");
	}

	/** 瞬态错误（可安全重试）：非业务异常 + 非数字 errcode 的微信响应。 */
	static boolean transientError(Throwable error) {
		if (error instanceof IntelligenceException) {
			return false;
		}
		if (error instanceof WechatApiClient.WechatApiException wechat) {
			return !businessWechatFailure(wechat);
		}
		return true;
	}

	private Mono<Boolean> markFailedFrom(SyncRow row, IntelligenceException error) {
		return syncs.markFailed(row.id(), error.code()).thenReturn(true);
	}

	/**
	 * 冻结 HTML 中的 /api/media/{id} 占位替换为已上传微信 URL。 封面与正文图都渲染在内容里（§6.7 封面置于 最前）——封面替换用
	 * add_material 返回的 URL（thumb_media_id 仍走 media_id 语义，两者不混用）。
	 */

	static String replaceImageUrls(String contentHtml, List<MediaMappingRow> mappings) {
		var document = org.jsoup.Jsoup.parseBodyFragment(contentHtml);
		document.outputSettings().prettyPrint(false);
		for (var image : document.select("img")) {
			String id = image.attr("src").replace("/api/media/", "");
			var mapping = mappings.stream()
					.filter(item -> "content".equals(item.purpose()) && item.mediaRefId().toString().equals(id)
							&& item.mediaUrl() != null)
					.findFirst()
					.or(() -> mappings.stream()
							.filter(item -> item.mediaRefId().toString().equals(id) && item.mediaUrl() != null)
							.findFirst());
			mapping.ifPresent(item -> image.attr("src", item.mediaUrl()));
		}
		return document.body().html();
	}

	boolean compare(SyncRow row, WechatApiClient.DraftArticle fetched, List<MediaMappingRow> mappings) {
		var payload = PlanJson.readJson(row.payloadJson());
		String expected = replaceImageUrls(value(payload.get("contentHtml")), mappings);
		if (!value(payload.get("title")).equals(value(fetched.title()))
				|| !value(payload.get("summary")).equals(value(fetched.digest()))
				|| !WechatContentVerifier.tokens(expected).equals(WechatContentVerifier.tokens(fetched.content())))
			return false;
		String cover = mappings.stream().filter(item -> "cover".equals(item.purpose())).map(MediaMappingRow::mediaId)
				.filter(java.util.Objects::nonNull).findFirst().orElse(null);
		return cover != null && cover.equals(fetched.thumbMediaId());
	}

	// ---- 候选缓存 JSON ----

	private Map<String, Object> candidateJson(CandidateResult result) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("searchedCount", result.searchedCount());
		body.put("hasMore", result.hasMore());
		body.put("items", result.items().stream().map(item -> {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("externalDraftMediaId", item.externalDraftMediaId());
			json.put("title", item.title());
			json.put("updatedAt", item.updatedAt());
			json.put("contentMatches", item.contentMatches());
			return json;
		}).toList());
		return body;
	}

	@SuppressWarnings("unchecked")
	private CandidateResult candidateResultOf(Map<String, Object> json) {
		List<Candidate> items = new ArrayList<>();
		if (json.get("items") instanceof List<?> raw) {
			for (Object entry : raw) {
				if (entry instanceof Map<?, ?> map) {
					items.add(new Candidate(String.valueOf(map.get("externalDraftMediaId")),
							String.valueOf(map.get("title")),
							map.get("updatedAt") == null ? null : String.valueOf(map.get("updatedAt")),
							Boolean.TRUE.equals(map.get("contentMatches"))));
				}
			}
		}
		return new CandidateResult(items, ((Number) json.getOrDefault("searchedCount", items.size())).intValue(),
				Boolean.TRUE.equals(json.get("hasMore")));
	}
}
