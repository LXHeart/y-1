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
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository.VisualJobRow;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-08（§6.6 运行拆分）：单项 prepare/execute/reconcile 编排。
 *
 * <p>
 * 执行快照由父任务 {@code snapshot_json} 携带（C101-10 组装）：{accountId, organizationId,
 * prompt, size, itemId, references}。本类只负责「一个子项一次可恢复执行」：
 *
 * <ul>
 * <li>queued → 认领（只有一个派发者）→ 固定 executionOperationId 执行 → prepared 闸门（失败上游 0
 * 调用）→ 确定性原图 → generated 落 mediaId → 结算 → succeeded；</li>
 * <li>generated_unsettled → 只重放结算（同 run 同图，供应商调用不增加）；</li>
 * <li>dispatching 崩溃无可确认产物 → unknown，不自动重派；run 未绑定（pre-prepare 失败）→
 * failed。</li>
 * </ul>
 *
 * <p>
 * 不变量（TC101-035～041）：重复派发被 claim CAS 拦截；B 账号按操作 ID 查询恢复 A 的运行返回 404（owner 校验在
 * run 回读）；unknown 主动重做需 API101-13 acknowledgedUnknownAttemptIds（C101-10）。
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

	public VisualExecutionBridge(CardSeriesOperationRepository operations, VisualItemRepository items,
			IndependentImageGenerationService independent, AiExecutionService executions, AiRunRepository runs) {
		this.operations = operations;
		this.items = items;
		this.independent = independent;
		this.executions = executions;
		this.runs = runs;
	}

	/** 子项执行结果（调用方组装父任务状态；不假装成功）。 */
	public record ItemExecution(String itemId, String state, UUID runId, UUID originalMediaId, String errorCode,
			String message) {
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
				.flatMap(job -> items.findById(attemptId).flatMap(item -> {
					if (!VisualItemRepository.STATE_GENERATED_UNSETTLED.equals(item.state())) {
						return Mono.just(toResult(item, item.state(), null));
					}
					// 已完成（先前恢复成功）直接收敛
					return runs.findByOperationIdAndOwner(item.executionOperationId(), accountId)
							.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "运行记录不存在")))
							.flatMap(run -> {
								if ("completed".equals(run.status())) {
									return items.markSucceeded(attemptId)
											.thenReturn(toResult(item, VisualItemRepository.STATE_SUCCEEDED, null));
								}
								var reservation = ModelBudgetService.BudgetCheckResult.allowed(item.budgetId(),
										item.budgetReservationDate(), 0,
										item.reservedCents() == null ? 0 : item.reservedCents());
								// 冻结成本结算：媒体 run 实际==预估（不重读新价格替代快照）
								return executions.settleRecoveredMediaRun(run, reservation, run.budgetCents())
										.flatMap(settled -> settled
												? items.markSucceeded(attemptId).thenReturn(
														toResult(item, VisualItemRepository.STATE_SUCCEEDED, null))
												: Mono.just(
														toResult(item, VisualItemRepository.STATE_GENERATED_UNSETTLED,
																"结算暂未完成，可再次恢复")));
							});
				}));
	}

	private Mono<ItemExecution> dispatch(VisualJobRow job, VisualItemRepository.ItemRow item) {
		UUID claimToken = UUID.randomUUID();
		return items.claimForDispatch(item.id()).flatMap(token -> {
			if (token == null) {
				// 认领失败：其他 worker 已持有派发权（TC101-036）——只读返回
				return items.findById(item.id()).map(current -> toResult(current, current.state(), "另一执行者已持有派发权"));
			}
			return doDispatch(job, item, token);
		}).onErrorResume(error -> classifyAndMark(item, error));
	}

	private Mono<ItemExecution> doDispatch(VisualJobRow job, VisualItemRepository.ItemRow item, UUID claimToken) {
		Snapshot snapshot = parseSnapshot(job.snapshotJson());
		if (snapshot == null) {
			return items.markFailed(item.id(), "STUDIO_INVALID_PLAN")
					.then(Mono.error(new IntelligenceException(502, "STUDIO_INVALID_PLAN", "执行快照缺失或不合法")));
		}
		String inputHash = MediaChecksums.sha256((snapshot.prompt() + "|" + snapshot.size()).getBytes());
		var command = new ArticleImageService.GenerateCommand(snapshot.prompt(), snapshot.size(), java.util.List.of());
		var observer = new ItemObserver(item.id(), claimToken, inputHash);
		return independent.generate(command, snapshot.accountId(), snapshot.organizationId(),
				com.grassland.intelligence.media.MediaPurpose.ARTICLE_GENERATED, item.executionOperationId(), observer)
				.flatMap(traced -> items.markSucceeded(item.id())
						.flatMap(marked -> marked
								? Mono.just(new ItemExecution(item.itemId(), VisualItemRepository.STATE_SUCCEEDED,
										traced.aiRunId(), traced.response().mediaId(), null, null))
								// CAS 失败（状态已被并发推进）：回读真实状态，不虚报成功
								: items.findById(item.id())
										.map(current -> toResult(current, current.state(), "状态已并发变化"))))
				.onErrorResume(error -> classifyAndMark(item, error));
	}

	/**
	 * 失败分类（§4.3/§6.9）：
	 *
	 * <ul>
	 * <li>run 未绑定（prepare 前/observer.prepared 失败）——供应商未调用 → failed；</li>
	 * <li>run 已绑定且确定性 4xx —— 明确失败 → failed；</li>
	 * <li>run 已绑定、dispatching 无可确认产物 —— unknown（不自动重派，TC101-037）；</li>
	 * <li>generated_unsettled —— 保持（可恢复结算）。</li>
	 * </ul>
	 */
	private Mono<ItemExecution> classifyAndMark(VisualItemRepository.ItemRow item, Throwable error) {
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
		return new ItemExecution(item.itemId(), state, item.runId(), item.originalMediaId(), item.errorCode(), message);
	}

	private static String errorCodeOf(Throwable error) {
		if (error instanceof IntelligenceException exception && exception.code() != null) {
			return exception.code();
		}
		return "STUDIO_PROVIDER_FAILED";
	}

	private static String messageOf(Throwable error) {
		return error.getMessage() == null ? "图片生成失败" : error.getMessage();
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

	public record Snapshot(String accountId, String organizationId, String prompt, String size, String itemId) {
	}

	static Snapshot parseSnapshot(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonNode node = MAPPER.readTree(json);
			String accountId = node.path("accountId").asText(null);
			String prompt = node.path("prompt").asText(null);
			String size = node.path("size").asText(null);
			if (accountId == null || accountId.isBlank() || prompt == null || prompt.isBlank() || size == null) {
				return null;
			}
			String organizationId = node.path("organizationId").asText(null);
			return new Snapshot(accountId, organizationId == null || organizationId.isBlank() ? null : organizationId,
					prompt, size, node.path("itemId").asText(null));
		} catch (Exception error) {
			return null;
		}
	}

	/** 快照 JSON 组装（C101-10 父任务创建时使用）。 */
	public static String snapshotJson(String accountId, String organizationId, String prompt, String size,
			String itemId) {
		try {
			var node = MAPPER.createObjectNode();
			node.put("accountId", accountId);
			if (organizationId != null) {
				node.put("organizationId", organizationId);
			}
			node.put("prompt", prompt);
			node.put("size", size);
			node.put("itemId", itemId);
			return MAPPER.writeValueAsString(node);
		} catch (Exception error) {
			throw new IllegalArgumentException("执行快照序列化失败", error);
		}
	}
}
