package com.grassland.intelligence.hypit.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.hypit.agent.HypitKnowledgeService.SearchHit;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 步骤执行（任务书 #107-2 C107-14 / 14.3-14.5）：一次步骤 = 一批 scope
 * 白名单内的工具调用。每个工具调用持久一行 hypit_job_action （input_hash 幂等定位、result_json 回执），失败如实
 * failed 不吞。
 *
 * <p>
 * C107F-04（107-fix-1 D-04/W17）：planner 首步——actions 为空且 stepIndex=0 时，第一步经平台 LLM 执行环
 * （{@link FrozenTextExecutionService#executeIndependent}，ai_run 记账不旁路）生成 action 计划；输出 JSON
 * schema 校验，失败重试 1 次，仍失败由调用方落 job failed {@code planner_failed} 并保留两次原始输出。
 */
@Service
public class HypitAgentStepService {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration PLANNER_TIMEOUT = Duration.ofSeconds(60);
	private static final int PLANNER_MAX_TOKENS = 2000;

	private final HypitKnowledgeService knowledge;
	private final HypitBuildRepository builds;
	private final HypitBuildService buildService;
	private final HypitJobActionRepository actions;
	private final FrozenTextExecutionService frozen;

	public HypitAgentStepService(HypitKnowledgeService knowledge, HypitBuildRepository builds,
			HypitBuildService buildService, HypitJobActionRepository actions,
			FrozenTextExecutionService frozen) {
		this.knowledge = knowledge;
		this.builds = builds;
		this.buildService = buildService;
		this.actions = actions;
		this.frozen = frozen;
	}

	/** 执行一批动作；每个动作独立持久化，单动作失败不阻断其余动作。 */
	public Mono<List<ActionRow>> run(UUID jobId, UUID projectId, HypitAgentScope scope, int stepIndex,
			List<HypitAgentAction> requested) {
		List<ActionRow> persisted = new ArrayList<>();
		Mono<List<ActionRow>> chain = Mono.just(persisted);
		int actionSlot = 0;
		for (HypitAgentAction action : requested) {
			// UNIQUE(job_id, step_index)：同批动作各占一个动作槽（step*1000+槽位）。
			final int slot = stepIndex * 1000 + actionSlot++;
			chain = chain
					.flatMap(rows -> dispatch(jobId, projectId, scope, stepIndex, action)
							.flatMap(result -> persist(jobId, slot, action, result))
							.onErrorResume(error -> persist(jobId, slot, action, Map.of("error",
									error instanceof com.grassland.intelligence.security.IntelligenceException refused
											? refused.code() + ": " + refused.getMessage()
											: String.valueOf(error.getMessage()))))
							.map(row -> {
								rows.add(row);
								return rows;
							}));
		}
		return chain;
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
	 * Planner 首步（D-04）：经平台 LLM 执行环生成 action 计划。prompt 内置 intent 语义与 allowedTools 清单；
	 * 输出必须是 {"actions":[{"kind","input"}]}，kind 只允许 allowedTools 内工具名，条数截到 maxSteps。
	 * 第一次输出无效重试一次；两次均无效抛 {@link PlannerFailed}（携带两次原始输出）。
	 */
	public Mono<PlannedActions> plan(String accountId, String intent, String brief, HypitAgentScope scope,
			int maxSteps, List<UUID> assetIds) {
		String system = plannerSystemPrompt(intent, scope, maxSteps);
		StringBuilder user = new StringBuilder("任务简报：\n").append(brief).append('\n');
		if (assetIds != null && !assetIds.isEmpty()) {
			user.append("已选素材（assetId）：").append(assetIds.stream().map(UUID::toString).toList()).append('\n');
		}
		user.append("输出 action 计划 JSON。");
		List<ChatMessage> messages = List.of(ChatMessage.system(system), ChatMessage.user(user.toString()));
		// worker 语境 Caller：复刻任务提交人（WechatSyncExecutionService 同款构造）；exchange 为空是
		// executeIndependent 的既定支持形态（tracedWithExchange null 安全）。
		Caller caller = new Caller(accountId, null, null, null, null, null, null, null);
		return attemptPlan(caller, messages, scope, maxSteps).flatMap(first -> first.parsed() != null
				? Mono.just(first.parsed().withRunId(first.runId()))
				: attemptPlan(caller, messages, scope, maxSteps).flatMap(second -> second.parsed() != null
						? Mono.just(second.parsed().withRunId(second.runId()))
						: Mono.<PlannedActions>error(new PlannerFailed(first.raw(), second.raw()))));
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
	private PlannedActions parsePlan(String raw, HypitAgentScope scope, int maxSteps) {
		try {
			JsonNode root = JSON.readTree(stripCodeFence(raw));
			JsonNode actionsNode = root.get("actions");
			if (actionsNode == null || !actionsNode.isArray()) {
				return null;
			}
			List<HypitAgentAction> parsed = new ArrayList<>();
			for (JsonNode item : actionsNode) {
				if (parsed.size() >= maxSteps) {
					break;
				}
				JsonNode kind = item.get("kind");
				if (kind == null || !kind.isTextual() || !scope.allowedTools().contains(kind.asText())) {
					return null;
				}
				Map<String, Object> input = new HashMap<>();
				JsonNode inputNode = item.get("input");
				if (inputNode != null && inputNode.isObject()) {
					// 值节点取 asText（toString 会带 JSON 引号，文本参数如 path 将失配）；
					// 对象/数组保持 JSON 形态。
					inputNode.fields().forEachRemaining(field -> {
						JsonNode node = field.getValue();
						input.put(field.getKey(), node.isValueNode() ? node.asText() : node.toString());
					});
				}
				parsed.add(new HypitAgentAction(kind.asText(), HypitJson.write(input), null, false));
			}
			return parsed.isEmpty() ? null : new PlannedActions(parsed, null);
		}
		catch (Exception invalid) {
			return null;
		}
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
				+ " 条动作；每条 input 是合法 JSON 对象。";
	}

	private Mono<Map<String, Object>> dispatch(UUID jobId, UUID projectId, HypitAgentScope scope, int stepIndex,
			HypitAgentAction action) {
		try {
			scope.assertAllowed(action.kind());
		} catch (IntelligenceException refused) {
			return Mono.error(refused);
		}
		return switch (action.kind()) {
			case HypitAgentScope.TOOL_KNOWLEDGE_SEARCH -> search(action);
			case HypitAgentScope.TOOL_KNOWLEDGE_READ -> read(action);
			case HypitAgentScope.TOOL_BUILD_STATUS -> buildStatus(projectId, action);
			default -> Mono.error(new IntelligenceException(HttpStatus.NOT_IMPLEMENTED.value(),
					"hypit_agent_tool_unavailable", "工具在本卡未开放：" + action.kind()));
		};
	}

	private Mono<Map<String, Object>> search(HypitAgentAction action) {
		Map<String, Object> input = HypitJson.read(action.inputJson());
		return knowledge.search(stringOr(input.get("topic"), null), stringOr(input.get("query"), null),
				intOr(input.get("limit"), 10)).map(hits -> {
					List<Map<String, Object>> items = new ArrayList<>();
					for (SearchHit hit : hits) {
						Map<String, Object> item = new HashMap<>();
						item.put("path", hit.doc().path());
						item.put("title", hit.doc().title());
						item.put("topic", hit.doc().topic());
						if (hit.snippet() != null) {
							item.put("snippet", hit.snippet());
						}
						items.add(item);
					}
					return Map.<String, Object>of("hits", items, "sourceCommit",
							String.valueOf(knowledge.sourceCommit()));
				});
	}

	private Mono<Map<String, Object>> read(HypitAgentAction action) {
		Map<String, Object> input = HypitJson.read(action.inputJson());
		return knowledge.read(stringOr(input.get("path"), ""))
				.map(text -> Map.<String, Object>of("path", stringOr(input.get("path"), ""), "document", text));
	}

	private Mono<Map<String, Object>> buildStatus(UUID projectId, HypitAgentAction action) {
		Map<String, Object> input = HypitJson.read(action.inputJson());
		String buildId = stringOr(input.get("buildId"), "");
		if (buildId.isBlank()) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "build.status 需要 buildId"));
		}
		return builds.findById(UUID.fromString(buildId)).filter(row -> row.projectId().equals(projectId))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "hypit_not_found", "Build 不存在")))
				.map(row -> Map.<String, Object>of("lifecycle", row.lifecycle(), "outcome",
						String.valueOf(row.outcome())));
	}

	private Mono<ActionRow> persist(UUID jobId, int stepIndex, HypitAgentAction action, Map<String, Object> result) {
		try {
			String inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(action.inputJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			boolean ok = !result.containsKey("error");
			// hypit_job_action.kind 是受限枚举（llm/tool/...），工具名入 result_json.tool
			Map<String, Object> stored = new HashMap<>();
			stored.put("tool", action.kind());
			stored.putAll(result);
			return actions.insert(UUID.randomUUID(), jobId, stepIndex, "tool", ok ? "succeeded" : "failed", inputHash,
					action.inputJson(), HypitJson.write(stored));
		} catch (Exception error) {
			return Mono.error(error);
		}
	}

	public Flux<ActionRow> actionsOf(UUID jobId) {
		return actions.findByJob(jobId);
	}

	private static String stringOr(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}

	private static int intOr(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}
}
