package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.Accepted;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通用 Agent 任务面（任务书 #107-fix-1 C107F-04 / W16，REQ-F05）： POST 投递（intent 白名单 +
 * scope 收敛 D-04 + 单事务 command+job）、本人分页列表、 resume/cancel 动作（§4.4 状态机）。七条
 * pending 桩的生产语义落点。
 *
 * <p>
 * 幂等：create/action 均以 (account, action, requestId) 命令行为准；同 hash 重放回读同一 job/回执，异
 * payload 409 {@code hypit_idempotency_conflict}（HypitCommandRepository 既有码；
 * 任务书 §6 API-F04 写作 hypit_command_conflict 系笔误，以 contracts/hypit-api.v1.json
 * 为准）。 RULE-F01：校验失败零 command/job 行。 事件类型受 hypit_job_event CHECK 约束：生命周期相位一律
 * {@code checkpoint}（payload.phase 区分 queued/resumed/waiting_input…），scope
 * 剔除记录用 {@code diagnostic}，终态 {@code terminal}。
 */
@Service
public class HypitAgentJobService {

	static final int MAX_STEPS = 40;
	private static final int MAX_ASSETS = 16;
	private static final int BRIEF_MAX = 8000;
	private static final int INPUT_MAX_BYTES = 64 * 1024;

	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;
	private final HypitProjectRepository projects;
	/** C107F2-05：revision0 存量工程的幂等首版 bootstrap 走工程服务（稳定 commandId）。 */
	private final com.grassland.intelligence.hypit.project.HypitProjectService projectService;
	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public HypitAgentJobService(HypitCommandRepository commands, HypitJobRepository jobs,
			HypitJobEventRepository events, HypitProjectRepository projects,
			com.grassland.intelligence.hypit.project.HypitProjectService projectService, DatabaseClient db,
			TransactionalOperator transactions) {
		this.commands = commands;
		this.jobs = jobs;
		this.events = events;
		this.projects = projects;
		this.projectService = projectService;
		this.db = db;
		this.transactions = transactions;
	}

	// ------------------------------------------------------------------
	// POST /projects/{p}/agent-jobs（API-F04）
	// ------------------------------------------------------------------

	/** 创建 hypit.agent job（intent 白名单 + scope 收敛 D-04）；同 requestId 幂等返回同 job。 */
	public Mono<Map<String, Object>> create(String accountId, UUID projectId, UUID requestId, String intent,
			String brief, List<UUID> assetIds, Long baseRevision, Map<String, Object> scope) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		if (intent == null || !HypitAgentScope.INTENTS.contains(intent)) {
			return Mono.error(invalid("intent 必须是 " + HypitAgentScope.INTENTS + " 之一。"));
		}
		String trimmedBrief = brief == null ? "" : brief.trim();
		if (trimmedBrief.isEmpty() || trimmedBrief.length() > BRIEF_MAX) {
			return Mono.error(invalid("brief 必须是 1.." + BRIEF_MAX + " 字符（trim 后计）。"));
		}
		List<UUID> assets = assetIds == null ? List.of() : assetIds;
		if (assets.size() > MAX_ASSETS) {
			return Mono.error(invalid("assetIds 至多 " + MAX_ASSETS + " 项。"));
		}
		List<String> callerTools = callerAllowedTools(scope);
		// C107F2-05（TC-F2-05-04）：存量 revision0 工程首次明确制作动作时幂等
		// bootstrap 到 revision1（stable commandId + 条件晋升；并发只产生一行），
		// 之后再走 ready/head 门——Agent 不再因 revision0 拒绝。
		return projectService.ensureInitialRevision(accountId, projectId).then(requireReadyOwner(accountId, projectId))
				.then(validateAssets(projectId, assets)).then(headRevision(projectId)).flatMap(head -> {
					long resolvedRevision = baseRevision == null ? head : baseRevision;
					if (resolvedRevision < 1) {
						return Mono.<Long>error(invalid("baseRevision 必须 ≥1。"));
					}
					return Mono.just(resolvedRevision);
				}).flatMap(resolvedRevision -> {
					HypitAgentScope.Converged converged = HypitAgentScope.converge(intent, callerTools);
					String canonical = HypitJson
							.write(canonicalPayload(intent, trimmedBrief, assets, resolvedRevision, scope));
					String payloadHash = sha256Hex(canonical);
					Map<String, Object> checkpoint = new LinkedHashMap<>();
					checkpoint.put("stepIndex", 0);
					checkpoint.put("phase", "planning");
					checkpoint.put("scope", Map.of("allowedTools", List.copyOf(converged.scope().allowedTools())));
					checkpoint.put("intent", intent);
					checkpoint.put("brief", trimmedBrief);
					checkpoint.put("assetIds", assets.stream().map(UUID::toString).toList());
					checkpoint.put("baseRevision", resolvedRevision);
					checkpoint.put("inputs", Map.of());
					checkpoint.put("actions", List.of());
					return commands
							.insert(accountId, "agent.create", requestId, "agent:" + projectId, payloadHash,
									"{\"canonical\":" + canonical + "}", projectId)
							.flatMap(
									accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
											? Mono.<Accepted>error(HypitCommandRepository.conflict(accepted.row()))
											: Mono.just(accepted))
							.flatMap(accepted -> accepted.existing()
									? replayJob(accepted.row().id()).map(job -> new Seed(job, true))
									: jobs.insert(new JobRow(UUID.randomUUID(), accepted.row().id(), projectId,
											accountId, "hypit.agent", "queued", "pending", null,
											HypitJson.write(checkpoint), resolvedRevision, null, 0, 1, null, null, 1,
											null, null, null, null, null, null)).map(job -> new Seed(job, false)))
							.as(transactions::transactional)
							.flatMap(seed -> seed.replay()
									? Mono.just(seed)
									: events.append(seed.job().id(), "checkpoint",
											HypitJson.write(Map.of("phase", "queued", "intent", intent)))
											.thenReturn(seed))
							.flatMap(seed -> seed.replay() || !converged.narrowed()
									? Mono.just(seed)
									: events.append(seed.job().id(), "diagnostic",
											HypitJson.write(Map.of("event", "scope_narrowed", "kept",
													List.copyOf(converged.scope().allowedTools()))))
											.thenReturn(seed))
							.map(seed -> {
								Map<String, Object> receipt = new LinkedHashMap<>();
								receipt.put("jobId", seed.job().id().toString());
								receipt.put("state", seed.job().state());
								receipt.put("resourceId", null);
								return receipt;
							});
				});
	}

	// ------------------------------------------------------------------
	// GET /projects/{p}/agent-jobs（API-F04）
	// ------------------------------------------------------------------

	/** 本人任务分页列表（limit≤100 默认 20，after=jobId 游标；state 支持 waiting_input 相位名）。 */
	public Mono<Map<String, Object>> list(String accountId, UUID projectId, int limit, UUID after, String state) {
		int pageLimit = limit < 1 ? 20 : Math.min(limit, 100);
		StringBuilder where = new StringBuilder(
				"account_id = :account AND project_id = CAST(:project AS uuid) AND kind = 'hypit.agent'");
		if ("waiting_input".equals(state)) {
			where.append(" AND state = 'running' AND checkpoint_json->>'phase' = 'waiting_input'");
		} else if (state != null && !state.isBlank()) {
			where.append(" AND state = :state");
		}
		String cursor = after == null
				? ""
				: " AND (created_at, id) < ((SELECT created_at FROM hypit_job WHERE id = CAST(:after AS uuid)),"
						+ " CAST(:after AS uuid))";
		var statement = db
				.sql("SELECT id::text, state, step_index, checkpoint_json::text AS checkpoint,"
						+ " created_at, updated_at FROM hypit_job WHERE " + where + cursor
						+ " ORDER BY created_at DESC, id DESC LIMIT :limit")
				.bind("account", accountId).bind("project", projectId.toString()).bind("limit", pageLimit + 1);
		if (state != null && !"waiting_input".equals(state) && !state.isBlank()) {
			statement = statement.bind("state", state);
		}
		if (after != null) {
			statement = statement.bind("after", after.toString());
		}
		return statement.map((row, meta) -> row).all().collectList().map(rows -> {
			boolean more = rows.size() > pageLimit;
			List<Map<String, Object>> items = new ArrayList<>();
			List<io.r2dbc.spi.Row> page = rows.subList(0, Math.min(rows.size(), pageLimit));
			for (io.r2dbc.spi.Row row : page) {
				Map<String, Object> checkpoint = HypitJson.read(row.get("checkpoint", String.class));
				String phase = checkpoint.get("phase") == null ? null : String.valueOf(checkpoint.get("phase"));
				int stepIndex = row.get("step_index", Integer.class) == null ? 0 : row.get("step_index", Integer.class);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("jobId", row.get("id", String.class));
				item.put("intent", checkpoint.get("intent") == null ? null : String.valueOf(checkpoint.get("intent")));
				item.put("state", "waiting_input".equals(phase) ? "waiting_input" : row.get("state", String.class));
				item.put("stepIndex", stepIndex);
				item.put("maxSteps", MAX_STEPS);
				item.put("createdAt", String.valueOf(row.get("created_at", Instant.class)));
				item.put("updatedAt", String.valueOf(row.get("updated_at", Instant.class)));
				items.add(item);
			}
			String nextCursor = more && !page.isEmpty() ? page.get(page.size() - 1).get("id", String.class) : null;
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("items", items);
			data.put("nextCursor", nextCursor);
			return data;
		});
	}

	// ------------------------------------------------------------------
	// POST /jobs/{jobId}/actions（API-F05，全局与项目级共用）
	// ------------------------------------------------------------------

	/** resume：waiting_input→queued 并入 input（RULE-F04）；cancel：状态机 §4.4。 */
	public Mono<Map<String, Object>> submitAction(String callerAccount, UUID projectId, UUID jobId, UUID requestId,
			String action, Map<String, Object> input) {
		return submitAction(callerAccount, projectId, jobId, requestId, action, input, false);
	}

	/**
	 * 全局路径的 operator 形态（§5.4：operator 可对任意任务动作）——鉴权由调用方经 jobById 完成， 这里只放行归属断言。
	 */
	public Mono<Map<String, Object>> submitAction(String callerAccount, UUID projectId, UUID jobId, UUID requestId,
			String action, Map<String, Object> input, boolean operatorOverride) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		if (action == null || !(action.equals("resume") || action.equals("cancel"))) {
			return Mono.error(invalid("action 必须是 resume 或 cancel。"));
		}
		Map<String, Object> safeInput = input == null ? Map.of() : input;
		String inputJson = HypitJson.write(safeInput);
		if (inputJson.getBytes(StandardCharsets.UTF_8).length > INPUT_MAX_BYTES) {
			return Mono.error(invalid("input 超过 64KiB 上限。"));
		}
		return jobs.findById(jobId).switchIfEmpty(Mono.error(notFound()))
				.flatMap(job -> !"hypit.agent".equals(job.kind())
						? Mono.<JobRow>error(notFound())
						: !operatorOverride && !job.accountId().equals(callerAccount)
								? Mono.<JobRow>error(forbidden())
								: projectId != null && !projectId.equals(job.projectId())
										? Mono.<JobRow>error(notFound())
										: Mono.just(job))
				// 幂等先行（C04/E-d 连点）：同 requestId 命中既有 command 时直接回放回执，不重走状态闸——
				// 否则第一次 resume 已把 job 置 queued，第二次连点会被闸误判 409。
				.flatMap(job -> {
					String canonical = HypitJson.write(Map.of("action", action, "input", safeInput));
					String payloadHash = sha256Hex(canonical);
					return commands
							.insert(callerAccount, "agent.action", requestId, "job-action:" + jobId, payloadHash,
									"{\"canonical\":" + canonical + "}", job.projectId())
							.flatMap(
									accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
											? Mono.<Accepted>error(HypitCommandRepository.conflict(accepted.row()))
											: Mono.just(accepted))
							.flatMap(accepted -> accepted.existing()
									? replayReceipt(job, action)
									: actionGate(job, action).then(applyAction(job, action, safeInput)));
				});
	}

	/**
	 * resume/cancel 的状态闸（§4.4）：终态 resume 409；succeeded/failed cancel 409；canceled
	 * cancel 幂等。
	 */
	private Mono<Void> actionGate(JobRow job, String action) {
		if ("resume".equals(action)) {
			boolean waiting = "running".equals(job.state()) && "waiting_input".equals(phaseOf(job.checkpointJson()));
			if (!waiting) {
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"仅 waiting_input 状态可 resume，当前 " + job.state() + "/" + phaseOf(job.checkpointJson())));
			}
			return Mono.empty();
		}
		if ("cancelled".equals(job.state())) {
			return Mono.empty(); // 幂等例外：重复 cancel 200 无新副作用。
		}
		if (job.terminal()) {
			return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
					"终态 " + job.state() + " 不可 cancel。"));
		}
		return Mono.empty();
	}

	private Mono<Map<String, Object>> applyAction(JobRow job, String action, Map<String, Object> input) {
		if ("resume".equals(action)) {
			Map<String, Object> checkpoint = HypitJson.read(job.checkpointJson());
			Map<String, Object> mergedInputs = new HashMap<>(
					checkpoint.get("inputs") instanceof Map<?, ?> map ? HypitJson.mapValue(map) : Map.of());
			// RULE-F04：input 只并入 inputs；scope 原集不变（扩张项由后续 action 派发处拒绝留痕）。
			mergedInputs.putAll(input);
			Map<String, Object> next = new HashMap<>(checkpoint);
			next.put("inputs", mergedInputs);
			// C107F2-15（步骤 4）：resume 消费新输入后解除具体 blockedReason，进入新规划轮次
			// （actions 清空、phase=planning）——规划器基于新 inputs 与历史观察重新出计划。
			next.put("blockedReason", null);
			next.put("phase", "planning");
			next.put("actions", List.of());
			return db
					.sql("UPDATE hypit_job SET state = 'queued', lease_owner = NULL, lease_until = NULL,"
							+ " blocked_reason = NULL, updated_at = now() WHERE id = CAST(:id AS uuid)")
					.bind("id", job.id().toString()).fetch().rowsUpdated()
					.then(jobs.saveCheckpoint(job.id(), HypitJson.write(next)))
					.then(events.append(job.id(), "checkpoint",
							HypitJson.write(Map.of("phase", "resumed", "keys", input.keySet()))))
					.thenReturn(Map.of("jobId", job.id().toString(), "state", "queued", "accepted", "resume"));
		}
		// cancel：queued/running/waiting_input → cancelled；动作行已落的不回滚（§4.4）。
		// 幂等收口（C04/E-e）：已是 cancelled（gate 幂等例外放行的重复 cancel）只回执，零新副作用。
		if ("cancelled".equals(job.state())) {
			return Mono.just(Map.of("jobId", job.id().toString(), "state", "cancelled", "accepted", "cancel"));
		}
		// C107F2-15（步骤 4）：cancel_requested_at 先置位（在途 worker 步边界看到即收口），
		// 再经非终态 CAS 收口 cancelled——终态后的写被 CAS 挡住，terminal 序列保持唯一。
		return jobs.markCancelRequested(job.id()).then(jobs.updateState(job.id(), "cancelled", null, null))
				.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "cancelled"))))
				.thenReturn(Map.of("jobId", job.id().toString(), "state", "cancelled", "accepted", "cancel"));
	}

	private Mono<Map<String, Object>> replayReceipt(JobRow job, String action) {
		return jobs.findById(job.id())
				.map(fresh -> Map.of("jobId", job.id().toString(), "state", fresh.state(), "accepted", action));
	}

	// ------------------------------------------------------------------
	// 内部
	// ------------------------------------------------------------------

	private record Seed(JobRow job, boolean replay) {
	}

	private Mono<JobRow> replayJob(UUID commandId) {
		return db.sql("SELECT id::text AS id FROM hypit_job WHERE command_id = CAST(:command AS uuid)")
				.bind("command", commandId.toString()).map((row, meta) -> row.get("id", String.class)).one()
				.flatMap(id -> jobs.findById(UUID.fromString(id)));
	}

	private Mono<Void> requireReadyOwner(String accountId, UUID projectId) {
		return projects.findOwnerStatus(accountId, projectId)
				.flatMap(status -> "ready".equals(status)
						? Mono.just(status)
						: Mono.<String>error(new IntelligenceException(HttpStatus.CONFLICT.value(),
								"hypit_state_conflict", "工程当前状态不可创建 Agent 任务：" + status)))
				.switchIfEmpty(Mono.error(notFound())).then();
	}

	/** assetIds 全部须为本工程 ready 素材（E-a：跨工程/非 ready→400，零 command 行）。 */
	private Mono<Void> validateAssets(UUID projectId, List<UUID> assetIds) {
		if (assetIds.isEmpty()) {
			return Mono.empty();
		}
		return Flux.fromIterable(assetIds)
				.concatMap(assetId -> db
						.sql("SELECT status FROM hypit_asset WHERE id = CAST(:id AS uuid)"
								+ " AND project_id = CAST(:project AS uuid)")
						.bind("id", assetId.toString()).bind("project", projectId.toString())
						.map((row, meta) -> row.get("status", String.class)).one().switchIfEmpty(Mono.just("")))
				.collectList().flatMap(statuses -> {
					for (String status : statuses) {
						if (!"ready".equals(status)) {
							return Mono.error(invalid("assetIds 含不存在或非本工程 ready 素材。"));
						}
					}
					return Mono.empty();
				});
	}

	private Mono<Long> headRevision(UUID projectId) {
		return db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.switchIfEmpty(Mono.error(notFound()));
	}

	private static List<String> callerAllowedTools(Map<String, Object> scope) {
		if (scope == null) {
			return List.of();
		}
		Object tools = scope.get("allowedTools");
		if (!(tools instanceof List<?> list)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (Object tool : list) {
			if (tool != null) {
				names.add(String.valueOf(tool));
			}
		}
		return names;
	}

	private static Map<String, Object> canonicalPayload(String intent, String brief, List<UUID> assetIds,
			long baseRevision, Map<String, Object> scope) {
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("intent", intent);
		canonical.put("brief", brief);
		canonical.put("assetIds", assetIds.stream().map(UUID::toString).toList());
		canonical.put("baseRevision", baseRevision);
		canonical.put("scope", scope == null ? Map.of() : scope);
		return canonical;
	}

	private static String phaseOf(String checkpointJson) {
		if (checkpointJson == null || checkpointJson.isBlank()) {
			return "";
		}
		Object phase = HypitJson.read(checkpointJson).get("phase");
		return phase == null ? "" : String.valueOf(phase);
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "资源不存在。");
	}

	/** §5.4 冻结：全局路径非提交者且非 operator→403（消息式 403，沿 requireRole 服务惯例）。 */
	private static IntelligenceException forbidden() {
		return new IntelligenceException(403, "仅任务提交者或部署管理账号可操作该任务。");
	}
}
