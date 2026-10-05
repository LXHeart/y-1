package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.agent.HypitAgentStepService.PlannedActions;
import com.grassland.intelligence.hypit.agent.HypitAgentStepService.StepOutcome;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.DispatchOutcome;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 持久 Agent worker（任务书 #107-2 C107-14 / 107-fix-2 C107F2-14）：认领 hypit.agent job
 * 后按 「规划 → 执行一个动作 → 持久观察 → 是否达标 → 继续」推进（RULE-08：成功要求目标产物/动作验收通过， 绝不以 actions
 * 循环结束收口）。单动作一行 hypit_job_action（slot 幂等，崩溃恢复不重跑已成功动作）， 观察持久在
 * checkpoint.observations；AgentCheckpointV2 字段（schemaVersion/round/actionIndex/
 * totalActions/phase/pendingActionId/blockedReason/observations）在读取旧 checkpoint
 * 时补默认。
 *
 * <p>
 * C107F2-14 终止语义（步骤 4）：任一必要动作失败不能 succeeded——安全/越权拒绝立即终止 failed；可修复 错误进入有界
 * replan（含真实观察，最多 {@link #MAX_FIX_ROUNDS} 轮）；轮内动作跑完仍未达标（author 无 成功构建/写回、review
 * 无取证）再续一轮规划，预算耗尽 failed {@code hypit_goal_not_met}，缺资料 （hypit_not_found 类）进
 * waiting_input。每动作边界重读 state——cancel 竞态 canceled 优先。 测试预置 actions 的老式 job（无
 * intent）保持直接执行语义（全部成功才 succeeded）。
 */
@Component
public class HypitAgentWorker {

	private static final Logger logger = LoggerFactory.getLogger(HypitAgentWorker.class);
	static final String JOB_KIND = "hypit.agent";
	/** §5/RULE-09：租约 90s，每 15s 步进续租（每动作边界 renew 到 now+90）。 */
	private static final Duration LEASE = Duration.ofSeconds(90);
	private static final Duration RENEW_STEP = Duration.ofSeconds(15);
	private static final int CLAIM_LIMIT = 3;
	/** §6.7：最多 40 动作/轮、120 累计、两轮修复。 */
	static final int MAX_ACTIONS_PER_ROUND = 40;
	static final int MAX_TOTAL_ACTIONS = 120;
	static final int MAX_FIX_ROUNDS = 2;
	/** C107F3-10：clone.plan 持久重试上限（RULE-012：作者成功但 plan 未持久不能终态成功）。 */
	static final int CLONE_PLAN_MAX_ATTEMPTS = 3;

	private final HypitJobRepository jobs;
	private final HypitAgentStepService steps;
	private final HypitJobEventRepository events;
	private final DatabaseClient db;
	private final org.springframework.transaction.reactive.TransactionalOperator transactions;
	private final boolean enabled;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitAgentWorker(HypitJobRepository jobs, HypitAgentStepService steps, HypitJobEventRepository events,
			DatabaseClient db, org.springframework.transaction.reactive.TransactionalOperator transactions,
			com.grassland.intelligence.hypit.asset.HypitAssetService assets, HypitClonePlanService clonePlans,
			@Value("${hypit.agent-worker.enabled:true}") boolean enabled) {
		this.jobs = jobs;
		this.steps = steps;
		this.events = events;
		this.db = db;
		this.transactions = transactions;
		this.assets = assets;
		this.clonePlans = clonePlans;
		this.enabled = enabled;
	}

	private final com.grassland.intelligence.hypit.asset.HypitAssetService assets;
	private final HypitClonePlanService clonePlans;

	@Scheduled(fixedDelayString = "${hypit.agent-worker.poll-ms:5000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit agent worker cycle failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	public Mono<Integer> runOnce() {
		return requeueExpiredLeases().then(claimDue().flatMap(this::driveToCompletion).collectList().map(List::size));
	}

	/**
	 * 崩溃恢复（§4.4/E-h）：租约过期的 running 任务重排回 queued。waiting_input 相位不带租约
	 * （置空是它的进入条件），不会被此扫描触碰。
	 */
	private Mono<Long> requeueExpiredLeases() {
		return db.sql("""
				UPDATE hypit_job SET state = 'queued', lease_owner = NULL, lease_until = NULL, updated_at = now()
				WHERE kind = 'hypit.agent' AND state = 'running'
				  AND lease_until IS NOT NULL AND lease_until < now()
				  AND COALESCE(checkpoint_json->>'phase', '') <> 'waiting_input'
				""").fetch().rowsUpdated();
	}

	private Flux<HypitJobRepository.JobRow> claimDue() {
		// C107F2-37（缺陷 U）：只认领 hypit.agent——泛化认领会抢走 build/provision 等
		// 跟踪行（checkpoint 空 → intent=null → 老式空观察假 succeeded）。
		return jobs.claimDue(UUID.randomUUID(), Instant.now().plus(LEASE), CLAIM_LIMIT, "hypit.agent");
	}

	/**
	 * 认领后的推进循环（C107F2-14 状态机 / C107F2-15 fencing）：每轮推进一步（续租 → cancel 检查 → plan /
	 * 执行一个动作 / 判定收口），{@link #stepOnce} 返回是否继续；waiting_input 进入与终态返回 false 停止。所有
	 * checkpoint/终态写都带认领时的 lease_owner（RULE-09：旧 worker 失权后不能写结果）。
	 */
	private Mono<Void> driveToCompletion(HypitJobRepository.JobRow claimed) {
		return advance(claimed.id(), claimed.leaseOwner());
	}

	private Mono<Void> advance(UUID jobId, UUID leaseOwner) {
		return currentState(jobId).flatMap(state -> !"running".equals(state)
				? Mono.<Void>empty()
				: jobFields(jobId)
						.flatMap(fields -> stepOnce(new JobFields(fields.id(), fields.projectId(), fields.accountId(),
								leaseOwner, fields.cancelRequested(), fields.checkpoint())))
						.flatMap(continueLoop -> Boolean.TRUE.equals(continueLoop)
								? advance(jobId, leaseOwner)
								: Mono.empty()));
	}

	/** 步边界三件事（C15 步骤 1/4）：15s 步进续租 → cancel_requested 优先收口 → 推进一步。 */
	private Mono<Boolean> stepOnce(JobFields fields) {
		return jobs.renewLease(fields.id(), fields.owner(), Instant.now().plus(LEASE))
				.then(Mono.defer(() -> fields.cancelRequested() != null
						? finishTerminal(fields, "cancelled", null, null)
						: stepOnceInner(fields)));
	}

	/** 推进一步；返回是否继续推进（false=已终态/已进 waiting_input/被取消）。 */
	private Mono<Boolean> stepOnceInner(JobFields fields) {
		Map<String, Object> checkpoint = fields.checkpoint();
		String intent = stringOrNull(checkpoint.get("intent"));
		List<HypitAgentAction> actions = readActions(checkpoint);

		// C107F2-16（RULE-10 步骤 1）：intent=analyze 且携带素材时的确定性前置——真实
		// probe/抽帧/转写（绝不信浏览器自报）；未就绪进 waiting_input，完成才继续 LLM 规划轮。
		if ("analyze".equals(intent) && checkpoint.get("referenceAnalysisId") == null
				&& !assetIdsOf(checkpoint).isEmpty()) {
			return runReferenceAnalysis(fields, checkpoint);
		}
		// C107F3-10（§7.3）：新模式声明 true 但 checkpoint 缺 referenceContext（旧 worker 落的
		// 半截 job / 异常形态）→ waiting_input，绝不回退空 author（不能用固定 brief 跑无上下文生成）。
		if (isRegenerate(checkpoint) && checkpoint.get("referenceContext") == null) {
			return enterWaitingInput(fields, normalized(checkpoint), "重新生成上下文缺失（checkpoint 无 referenceContext），请刷新后重试");
		}
		// C107F3-10（TC-F3-10-03 恢复半边）：崩溃恢复（作者已写回、actions 已清）不得重烧 planner
		// 重新写源码——观察已达标时直接走 clone.plan 持久收口，读收据不重写。
		if (isRegenerate(checkpoint) && actions.isEmpty() && goalMet("author", observationsOf(checkpoint))) {
			return completeRegenerate(fields, checkpoint);
		}
		if (intent != null && actions.isEmpty()) {
			// 首轮规划与 replan 共用；waiting_input 相位带空动作同样重规划（resume 并入 inputs 后续跑）。
			return planRound(fields, checkpoint);
		}
		if (actions.isEmpty()) {
			// 老式测试预置 job（无 intent 无 actions）：无事可做，直接收口。
			return finishTerminal(fields, "succeeded", null, null);
		}
		// executing / waiting_input（resume 续跑）：继续逐动作执行与判定。
		return executeOrJudge(fields, checkpoint, actions);
	}

	/** 新模式标志（§7.3：旧 checkpoint 无标志按旧流程）。 */
	static boolean isRegenerate(Map<String, Object> checkpoint) {
		return Boolean.TRUE.equals(checkpoint.get("regenerateFromLatestAnalysis"))
				&& "author".equals(stringOrNull(checkpoint.get("intent")));
	}

	// ------------------------------------------------------------------
	// 参考分析内置步骤（C107F2-16）
	// ------------------------------------------------------------------

	/**
	 * 真实分析步骤：结果持久一行动作行（kind=tool、tool=reference.analyze）并回填观察；分析未就绪 （平台综合模型缺失等）→
	 * waiting_input 保留已完成部分；分析 PARTIAL（PROVISIONAL）如实记录， 由 judge 的目标语义阻止「未查全片即成功」。
	 */
	private Mono<Boolean> runReferenceAnalysis(JobFields fields, Map<String, Object> checkpoint) {
		UUID assetId = assetIdsOf(checkpoint).get(0);
		int slot = slotOf(checkpoint, intOr(checkpoint.get("actionIndex"), 0));
		UUID operationId = HypitAgentStepService.operationIdOf(fields.id(), slot,
				("reference:" + assetId).getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 0
						? "ref"
						: "ref:" + assetId);
		return assets.analyzeReference(fields.accountId(), fields.projectId(), assetId, operationId)
				.flatMap(outcome -> {
					Map<String, Object> observation = new LinkedHashMap<>();
					observation.put("actionId", UUID.randomUUID().toString());
					observation.put("state", outcome.ready() ? "succeeded" : "failed");
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("tool", "reference.analyze");
					if (outcome.ready()) {
						result.put("analysisId", outcome.analysis().analysisId());
						result.put("status", outcome.analysis().status().name());
						result.put("segments", outcome.analysis().segments().size());
						result.put("gaps", outcome.analysis().gaps().size());
						result.put("audioTrack", String.valueOf(outcome.analysis().audioTrack()));
						result.put("analysisMarkdown", outcome.analysis().toAnalysisMarkdown());
					} else {
						result.put("notReadyReason", outcome.notReadyReason());
					}
					observation.put("result", result);
					Map<String, Object> next = normalized(checkpoint);
					List<Map<String, Object>> observations = observationsOf(next);
					observations.add(observation);
					next.put("observations", observations);
					next.put("actionIndex", intOr(checkpoint.get("actionIndex"), 0) + 1);
					next.put("actionSlot", slot + 1);
					next.put("executedActions", executedCount(checkpoint) + 1);
					next.put("pendingActionId", null);
					if (outcome.ready()) {
						next.put("referenceAnalysisId", outcome.analysis().analysisId());
						next.put("referenceMediaHash", outcome.analysis().mediaHash());
						next.put("blockedReason", null);
						// C107F2-37（缺陷 N 接线）：分析成功即按证据确定性派生并持久
						// clone.plan——此前 derive/save 只有 HTTP PUT 入口且前端从不调用，
						// clone_plan 行在任何活链路都不产生，方案面板 GET 恒 404。
						// 派生失败不回退分析终态：如实记入 checkpoint，方案面板可重读重试。
						return clonePlans.deriveAndSave(fields.projectId(), outcome.analysis()).map(plan -> {
							next.put("clonePlanId", plan.planId());
							return true;
						}).onErrorResume(error -> {
							next.put("clonePlanError", String.valueOf(error.getMessage()));
							return Mono.just(true);
						}).flatMap(
								saved -> jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
										.thenReturn(true));
					}
					next.put("blockedReason", "参考分析未就绪：" + outcome.notReadyReason());
					return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
							.then(enterWaitingInput(fields, next, "参考分析未就绪：" + outcome.notReadyReason()));
				});
	}

	// ------------------------------------------------------------------
	// 规划轮（首轮与 replan 共用）
	// ------------------------------------------------------------------

	private Mono<Boolean> planRound(JobFields fields, Map<String, Object> checkpoint) {
		String intent = String.valueOf(checkpoint.get("intent"));
		String brief = String.valueOf(checkpoint.get("brief"));
		List<UUID> assetIds = assetIdsOf(checkpoint);
		int round = intOr(checkpoint.get("round"), 0) + 1;
		List<Map<String, Object>> observations = observationsOf(checkpoint);
		// C107F2-25（§6.11）：revise intent 携评论输入（brief 为机器可读 JSON：
		// {"commentIds":[...],"run":"main.svrun","summary":"…"}）时走确定性回放
		// 路径——不烧 LLM planner，单动作 review.revise 由 StepService 分派。
		Map<String, Object> reviseInput = reviseInputOf(brief);
		if ("revise".equals(intent) && reviseInput != null) {
			return planRound(fields, checkpoint,
					List.of(new HypitAgentAction("review.revise", HypitJson.write(reviseInput), null, false)), round);
		}
		return events
				.append(fields.id(), "checkpoint",
						HypitJson.write(Map.of("phase", "planning", "intent", intent, "round", round)))
				.then(Mono.defer(() -> steps
						.plan(fields.accountId(), intent, brief, scopeOf(checkpoint), MAX_ACTIONS_PER_ROUND, assetIds,
								plannerObservations(checkpoint, observations), round - 1)
						.flatMap((PlannedActions planned) -> {
							Map<String, Object> next = normalized(checkpoint);
							next.put("actions", planned.actions().stream().map(action -> Map.of("kind", action.kind(),
									"input", HypitJson.read(action.inputJson()))).toList());
							next.put("phase", "executing");
							next.put("actionIndex", 0);
							next.put("totalActions", planned.actions().size());
							next.put("round", round);
							next.put("blockedReason", null);
							if (planned.runId() != null) {
								next.put("plannerRunId", planned.runId().toString());
							}
							return jobs.saveCheckpoint(fields.id(), HypitJson.write(next))
									.then(events
											.append(fields.id(), "checkpoint",
													HypitJson.write(Map.of("phase", "executing", "actions",
															planned.actions().size(), "round", round))))
									.thenReturn(true);
						}).onErrorResume(HypitAgentStepService.PlannerFailed.class, failure -> {
							Map<String, Object> evidence = normalized(checkpoint);
							evidence.put("plannerOutputs", List.of(failure.firstAttempt, failure.secondAttempt));
							return jobs.saveCheckpoint(fields.id(), HypitJson.write(evidence)).then(finishTerminal(
									fields, "failed", "hypit_planner_failed", "planner 两次输出均无效，原始输出已留存"));
						})));
	}

	/**
	 * C107F3-10（§6.4）：新模式规划输入的首项观察 = 冻结快照（每轮从 checkpoint 重建，不持久进
	 * checkpoint.observations——避免 resume/重读时重复叠加）；后接既有动作观察，round 仍按真实动作轮数。
	 */
	private static List<Map<String, Object>> plannerObservations(Map<String, Object> checkpoint,
			List<Map<String, Object>> observations) {
		if (!isRegenerate(checkpoint) || !(checkpoint.get("referenceContext") instanceof Map<?, ?> context)) {
			return observations;
		}
		List<Map<String, Object>> withContext = new ArrayList<>();
		Map<String, Object> head = new LinkedHashMap<>();
		head.put("kind", "reference_context");
		head.put("referenceContext", HypitJson.mapValue(context));
		withContext.add(head);
		withContext.addAll(observations);
		return withContext;
	}

	/** 确定性计划落盘（与 LLM 计划同 checkpoint 形状；无 plannerRunId）。 */
	private Mono<Boolean> planRound(JobFields fields, Map<String, Object> checkpoint, List<HypitAgentAction> actions,
			int round) {
		String intent = String.valueOf(checkpoint.get("intent"));
		Map<String, Object> next = normalized(checkpoint);
		next.put("actions", actions.stream()
				.map(action -> Map.of("kind", action.kind(), "input", HypitJson.read(action.inputJson()))).toList());
		next.put("phase", "executing");
		next.put("actionIndex", 0);
		next.put("totalActions", actions.size());
		next.put("round", round);
		next.put("blockedReason", null);
		return jobs.saveCheckpoint(fields.id(), HypitJson.write(next))
				.then(events.append(fields.id(), "checkpoint", HypitJson.write(Map.of("phase", "executing", "actions",
						actions.size(), "round", round, "deterministic", true))))
				.thenReturn(true);
	}

	/**
	 * revise 简报解析：brief 为 JSON（commentIds 必填，run 可选，summary 人读）。 非 JSON 或缺
	 * commentIds → null（退回 LLM planner 路径）。
	 */
	private static Map<String, Object> reviseInputOf(String brief) {
		if (brief == null || !brief.strip().startsWith("{")) {
			return null;
		}
		try {
			Map<String, Object> parsed = HypitJson.read(brief.strip());
			if (parsed.get("commentIds") instanceof List<?> ids && !ids.isEmpty()) {
				Map<String, Object> input = new java.util.LinkedHashMap<>();
				input.put("commentIds", ids);
				if (parsed.get("run") != null) {
					input.put("run", String.valueOf(parsed.get("run")));
				}
				return input;
			}
			return null;
		} catch (Exception error) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// 执行一个动作 / 判定
	// ------------------------------------------------------------------

	private Mono<Boolean> executeOrJudge(JobFields fields, Map<String, Object> checkpoint,
			List<HypitAgentAction> actions) {
		int actionIndex = intOr(checkpoint.get("actionIndex"), 0);
		int totalActions = intOr(checkpoint.get("totalActions"), actions.size());
		if (actionIndex >= actions.size() || actionIndex >= totalActions) {
			return judge(fields, checkpoint);
		}
		int executed = executedCount(checkpoint);
		if (executed >= MAX_TOTAL_ACTIONS) {
			return finishTerminal(fields, "failed", "hypit_goal_not_met", "累计动作超过 " + MAX_TOTAL_ACTIONS + " 上限，目标未达成");
		}
		int slot = slotOf(checkpoint, actionIndex);
		HypitAgentAction action = actions.get(actionIndex);
		long baseRevision = longOr(checkpoint.get("baseRevision"), 1L);
		return steps.executeOne(fields.id(), fields.projectId(), fields.accountId(), baseRevision, scopeOf(checkpoint),
				slot, action).flatMap(outcome -> {
					Map<String, Object> next = normalized(checkpoint);
					Map<String, Object> observation = new LinkedHashMap<>();
					observation.put("actionId", outcome.row().id().toString());
					observation.put("state", outcome.row().state());
					observation.put("result",
							HypitJson.read(outcome.row().resultJson() == null ? "{}" : outcome.row().resultJson()));
					List<Map<String, Object>> observations = observationsOf(next);
					observations.add(observation);
					next.put("observations", observations);
					next.put("actionIndex", actionIndex + 1);
					next.put("actionSlot", slot + 1);
					next.put("pendingActionId", outcome.row().id().toString());
					next.put("executedActions", executed + 1);
					DispatchOutcome dispatch = outcome.outcome();
					if (!dispatch.ok()) {
						// C107F2-38（TC-F2-38-02 round-7 实录定案）：暂缓类失败（维护窗/
						// sidecar 不可达）必须【原地续跑】——不前移 actionIndex、不落失败
						// 观察、checkpoint 原样保留，下个 worker 周期重跑同一动作。旧路径
						// defer 后仍走 judge→replan：重规划期间 infra 抖动让 canonical
						// 漂移，同 requestId 必撞 hypit_idempotency_conflict 烧光修复轮。
						// （C107F2-32 的「下一轮循环自动续跑」语义由此才真正成立。）
						if (isDeferredClass(dispatch.errorCode())) {
							// 返回 false 终止本轮 advance 递归：defer 若返回 true 会在同一认领内
							// 无界热循环（round-8 实录：单 job 150ms/轮烧了 8127 次）。running 态
							// 租约由 stepOnce 续到 90s，到期后 requeueExpiredLeases 重排 queued，
							// 下一次认领自然退避重试——这才是「恢复后自动续跑」的真实节拍。
							return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(checkpoint))
									.then(events.append(fields.id(), "progress",
											HypitJson.write(Map.of("phase", String.valueOf(checkpoint.get("phase")),
													"deferred", dispatch.errorCode(), "reason", dispatch.tool() + ": "
															+ dispatch.errorCode() + " " + dispatch.errorMessage()))))
									.thenReturn(false);
						}
						next.put("blockedReason",
								dispatch.tool() + ": " + dispatch.errorCode() + " " + dispatch.errorMessage());
						return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
								.then(handleFailure(fields, next, dispatch));
					}
					next.put("blockedReason", null);
					return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
							.thenReturn(true);
				});
	}

	/**
	 * 暂缓类错误码：结果未知/暂时不可用，checkpoint 原地保留续跑，不烧修复轮预算。 包可见：HypitAgentStepService
	 * 用同一分类判定 failed 动作行是否可重派发。
	 */
	static boolean isDeferredClass(String errorCode) {
		return "hypit_maintenance".equals(errorCode) || "hypit_broker_unreachable".equals(errorCode);
	}

	/** 必要动作失败的终止语义（步骤 4）：安全/越权立即终止；有界 replan；legacy 老式 job 直接 failed。 */
	private Mono<Boolean> handleFailure(JobFields fields, Map<String, Object> checkpoint, DispatchOutcome failure) {
		String reason = failure.tool() + ": " + failure.errorCode() + " " + failure.errorMessage();
		// 暂缓类（maintenance/broker_unreachable）已在 executeOrJudge 拦截为原地续跑，
		// 不会进入本方法——此处只处理真失败语义。
		if (failure.scopeRefusal()) {
			return finishTerminal(fields, "failed", "hypit_agent_refused", "安全/权限拒绝，任务终止：" + reason);
		}
		String intent = stringOrNull(checkpoint.get("intent"));
		int fixRounds = intOr(checkpoint.get("fixRounds"), 0);
		if (intent == null) {
			return finishTerminal(fields, "failed", "hypit_action_failed", "必要动作失败：" + reason);
		}
		if (fixRounds >= MAX_FIX_ROUNDS) {
			// 缺资料类失败（引用资源不存在）等待补充；其余修复预算耗尽如实 failed。
			if ("hypit_not_found".equals(failure.errorCode())) {
				return enterWaitingInput(fields, checkpoint, "缺资料：" + reason);
			}
			return finishTerminal(fields, "failed", "hypit_action_failed", "修复轮耗尽，必要动作仍失败：" + reason);
		}
		Map<String, Object> next = new HashMap<>(checkpoint);
		next.put("fixRounds", fixRounds + 1);
		next.put("phase", "planning");
		next.put("actions", List.of());
		return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next)).then(events
				.append(fields.id(), "checkpoint", HypitJson.write(Map.of("phase", "replanning", "reason", reason))))
				.thenReturn(true);
	}

	/** 轮内动作跑完后的达标判定（RULE-08：目标产物/动作验收，而非循环结束）。 */
	private Mono<Boolean> judge(JobFields fields, Map<String, Object> checkpoint) {
		String intent = stringOrNull(checkpoint.get("intent"));
		List<Map<String, Object>> observations = observationsOf(checkpoint);
		boolean allOk = observations.stream()
				.allMatch(observation -> "succeeded".equals(String.valueOf(observation.get("state"))));
		if (intent == null) {
			// 老式预置 job：全部动作成功即达标（保持 107-2 语义但失败不再收 succeeded）。
			return allOk
					? finishTerminal(fields, "succeeded", null, null)
					: finishTerminal(fields, "failed", "hypit_action_failed",
							String.valueOf(checkpoint.get("blockedReason")));
		}
		// 新式 job 的达标只看目标验收（goalMet）：历史失败观察已被有界 replan 消化，不永久阻挠
		// 收口——否则第一轮失败痕迹会让后续修复轮永远 allOk=false，replan 烧到预算耗尽假 failed。
		if (goalMet(intent, observations)) {
			// C107F3-10（RULE-012）：新模式作者达标≠终态成功——clone.plan 以稳定 requestId
			// 持久（读收据幂等，恢复只补一次）之后才允许 succeeded。
			if (isRegenerate(checkpoint)) {
				return completeRegenerate(fields, checkpoint);
			}
			return finishTerminal(fields, "succeeded", null, null);
		}
		// C107F2-25：revise 的 WAITING_INPUT（无法定位/冲突意见）直接转 waiting_input
		// 等用户澄清——不烧 replan 预算，源码与评论保持原状（§6.11）。
		if ("revise".equals(intent)) {
			for (Map<String, Object> observation : observations) {
				Map<String, Object> result = resultOf(observation);
				if ("WAITING_INPUT".equals(String.valueOf(result.get("status")))) {
					@SuppressWarnings("unchecked")
					List<String> waiting = ((List<Object>) result.getOrDefault("waitingCommentIds", List.of())).stream()
							.map(String::valueOf).toList();
					@SuppressWarnings("unchecked")
					List<String> conflicts = ((List<Object>) result.getOrDefault("conflicts", List.of())).stream()
							.map(String::valueOf).toList();
					return enterWaitingInput(fields, checkpoint,
							"评论需要澄清：" + (conflicts.isEmpty() ? "待补充 " + waiting : "同对象意见冲突，请选择 " + conflicts));
				}
			}
		}
		int fixRounds = intOr(checkpoint.get("fixRounds"), 0);
		int replans = intOr(checkpoint.get("replans"), 0);
		if (fixRounds < MAX_FIX_ROUNDS && replans < MAX_FIX_ROUNDS) {
			Map<String, Object> next = normalized(checkpoint);
			next.put("replans", replans + 1);
			next.put("phase", "planning");
			next.put("actions", List.of());
			return jobs.saveCheckpoint(fields.id(), HypitJson.write(next)).then(events.append(fields.id(), "checkpoint",
					HypitJson.write(Map.of("phase", "replanning", "reason", "目标未达成，携观察续规划")))).thenReturn(true);
		}
		return finishTerminal(fields, "failed", "hypit_goal_not_met",
				"动作已执行但目标未达成（intent=" + intent + "，观察 " + observations.size() + " 条）");
	}

	// ------------------------------------------------------------------
	// C107F3-10：新模式收口（作者达标 → 稳定 requestId 持久 clone.plan → succeeded）
	// ------------------------------------------------------------------

	/**
	 * 新模式成功收口（RULE-012）：作者 validated 写回达标后，用稳定 requestId
	 * {@code UUID.nameUUIDFromBytes((jobId+":clone-plan").getBytes(UTF_8))} 持久
	 * clone.plan（来源/revision 元数据见 W22），plan 落档才终态 succeeded。崩溃/租约失权后恢复重入此处：commands
	 * 同 requestId 幂等 命中已有回执（读收据不重写），checkpoint 置 saved 后收口——「恢复只补 plan 一次」。所有新
	 * checkpoint 写带 lease fencing（§7.4）。
	 */
	private Mono<Boolean> completeRegenerate(JobFields fields, Map<String, Object> checkpoint) {
		if ("saved".equals(stringOrNull(checkpoint.get("clonePlanStatus")))) {
			return finishTerminal(fields, "succeeded", null, null);
		}
		int attempts = intOr(checkpoint.get("clonePlanAttempts"), 0);
		if (attempts >= CLONE_PLAN_MAX_ATTEMPTS) {
			return finishTerminal(fields, "failed", "hypit_clone_plan_unsaved",
					"作者写回成功但方案持久化失败（已尝试 " + attempts + " 次），任务不判成功");
		}
		HypitAuthorContextService.ReferenceContext ctx = HypitAuthorContextService.ReferenceContext
				.fromMap(HypitJson.mapValue(checkpoint.get("referenceContext")));
		long resultRevision = lastAppliedRevision(checkpoint);
		UUID requestId = UUID.nameUUIDFromBytes((fields.id() + ":clone-plan").getBytes(StandardCharsets.UTF_8));
		return clonePlans.deriveAndSave(fields.projectId(), requestId, ctx, ctx.analysisJobId(), resultRevision)
				.flatMap(plan -> {
					Map<String, Object> next = normalized(checkpoint);
					next.put("clonePlanStatus", "saved");
					next.put("clonePlanId", plan.planId());
					next.put("clonePlanError", null);
					return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
							.then(finishTerminal(fields, "succeeded", null, null));
				}).onErrorResume(error -> {
					// 持久失败不判成功（RULE-012）：有界重试（下一认领周期重入），耗尽如实 failed。
					Map<String, Object> next = normalized(checkpoint);
					next.put("clonePlanStatus", "pending");
					next.put("clonePlanAttempts", attempts + 1);
					next.put("clonePlanError", String.valueOf(error.getMessage()));
					return jobs.saveCheckpointFenced(fields.id(), fields.owner(), HypitJson.write(next))
							.thenReturn(true);
				});
	}

	/** 观察中最后一次成功写回的 revision（goalMet 保证存在；无则 0 如实呈现）。 */
	private static long lastAppliedRevision(Map<String, Object> checkpoint) {
		long revision = 0;
		for (Map<String, Object> observation : observationsOf(checkpoint)) {
			Map<String, Object> result = resultOf(observation);
			if ("mutation.apply".equals(String.valueOf(result.get("tool")))
					&& result.get("revision") instanceof Number number && number.longValue() > 0) {
				revision = number.longValue();
			}
		}
		return revision;
	}

	/** 达标判定（§6.7/RULE-08）：产物/验收语义按 intent。 */
	static boolean goalMet(String intent, List<Map<String, Object>> observations) {
		return switch (intent) {
			case "analyze", "plan" -> observations.stream().anyMatch(observation -> substantive(observation));
			case "review" -> observations.stream().anyMatch(observation -> Set.of("snapshot", "feedback.mutate")
					.contains(String.valueOf(resultOf(observation).get("tool"))) && substantive(observation));
			case "author", "revise" -> observations.stream().anyMatch(observation -> {
				Map<String, Object> result = resultOf(observation);
				String tool = String.valueOf(result.get("tool"));
				if ("mutation.apply".equals(tool)) {
					return result.get("revision") instanceof Number number && number.longValue() > 0;
				}
				if ("build.submit".equals(tool) || "build.status".equals(tool)) {
					return "finished".equals(result.get("lifecycle")) && "succeeded".equals(result.get("outcome"));
				}
				return false;
			});
			default -> false;
		};
	}

	/** 观察是否携带实质结果（非空产物——空 hits/空回执不算证据）。 */
	private static boolean substantive(Map<String, Object> observation) {
		Map<String, Object> result = resultOf(observation);
		for (Map.Entry<String, Object> entry : result.entrySet()) {
			if ("tool".equals(entry.getKey())) {
				continue;
			}
			Object value = entry.getValue();
			if (value == null) {
				continue;
			}
			if (value instanceof List<?> list) {
				if (!list.isEmpty()) {
					return true;
				}
			} else if (value instanceof Map<?, ?> map) {
				if (!map.isEmpty()) {
					return true;
				}
			} else if (!String.valueOf(value).isBlank()) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> resultOf(Map<String, Object> observation) {
		Object result = observation.get("result");
		return result instanceof Map<?, ?> map ? HypitJson.mapValue(map) : Map.of();
	}

	// ------------------------------------------------------------------
	// waiting_input / 终态
	// ------------------------------------------------------------------

	/** waiting_input 进入（等价法：state 保持 running、租约清空；事件形态沿 §6.11 checkpoint 终帧）。 */
	private Mono<Boolean> enterWaitingInput(JobFields fields, Map<String, Object> checkpoint, String reason) {
		Map<String, Object> next = normalized(checkpoint);
		next.put("phase", "waiting_input");
		next.put("blockedReason", reason);
		return db
				.sql("UPDATE hypit_job SET state = 'running', lease_owner = NULL, lease_until = NULL,"
						+ " blocked_reason = :reason, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " AND lease_owner = CAST(:owner AS uuid) AND state = 'running'")
				.bind("id", fields.id().toString()).bind("owner", fields.owner().toString()).bind("reason", reason)
				.fetch().rowsUpdated().then(Mono.defer(() -> jobs.saveCheckpoint(fields.id(), HypitJson.write(next))))
				.then(events.append(fields.id(), "checkpoint",
						HypitJson.write(Map.of("phase", "waiting_input", "reason", reason))))
				.thenReturn(false);
	}

	/**
	 * 终态收口（C15 步骤 4）：owner fencing + 非终态 CAS（失权/已收口时 rowsUpdated=0，不再写 terminal
	 * 事件——双 worker/取消竞争下事件序列唯一）；状态更新与 terminal 事件同事务（§7.4）。
	 */
	private Mono<Boolean> finishTerminal(JobFields fields, String state, String errorCode, String errorMessage) {
		return jobs.updateStateFenced(fields.id(), fields.owner(), state, errorCode, errorMessage)
				.flatMap(updated -> updated > 0
						? events.append(fields.id(), "terminal",
								HypitJson.write(errorCode == null
										? Map.of("state", state)
										: Map.of("state", state, "reason", errorCode)))
								.thenReturn(true)
						// 已被取消/接管收口：不再写事件（terminal 序列保持首个收口者的形态）。
						: Mono.just(false))
				.as(transactions::transactional).thenReturn(true);
	}

	// ------------------------------------------------------------------
	// checkpoint 读取/归一
	// ------------------------------------------------------------------

	private Mono<String> currentState(UUID jobId) {
		return db.sql("SELECT state FROM hypit_job WHERE id = CAST(:id AS uuid)").bind("id", jobId.toString())
				.map((row, meta) -> row.get("state", String.class)).one();
	}

	private record JobFields(UUID id, UUID projectId, String accountId, UUID owner, Instant cancelRequested,
			Map<String, Object> checkpoint) {
	}

	private record RawFields(UUID projectId, String accountId, Instant cancelRequested,
			Map<String, Object> checkpoint) {
	}

	private Mono<JobFields> jobFields(UUID jobId) {
		return db.sql("""
				SELECT project_id::text AS project, account_id, cancel_requested_at, checkpoint_json::text AS checkpoint
				FROM hypit_job WHERE id = CAST(:id AS uuid)
				""").bind("id", jobId.toString()).map((row, meta) -> {
			String project = row.get("project", String.class);
			String checkpoint = row.get("checkpoint", String.class);
			Map<String, Object> parsed = checkpoint == null || checkpoint.isBlank()
					? Map.of()
					: HypitJson.read(checkpoint);
			return new RawFields(project == null ? null : UUID.fromString(project), row.get("account_id", String.class),
					row.get("cancel_requested_at", Instant.class), parsed);
		}).one().switchIfEmpty(Mono.error(new IllegalStateException("agent job vanished: " + jobId)))
				.map(raw -> new JobFields(jobId, raw.projectId(), raw.accountId(), null, raw.cancelRequested(),
						raw.checkpoint()));
	}

	/**
	 * AgentCheckpointV2 归一（§6.7）：旧 checkpoint 补
	 * schemaVersion/计数默认（round/actionIndex/
	 * totalActions/observations/executedActions）；既有键原样保留（actions/scope/intent/brief/
	 * baseRevision/inputs/plannerRunId/stepIndex）。老式 batch 槽位 = stepIndex*1000，v2
	 * 续用 actionSlot 递增，两种形态互不冲突。
	 */
	private static Map<String, Object> normalized(Map<String, Object> checkpoint) {
		Map<String, Object> next = new LinkedHashMap<>(checkpoint);
		next.put("schemaVersion", 2);
		next.putIfAbsent("round", 0);
		next.putIfAbsent("actionIndex", 0);
		next.putIfAbsent("totalActions", readActions(checkpoint).size());
		next.putIfAbsent("observations", new ArrayList<Map<String, Object>>());
		next.putIfAbsent("executedActions", 0);
		next.putIfAbsent("actionSlot", intOr(checkpoint.get("stepIndex"), 0) * 1000);
		next.putIfAbsent("pendingActionId", null);
		next.putIfAbsent("blockedReason", null);
		next.putIfAbsent("fixRounds", 0);
		next.putIfAbsent("replans", 0);
		return next;
	}

	/** 当前动作槽位：v2 沿用 actionSlot（每动作 +1）；老式 batch 语义 stepIndex*1000+序号。 */
	private static int slotOf(Map<String, Object> checkpoint, int actionIndex) {
		if (checkpoint.get("actionSlot") instanceof Number number) {
			return number.intValue();
		}
		return intOr(checkpoint.get("stepIndex"), 0) * 1000 + actionIndex;
	}

	private static int executedCount(Map<String, Object> checkpoint) {
		return intOr(checkpoint.get("executedActions"), 0);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> observationsOf(Map<String, Object> checkpoint) {
		Object raw = checkpoint.get("observations");
		if (raw instanceof List<?> list) {
			List<Map<String, Object>> observations = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					observations.add(HypitJson.mapValue(map));
				}
			}
			return observations;
		}
		return new ArrayList<>();
	}

	private static List<UUID> assetIdsOf(Map<String, Object> checkpoint) {
		Object raw = checkpoint.get("assetIds");
		List<UUID> assetIds = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				assetIds.add(UUID.fromString(String.valueOf(item)));
			}
		}
		return assetIds;
	}

	/**
	 * scope 读取：新式 {allowedTools:[…]}（converge 收敛产物）与老式 "read_only"/"execute" 字符串都认。
	 */
	static HypitAgentScope scopeOf(Map<String, Object> checkpoint) {
		Object scope = checkpoint.get("scope");
		if (scope instanceof Map<?, ?> map && map.get("allowedTools") instanceof List<?> tools) {
			Set<String> allowed = new LinkedHashSet<>();
			for (Object tool : tools) {
				allowed.add(String.valueOf(tool));
			}
			return new HypitAgentScope(HypitAgentScope.readOnlyScope().allowedTools().containsAll(allowed), allowed);
		}
		return "execute".equals(scope) ? HypitAgentScope.executeScope() : HypitAgentScope.readOnlyScope();
	}

	private static List<HypitAgentAction> readActions(Map<String, Object> checkpoint) {
		Object raw = checkpoint.get("actions");
		List<HypitAgentAction> actions = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					actions.add(new HypitAgentAction(String.valueOf(map.get("kind")),
							map.get("input") == null ? "{}" : HypitJson.write(map.get("input")), null, false));
				}
			}
		}
		return actions;
	}

	private static String stringOrNull(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static int intOr(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static long longOr(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}
}
