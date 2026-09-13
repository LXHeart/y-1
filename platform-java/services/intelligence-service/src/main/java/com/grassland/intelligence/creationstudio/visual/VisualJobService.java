package com.grassland.intelligence.creationstudio.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.intelligence.articleimage.ImageProtocolPolicy;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository.VisualJobRow;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.plan.VisualPlanRepository;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService;
import com.grassland.intelligence.orchestration.CreationVisualWorkflowStarter;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-10（API101-13～16、§4.4/§6.6）：异步视觉任务。
 *
 * <p>
 * 创建：quote／计划／权限校验后<b>一个事务</b> claim 父操作与子项（§7.3.1）； 首项（封面）先行，reference-image
 * 模式下后续项 waiting_anchor 直到封面 artifact 成功；并发最多 2（§5.4）。
 *
 * <p>
 * 推进（{@link #advance}）：Temporal activity / 收养清扫调用——只读数据库决定派发范围， 单项执行经
 * {@link VisualExecutionBridge}（业务与状态在领域服务和数据库；activity 不复制副作用）。
 * 取消只阻止新派发；已发出请求继续核实。
 *
 * <p>
 * unknown 主动重做（TC101-051）：所选条目存在先前 unknown attempt 时，请求必须携带
 * acknowledgedUnknownAttemptIds（服务端不推定同意）；确认后旧项保留审计、新 job 建新 attempt。
 */
@Service
public class VisualJobService {

	private static final Logger log = LoggerFactory.getLogger(VisualJobService.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_CONCURRENT_DISPATCH = 2;
	private static final Set<String> TERMINAL_ITEM_STATES = Set.of(VisualItemRepository.STATE_SUCCEEDED,
			VisualItemRepository.STATE_FAILED, VisualItemRepository.STATE_CANCELLED,
			VisualItemRepository.STATE_UNKNOWN);

	private final CardSeriesOperationRepository operations;
	private final VisualItemRepository items;
	private final VisualArtifactRepository artifacts;
	private final VisualPlanRepository plans;
	private final VisualPlanService planService;
	private final ByokRoutingService routing;
	private final ImageGenerationConfig imageConfig;
	private final CreationStudioProperties properties;
	private final VisualExecutionBridge bridge;
	private final CreationVisualWorkflowStarter starter;
	private final org.springframework.r2dbc.core.DatabaseClient db;

	public VisualJobService(CardSeriesOperationRepository operations, VisualItemRepository items,
			VisualArtifactRepository artifacts, VisualPlanRepository plans, VisualPlanService planService,
			ByokRoutingService routing, ImageGenerationConfig imageConfig, CreationStudioProperties properties,
			VisualExecutionBridge bridge, CreationVisualWorkflowStarter starter,
			org.springframework.r2dbc.core.DatabaseClient db) {
		this.operations = operations;
		this.items = items;
		this.artifacts = artifacts;
		this.plans = plans;
		this.planService = planService;
		this.routing = routing;
		this.imageConfig = imageConfig;
		this.properties = properties;
		this.bridge = bridge;
		this.starter = starter;
		this.db = db;
	}

	// ---- API101-13 create ----

	public record CreateCommand(UUID requestId, UUID planId, UUID quoteId, List<String> selectedItemIds,
			String consistencyMode, UUID anchorArtifactId, List<UUID> acknowledgedUnknownAttemptIds) {

		String digest() {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("kind", "visual-job");
			canonical.put("planId", planId.toString());
			canonical.put("quoteId", quoteId.toString());
			canonical.put("selectedItemIds", selectedItemIds == null ? List.of() : selectedItemIds);
			canonical.put("consistencyMode", consistencyMode);
			canonical.put("anchorArtifactId", anchorArtifactId == null ? "" : anchorArtifactId.toString());
			canonical.put("acknowledgedUnknownAttemptIds",
					acknowledgedUnknownAttemptIds == null ? List.of() : acknowledgedUnknownAttemptIds);
			return PlanJson.sha256(PlanJson.json(canonical));
		}
	}

	public record CreateOutcome(VisualJobRow job, List<VisualItemRepository.ItemRow> itemRows, boolean created,
			Map<UUID, VisualArtifact> artifactsByAttempt) {

		public CreateOutcome(VisualJobRow job, List<VisualItemRepository.ItemRow> itemRows, boolean created) {
			this(job, itemRows, created, Map.of());
		}
	}

	public Mono<CreateOutcome> create(Caller caller, CreateCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		String digest = command.digest();
		// §6.1 顺序：先读本人已有操作（同键同参回放），仅新操作继续业务校验
		return operations.find(caller.accountId(), command.requestId().toString()).flatMap(existing -> {
			if (existing.requestDigest() == null || !existing.requestDigest().equals(digest)) {
				return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同请求"));
			}
			// 重放也走 loadJob：终态任务带回完整 artifacts（候选预览需要）
			return loadJob(existing.id(), caller).map(VisualJobView::toOutcome);
		}).switchIfEmpty(Mono.defer(() -> validateAndClaim(caller, command, digest)));
	}

	private Mono<CreateOutcome> validateAndClaim(Caller caller, CreateCommand command, String digest) {
		return planService.loadOwnedRow(command.planId(), caller).flatMap(plan -> {
			if (!"ready".equals(plan.status())) {
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划未就绪"));
			}
			if (plan.confirmedRevision() == null || plan.confirmedRevision() != plan.currentRevision()) {
				return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "计划未确认或已变更，请重新确认"));
			}
			return plans.findRevision(plan.id(), plan.currentRevision())
					.flatMap(revision -> validateQuoteAndClaim(caller, command, digest, plan, revision.documentJson()));
		});
	}

	private Mono<CreateOutcome> validateQuoteAndClaim(Caller caller, CreateCommand command, String digest,
			com.grassland.intelligence.creationstudio.plan.VisualPlan.PlanRow plan, String documentJson) {
		return plans.findQuoteById(command.quoteId(), caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "费用估算不存在")))
				.flatMap(quote -> {
					if (!quote.planId().equals(plan.id()) || quote.planRevision() != plan.currentRevision()) {
						return Mono.error(new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "估算与计划版本不匹配，请重新估算"));
					}
					if (quote.expiresAt() != null && quote.expiresAt().isBefore(OffsetDateTime.now())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "估算已过期（120 秒），请重新估算"));
					}
					Object mode = quote.quote().get("consistencyMode");
					if (mode != null && !mode.equals(command.consistencyMode())) {
						return Mono
								.error(new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "估算与请求的一致性模式不匹配，请重新估算"));
					}
					@SuppressWarnings("unchecked")
					List<String> quoted = (List<String>) quote.quote().getOrDefault("selectedItemIds", List.of());
					if (!new LinkedHashSet<>(quoted).equals(new LinkedHashSet<>(command.selectedItemIds()))) {
						return Mono.error(new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "估算范围与请求不符，请重新估算"));
					}
					return routing
							.resolveProvider(caller.organizationId(), caller.accountId(), "image_generation", true)
							.flatMap(provider -> {
								String protocol = ImageProtocolPolicy.protocolOf(provider.provider(),
										provider.baseUrl());
								String fingerprint = String.valueOf(quote.quote().get("configurationFingerprint"));
								if (!fingerprint.equals(String.valueOf(quoteFingerprint(provider)))) {
									return Mono.error(
											new IntelligenceException(409, "STUDIO_QUOTE_EXPIRED", "执行配置已变化，请重新估算"));
								}
								if ("reference-image".equals(command.consistencyMode())
										&& !ImageProtocolPolicy.supportsImageReference(protocol)) {
									return Mono.error(new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED",
											"当前图片协议不支持通用参考，请改用 prompt-only 并重新估算"));
								}
								return validateItemsAndClaim(caller, command, digest, plan, documentJson, protocol);
							});
				});
	}

	private String quoteFingerprint(ByokRoutingService.ProviderResolution provider) {
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("provider", provider.provider());
		canonical.put("model", provider.model());
		canonical.put("platformModelVersion", provider.platformModelVersion());
		canonical.put("credentialVersion", provider.credentialVersion() == null ? 0 : provider.credentialVersion());
		canonical.put("pricingVersion", imageConfig.pricingVersion());
		canonical.put("protocol", ImageProtocolPolicy.protocolOf(provider.provider(), provider.baseUrl()));
		return PlanJson.sha256(PlanJson.json(canonical));
	}

	@SuppressWarnings("unchecked")
	private Mono<CreateOutcome> validateItemsAndClaim(Caller caller, CreateCommand command, String digest,
			com.grassland.intelligence.creationstudio.plan.VisualPlan.PlanRow plan, String documentJson,
			String protocol) {
		Map<String, Object> document = PlanJson.readJson(documentJson);
		List<Map<String, Object>> documentItems = (List<Map<String, Object>>) (List<?>) (document
				.get("items") instanceof List<?> list ? list : List.of());
		Map<String, Map<String, Object>> byItemId = new LinkedHashMap<>();
		for (Map<String, Object> item : documentItems) {
			if (item.get("itemId") instanceof String id) {
				byItemId.put(id, item);
			}
		}
		List<String> selected = command.selectedItemIds() == null ? List.of() : command.selectedItemIds();
		if (selected.isEmpty() || new LinkedHashSet<>(selected).size() != selected.size()) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selectedItemIds 不能为空且不重复"));
		}
		for (String itemId : selected) {
			if (!byItemId.containsKey(itemId)) {
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "所选项目不在当前计划版本内"));
			}
		}
		// 选中条目按文档顺序（封面先行）
		List<String> ordered = new ArrayList<>();
		for (Map<String, Object> item : documentItems) {
			String id = (String) item.get("itemId");
			if (selected.contains(id)) {
				ordered.add(id);
			}
		}
		boolean referenceImage = "reference-image".equals(command.consistencyMode());
		boolean coverSelected = !ordered.isEmpty() && "cover".equals(byItemId.get(ordered.get(0)).get("role"));
		if (referenceImage && !coverSelected && command.anchorArtifactId() == null) {
			return Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "只生成后续页时必须提供已成功封面作为锚点"));
		}
		return validateAnchorAndUnknown(caller, command, plan, ordered).then(Mono.defer(() -> {
			// 同一事务：claim 父操作 + 全部子项（§7.3.1）
			String snapshot = VisualExecutionBridge.snapshotJson(caller.accountId(), caller.organizationId(),
					command.consistencyMode(), generationSize(protocol, defaultAspect(document)), paletteIdOf(document),
					documentJson);
			List<VisualItemRepository.NewItem> newItems = new ArrayList<>();
			UUID anchorArtifactId = command.anchorArtifactId();
			int position = 0;
			for (String itemId : ordered) {
				position++;
				boolean first = position == 1;
				// reference-image：非首项等待封面锚点；prompt-only 全部可派发
				String state = referenceImage && !first && anchorArtifactId == null
						? VisualItemRepository.STATE_WAITING_ANCHOR
						: VisualItemRepository.STATE_QUEUED;
				newItems.add(new VisualItemRepository.NewItem(UUID.randomUUID(), itemId, position, state));
			}
			return operations.claimVisualJob(caller.accountId(), command.requestId().toString(), digest, plan.draftId(),
					plan.id(), plan.currentRevision(), command.quoteId(), snapshot).flatMap(claim -> {
						if (!claim.inserted()) {
							return Mono
									.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "该视觉任务请求已被使用"));
						}
						UUID operationId = claim.row().id();
						VisualItemRepository.ItemRow firstRow = null;
						return bindAnchor(operationId, ordered, referenceImage, anchorArtifactId)
								.then(items.insertItems(operationId, newItems))
								.then(loadJobItems(operationId, caller.accountId()))
								.flatMap(itemRows -> finishCreate(operationId, caller, itemRows, claim));
					});
		}));
	}

	private Mono<CreateOutcome> finishCreate(UUID operationId, Caller caller,
			List<VisualItemRepository.ItemRow> itemRows, CardSeriesOperationRepository.ClaimOutcome claim) {
		return operations.casDispatchState(operationId, "pending", "dispatching").flatMap(cas -> {
			if (!cas) {
				return loadJob(operationId, caller).map(VisualJobView::toOutcome);
			}
			// 异步启动失败不回滚：留 queued 行由收养清扫同 ID 补起（§6.6）
			try {
				starter.start(operationId);
			} catch (RuntimeException error) {
				log.warn("visual workflow start failed operation={}（收养清扫将补起）", operationId, error);
			}
			return operations.findVisualJob(operationId, caller.accountId())
					.map(job -> new CreateOutcome(job, itemRows, true));
		});
	}

	/** reference-image 的封面锚点写入后续子项（anchor_artifact_id）。 */
	private Mono<Void> bindAnchor(UUID operationId, List<String> ordered, boolean referenceImage,
			UUID anchorArtifactId) {
		if (!referenceImage || anchorArtifactId == null || ordered.size() <= 1) {
			return Mono.empty();
		}
		return db.sql("""
				UPDATE creation_visual_item SET anchor_artifact_id = CAST(:anchor AS uuid), updated_at=now()
				WHERE operation_id = CAST(:op AS uuid) AND state = 'waiting_anchor'
				""").bind("anchor", anchorArtifactId.toString()).bind("op", operationId.toString()).then();
	}

	/** 锚点归属校验 + unknown attempt 确认闸（TC101-051）。 */
	private Mono<Void> validateAnchorAndUnknown(Caller caller, CreateCommand command,
			com.grassland.intelligence.creationstudio.plan.VisualPlan.PlanRow plan, List<String> ordered) {
		Mono<Void> anchorCheck = command.anchorArtifactId() == null
				? Mono.empty()
				: artifacts.findByIdAndOwner(command.anchorArtifactId(), caller.accountId())
						.filter(artifact -> artifact.planId().equals(plan.id()))
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "封面锚点不存在")))
						.then();
		Set<UUID> acknowledged = new LinkedHashSet<>(
				command.acknowledgedUnknownAttemptIds() == null ? List.of() : command.acknowledgedUnknownAttemptIds());
		Mono<Void> unknownCheck = db.sql("""
				SELECT item.id, item.item_id FROM creation_visual_item item
				JOIN card_series_operation op ON op.id = item.operation_id
				WHERE op.owner_account_id = :owner AND op.plan_id = CAST(:plan AS uuid)
				  AND item.state = 'unknown' AND item.item_id IN (:items)
				""").bind("owner", caller.accountId()).bind("plan", plan.id().toString()).bind("items", ordered)
				.map((row, metadata) -> row.get("id", UUID.class)).all().collectList().flatMap(unknownAttempts -> {
					if (unknownAttempts.isEmpty()) {
						return Mono.empty();
					}
					if (!acknowledged.containsAll(unknownAttempts)) {
						return Mono.error(
								new IntelligenceException(409, "STUDIO_UNKNOWN_OUTCOME", "所选条目存在结果未知的旧尝试，需显式确认后才能重做"));
					}
					return Mono.empty();
				});
		return anchorCheck.then(unknownCheck);
	}

	// ---- API101-14/15 load/list ----

	public record VisualJobView(VisualJobRow job, List<VisualItemRepository.ItemRow> items,
			Map<UUID, VisualArtifact> artifactsByAttempt) {

		public CreateOutcome toOutcome() {
			return new CreateOutcome(job, items, false, artifactsByAttempt);
		}
	}

	public Mono<VisualJobView> loadJob(UUID jobId, Caller caller) {
		return operations.findVisualJob(jobId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉任务不存在")))
				.flatMap(this::viewOf);
	}

	private Mono<VisualJobView> viewOf(VisualJobRow job) {
		return items.findByOperation(job.id()).collectList().flatMap(itemRows -> artifacts.findByOperation(job.id())
				.collectMap(VisualArtifact::attemptId).map(artifacts -> new VisualJobView(job, itemRows, artifacts)));
	}

	private Mono<List<VisualItemRepository.ItemRow>> loadJobItems(UUID operationId, String accountId) {
		return operations.findVisualJob(operationId, accountId)
				.flatMap(job -> items.findByOperation(operationId).collectList());
	}

	public record JobPage(List<VisualJobRow> jobs, String nextCursor) {
	}

	public Mono<JobPage> listJobs(Caller caller, UUID draftId, int limit, String cursor) {
		String cursorAt = null;
		String cursorId = null;
		if (cursor != null && !cursor.isBlank()) {
			String[] parts = cursor.split("\\|", 2);
			if (parts.length == 2) {
				cursorAt = parts[0];
				cursorId = parts[1];
			} else {
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "cursor 不合法"));
			}
		}
		final String at = cursorAt;
		final String id = cursorId;
		return operations.findVisualJobsByDraft(caller.accountId(), draftId, limit + 1, at, id).collectList()
				.map(jobs -> {
					if (jobs.size() > limit) {
						VisualJobRow last = jobs.get(limit - 1);
						return new JobPage(jobs.subList(0, limit), last.updatedAt() + "|" + last.id());
					}
					return new JobPage(jobs, null);
				});
	}

	// ---- API101-16 cancel ----

	public Mono<VisualJobView> cancel(Caller caller, UUID jobId, UUID requestId, int expectedVersion) {
		return operations.findVisualJob(jobId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉任务不存在")))
				.flatMap(job -> operations.requestCancel(job.id(), expectedVersion).flatMap(cancelled -> {
					if (!cancelled) {
						return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "任务版本已变化，请刷新后重试"));
					}
					// 未派发项立即 cancelled；已发出请求继续核实（§4.4）
					return items.findByOperation(job.id())
							.filter(item -> VisualItemRepository.STATE_WAITING_ANCHOR.equals(item.state())
									|| VisualItemRepository.STATE_QUEUED.equals(item.state()))
							.concatMap(item -> items.markCancelled(item.id()))
							.then(rollup(job.id(), caller.accountId()));
				}).then(loadJob(job.id(), caller)));
	}

	// ---- 推进（activity/清扫共用；§4.4 状态汇总） ----

	/** 单轮推进：派发可执行项（封面先行、并发 ≤2、锚点门控），随后汇总父状态。返回是否全部终态。 */
	public Mono<Boolean> advance(UUID operationId) {
		return operations.findVisualJobById(operationId).flatMap(job -> {
			if ("completed".equals(job.dispatchState())) {
				return Mono.just(true);
			}
			return cancelWaitingIfAnchorFailed(job).then(dispatchReady(job)).then(rollup(operationId, job.ownerId()));
		}).defaultIfEmpty(true);
	}

	private Mono<Void> cancelWaitingIfAnchorFailed(VisualJobRow job) {
		// 首项失败/取消/未知 → 后续未派发项（waiting_anchor 与 queued）停止派发转 cancelled（§5.4：
		// 首项失败则后续停止——依赖链与一致性模式无关，默认先生成封面）
		return items.findByOperation(job.id()).collectList().flatMap(all -> {
			if (all.isEmpty()) {
				return Mono.empty();
			}
			VisualItemRepository.ItemRow first = all.get(0);
			boolean anchorTerminalBad = VisualItemRepository.STATE_FAILED.equals(first.state())
					|| VisualItemRepository.STATE_CANCELLED.equals(first.state())
					|| VisualItemRepository.STATE_UNKNOWN.equals(first.state());
			if (!anchorTerminalBad) {
				return Mono.empty();
			}
			return Flux.fromIterable(all)
					.filter(item -> VisualItemRepository.STATE_WAITING_ANCHOR.equals(item.state())
							|| VisualItemRepository.STATE_QUEUED.equals(item.state()))
					.concatMap(item -> items.markCancelled(item.id())).then();
		});
	}

	private Mono<Void> dispatchReady(VisualJobRow job) {
		return items.findByOperation(job.id()).collectList().flatMap(all -> {
			long inFlight = all.stream().filter(item -> VisualItemRepository.STATE_DISPATCHING.equals(item.state())
					|| VisualItemRepository.STATE_GENERATED_UNSETTLED.equals(item.state())).count();
			int slots = MAX_CONCURRENT_DISPATCH - (int) inFlight;
			if (slots <= 0) {
				return Mono.empty();
			}
			VisualItemRepository.ItemRow first = all.isEmpty() ? null : all.get(0);
			boolean firstSucceeded = first != null && VisualItemRepository.STATE_SUCCEEDED.equals(first.state());
			boolean hasUnknown = all.stream().anyMatch(item -> VisualItemRepository.STATE_UNKNOWN.equals(item.state()));
			boolean jobCancelRequested = job.cancelRequested();
			List<VisualItemRepository.ItemRow> dispatchable = new ArrayList<>();
			for (VisualItemRepository.ItemRow item : all) {
				if (slots <= 0) {
					break;
				}
				if (!VisualItemRepository.STATE_QUEUED.equals(item.state())) {
					continue;
				}
				if (jobCancelRequested || hasUnknown) {
					break; // 取消/未知：不再派新项（已发出的继续核实）
				}
				boolean isFirst = first != null && item.id().equals(first.id());
				if (!isFirst && !firstSucceeded && all.size() > 1) {
					continue; // 首项未成功：后续不提前派发（§5.4）
				}
				if (!isFirst && item.anchorArtifactId() == null && !firstSucceeded) {
					continue;
				}
				dispatchable.add(item);
				slots--;
			}
			return Flux.fromIterable(dispatchable)
					.concatMap(item -> bridge.executeItem(job.id(), item.id(), job.ownerId())
							.timeout(java.time.Duration.ofSeconds(200)).onErrorResume(error -> {
								log.warn("visual item dispatch failed job={} item={}", job.id(), item.itemId(), error);
								return Mono.empty();
							}).then())
					.then();
		});
	}

	/**
	 * 父状态汇总（§4.4）：unknown 优先；全成功 succeeded；成功+失败/取消 partial；全取消 cancelled；其余
	 * failed。
	 */
	public Mono<Boolean> rollup(UUID operationId, String accountId) {
		return operations.findVisualJob(operationId, accountId)
				.flatMap(job -> items.findByOperation(operationId).collectList().flatMap(all -> {
					long unknown = all.stream().filter(item -> VisualItemRepository.STATE_UNKNOWN.equals(item.state()))
							.count();
					long succeeded = all.stream()
							.filter(item -> VisualItemRepository.STATE_SUCCEEDED.equals(item.state())).count();
					long cancelled = all.stream()
							.filter(item -> VisualItemRepository.STATE_CANCELLED.equals(item.state())).count();
					boolean allTerminal = !all.isEmpty()
							&& all.stream().allMatch(item -> TERMINAL_ITEM_STATES.contains(item.state()));
					String state;
					if (!all.isEmpty() && unknown > 0 && allTerminal) {
						state = "unknown";
					} else if (allTerminal && succeeded == all.size()) {
						state = "succeeded";
					} else if (allTerminal && succeeded > 0) {
						state = "partial";
					} else if (allTerminal && succeeded == 0 && cancelled == all.size()) {
						state = "cancelled";
					} else if (allTerminal) {
						state = "failed";
					} else {
						state = "running";
					}
					boolean done = allTerminal;
					String resultJson = resultJsonOf(job, all, state);
					Mono<Boolean> update = done
							? operations.finishVisualJob(job.id(), state, resultJson)
							: operations
									.casDispatchState(job.id(),
											job.dispatchState() == null ? "pending" : job.dispatchState(),
											job.dispatchState() == null ? "dispatching" : job.dispatchState())
									.then(Mono.just(false));
					return update.thenReturn(done);
				}));
	}

	private String resultJsonOf(VisualJobRow job, List<VisualItemRepository.ItemRow> all, String state) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("state", state);
		List<Map<String, Object>> itemsView = new ArrayList<>();
		for (VisualItemRepository.ItemRow item : all) {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("itemId", item.itemId());
			view.put("attemptId", item.id().toString());
			view.put("position", item.position());
			view.put("state", item.state());
			if (item.runId() != null) {
				view.put("runId", item.runId().toString());
			}
			if (item.originalMediaId() != null) {
				view.put("originalMediaId", item.originalMediaId().toString());
			}
			if (item.artifactId() != null) {
				view.put("artifactId", item.artifactId().toString());
			}
			if (item.errorCode() != null) {
				view.put("errorCode", item.errorCode());
			}
			itemsView.add(view);
		}
		result.put("items", itemsView);
		try {
			return MAPPER.writeValueAsString(result);
		} catch (Exception error) {
			return "{\"state\":\"" + state + "\"}";
		}
	}

	// ---- 快照派生尺寸 ----

	private static String defaultAspect(Map<String, Object> document) {
		if (document.get("items") instanceof List<?> items && !items.isEmpty()
				&& items.get(0) instanceof Map<?, ?> first && first.get("targetAspect") instanceof String aspect) {
			return aspect;
		}
		return "1:1";
	}

	private static String paletteIdOf(Map<String, Object> document) {
		if (document.get("style") instanceof Map<?, ?> style && style.get("paletteId") instanceof String palette) {
			return palette;
		}
		return null;
	}

	/** 协议 × 画幅 → 生成尺寸（openai-image 固定值集；其余沿用近似尺寸）。 */
	static String generationSize(String protocol, String aspect) {
		if (ImageProtocolPolicy.PROTOCOL_OPENAI_IMAGE.equals(protocol)) {
			return switch (aspect == null ? "1:1" : aspect) {
				case "3:4", "9:16" -> "1024x1536";
				case "16:9", "2.35:1" -> "1536x1024";
				default -> "1024x1024";
			};
		}
		return switch (aspect == null ? "1:1" : aspect) {
			case "3:4" -> "1080x1440";
			case "9:16" -> "1080x1920";
			case "16:9" -> "1920x1080";
			case "2.35:1" -> "1410x600";
			default -> "1080x1080";
		};
	}

}
