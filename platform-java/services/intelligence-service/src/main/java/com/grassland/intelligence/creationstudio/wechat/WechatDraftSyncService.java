package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.StudioCommandStore;
import com.grassland.intelligence.creationstudio.StudioCursor;
import com.grassland.intelligence.creationstudio.render.CreationRenderService;
import com.grassland.intelligence.creationstudio.render.CreationExportRepository;
import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountRepository.AccountRow;
import com.grassland.intelligence.creationstudio.wechat.WechatApiClient.WechatArticle;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.MediaMappingRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.SyncRow;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.nio.charset.StandardCharsets;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 任务书 #101 C101-21（API101-26~31、§6.8）：公众号草稿同步。
 *
 * <p>
 * 创建时冻结完整 payload（标题/正文 HTML/摘要/作者/来源链接/评论选项/封面与图片顺序）； 同账号＋draftVersion＋
 * payloadHash 复用活动记录；上传映射按 连接＋凭据版本＋内容hash＋用途 缓存； 持久 submitting 后由同一次 advance
 * 完成唯一 draft/add——崩溃/重放看到悬置标记一律 unknown，禁止盲重发（R101-15）； 回读 draft/get 受控比对（文本
 * token＋图片顺序＋封面）一致才 succeeded。Token 失效有界刷新一次后重试一次； 上传/只读最多重试 2 次；候选搜索 20/页 ≤100
 * 条、总截止 20s、Redis 缓存 30s；每同步总时限 10min。
 */
@Service
public class WechatDraftSyncService {

	private static final Duration TOTAL_BUDGET = Duration.ofMinutes(10);
	private static final int MAX_IMAGES = 20;
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
	private final CreationExportRepository exports;
	private final MediaReferenceRepository media;
	private final CreationImageProcessor images;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final ObjectProvider<EnvelopeEncryption> cryptoProvider;
	private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
	private final WechatProperties properties;
	private final WechatDraftWorkflowStarter starter;
	private final StudioCommandStore commands;
	private final CreationRenderService renderer;

	public WechatDraftSyncService(WechatDraftSyncRepository syncs, WechatAccountRepository accounts,
			WechatTokenService tokens, WechatApiClient wechat, CreationDraftRepository drafts,
			CreationExportRepository exports, MediaReferenceRepository media, CreationImageProcessor images,
			ObjectProvider<ObjectStorageAdapter> storageProvider, ObjectProvider<EnvelopeEncryption> cryptoProvider,
			ObjectProvider<ReactiveStringRedisTemplate> redisProvider, WechatProperties properties,
			WechatDraftWorkflowStarter starter, StudioCommandStore commands, CreationRenderService renderer) {
		this.syncs = syncs;
		this.accounts = accounts;
		this.tokens = tokens;
		this.wechat = wechat;
		this.drafts = drafts;
		this.exports = exports;
		this.media = media;
		this.images = images;
		this.storageProvider = storageProvider;
		this.cryptoProvider = cryptoProvider;
		this.redisProvider = redisProvider;
		this.properties = properties;
		this.starter = starter;
		this.commands = commands;
		this.renderer = renderer;
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL = com.fasterxml.jackson.databind.json.JsonMapper
			.builder().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

	public record CreateCommand(UUID requestId, UUID accountId, int expectedAccountVersion, UUID draftId,
			int draftVersion, UUID exportId, String author, String contentSourceUrl, int needOpenComment,
			int onlyFansCanComment) {
	}

	// ---- API101-26 创建（冻结快照 + 幂等） ----

	public Mono<SyncRow> create(Caller caller, CreateCommand command) {
		String hash = PlanJson.sha256(PlanJson.json(commandInput(command)));
		return commands
				.execute(caller.accountId(), "wechat-sync-create", command.requestId(), hash,
						() -> createNew(caller, command)
								.map(row -> new StudioCommandStore.Result(row.id(), row.version(), Map.of())),
						id -> get(caller, id))
				.flatMap(result -> get(caller, result.resourceId())).flatMap(this::startAfterCommit);
	}

	private Mono<SyncRow> startAfterCommit(SyncRow row) {
		if (!properties.isWorkerEnabled() || !"pending".equals(row.dispatchState())
				|| !ACTIVE_STATES.contains(row.state()))
			return Mono.just(row);
		return Mono.fromRunnable(() -> starter.start(row.id()))
				.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
				.then(syncs.markDispatch(row.id().toString(), "started")).onErrorResume(error -> Mono.empty())
				.thenReturn(row);
	}

	private Mono<SyncRow> createNew(Caller caller, CreateCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "公众号渠道写入暂未开放"));
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "对象存储不可用"));
		}
		return loadAccountForCreate(caller, command)
				.flatMap(account -> loadDraftSnapshot(caller, command).flatMap(draft -> validateExport(caller, command)
						.flatMap(exportRow -> loadFrozenContent(storage, exportRow).flatMap(contentHtml -> {
							String title = draft.articleTitle() == null ? draft.title() : draft.articleTitle();
							if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 64) {
								return Mono.error(
										new IntelligenceException(400, "STUDIO_INVALID_INPUT", "公众号标题须为 1～64 字"));
							}
							return collectFrozenMedia(caller, draft, storage, contentHtml).flatMap(frozen -> {
								Map<String, Object> payload = freezePayload(title, contentHtml, frozen.summary(),
										command);
								payload.put("cover", frozen.cover());
								payload.put("images", frozen.images());
								String payloadJson = PlanJson.json(payload);
								// jsonb 不保键序——哈希须用键排序规范形（重放比对才稳定）
								return claim(caller, command, account, PlanJson.sha256(canonicalJson(payload)),
										payloadJson);
							});
						}))));
	}

	private static Map<String, Object> commandInput(CreateCommand command) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("accountId", command.accountId());
		input.put("accountVersion", command.expectedAccountVersion());
		input.put("draftId", command.draftId());
		input.put("draftVersion", command.draftVersion());
		input.put("exportId", command.exportId());
		input.put("author", value(command.author()));
		input.put("contentSourceUrl", value(command.contentSourceUrl()));
		input.put("needOpenComment", command.needOpenComment());
		input.put("onlyFansCanComment", command.onlyFansCanComment());
		return input;
	}

	private static String value(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private Mono<AccountRow> loadAccountForCreate(Caller caller, CreateCommand command) {
		return accounts.findByIdAndOwner(command.accountId(), caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "连接不存在")))
				.flatMap(account -> {
					if (account.version() != command.expectedAccountVersion()) {
						return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "连接版本已变化，请刷新后重试"));
					}
					if (!"active".equals(account.state())) {
						return Mono.error(new IntelligenceException(422, "STUDIO_CHANNEL_ACCOUNT_INVALID",
								"连接未验证或已失效，请先在连接设置中校验"));
					}
					return Mono.just(account);
				});
	}

	private Mono<CreationDraft> loadDraftSnapshot(Caller caller, CreateCommand command) {
		return drafts.findById(command.draftId())
				.filter(draft -> caller.accountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在")))
				.flatMap(draft -> {
					if (command.draftVersion() < 1 || command.draftVersion() > draft.version()) {
						return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿版本不存在"));
					}
					if (command.draftVersion() == draft.version()) {
						return Mono.just(draft);
					}
					return drafts.findVersion(command.draftId(), command.draftVersion())
							.map(row -> new CreationDraft(row.draftId(), draft.ownerAccountId(), draft.organizationId(),
									row.title(), row.sourceType(), row.taskId(), row.taskVersion(), row.storeId(),
									row.platform(), row.contentForm(), row.topic(), row.articleTitle(), row.outline(),
									row.content(), row.contentMode(), row.questionText(), row.questionRef(),
									row.status(), row.version(), null, row.createdAt(), null, row.workspace(),
									row.resultAssetIds(), row.runIds()));
				}).map(snapshot -> {
					com.grassland.intelligence.creationstudio.CreationStudioContextService.requireStudioScope(snapshot);
					if (!"wechat-official".equals(snapshot.platform())) {
						throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "仅公众号图文草稿可同步到草稿箱");
					}
					Map<?, ?> delivery = snapshot.workspace().get("delivery") instanceof Map<?, ?> value
							? value
							: Map.of();
					Map<?, ?> declarations = delivery.get("declarations") instanceof Map<?, ?> value ? value : Map.of();
					for (String key : List.of("aiGenerated", "commercial", "original")) {
						if (!java.util.Set.of("confirmed", "not-applicable").contains(value(declarations.get(key)))) {
							throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请先完成 AI、商业合作与原创声明");
						}
					}
					if (value(delivery.get("summary")).isBlank()) {
						throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请先填写公众号摘要");
					}
					if (snapshot.content() == null || snapshot.content().isBlank()) {
						throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请先填写公众号正文");
					}
					return snapshot;
				});
	}

	private Mono<CreationExportRepository.ExportRow> validateExport(Caller caller, CreateCommand command) {
		return exports.findByIdAndOwner(command.exportId(), caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "导出不存在")))
				.flatMap(exportRow -> {
					if (!"wechat-html".equals(exportRow.format()) || !"ready".equals(exportRow.state())) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT",
								"需要可用的公众号 HTML 导出（先完成 wechat-html 导出）"));
					}
					if (!exportRow.draftId().equals(command.draftId())
							|| exportRow.version() != command.draftVersion()) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "导出与所选草稿版本不一致"));
					}
					return Mono.just(exportRow);
				});
	}

	private Mono<String> loadFrozenContent(ObjectStorageAdapter storage, CreationExportRepository.ExportRow exportRow) {
		return Mono.fromCallable(() -> {
			Map<String, Object> manifest = PlanJson.readJson(exportRow.manifestJson());
			byte[] bytes = storage.getObject(String.valueOf(manifest.get("objectKey")));
			if (bytes == null || bytes.length == 0) {
				throw new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "导出文件已不可用，请重新导出");
			}
			String html;
			if ("application/zip".equals(manifest.get("contentType"))) {
				html = null;
				try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
					java.util.zip.ZipEntry entry;
					while ((entry = zip.getNextEntry()) != null) {
						if ("article.html".equals(entry.getName())) {
							byte[] content = zip.readNBytes(1024 * 1024 + 1);
							if (content.length > 1024 * 1024)
								throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "公众号正文超过 1 MiB");
							html = new String(content, StandardCharsets.UTF_8);
							break;
						}
					}
				}
				if (html == null)
					throw new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "导出包缺少文章 HTML");
			} else
				html = new String(bytes, StandardCharsets.UTF_8);
			var document = org.jsoup.Jsoup.parse(html);
			document.outputSettings().prettyPrint(false);
			if (manifest.get("mediaFiles") instanceof List<?> files) {
				for (Object file : files)
					if (file instanceof Map<?, ?> mapping) {
						for (var image : document.select("img")) {
							if (image.attr("src").equals(String.valueOf(mapping.get("path"))))
								image.attr("src", "/api/media/" + mapping.get("mediaId"));
						}
					}
			}
			return document.body().html();
		}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
	}

	private record FrozenMedia(Map<String, Object> cover, List<Map<String, Object>> images, String summary) {
	}

	/** 冻结封面与正文图片（resultRefs 顺序；权限/字节缺失 → 409 STUDIO_MEDIA_UNAVAILABLE）。 */

	private Mono<FrozenMedia> collectFrozenMedia(Caller caller, CreationDraft draft, ObjectStorageAdapter storage,
			String html) {
		return renderer.prepare(caller, draft.id(), draft.version()).flatMap(prepared -> {
			if (!prepared.unavailable().isEmpty())
				return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "配图已不可用，请重新核对"));
			var cover = prepared.media().stream().filter(item -> "cover".equals(item.ref().get("role"))).findFirst();
			if (cover.isEmpty())
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "请先采用封面"));
			Map<String, CreationRenderService.ResolvedMedia> byId = new LinkedHashMap<>();
			prepared.media().forEach(item -> byId.put(item.media().id().toString(), item));
			List<String> contentIds = new ArrayList<>();
			for (String src : imgSrcs(html)) {
				if (!src.startsWith("/api/media/") || !byId.containsKey(src.substring(11)))
					return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "导出图片与草稿引用不一致"));
				String id = src.substring(11);
				if (!contentIds.contains(id))
					contentIds.add(id);
			}
			if (contentIds.size() > MAX_IMAGES)
				return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "正文图片最多 20 张"));
			String coverId = cover.get().media().id().toString();
			var ids = new java.util.LinkedHashSet<String>();
			ids.add(coverId);
			ids.addAll(contentIds);
			return Flux.fromIterable(ids).concatMap(id -> images
					.bounded(() -> storage.getObject(byId.get(id).media().objectKey()))
					.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "媒体文件已不可用")))
					.flatMap(bytes -> images.validateAndDecode(bytes)
							.map(decoded -> Map.<String, Object>of("mediaRefId", id, "contentHash",
									MediaChecksums.sha256(bytes)))))
					.collectMap(item -> String.valueOf(item.get("mediaRefId"))).map(frozen -> {
						Map<?, ?> delivery = draft.workspace().get("delivery") instanceof Map<?, ?> value
								? value
								: Map.of();
						String summary = value(delivery.get("summary"));
						if (summary.codePointCount(0, summary.length()) > 120)
							throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "公众号摘要最多 120 字");
						return new FrozenMedia(frozen.get(coverId), contentIds.stream().map(frozen::get).toList(),
								summary);
					});
		});
	}

	private static Map<String, Object> freezePayload(String title, String contentHtml, String summary,
			CreateCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title", title);
		payload.put("contentHtml", contentHtml);
		payload.put("summary", summary);
		payload.put("author", command.author());
		payload.put("contentSourceUrl", command.contentSourceUrl());
		payload.put("needOpenComment", command.needOpenComment());
		payload.put("onlyFansCanComment", command.onlyFansCanComment());
		return payload;
	}

	/** 同键重放读回；不同键同快照复用活动记录（部分唯一索引兜底并发，TC101-099）。 */
	private Mono<SyncRow> claim(Caller caller, CreateCommand command, AccountRow account, String payloadHash,
			String payloadJson) {
		return syncs.findByOwnerAndRequest(caller.accountId(), command.requestId().toString()).flatMap(existing -> {
			if (!existing.payloadHash().equals(payloadHash)) {
				return Mono
						.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同同步请求"));
			}
			return Mono.just(existing);
		}).switchIfEmpty(
				syncs.findActiveBySnapshot(command.accountId(), command.draftId(), command.draftVersion(), payloadHash))
				.switchIfEmpty(Mono.defer(() -> syncs.insertOrGet(UUID.randomUUID(), caller.accountId(),
						command.requestId().toString(), command.accountId(), account.version(), command.draftId(),
						command.draftVersion(), command.exportId(), payloadHash, payloadJson)));
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
				.retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).filter(WechatDraftSyncService::transientError));
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
						.retryWhen(
								Retry.backoff(2, Duration.ofSeconds(1)).filter(WechatDraftSyncService::transientError))
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

	public record Candidate(String externalDraftMediaId, String title, String updatedAt, boolean contentMatches) {
	}

	public record CandidateResult(List<Candidate> items, int searchedCount, boolean hasMore) {
	}

	/** 候选搜索（只在用户点击时执行）：20/页、最多 100 条、总截止 20s；Redis 缓存 30s（同 sync+版本）。 */
	public Mono<CandidateResult> candidates(Caller caller, UUID syncId) {
		return get(caller, syncId).flatMap(row -> {
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
		return get(caller, id).then(commands.execute(caller.accountId(), "wechat-sync-" + kind, request, hash,
				() -> action.get().map(row -> new StudioCommandStore.Result(row.id(), row.version(), Map.of())),
				resource -> get(caller, resource))).flatMap(result -> get(caller, result.resourceId()));
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

	// ---- 读取 ----

	public Mono<SyncRow> get(Caller caller, UUID syncId) {
		return syncs.findByIdAndOwner(syncId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "同步不存在")))
				.flatMap(row -> readableDraft(caller, row.draftId()).thenReturn(row));
	}

	private Mono<CreationDraft> readableDraft(Caller caller, UUID id) {
		return drafts.findById(id)
				.filter(draft -> caller.accountId().equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在")));
	}

	public Mono<Map<String, Object>> list(Caller caller, UUID draftId, int limit, String cursor) {
		var key = StudioCursor.parse(cursor);
		return readableDraft(caller, draftId).then(syncs
				.listByOwnerAndDraft(caller.accountId(), draftId, limit + 1, key.createdAt(), key.id()).collectList())
				.map(rows -> {
					List<Map<String, Object>> items = new ArrayList<>();
					String nextCursor = null;
					int end = Math.min(rows.size(), limit);
					for (int index = 0; index < end; index++) {
						items.add(toBody(rows.get(index)));
					}
					if (rows.size() > limit && end > 0) {
						nextCursor = StudioCursor.encode(rows.get(end - 1).createdAt(), rows.get(end - 1).id());
					}
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("items", items);
					data.put("nextCursor", nextCursor);
					return data;
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

	static List<String> imgSrcs(String html) {
		return WechatContentVerifier.imageSources(html);
	}
	static String normalizeText(String html) {
		return org.jsoup.Jsoup.parseBodyFragment(html == null ? "" : html).text().replace('\u00a0', ' ').strip();
	}

	// ---- 响应体（§6.3 WechatDraftSync；完整快照永不返回） ----

	public Map<String, Object> toBody(SyncRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("requestId", row.requestId());
		body.put("accountId", row.accountId().toString());
		body.put("draftId", row.draftId().toString());
		body.put("draftVersion", row.draftVersion());
		body.put("state", row.state());
		body.put("externalDraftMediaId", row.externalDraftMediaId());
		body.put("payloadHash", row.payloadHash());
		body.put("version", row.version());
		body.put("createdAt", row.createdAt().toInstant().toString());
		body.put("verifiedAt", row.verifiedAt() == null ? null : row.verifiedAt().toInstant().toString());
		body.put("error",
				row.errorCode() == null
						? null
						: Map.of("code", row.errorCode(), "message", errorMessage(row.errorCode())));
		return body;
	}

	private static String errorMessage(String code) {
		return switch (code) {
			case "STUDIO_UNKNOWN_OUTCOME" -> "草稿写入结果未知，请核实草稿箱后确认";
			case "STUDIO_CHANNEL_CONTENT_MISMATCH" -> "外部草稿与快照不一致";
			case "STUDIO_CHANNEL_ACCOUNT_INVALID" -> "连接已断开或凭据已变更";
			case "STUDIO_MEDIA_UNAVAILABLE" -> "配图媒体已删除或不可用";
			case "STUDIO_TIMEOUT" -> "同步超出时限";
			default -> code != null && code.startsWith("WECHAT_") ? "微信接口返回错误，请核对后重试" : "同步未完成";
		};
	}

	/** 键排序规范 JSON（ORDER_MAP_ENTRIES_BY_KEYS 深度排序）——payload 哈希/比对基准。 */
	static String canonicalJson(Map<String, Object> payload) {
		try {
			return CANONICAL.writeValueAsString(PlanJson.readJson(PlanJson.json(payload)));
		} catch (Exception error) {
			throw new IllegalStateException("payload 序列化失败", error);
		}
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
