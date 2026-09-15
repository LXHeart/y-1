package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.StudioCommandStore;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.render.CreationExportRepository;
import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.creationstudio.render.CreationRenderService;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountRepository.AccountRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.SyncRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService.CreateCommand;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：公众号草稿同步「准备」服务——自 {@link WechatDraftSyncService} 按职责原样搬移：
 * 创建同步的完整冻结链（连接/草稿快照/导出校验 → HTML 冻结 → 封面与正文图片冻结 → payload 键序规范哈希 → 同键重放与快照复用
 * claim）与本人可见读取 get。逻辑零变更；facade 以委托保持既有 API。
 */
@Service
public class WechatSyncPreparationService {

	private static final int MAX_IMAGES = 20;
	private static final List<String> ACTIVE_STATES = List.of("preparing", "uploading", "submitting", "verifying");

	private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL = com.fasterxml.jackson.databind.json.JsonMapper
			.builder().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

	private final WechatDraftSyncRepository syncs;
	private final WechatAccountRepository accounts;
	private final CreationDraftRepository drafts;
	private final CreationExportRepository exports;
	private final CreationImageProcessor images;
	private final CreationRenderService renderer;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final WechatProperties properties;
	private final WechatDraftWorkflowStarter starter;
	private final StudioCommandStore commands;

	public WechatSyncPreparationService(WechatDraftSyncRepository syncs, WechatAccountRepository accounts,
			CreationDraftRepository drafts, CreationExportRepository exports, CreationImageProcessor images,
			CreationRenderService renderer, ObjectProvider<ObjectStorageAdapter> storageProvider,
			WechatProperties properties, WechatDraftWorkflowStarter starter, StudioCommandStore commands) {
		this.syncs = syncs;
		this.accounts = accounts;
		this.drafts = drafts;
		this.exports = exports;
		this.images = images;
		this.renderer = renderer;
		this.storageProvider = storageProvider;
		this.properties = properties;
		this.starter = starter;
		this.commands = commands;
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

	static String value(Object value) {
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

	static List<String> imgSrcs(String html) {
		return WechatContentVerifier.imageSources(html);
	}

	/** 键排序规范 JSON（ORDER_MAP_ENTRIES_BY_KEYS 深度排序）——payload 哈希/比对基准。 */
	static String canonicalJson(Map<String, Object> payload) {
		try {
			return CANONICAL.writeValueAsString(PlanJson.readJson(PlanJson.json(payload)));
		} catch (Exception error) {
			throw new IllegalStateException("payload 序列化失败", error);
		}
	}

	// ---- 读取列表（listByOwnerAndDraft 游标分页） ----

	public Mono<Map<String, Object>> list(Caller caller, UUID draftId, int limit, String cursor) {
		var key = com.grassland.intelligence.creationstudio.StudioCursor.parse(cursor);
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
						nextCursor = com.grassland.intelligence.creationstudio.StudioCursor
								.encode(rows.get(end - 1).createdAt(), rows.get(end - 1).id());
					}
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("items", items);
					data.put("nextCursor", nextCursor);
					return data;
				});
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
}
