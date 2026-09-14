package com.grassland.intelligence.creationstudio.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiRun;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.ImageExecutionObserver;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService;
import com.grassland.intelligence.articleimage.ReferenceImage;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository.VisualJobRow;
import com.grassland.intelligence.creationstudio.CreationVisualPresetCatalog;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-08/10（§6.6 运行拆分 + 参考链）：单项 prepare/execute/reconcile 编排。
 *
 * <p>
 * 执行快照由父任务 {@code snapshot_json} 携带（C101-10 组装）：{accountId, organizationId,
 * consistencyMode, size, paletteId, document}。本类负责「一个子项一次可恢复执行」：
 *
 * <ul>
 * <li>queued → 认领（单一派发者）→ 按条目组装 prompt → 固定 executionOperationId 执行 → prepared
 * 闸门 → 确定性原图 → generated 落 mediaId → 结算 → succeeded → 交付画幅
 * artifact（C101-09）登记；</li>
 * <li>参考链：条目携带 anchor_artifact_id 时，把锚点封面原图字节作为参考传入（reference-image 模式）；</li>
 * <li>generated_unsettled → 只重放结算（同 run 同图，供应商调用不增加）；</li>
 * <li>dispatching 崩溃无可确认产物 → unknown，不自动重派（TC101-037）。</li>
 * </ul>
 */
@Service
public class VisualExecutionBridge {

	private static final Logger log = LoggerFactory.getLogger(VisualExecutionBridge.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CardSeriesOperationRepository operations;
	private final VisualItemRepository items;
	private final IndependentImageGenerationService independent;
	private final AiExecutionService executions;
	private final AiRunRepository runs;
	private final VisualArtifactService artifacts;
	private final VisualArtifactRepository artifactRows;
	private final com.grassland.intelligence.creationstudio.CreationStudioContextService contexts;
	private final com.grassland.intelligence.creationassistant.CreationDraftService drafts;
	private final com.grassland.intelligence.articleimage.TaskImageGenerationService taskImages;
	private final com.grassland.intelligence.creationstudio.render.CreationImageProcessor imageProcessor;
	private final com.grassland.intelligence.media.MediaReferenceRepository mediaRows;
	private final com.grassland.intelligence.creationassistant.CreationResultReferences references;
	private final org.springframework.beans.factory.ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storage;

	public VisualExecutionBridge(CardSeriesOperationRepository operations, VisualItemRepository items,
			IndependentImageGenerationService independent, AiExecutionService executions, AiRunRepository runs,
			VisualArtifactService artifacts, VisualArtifactRepository artifactRows,
			com.grassland.intelligence.creationstudio.CreationStudioContextService contexts,
			com.grassland.intelligence.creationassistant.CreationDraftService drafts,
			com.grassland.intelligence.articleimage.TaskImageGenerationService taskImages,
			com.grassland.intelligence.creationstudio.render.CreationImageProcessor imageProcessor,
			com.grassland.intelligence.media.MediaReferenceRepository mediaRows,
			com.grassland.intelligence.creationassistant.CreationResultReferences references,
			org.springframework.beans.factory.ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storage) {
		this.operations = operations;
		this.items = items;
		this.independent = independent;
		this.executions = executions;
		this.runs = runs;
		this.artifacts = artifacts;
		this.artifactRows = artifactRows;
		this.contexts = contexts;
		this.drafts = drafts;
		this.taskImages = taskImages;
		this.imageProcessor = imageProcessor;
		this.mediaRows = mediaRows;
		this.references = references;
		this.storage = storage;
	}

	/** 子项执行结果（调用方组装父任务状态；不假装成功）。 */
	public record ItemExecution(String itemId, String state, UUID runId, UUID originalMediaId, UUID artifactId,
			String errorCode, String message) {
	}

	public Mono<ItemExecution> executeItem(UUID operationId, UUID attemptId, String accountId) {
		return operations.findVisualJob(operationId, accountId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉任务不存在")))
				.flatMap(job -> items.findById(attemptId)
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉子项不存在")))
						.flatMap(item -> {
							if (!item.operationId().equals(operationId)) {
								return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉子项不存在"));
							}
							return switch (item.state()) {
								case VisualItemRepository.STATE_QUEUED -> dispatch(job, item);
								case VisualItemRepository.STATE_GENERATED_UNSETTLED ->
									reconcile(operationId, attemptId, accountId);
								default -> Mono.just(toResult(item, item.state(), null));
							};
						}));
	}

	/** 恢复结算入口（generated_unsettled；也可由收尾扫描调用）。 */
	public Mono<ItemExecution> reconcile(UUID operationId, UUID attemptId, String accountId) {
		return operations.findVisualJob(operationId, accountId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉任务不存在")))
				.flatMap(job -> items.findById(attemptId).filter(item -> item.operationId().equals(job.id()))
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "图片条目不属于此任务")))
						.flatMap(item -> {
							if (!VisualItemRepository.STATE_GENERATED_UNSETTLED.equals(item.state())) {
								return Mono.just(toResult(item, item.state(), null));
							}
							return runs.findByOperationIdAndOwner(item.executionOperationId(), accountId)
									.switchIfEmpty(
											Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "运行记录不存在")))
									.flatMap(run -> finishReconcile(item, run));
						}));
	}

	private Mono<ItemExecution> finishReconcile(VisualItemRepository.ItemRow item, AiRun run) {
		if ("completed".equals(run.status())) {
			return ensureArtifactId(item).then(items.markSucceeded(item.id()))
					.thenReturn(toResult(item, VisualItemRepository.STATE_SUCCEEDED, null));
		}
		var reservation = ModelBudgetService.BudgetCheckResult.allowed(item.budgetId(), item.budgetReservationDate(), 0,
				item.reservedCents() == null ? 0 : item.reservedCents());
		// 冻结成本结算：媒体 run 实际==预估（不重读新价格替代快照）
		return executions.settleRecoveredMediaRun(run, reservation, run.budgetCents())
				.flatMap(settled -> settled
						? ensureArtifactId(item).then(items.markSucceeded(item.id()))
								.thenReturn(toResult(item, VisualItemRepository.STATE_SUCCEEDED, null))
						: Mono.just(toResult(item, VisualItemRepository.STATE_GENERATED_UNSETTLED, "结算暂未完成，可再次恢复")));
	}

	private Mono<ItemExecution> dispatch(VisualJobRow job, VisualItemRepository.ItemRow item) {
		return items.claimForDispatch(item.id()).flatMap(token -> {
			if (token == null) {
				// 认领失败：其他 worker 已持有派发权（TC101-036）——只读返回
				return items.findById(item.id()).map(current -> toResult(current, current.state(), "另一执行者已持有派发权"));
			}
			return doDispatch(job, item, token);
		}).onErrorResume(error -> classifyAndMark(item, error));
	}

	private Mono<ItemExecution> doDispatch(VisualJobRow job, VisualItemRepository.ItemRow item, UUID claimToken) {
		Snapshot snapshot = Snapshot.parse(job.snapshotJson());
		if (snapshot == null || !job.ownerId().equals(snapshot.accountId()) || snapshot.itemOf(item.itemId()) == null)
			return Mono.error(new IntelligenceException(502, "STUDIO_INVALID_PLAN", "执行快照不完整"));
		JsonNode documentItem = snapshot.itemOf(item.itemId());
		return drafts.loadOwned(job.draftId().toString(), job.ownerId())
				.flatMap(draft -> contexts.imageRoute(draft, job.ownerId(), snapshot.organizationId()))
				.flatMap(route -> {
					Map<String, Object> frozen = com.grassland.intelligence.creationstudio.plan.PlanJson
							.readJson(job.snapshotJson());
					if (!com.grassland.intelligence.creationstudio.CreationStudioContextService.imageFingerprint(route)
							.equals(frozen.get("configurationFingerprint")))
						return Mono
								.error(new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "已接受任务的模型配置已变化，请重新核对"));
					String snapshotId = route.binding() == null ? null : route.binding().snapshot().id().toString();
					if (!java.util.Objects.equals(snapshotId, frozen.get("contextSnapshotId")))
						return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "任务冻结来源不一致"));
					String protocol = com.grassland.intelligence.articleimage.ImageProtocolPolicy
							.protocolOf(route.provider().provider(), route.provider().baseUrl());
					String size = VisualJobService.generationSize(protocol, documentItem.path("targetAspect").asText());
					String prompt = assemblePrompt(snapshot, documentItem, item.position());
					String inputHash = MediaChecksums
							.sha256((prompt + "|" + size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
					if ("reference-image".equals(snapshot.consistencyMode())
							&& !"cover".equals(documentItem.path("role").asText()) && item.anchorArtifactId() == null)
						return Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "后续页缺少已确认封面参考"));
					return inputReferences(item, job.ownerId(), snapshot.organizationId(), documentItem, protocol)
							.flatMap(references -> {
								var command = new ArticleImageService.GenerateCommand(prompt, size, references);
								var observer = new ItemObserver(item.id(), claimToken, inputHash);
								Mono<IndependentImageGenerationService.Traced> generated;
								if (route.binding() == null) {
									generated = independent.generateFrozen(command, job.ownerId(),
											snapshot.organizationId(),
											com.grassland.intelligence.media.MediaPurpose.ARTICLE_GENERATED,
											route.provider(), route.unitPriceCents(), route.pricingVersion(),
											item.executionOperationId(), observer);
								} else {
									var binding = route.binding();
									generated = taskImages
											.generateForBoundContextFrozen(command, binding.snapshot(),
													binding.promptContext(),
													com.grassland.intelligence.media.MediaPurpose.ARTICLE_GENERATED,
													route.provider(), route.unitPriceCents(), route.pricingVersion(),
													item.executionOperationId(), observer)
											.map(result -> new IndependentImageGenerationService.Traced(
													result.response(), result.aiRunId(), result.provider(),
													result.model()));
								}
								Mono<IndependentImageGenerationService.Traced> execution = generated;
								return Mono.using(
										() -> reactor.core.publisher.Flux.interval(java.time.Duration.ofSeconds(30))
												.concatMap(tick -> items.heartbeat(item.id(), claimToken))
												.subscribe(ignored -> {
												}, failure -> {
												}),
										heartbeat -> execution, reactor.core.Disposable::dispose)
										.flatMap(traced -> registerArtifact(job, snapshot, item, documentItem,
												traced.aiRunId(), traced.response().mediaId())
												.flatMap(artifactId -> items.markArtifact(item.id(), artifactId)
														.then(items.markSucceeded(item.id()))
														.then(items.findById(item.id()))
														.map(saved -> toResult(saved, saved.state(), null))));
							});
				}).onErrorResume(error -> classifyAndMark(item, error));
	}

	/** 参考链（§6.6）：reference-image 模式下，锚点封面原图字节作为后续条目的通用参考。 */
	private Mono<List<ReferenceImage>> inputReferences(VisualItemRepository.ItemRow item, String ownerId,
			String organizationId, JsonNode documentItem, String protocol) {
		if (documentItem.path("inputMediaRef").isObject()) {
			if (item.anchorArtifactId() != null
					|| !com.grassland.intelligence.articleimage.ImageProtocolPolicy.supportsImageReference(protocol))
				return Mono.error(new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED", "当前引用组合超出模型的一张参考图上限"));
			var adapter = storage.getIfAvailable();
			if (adapter == null)
				return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "图片存储不可用"));
			Map<String, Object> ref = com.grassland.intelligence.creationstudio.plan.PlanJson
					.readJson(documentItem.path("inputMediaRef").toString());
			var caller = new com.grassland.intelligence.security.IntelligenceCallerResolver.Caller(ownerId, null, null,
					organizationId, null, "user", ownerId, "user");
			return references.resolveMedia(ref, caller)
					.flatMap(media -> imageProcessor.bounded(() -> adapter.getObject(media.objectKey())))
					.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "输入参考图已不可用")))
					.flatMap(bytes -> imageProcessor.deriveForWechat(bytes, 5 * 1024 * 1024, "#ffffff"))
					.map(derived -> List.of(new ReferenceImage(derived.contentType(), derived.bytes())));
		}
		if (item.anchorArtifactId() == null)
			return Mono.just(List.of());
		return artifactRows.findByIdAndOwner(item.anchorArtifactId(), ownerId)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "封面锚点不存在")))
				.flatMap(anchor -> artifacts.readOriginalBytes(anchor.originalMediaId(), ownerId))
				.flatMap(bytes -> imageProcessor.deriveForWechat(bytes, 5 * 1024 * 1024, "#ffffff"))
				.map(derived -> List.of(new ReferenceImage(derived.contentType(), derived.bytes())));
	}

	private Mono<UUID> registerArtifact(VisualJobRow job, Snapshot snapshot, VisualItemRepository.ItemRow item,
			JsonNode documentItem, UUID runId, UUID mediaId) {
		return artifacts.register(new VisualArtifactService.RegisterCommand(
				UUID.nameUUIDFromBytes(
						("visual-artifact:" + item.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				job.ownerId(), job.draftId(), job.planId(), job.planRevision() == null ? 1 : job.planRevision(),
				item.itemId(), item.id(), runId, mediaId, documentItem.path("targetAspect").asText("1:1"),
				snapshot.paletteId(), item.anchorArtifactId())).map(VisualArtifact::id);
	}

	/** 结算恢复路径的成品可能已登记（重放）——按 attempt 读回。 */
	private Mono<UUID> ensureArtifactId(VisualItemRepository.ItemRow item) {
		if (item.artifactId() != null)
			return Mono.just(item.artifactId());
		return artifactRows.findByAttempt(item.id()).map(VisualArtifact::id)
				.switchIfEmpty(operations.findVisualJobById(item.operationId()).flatMap(job -> {
					Snapshot snapshot = Snapshot.parse(job.snapshotJson());
					if (snapshot == null || item.originalMediaId() == null || item.runId() == null)
						return Mono.error(new IntelligenceException(409, "STUDIO_MEDIA_UNAVAILABLE", "原图或运行记录尚未核实"));
					return registerArtifact(job, snapshot, item, snapshot.itemOf(item.itemId()), item.runId(),
							item.originalMediaId());
				})).flatMap(id -> items.markArtifact(item.id(), id).thenReturn(id));
	}

	/**
	 * A lost generated() callback is recoverable from the deterministic
	 * original-media identity.
	 */
	public Mono<ItemExecution> recoverExpired(VisualJobRow job, VisualItemRepository.ItemRow item) {
		UUID mediaId = UUID.nameUUIDFromBytes(
				("visual-original:" + item.executionOperationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return mediaRows.findById(mediaId)
				.filter(media -> job.ownerId().equals(media.ownerAccountId()) && media.deletedAt() == null
						&& media.status() == com.grassland.intelligence.media.MediaStatus.ACTIVE
						&& (media.expiresAt() == null || media.expiresAt().isAfter(java.time.Instant.now())))
				.flatMap(media -> items.markGeneratedUnsettled(item.id(), media.id())
						.then(reconcile(job.id(), item.id(), job.ownerId())))
				.switchIfEmpty(items.markUnknown(item.id()).then(items.findById(item.id()))
						.map(saved -> toResult(saved, saved.state(), "外部结果尚未确认")));
	}

	/**
	 * 失败分类（§4.3/§6.9）：run 未绑定（供应商未调用）→ failed；run 已绑定且确定性 4xx → failed； dispatching
	 * 无可确认产物 → unknown（不自动重派，TC101-037）；generated_unsettled → 保持（可恢复结算）。
	 */
	private Mono<ItemExecution> classifyAndMark(VisualItemRepository.ItemRow item, Throwable error) {
		log.warn("visual item execution failed attempt={} state={} code={}", item.id(), item.state(),
				errorCodeOf(error));
		return items.findById(item.id()).flatMap(current -> {
			if (VisualItemRepository.STATE_GENERATED_UNSETTLED.equals(current.state())) {
				return Mono.just(toResult(current, current.state(), "原图已保存，结算待恢复"));
			}
			if (current.runId() == null) {
				return items.markFailed(current.id(), errorCodeOf(error))
						.thenReturn(toResult(current, VisualItemRepository.STATE_FAILED, messageOf(error)));
			}
			if (error instanceof IntelligenceException exception && exception.status() < 500) {
				return items.markFailed(current.id(), errorCodeOf(error))
						.thenReturn(toResult(current, VisualItemRepository.STATE_FAILED, messageOf(error)));
			}
			return items.markUnknown(current.id())
					.thenReturn(toResult(current, VisualItemRepository.STATE_UNKNOWN, "外部请求结果未知，需人工核实后才能重做"));
		}).onErrorResume(markError -> {
			log.warn("visual item {} failure marking failed", item.id(), markError);
			return Mono.error(error);
		});
	}

	private static ItemExecution toResult(VisualItemRepository.ItemRow item, String state, String message) {
		return new ItemExecution(item.itemId(), state, item.runId(), item.originalMediaId(), item.artifactId(),
				item.errorCode(), message);
	}

	private static String errorCodeOf(Throwable error) {
		if (error instanceof IntelligenceException exception && exception.code() != null) {
			return exception.code();
		}
		return "STUDIO_PROVIDER_FAILED";
	}

	private static String messageOf(Throwable error) {
		return error instanceof IntelligenceException ? "图片执行未完成，请核对配置或原任务状态" : "图片生成失败，请稍后核实";
	}

	/** observer 由视觉持久层实现（§6.6）——落库失败阻止外部请求（prepared）或中断结算（generated）。 */
	private final class ItemObserver implements ImageExecutionObserver {

		private final UUID attemptId;
		private final UUID claimToken;
		private final String inputHash;

		ItemObserver(UUID attemptId, UUID claimToken, String inputHash) {
			this.attemptId = attemptId;
			this.claimToken = claimToken;
			this.inputHash = inputHash;
		}

		@Override
		public Mono<Void> prepared(UUID runId) {
			return items.markRunBound(attemptId, claimToken, runId, inputHash, null, null, null)
					.flatMap(bound -> bound ? Mono.empty() : Mono.error(new IllegalStateException("run 绑定失败：派发权已丢失")));
		}

		@Override
		public Mono<Void> generated(UUID runId, UUID mediaId) {
			return items.markGeneratedUnsettled(attemptId, mediaId)
					.flatMap(marked -> marked ? Mono.empty() : Mono.error(new IllegalStateException("mediaId 持久化失败")));
		}

		@Override
		public Mono<Void> reserved(UUID runId, UUID budgetId, LocalDate reservationDate, int reservedCents) {
			// run 绑定（prepared）之后落预算句柄——恢复结算重建 BudgetCheckResult 用（V62 同款）
			return items.markBudgetHandles(attemptId, claimToken, budgetId, reservationDate, reservedCents)
					.flatMap(bound -> bound ? Mono.empty() : Mono.error(new IllegalStateException("预算句柄持久化失败：派发权已丢失")));
		}
	}

	// ---- 执行快照（C101-10 组装；本类只消费） ----

	/** 快照：执行身份 + 一致性模式 + 生成尺寸 + 整套计划文档（按条目取 prompt 素材）。 */
	public record Snapshot(String accountId, String organizationId, String consistencyMode, String size,
			String paletteId, JsonNode document) {

		static Snapshot parse(String json) {
			if (json == null || json.isBlank()) {
				return null;
			}
			try {
				JsonNode node = MAPPER.readTree(json);
				String accountId = node.path("accountId").asText(null);
				String size = node.path("size").asText(null);
				JsonNode document = node.path("document");
				if (accountId == null || accountId.isBlank() || size == null || size.isBlank()
						|| !document.isObject()) {
					return null;
				}
				String organizationId = node.path("organizationId").asText(null);
				return new Snapshot(accountId,
						organizationId == null || organizationId.isBlank() ? null : organizationId,
						node.path("consistencyMode").asText("prompt-only"), size, node.path("paletteId").asText(null),
						document);
			} catch (Exception error) {
				return null;
			}
		}

		JsonNode itemOf(String itemId) {
			for (JsonNode candidate : document.path("items")) {
				if (itemId.equals(candidate.path("itemId").asText(null))) {
					return candidate;
				}
			}
			return null;
		}
	}

	/** 快照 JSON 组装（C101-10 父任务创建时使用）。 */
	public static String snapshotJson(String accountId, String organizationId, String consistencyMode, String size,
			String paletteId, String documentJson) {
		try {
			var node = MAPPER.createObjectNode();
			node.put("accountId", accountId);
			if (organizationId != null) {
				node.put("organizationId", organizationId);
			}
			node.put("consistencyMode", consistencyMode);
			node.put("size", size);
			node.put("paletteId", paletteId == null ? "" : paletteId);
			node.set("document", MAPPER.readTree(documentJson));
			return MAPPER.writeValueAsString(node);
		} catch (Exception error) {
			throw new IllegalArgumentException("执行快照序列化失败", error);
		}
	}

	/** 条目 prompt：计划条目字段 + 预设目录风格/布局/配色（字图一体要求与旧卡一致）。 */
	static String assemblePrompt(Snapshot snapshot, JsonNode item, int position) {
		JsonNode style = snapshot.document().path("style");
		String styleId = style.path("styleId").asText("");
		String layoutId = item.path("layoutId").asText(style.path("layoutId").asText(""));
		String paletteId = style.path("paletteId").asText("");
		var stylePreset = CreationVisualPresetCatalog.style(styleId);
		var layoutPreset = CreationVisualPresetCatalog.layout(layoutId);
		var palettePreset = CreationVisualPresetCatalog.palette(paletteId);
		StringBuilder prompt = new StringBuilder();
		prompt.append("生成一张社交媒体图文卡片，标题与要点直接绘制在画面中。");
		String role = item.path("role").asText("content");
		if ("cover".equals(role)) {
			prompt.append("这是系列封面卡，画面需有最强视觉冲击力。");
		} else {
			prompt.append("这是系列第 ").append(position).append("summary".equals(role) ? " 张总结卡。" : " 张内容卡。");
		}
		prompt.append("。画面：").append(item.path("illustration").asText(""));
		if (stylePreset != null) {
			prompt.append("。视觉风格：").append(truncate(stylePreset.prompt(), 300));
		}
		if (layoutPreset != null) {
			prompt.append("。画面布局：").append(truncate(layoutPreset.prompt(), 300));
		}
		if (palettePreset != null) {
			prompt.append("。配色基调：").append(truncate(palettePreset.prompt(), 200));
		}
		prompt.append("。绘制以下文字：主标题\"").append(item.path("title").asText("")).append("\"");
		JsonNode bullets = item.path("bullets");
		if (bullets.isArray() && !bullets.isEmpty()) {
			prompt.append("；要点 ").append(bullets.size()).append(" 条：");
			for (int index = 0; index < bullets.size(); index++) {
				if (index > 0) {
					prompt.append("、");
				}
				prompt.append("\"").append(bullets.get(index).asText("")).append("\"");
			}
		}
		prompt.append("。排版要求：标题醒目、要点逐条清晰可读，中文准确无误、逐字对应且每处文字只出现一次；" + "文字排版与插画协调融合，不遮挡画面主体；字体风格与整体视觉统一，无多余字符和水印。");
		return prompt.toString();
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
