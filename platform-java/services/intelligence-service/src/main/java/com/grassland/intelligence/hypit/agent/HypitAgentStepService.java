package com.grassland.intelligence.hypit.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.DispatchOutcome;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.ToolCall;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 步骤执行（任务书 #107-2 C107-14 / 107-fix-2 C107F2-14）：规划（平台 LLM 执行环， ai_run
 * 记账不旁路）+ 单动作执行（C107F2-14 起一次一个动作，经 {@link HypitAgentToolRegistry} 派发到真实
 * handler，结果持久一行 hypit_job_action，观察回填 checkpoint）。
 *
 * <p>
 * C107F2-14 关键约束（RULE-08/§6.7）：
 * <ul>
 * <li>planner 可见集 = scope ∩ 注册表登记集（未实现工具不进 planner）；
 * <li>planner 输出预过注册表 schema 校验，无效计划在规划层重试/失败；
 * <li>JSON 参数类型保真——number/boolean/object/array 原样保留，绝不 asText 字符串化；
 * <li>replan 输入携带真实观察（上一轮动作的持久结果）；
 * <li>动作行 input_json 记 {"tool":工具名,"input":参数}（工具名不写 kind，kind 恒为受限枚举）。
 * </ul>
 */
@Service
public class HypitAgentStepService {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HypitAgentStepService.class);

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Duration PLANNER_TIMEOUT = Duration.ofSeconds(60);
	private static final int PLANNER_MAX_TOKENS = 2000;

	private final HypitAgentToolRegistry registry;
	private final HypitJobActionRepository actions;
	private final FrozenTextExecutionService frozen;
	private final HypitReviewService reviews;

	public HypitAgentStepService(HypitAgentToolRegistry registry, HypitJobActionRepository actions,
			FrozenTextExecutionService frozen, HypitReviewService reviews) {
		this.registry = registry;
		this.actions = actions;
		this.frozen = frozen;
		this.reviews = reviews;
	}

	/** 单动作执行产物：持久行 + 注册表结构化结果；slot 命中既有成功行时 skipped=true 不再执行。 */
	public record StepOutcome(ActionRow row, DispatchOutcome outcome, boolean skipped) {
	}

	/** 规划产物：计划动作 + 本次 ai_run 绑定（留 checkpoint.plannerRunId）。 */
	public record PlannedActions(List<HypitAgentAction> actions, UUID runId) {
		public PlannedActions withRunId(UUID newRunId) {
			return new PlannedActions(actions, newRunId);
		}
	}

	/** planner 两次输出均无效：携带两次原始输出供 job failed 留证（C04/E-b）。 */
	public static class PlannerFailed extends RuntimeException {
		public final String firstAttempt;
		public final String secondAttempt;

		public PlannerFailed(String firstAttempt, String secondAttempt) {
			super("planner produced invalid output twice");
			this.firstAttempt = firstAttempt;
			this.secondAttempt = secondAttempt;
		}
	}

	/**
	 * 执行单个动作（C107F2-15 / RULE-09 步骤 2 的原子预留协议）：
	 *
	 * <ul>
	 * <li>同槽位既有行 inputHash 不同 → 明确冲突 {@code hypit_action_conflict}（同 slot 不复用异输入
	 * 旧结果）；</li>
	 * <li>succeeded 终态行 → 原样回放（已有成功 action 不重跑，§6.7）；failed 行仅 defer 类（broker
	 * 断网/维护窗，结果未知）重置 prepared 重派发，真失败原样回放；</li>
	 * <li>prepared 行（崩溃恢复）→ 以同一 operationId 重放 dispatch——副作用工具的幂等键从 (jobId, tool,
	 * input) 确定性派生，command 命中既有回执即复用，不重复副作用、不换 ID 重试；</li>
	 * <li>无行 → 先落 prepared 行（operation_id 持久），再 dispatch，CAS 收敛
	 * succeeded/failed。</li>
	 * </ul>
	 */
	public Mono<StepOutcome> executeOne(UUID jobId, UUID projectId, String accountId, long baseRevision,
			HypitAgentScope scope, int slot, HypitAgentAction action) {
		Map<String, Object> input = HypitJson.read(action.inputJson());
		String inputHash = hashOf(action);
		return actions.findBySlot(jobId, slot)
				.flatMap(existing -> runExisting(jobId, projectId, accountId, baseRevision, scope, slot, action, input,
						inputHash, existing))
				.switchIfEmpty(Mono.defer(() -> startPrepared(jobId, projectId, accountId, baseRevision, scope, slot,
						action, input, inputHash)));
	}

	/** 既有行处置：冲突拒绝 / 终态回放 / prepared 同 operationId 重放。 */
	private Mono<StepOutcome> runExisting(UUID jobId, UUID projectId, String accountId, long baseRevision,
			HypitAgentScope scope, int slot, HypitAgentAction action, Map<String, Object> input, String inputHash,
			ActionRow existing) {
		if (!existing.inputHash().equals(inputHash)) {
			return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_action_conflict", "动作槽位 "
					+ slot + " 已有不同输入的记录（hash " + existing.inputHash().substring(0, 8) + "…），不复用旧结果；请进入新规划轮次。"));
		}
		if ("prepared".equals(existing.state())) {
			// 崩溃恢复：同 operationId 重放（工具层幂等键稳定 → command/归档 CAS 命中既有回执）。
			return dispatchAndConverge(existing, projectId, accountId, baseRevision, scope, action, input);
		}
		// C107F2-38（round-9 实录）：defer 类失败（broker 断网/维护窗）是暂缓不是终判——
		// 首次派发失败会把行收敛到 failed，若此后原样回放，worker 的 defer 重试每轮都
		// 重读同一失败结果、永不重新派发，job 陷入 90s/轮的无限 defer（实测 broker 已
		// 恢复仍不收敛）。CAS 重置 failed→prepared 后同 operationId 重派发：副作用幂等
		// 键确定性派生，恢复后 workspace.read 重读同 hash → 同 requestId 幂等收敛。
		// 真失败（拒绝/编译错等）保持终态回放语义不重跑（§6.7）。
		if ("failed".equals(existing.state())) {
			DispatchOutcome stored = outcomeOf(existing);
			if (HypitAgentWorker.isDeferredClass(stored.errorCode())) {
				return actions
						.transition(existing.id(), "failed", "prepared",
								"{\"operationId\":\"" + operationIdOf(jobId, slot, inputHash) + "\"}")
						.flatMap(reset -> dispatchAndConverge(reset, projectId, accountId, baseRevision, scope, action,
								input));
			}
		}
		return Mono.just(new StepOutcome(existing, outcomeOf(existing), true));
	}

	private Mono<StepOutcome> startPrepared(UUID jobId, UUID projectId, String accountId, long baseRevision,
			HypitAgentScope scope, int slot, HypitAgentAction action, Map<String, Object> input, String inputHash) {
		UUID operationId = operationIdOf(jobId, slot, inputHash);
		return actions
				.insertPrepared(UUID.randomUUID(), jobId, slot, "tool", inputHash, storedInputOf(action.kind(), input),
						operationId)
				.flatMap(prepared -> dispatchAndConverge(prepared, projectId, accountId, baseRevision, scope, action,
						input));
	}

	/** 真实派发 + prepared→终态 CAS 收敛；结果（含结构化失败）如实落在既有动作行。 */
	private Mono<StepOutcome> dispatchAndConverge(ActionRow prepared, UUID projectId, String accountId,
			long baseRevision, HypitAgentScope scope, HypitAgentAction action, Map<String, Object> input) {
		// C107F2-25：review.revise 确定性动作（不走 LLM 注册表）——语义源码修改在
		// HypitReviewService（读源码/显式锚点/最小 changeset/apply/resolve）。
		Mono<DispatchOutcome> dispatch = "review.revise".equals(action.kind())
				? dispatchReviewRevise(projectId, accountId, baseRevision, prepared.jobId(), input)
				: registry.dispatch(scope, action.kind(),
						new ToolCall(prepared.jobId(), projectId, accountId, baseRevision, input));
		return dispatch.flatMap(outcome -> actions
				.transition(prepared.id(), "prepared", outcome.ok() ? "succeeded" : "failed",
						resultOf(prepared.kind(), action.kind(), outcome))
				.map(row -> new StepOutcome(row, outcome, false)));
	}

	/**
	 * C107F2-25 确定性 revise 动作：input {commentIds, run} → ReviewService 语义修改。
	 * 结果映射注册表口径：REVISED→ok（tool=mutation.apply, revision）；WAITING_INPUT→ ok=false
	 * 且带 status/waiting/conflicts（worker 转 waiting_input，不烧 replan）。
	 */
	private Mono<DispatchOutcome> dispatchReviewRevise(UUID projectId, String accountId, long baseRevision, UUID jobId,
			Map<String, Object> input) {
		Object rawIds = input.get("commentIds");
		if (!(rawIds instanceof List<?> list) || list.isEmpty()) {
			return Mono.just(new DispatchOutcome(false, "review.revise", Map.of("status", "FAILED"),
					"hypit_invalid_input", "commentIds 必填", false));
		}
		List<String> commentIds = list.stream().map(String::valueOf).toList();
		String run = input.get("run") == null ? null : String.valueOf(input.get("run"));
		return reviews.revise(accountId, projectId, UUID.randomUUID(), baseRevision, commentIds, run, jobId)
				.map(result -> new DispatchOutcome("REVISED".equals(result.status()), "mutation.apply",
						Map.of("status", result.status(), "revision",
								result.revision() == null ? 0L : result.revision(), "changesetId",
								result.changesetId() == null ? "" : result.changesetId(), "appliedCommentIds",
								result.appliedCommentIds(), "waitingCommentIds", result.waitingCommentIds(),
								"conflicts", result.conflicts(), "diagnostics", result.diagnostics()),
						"REVISED".equals(result.status()) ? null : "hypit_waiting_input",
						"REVISED".equals(result.status()) ? null : "评论修订未全部落地", false))
				.onErrorResume(error -> Mono.just(
						new DispatchOutcome(false, "mutation.apply", Map.of("status", "FAILED"), "hypit_revise_failed",
								error instanceof com.grassland.intelligence.security.IntelligenceException intelligence
										? intelligence.getMessage()
										: String.valueOf(error),
								false)));
	}

	/** 动作层稳定 operation_id：(jobId, slot, inputHash) 派生——重放/接手命中同一动作副作用。 */
	public static UUID operationIdOf(UUID jobId, int slot, String inputHash) {
		try {
			return UUID.nameUUIDFromBytes(MessageDigest.getInstance("SHA-256")
					.digest((jobId + "|" + slot + "|" + inputHash).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	/** 持久 input_json 形态（§6.7）：kind 恒受限枚举，工具名随参数。 */
	private static String storedInputOf(String kind, Map<String, Object> input) {
		Map<String, Object> storedInput = new LinkedHashMap<>();
		storedInput.put("tool", kind);
		storedInput.put("input", input);
		return HypitJson.write(storedInput);
	}

	/** 持久 result_json 形态：保留 operationId 痕迹（追溯）+ 工具名 + 结构化结果。 */
	private static String resultOf(String storedKind, String tool, DispatchOutcome outcome) {
		Map<String, Object> storedResult = new LinkedHashMap<>();
		storedResult.put("tool", tool);
		storedResult.putAll(outcome.result());
		return HypitJson.write(storedResult);
	}

	/** 旧签名兼容（无观察、首轮规划）。 */
	public Mono<PlannedActions> plan(String accountId, String intent, String brief, HypitAgentScope scope, int maxSteps,
			List<UUID> assetIds) {
		return plan(accountId, intent, brief, scope, maxSteps, assetIds, List.of(), 0);
	}

	/**
	 * Planner 首步/续规划（C107F2-14）：经平台 LLM 执行环生成 action 计划。可见工具 = scope ∩ 注册表
	 * 登记集；输出必须每条都过注册表 schema 预检（工具名 + 参数类型），第一次无效重试一次，两次均无效抛
	 * {@link PlannerFailed}。续规划轮（round>0）把上一轮真实观察注入 prompt——修复动作基于真实诊断。
	 *
	 * <p>
	 * C107F3-10（§6.4）：观察列表首项可为 {@code kind=reference_context} 的冻结快照（W19 从
	 * checkpoint 重建）——以「参考分析上下文」标签呈现，<b>不称作已执行动作</b>；其余观察保持既有「真实动作观察」语义， 旧调用（无该
	 * kind）行为与旧签名完全不变。
	 */
	public Mono<PlannedActions> plan(String accountId, String intent, String brief, HypitAgentScope scope, int maxSteps,
			List<UUID> assetIds, List<Map<String, Object>> observations, int round) {
		HypitAgentScope visible = scope.confinedTo(registry.visibleTools());
		String system = plannerSystemPrompt(intent, visible, maxSteps);
		StringBuilder user = new StringBuilder("任务简报：\n").append(brief).append('\n');
		if (assetIds != null && !assetIds.isEmpty()) {
			user.append("已选素材（assetId）：").append(assetIds.stream().map(UUID::toString).toList()).append('\n');
		}
		List<Map<String, Object>> contextObservations = new ArrayList<>();
		List<Map<String, Object>> actionObservations = new ArrayList<>();
		if (observations != null) {
			for (Map<String, Object> observation : observations) {
				if (observation != null && "reference_context".equals(String.valueOf(observation.get("kind")))) {
					contextObservations.add(observation);
				} else {
					actionObservations.add(observation);
				}
			}
		}
		if (!contextObservations.isEmpty()) {
			user.append("参考分析上下文（冻结快照，非已执行动作；重生成必须基于该分析的系统/锚点/证据，不得凭空编造）：\n")
					.append(HypitJson.write(contextObservations)).append('\n');
		}
		if (!actionObservations.isEmpty()) {
			user.append("第 1..").append(round).append(" 轮动作的真实观察（基于这些结果决定下一步，不要重复已成功动作）：\n")
					.append(HypitJson.write(actionObservations)).append('\n');
		}
		user.append("输出 action 计划 JSON。");
		List<ChatMessage> messages = List.of(ChatMessage.system(system), ChatMessage.user(user.toString()));
		// worker 语境 Caller：复刻任务提交人（WechatSyncExecutionService 同款构造）；exchange 为空是
		// executeIndependent 的既定支持形态（tracedWithExchange null 安全）。
		Caller caller = new Caller(accountId, null, null, null, null, null, null, null);
		return planWithRetry(caller, messages, visible, maxSteps, 0, "planner 未执行");
	}

	/** §6.7/C15 步骤 3：planner 瞬时失败（连接错误或无效输出）最多重试 2 次，退避 1/2 秒；超限如实失败。 */
	private static final int MAX_PLANNER_RETRIES = 2;

	private Mono<PlannedActions> planWithRetry(Caller caller, List<ChatMessage> messages, HypitAgentScope scope,
			int maxSteps, int retry, String previousRaw) {
		return attemptPlan(caller, messages, scope, maxSteps)
				.flatMap(attempt -> attempt.parsed() != null
						? Mono.just(attempt.parsed().withRunId(attempt.runId()))
						: retryAdvance(caller, messages, scope, maxSteps, retry, previousRaw, attempt.raw()))
				.onErrorResume(error -> error instanceof PlannerFailed
						// 重试耗尽的终局错误直传，不得再次计数/包装（否则 PlannerFailed.message 污染留证）。
						? Mono.error(error)
						: retryAdvance(caller, messages, scope, maxSteps, retry, previousRaw,
								error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
	}

	/** 计一次失败（无效输出或连接错误）：未耗尽则退避 1/2 秒续试；耗尽如实 PlannerFailed 留证。 */
	private Mono<PlannedActions> retryAdvance(Caller caller, List<ChatMessage> messages, HypitAgentScope scope,
			int maxSteps, int retry, String previousRaw, String raw) {
		if (retry >= MAX_PLANNER_RETRIES) {
			return Mono.error(new PlannerFailed(previousRaw, raw));
		}
		return Mono.delay(Duration.ofSeconds(retry == 0 ? 1 : 2))
				.then(planWithRetry(caller, messages, scope, maxSteps, retry + 1, raw));
	}

	/** 一次规划尝试：原始输出留存（失败留证），解析结果可空。 */
	private record PlanAttempt(String raw, UUID runId, PlannedActions parsed) {
	}

	private Mono<PlanAttempt> attemptPlan(Caller caller, List<ChatMessage> messages, HypitAgentScope scope,
			int maxSteps) {
		return frozen
				.executeIndependent(null, caller, messages, PLANNER_MAX_TOKENS, CreditFeature.AI_RUN_TEXT,
						PLANNER_TIMEOUT, completion -> completion.content())
				.map(traced -> new PlanAttempt(traced.value(), traced.runId(),
						parsePlan(traced.value(), scope, maxSteps)));
	}

	/** 解析并校验一次规划输出；无效返回 null。 */
	PlannedActions parsePlan(String raw, HypitAgentScope scope, int maxSteps) {
		try {
			JsonNode root = JSON.readTree(stripCodeFence(raw));
			JsonNode actionsNode = root.get("actions");
			if (actionsNode == null || !actionsNode.isArray()) {
				logger.warn("planner output rejected: actions missing/malformed, raw prefix: {}",
						raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 240)));
				return null;
			}
			List<HypitAgentAction> parsed = new ArrayList<>();
			for (JsonNode item : actionsNode) {
				if (parsed.size() >= maxSteps) {
					break;
				}
				JsonNode kind = item.get("kind");
				if (kind == null || !kind.isTextual() || !scope.allowedTools().contains(kind.asText())) {
					logger.warn("planner output rejected: kind {} not in scope {}, raw prefix: {}", kind,
							scope.allowedTools(), raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 240)));
					return null;
				}
				Map<String, Object> input = new LinkedHashMap<>();
				JsonNode inputNode = item.get("input");
				if (inputNode != null && inputNode.isObject()) {
					inputNode.fields()
							.forEachRemaining(field -> input.put(field.getKey(), jsonValue(field.getValue())));
				}
				// 注册表 schema 预检：类型保真错误在规划层拦截，不落到动作行。
				String schemaViolation = registry.validateInput(kind.asText(), input);
				if (schemaViolation != null) {
					logger.warn("planner output rejected by schema: {}, raw prefix: {}", schemaViolation,
							raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 240)));
					return null;
				}
				parsed.add(new HypitAgentAction(kind.asText(), HypitJson.write(input), null, false));
			}
			return parsed.isEmpty() ? null : new PlannedActions(parsed, null);
		} catch (Exception invalid) {
			logger.warn("planner parse exception: {}", invalid.toString());
			return null;
		}
	}

	/**
	 * JSON 值类型保真（C107F2-14 步骤 2）：文本/数值/布尔/null 原样，对象/数组转回 Map/List—— 绝不用 asText 把
	 * number/boolean 字符串化。C107F2-37（缺陷 P）：容器节点统一走 Jackson convertValue——旧实现把数组也交给
	 * {@code HypitJson.read}（只认对象形）， 任何带对象数组的 planner 输出（如 mutation.apply 的
	 * changes）都解码失败 → 规划全灭 hypit_planner_failed。
	 */
	private static Object jsonValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.textValue();
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isIntegralNumber()) {
			return node.longValue();
		}
		if (node.isNumber()) {
			return node.doubleValue();
		}
		return JSON.convertValue(node, Object.class);
	}

	/** 规划输出常见的 ```json 围栏剥离（上游模型礼貌性包装，非错误）。 */
	private static String stripCodeFence(String raw) {
		String text = raw == null ? "" : raw.trim();
		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			int lastFence = text.lastIndexOf("```");
			if (firstNewline > 0 && lastFence > firstNewline) {
				return text.substring(firstNewline + 1, lastFence);
			}
		}
		return text;
	}

	private static String plannerSystemPrompt(String intent, HypitAgentScope scope, int maxSteps) {
		String semantics = switch (intent) {
			case "analyze" -> "分析工程与参考素材，只产出只读检查动作";
			case "plan" -> "为后续制作产出最小可执行计划（只读探查）";
			case "author" -> "制作/复刻内容：可提交构建、打包与写回变更";
			case "review" -> "审阅成片：取证（快照/反馈）为主，不重做";
			case "revise" -> "按已有审阅意见修订：可写回变更并重新构建";
			default -> "执行任务";
		};
		return "你是 Hypit 视频克隆工程的执行规划器。intent=" + intent + "（" + semantics + "）。\n" + "可用工具（kind 只允许以下值）："
				+ scope.allowedTools() + "\n" + "输出严格 JSON（不要任何解释文本、不要代码围栏）：\n"
				+ "{\"actions\":[{\"kind\":\"<工具名>\",\"input\":{<工具参数对象>}}]}\n" + "最多 " + maxSteps
				+ " 条动作；每条 input 是合法 JSON 对象，参数保持 JSON 原始类型（数值不写成字符串）。";
	}

	/**
	 * 动作输入指纹（C107F2-15）：对解析后的值做键排序规范化再哈希——checkpoint 经 PG jsonb
	 * 存取会重排键序，直接对原文哈希会让同一动作崩溃前后指纹不一致（误判冲突/重放失效）。
	 */
	private static String hashOf(HypitAgentAction action) {
		try {
			String canonical = HypitJson.write(canonicalValue(HypitJson.read(action.inputJson())));
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	/** 递归键排序的规范化值（Map→TreeMap；List 保序——数组序是语义）。 */
	private static Object canonicalValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				sorted.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
			}
			return sorted;
		}
		if (value instanceof List<?> list) {
			List<Object> items = new ArrayList<>();
			for (Object item : list) {
				items.add(canonicalValue(item));
			}
			return items;
		}
		return value;
	}

	/** 既有行回放成 StepOutcome（崩溃恢复读回原行，结果按行状态还原）。 */
	private static DispatchOutcome outcomeOf(ActionRow row) {
		boolean ok = "succeeded".equals(row.state());
		Map<String, Object> result = HypitJson.read(row.resultJson() == null ? "{}" : row.resultJson());
		if (ok) {
			return new DispatchOutcome(true, String.valueOf(result.get("tool")), result, null, null, false);
		}
		String message = HypitJson.stringValue(result.get("error"), "已持久失败动作（不重跑）");
		return new DispatchOutcome(false, String.valueOf(result.get("tool")), result,
				HypitJson.stringValue(result.get("code"), "hypit_action_failed"), message, false);
	}

	public Flux<ActionRow> actionsOf(UUID jobId) {
		return actions.findByJob(jobId);
	}
}
