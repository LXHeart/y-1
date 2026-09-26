package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 持久 Agent worker（任务书 #107-2 C107-14 / 14.6）：认领 hypit.agent job， 逐步骤经
 * StepService 执行 scope 白名单内的工具，全部步骤完成即收口 succeeded/failed。崩溃恢复沿用 K06.1
 * 租约语义（过期租约由 observe 侧统一 重排或下次重领），步骤进度持久在
 * job.checkpoint_json={stepIndex,scope}， 单步动作行由 hypit_job_action 承载（input_hash
 * 幂等定位）。
 *
 * <p>
 * C107F-04（107-fix-1 D-04/W18）：planner 首步接入——actions 为空且 checkpoint 带 intent 时先经
 * StepService.plan 生成计划（两次无效→failed {@code planner_failed} 留两次原始输出）；waiting_input
 * 等价法（§4.4）：stepIndex≥maxSteps 时 state 保持 running、checkpoint.phase='waiting_input'、租约置空并
 * 发 waiting_input 事件，resume 由 {@link HypitAgentJobService} 置回 queued。取消观察在每个 step 边界
 * （收口前重读 state，canceled 优先于 succeeded）。测试预置 actions 的老式 job（无 intent）保持直接执行语义。
 */
@Component
public class HypitAgentWorker {

	private static final Logger logger = LoggerFactory.getLogger(HypitAgentWorker.class);
	static final String JOB_KIND = "hypit.agent";
	private static final Duration LEASE = Duration.ofSeconds(45);
	private static final int CLAIM_LIMIT = 3;
	private static final int MAX_STEPS = 40;

	private final HypitJobRepository jobs;
	private final HypitAgentStepService steps;
	private final HypitJobEventRepository events;
	private final DatabaseClient db;
	private final boolean enabled;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitAgentWorker(HypitJobRepository jobs, HypitAgentStepService steps, HypitJobEventRepository events,
			DatabaseClient db, @Value("${hypit.agent-worker.enabled:true}") boolean enabled) {
		this.jobs = jobs;
		this.steps = steps;
		this.events = events;
		this.db = db;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${hypit.agent-worker.poll-ms:5000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit agent worker cycle failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	public Mono<Integer> runOnce() {
		return requeueExpiredLeases()
				.then(claimDue().flatMap(this::driveToCompletion).collectList().map(List::size));
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

	private Flux<UUID> claimDue() {
		return db.sql("""
				UPDATE hypit_job SET state = 'running', lease_owner = CAST(:owner AS uuid), lease_until = :lease,
				       updated_at = now()
				WHERE id IN (SELECT id FROM hypit_job WHERE state = 'queued' AND kind = 'hypit.agent'
				             ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
				RETURNING id::text
				""").bind("owner", UUID.randomUUID()).bind("lease", Instant.now().plus(LEASE))
				.bind("limit", CLAIM_LIMIT).map((row, meta) -> UUID.fromString(row.get("id", String.class))).all();
	}

	/**
	 * 单批步骤执行（C107F-04 状态机 §4.4）：
	 * planning（无 actions 有 intent）→ planner；两次无效→failed planner_failed；
	 * executing→执行批次→succeeded；stepIndex≥maxSteps→waiting_input（state 保持 running、租约清空）；
	 * 每个收口点先重读 state——cancel 竞态时 canceled 优先（已落动作行不回滚）。
	 */
	private Mono<Void> driveToCompletion(UUID jobId) {
		return jobFields(jobId).<Void>flatMap(fields -> currentState(jobId).flatMap(state -> {
			if (!"running".equals(state)) {
				return Mono.empty(); // 认领后立即被取消：动作行未落，直接退出。
			}
			Map<String, Object> checkpoint = fields.checkpoint();
			int stepIndex = checkpoint.get("stepIndex") instanceof Number number ? number.intValue() : 0;
			List<HypitAgentAction> requested = readActions(checkpoint);
			String phase = String.valueOf(checkpoint.get("phase") == null ? "" : checkpoint.get("phase"));

			if (requested.isEmpty() && checkpoint.get("intent") != null && !"waiting_input".equals(phase)) {
				return planThenExecute(jobId, fields, checkpoint);
			}
			if ("waiting_input".equals(phase)) {
				// resume 后的续跑（§4.4）：inputs 已在 resume 时并入；越步仍未解则回 waiting_input。
				return settle(jobId, checkpoint, stepIndex);
			}
			if (requested.isEmpty()) {
				// 老式测试预置 job（无 intent 无 actions）：保持 107-2 直接收口语义。
				return finishTerminal(jobId, "succeeded", null, null).then();
			}
			return settle(jobId, checkpoint, stepIndex);
		}));
	}

	/** planner 首步 + 随后执行（D-04）：事件序 planning→executing→…（AC-F04-01）。 */
	private Mono<Void> planThenExecute(UUID jobId, JobFields fields, Map<String, Object> checkpoint) {
		String intent = String.valueOf(checkpoint.get("intent"));
		String brief = String.valueOf(checkpoint.get("brief"));
		@SuppressWarnings("unchecked")
		List<String> assetIdsRaw = (List<String>) (checkpoint.get("assetIds") instanceof List<?> list ? list
				: List.of());
		List<UUID> assetIds = assetIdsRaw.stream().map(UUID::fromString).toList();
		int stepIndex = checkpoint.get("stepIndex") instanceof Number number ? number.intValue() : 0;
		return events.append(jobId, "checkpoint", HypitJson.write(Map.of("phase", "planning", "intent", intent)))
				.then(Mono.defer(() -> steps
						.plan(fields.accountId(), intent, brief, scopeOf(checkpoint), MAX_STEPS, assetIds)
						.flatMap(planned -> {
							Map<String, Object> next = new HashMap<>(checkpoint);
							next.put("actions", planned.actions().stream()
									.map(action -> Map.of("kind", action.kind(),
											"input", HypitJson.read(action.inputJson())))
									.toList());
							next.put("phase", "executing");
							if (planned.runId() != null) {
								next.put("plannerRunId", planned.runId().toString());
							}
							return jobs.saveCheckpoint(jobId, HypitJson.write(next))
									.then(events.append(jobId, "checkpoint",
											HypitJson.write(
													Map.of("phase", "executing", "actions", planned.actions().size()))))
									.then(settle(jobId, next, stepIndex));
						})
						.onErrorResume(HypitAgentStepService.PlannerFailed.class, failure -> {
							Map<String, Object> evidence = new HashMap<>(checkpoint);
							evidence.put("plannerOutputs",
									List.of(failure.firstAttempt, failure.secondAttempt));
							return jobs.saveCheckpoint(jobId, HypitJson.write(evidence))
									.then(finishTerminal(jobId, "failed", "hypit_planner_failed",
											"planner 两次输出均无效，原始输出已留存")).then();
						})));
	}

	/** 执行一批（或续跑）：越步→waiting_input；否则执行动作、收口（cancel 竞态下 canceled 优先）。 */
	private Mono<Void> settle(UUID jobId, Map<String, Object> checkpoint, int stepIndex) {
		if (stepIndex >= MAX_STEPS) {
			return enterWaitingInput(jobId, checkpoint, "stepIndex≥maxSteps，等待补充输入或人工处理");
		}
		List<HypitAgentAction> requested = readActions(checkpoint);
		if (requested.isEmpty()) {
			return enterWaitingInput(jobId, checkpoint, "无剩余动作，等待补充输入");
		}
		Map<String, Object> next = new HashMap<>(checkpoint);
		next.put("stepIndex", stepIndex + 1);
		return jobProject(jobId).flatMap(projectId -> steps
				.run(jobId, projectId, scopeOf(checkpoint), stepIndex, requested)
				.flatMap(rows -> jobs.saveCheckpoint(jobId, HypitJson.write(next)))
				.then(currentState(jobId)).flatMap(state -> "running".equals(state)
						? finishTerminal(jobId, "succeeded", null, null)
						: Mono.just(true)))
				.then();
	}

	/** waiting_input 进入（等价法：state 保持 running、租约清空；事件形态沿 §6.11 checkpoint 终帧）。 */
	private Mono<Void> enterWaitingInput(UUID jobId, Map<String, Object> checkpoint, String reason) {
		Map<String, Object> next = new HashMap<>(checkpoint);
		next.put("phase", "waiting_input");
		next.put("blockedReason", reason);
		return db.sql("UPDATE hypit_job SET state = 'running', lease_owner = NULL, lease_until = NULL,"
				+ " blocked_reason = :reason, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", jobId.toString()).bind("reason", reason).fetch().rowsUpdated()
				.then(jobs.saveCheckpoint(jobId, HypitJson.write(next)))
				.then(events.append(jobId, "checkpoint",
						HypitJson.write(Map.of("phase", "waiting_input", "reason", reason)))).then();
	}

	/** 终态收口：state 更新 + terminal 事件（SSE takeUntil 依赖 terminal 类型）。 */
	private Mono<Boolean> finishTerminal(UUID jobId, String state, String errorCode, String errorMessage) {
		return jobs.updateState(jobId, state, errorCode, errorMessage)
				.then(events.append(jobId, "terminal",
						HypitJson.write(errorCode == null ? Map.of("state", state)
								: Map.of("state", state, "reason", errorCode))))
				.thenReturn(true);
	}

	private Mono<String> currentState(UUID jobId) {
		return db.sql("SELECT state FROM hypit_job WHERE id = CAST(:id AS uuid)").bind("id", jobId.toString())
				.map((row, meta) -> row.get("state", String.class)).one();
	}

	private Mono<UUID> jobProject(UUID jobId) {
		return db.sql("SELECT project_id::text AS project FROM hypit_job WHERE id = CAST(:id AS uuid)")
				.bind("id", jobId.toString()).map((row, meta) -> UUID.fromString(row.get("project", String.class)))
				.one();
	}

	/** scope 读取：新式 {allowedTools:[…]}（converge 收敛产物）与老式 "read_only"/"execute" 字符串都认。 */
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

	private record JobFields(UUID id, UUID projectId, String accountId, Map<String, Object> checkpoint) {
	}

	private Mono<JobFields> jobFields(UUID jobId) {
		return db.sql("""
				SELECT project_id::text AS project, account_id, checkpoint_json::text AS checkpoint
				FROM hypit_job WHERE id = CAST(:id AS uuid)
				""").bind("id", jobId.toString()).map((row, meta) -> {
					String project = row.get("project", String.class);
					String checkpoint = row.get("checkpoint", String.class);
					Map<String, Object> parsed = checkpoint == null || checkpoint.isBlank()
							? Map.of()
							: HypitJson.read(checkpoint);
					return new JobFields(jobId, project == null ? null : UUID.fromString(project),
							row.get("account_id", String.class), parsed);
				}).one().switchIfEmpty(Mono.error(new IllegalStateException("agent job vanished: " + jobId)));
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
}
