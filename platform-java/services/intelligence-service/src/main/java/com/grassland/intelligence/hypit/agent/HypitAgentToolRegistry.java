package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.agent.HypitKnowledgeService.SearchHit;
import com.grassland.intelligence.hypit.asset.HypitArchiveService;
import com.grassland.intelligence.hypit.asset.HypitAssetService;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitBuildService.SubmitView;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.client.HypitSidecarUnreachableException;
import com.grassland.intelligence.hypit.project.HypitChangesetService;
import com.grassland.intelligence.hypit.project.HypitChangesetService.ApplyResult;
import com.grassland.intelligence.hypit.project.HypitChangesetService.ChangesetRow;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Agent 工具唯一注册表（任务书 107-fix-2 C107F2-14 / F21 / §6.7）：allowedTools 可见集、 输入
 * schema 与 handler 的唯一事实源。可见工具集合 == 实际 handler 集合——未实现工具不登记， 也不会出现在 planner
 * 可见集（{@link HypitAgentScope#confinedTo}）；dispatch 对未登记工具回 结构化 400
 * {@code hypit_unsupported_action}，不再有默认 501。
 *
 * <p>
 * 参数类型保真（RULE-08/§6.7）：number/boolean/object/array 原样进 handler，绝不 asText 字符串化；
 * 逐工具 schema 校验失败返回结构化结果（ok=false + code/message），调用方如实持久动作行。副作用工具
 * （build.submit/mutation.apply/packages.*）的幂等键从 (jobId, tool, input)
 * 确定性派生——同动作崩溃重放 命中同一 command，不双计费。
 */
@Component
public class HypitAgentToolRegistry {

	/** 工具入参描述；type ∈ string|number|boolean|object|array|uuid。 */
	public record Param(String name, String type, boolean required) {
	}

	/** 单次工具调用的执行上下文（真实服务依赖由注册表闭包持有）。 */
	public record ToolCall(UUID jobId, UUID projectId, String accountId, long baseRevision, Map<String, Object> input) {
	}

	/** 结构化派发结果：失败也成行（ok=false + errorCode/message），安全拒绝带 scopeRefusal 标记。 */
	public record DispatchOutcome(boolean ok, String tool, Map<String, Object> result, String errorCode,
			String errorMessage, boolean scopeRefusal) {
	}

	private record Tool(String id, String category, List<Param> schema, boolean freeform,
			Function<ToolCall, Mono<Map<String, Object>>> handler) {
	}

	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public HypitAgentToolRegistry(HypitKnowledgeService knowledge, HypitBuildRepository builds,
			HypitBuildService buildService, HypitOutputRepository outputs, HypitArchiveService archives,
			HypitChangesetService changesets, HypitAssetService assets, HypitSidecarClient sidecar) {
		// ---- knowledge：检索/读取（真实索引） ----
		register("knowledge.search", "knowledge",
				new Param[]{param("query", "string", false), param("topic", "string", false),
						param("limit", "number", false)},
				false, call -> knowledge.search(stringOrNull(call.input().get("topic")),
						stringOrNull(call.input().get("query")), intOr(call.input().get("limit"), 10)).map(hits -> {
							List<Map<String, Object>> items = new ArrayList<>();
							for (SearchHit hit : hits) {
								Map<String, Object> item = new LinkedHashMap<>();
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
						}));
		register("knowledge.read", "knowledge", new Param[]{param("path", "string", true)}, false, call -> knowledge
				.read(stringOr(call.input().get("path"), ""))
				.map(text -> Map.<String, Object>of("path", stringOr(call.input().get("path"), ""), "document", text)));
		// ---- build：状态/提交/取消（真实 Build 服务） ----
		register("build.status", "build", new Param[]{param("buildId", "uuid", true)}, false,
				call -> builds.findById(uuidOf(call.input().get("buildId")))
						.filter(row -> row.projectId().equals(call.projectId()))
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "hypit_not_found", "Build 不存在")))
						.map(row -> Map.<String, Object>of("buildId", row.id().toString(), "lifecycle", row.lifecycle(),
								"outcome", String.valueOf(row.outcome()))));
		register("build.submit", "build",
				new Param[]{param(
						"planId", "uuid", true), param("title", "string", false), param("grantId", "uuid", false)},
				false,
				call -> buildService.submit(call.accountId(), call.projectId(), idempotentId(call, "build.submit"),
						uuidOf(call.input().get("planId")), uuidOrNull(call.input().get("grantId")),
						stringOrNull(call.input().get("title"))).map(view -> submitResult(view)));
		register("build.cancel", "build", new Param[]{param("buildId", "uuid", true), param("reason", "string", false)},
				false,
				call -> buildService
						.cancel(call.accountId(), uuidOf(call.input().get("buildId")),
								idempotentId(call, "build.cancel"), stringOrNull(call.input().get("reason")))
						.map(row -> Map.<String, Object>of("buildId", row.id().toString(), "lifecycle", row.lifecycle(),
								"outcome", String.valueOf(row.outcome()))));
		// ---- output：归档（真实归档服务，幂等回现状） ----
		register("output.archive", "build", new Param[]{param("outputId", "uuid", true)}, false, call -> archives
				.archiveOutput(uuidOf(call.input().get("outputId"))).map(HypitAgentToolRegistry::archiveResult));
		// ---- mutation：写回变更（真实 Changeset 服务，create+apply 两段） ----
		register("mutation.apply", "mutation",
				new Param[]{param("changes", "array", true), param("applyMode", "string", false)}, false,
				call -> applyChangeset(changesets, sidecar, call));
		// ---- packages / snapshot：既有工具端点（真实 runTool 提交链） ----
		for (String tool : List.of("packages.build", "packages.pack", "snapshot")) {
			register(tool, "assets", new Param[0], true, call -> assets.runTool(call.accountId(), call.projectId(),
					tool, idempotentId(call, tool), call.input()));
		}
		// ---- feedback：审片评论修改（上游 FEEDBACK 原子批 + CAS，沿 C107-18 桥） ----
		register("feedback.mutate", "feedback", new Param[]{param("mutations", "array", true),
				param("run", "string", false), param("expectedHash", "string", false)}, false, call -> {
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("projectId", call.projectId().toString());
					String run = stringOrNull(call.input().get("run"));
					if (run != null && !run.isBlank()) {
						payload.put("run", run);
					}
					String expectedHash = stringOrNull(call.input().get("expectedHash"));
					if (expectedHash != null && !expectedHash.isBlank()) {
						payload.put("expectedHash", expectedHash);
					}
					payload.put("mutations",
							call.input().get("mutations") == null ? List.of() : call.input().get("mutations"));
					return sidecar
							.commandAsync(idempotentId(call, "feedback.mutate").toString(), "feedback.mutate", payload)
							.flatMap(HypitAgentToolRegistry::feedbackReceipt);
				});
	}

	/** 注册表可见工具集合（== 实际 handler 集合）。 */
	public Set<String> visibleTools() {
		return new LinkedHashSet<>(tools.keySet());
	}

	public boolean isRegistered(String tool) {
		return tools.containsKey(tool);
	}

	/**
	 * 派发：scope 闸 → 输入 schema 校验 → 真实 handler。所有失败都是结构化结果（不抛出、不 501）； 安全/越权拒绝置
	 * scopeRefusal=true（worker 据此终止而非 replan），且业务服务零调用。
	 */
	public Mono<DispatchOutcome> dispatch(HypitAgentScope scope, String tool, ToolCall call) {
		Tool registered = tools.get(tool);
		if (registered == null) {
			return Mono.just(failure(tool, "hypit_unsupported_action", "工具未登记或未开放：" + tool, false));
		}
		try {
			scope.assertAllowed(tool);
		} catch (IntelligenceException refused) {
			return Mono.just(failure(tool, refused.code(), refused.getMessage(), true));
		}
		String invalid = validateInput(tool, registered, call.input());
		if (invalid != null) {
			return Mono.just(failure(tool, "hypit_invalid_input", invalid, false));
		}
		return registered.handler().apply(call)
				.map(result -> new DispatchOutcome(true, tool, result, null, null, false)).onErrorResume(error -> {
					if (error instanceof IntelligenceException exception) {
						boolean security = exception.status() == HttpStatus.FORBIDDEN.value();
						return Mono
								.just(failure(tool, exception.code() == null ? "hypit_tool_failed" : exception.code(),
										String.valueOf(exception.getMessage()), security));
					}
					// C107F2-38（round-17 实录）：sidecar 传输层不可达（连接超时/拒绝）
					// 是暂缓不是终判——读类工具无副作用，结果未知恰是 defer 类定义，
					// 归 hypit_broker_unreachable 让 worker 走 90s/轮自然退避重试；
					// 真工具失败（拒绝/逻辑错）保持 hypit_tool_failed 终态。
					boolean unreachable = error instanceof HypitSidecarUnreachableException;
					return Mono.just(failure(tool, unreachable ? "hypit_broker_unreachable" : "hypit_tool_failed",
							error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), false));
				});
	}

	/** planner 输出的预检（无效计划在规划层重试/失败，不落到动作行）。 */
	public String validateInput(String tool, Map<String, Object> input) {
		Tool registered = tools.get(tool);
		if (registered == null) {
			return "工具未登记或未开放：" + tool;
		}
		return validateInput(tool, registered, input);
	}

	private String validateInput(String tool, Tool registered, Map<String, Object> input) {
		if (input == null) {
			input = Map.of();
		}
		if (registered.freeform()) {
			return null;
		}
		Set<String> known = new LinkedHashSet<>();
		for (Param param : registered.schema()) {
			known.add(param.name());
			Object value = input.get(param.name());
			if (value == null) {
				if (param.required()) {
					return tool + " 缺少必填参数 " + param.name();
				}
				continue;
			}
			String mismatch = typeMismatch(tool, param, value);
			if (mismatch != null) {
				return mismatch;
			}
		}
		for (String key : input.keySet()) {
			if (!known.contains(key)) {
				return tool + " 不认识参数 " + key;
			}
		}
		return null;
	}

	private static String typeMismatch(String tool, Param param, Object value) {
		String type = param.type();
		boolean matches = switch (type) {
			case "string" -> value instanceof String;
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			case "object" -> value instanceof Map;
			case "array" -> value instanceof List;
			case "uuid" -> value instanceof String text && isUuid(text);
			default -> false;
		};
		return matches
				? null
				: tool + " 参数 " + param.name() + " 必须是 " + type + "（收到 " + value.getClass().getSimpleName() + "）";
	}

	private static boolean isUuid(String text) {
		try {
			UUID.fromString(text);
			return true;
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	private void register(String id, String category, Param[] schema, boolean freeform,
			Function<ToolCall, Mono<Map<String, Object>>> handler) {
		tools.put(id, new Tool(id, category, Arrays.asList(schema), freeform, handler));
	}

	private static Param param(String name, String type, boolean required) {
		return new Param(name, type, required);
	}

	private static DispatchOutcome failure(String tool, String code, String message, boolean scopeRefusal) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("error", code + ": " + message);
		result.put("code", code);
		result.put("message", message);
		return new DispatchOutcome(false, tool, result, code, message, scopeRefusal);
	}

	private static Map<String, Object> submitResult(SubmitView view) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("buildId", view.build().id().toString());
		result.put("jobId", view.job().id().toString());
		result.put("lifecycle", view.build().lifecycle());
		result.put("outcome", String.valueOf(view.build().outcome()));
		return result;
	}

	private static Map<String, Object> archiveResult(OutputRow row) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("outputId", row.id().toString());
		result.put("outputName", row.outputName());
		result.put("archiveState", row.archiveState());
		if (row.mediaId() != null) {
			result.put("mediaId", row.mediaId().toString());
		}
		return result;
	}

	/**
	 * C107F2-37（缺陷 Q）：planner 产物只有「目标内容」，没有也不可能有文件 hash—— put 且缺 baseHash 时由服务端实读当前
	 * head hash 作为 CAS 基线（文件不存在则保持 absent 预期）。CAS 语义不变（基线=服务端实读而非客户端自报），validated
	 * 模式的 runner check 照跑。此前缺 hash 的 put 被当作「预期不存在」，对既有文件恒
	 * hypit_revision_conflict，author 链全灭。
	 */
	private Mono<Map<String, Object>> applyChangeset(HypitChangesetService changesets, HypitSidecarClient sidecar,
			ToolCall call) {
		List<FileChange> changes = new ArrayList<>();
		Object raw = call.input().get("changes");
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				Map<String, Object> change = HypitJson.mapValue(item);
				changes.add(new FileChange(stringOrNull(change.get("path")), stringOrNull(change.get("action")),
						stringOrNull(change.get("content")), stringOrNull(change.get("baseHash"))));
			}
		}
		String applyMode = stringOrNull(call.input().get("applyMode"));
		List<FileChange> needsBase = changes.stream().filter(
				change -> "put".equals(change.action()) && (change.baseHash() == null || change.baseHash().isBlank()))
				.toList();
		// C107F2-37（缺陷 Q2）：哈希必须按 needsBase 顺序回填——flatMap 按完成序
		// 收集会乱序（实测 main.svml 拿到 style.svs 的哈希 → apply 必 409），concatMap 保序。
		return reactor.core.publisher.Flux
				.<String>fromIterable(needsBase.stream()
						.map(change -> idempotentId(call, "workspace.read:" + change.path()).toString()).toList())
				.zipWithIterable(needsBase)
				.concatMap(tuple -> sidecar
						.commandAsync(tuple.getT1(), "workspace.read",
								Map.of("projectId", call.projectId().toString(), "path", tuple.getT2().path()))
						.map(command -> "succeeded".equals(command.state())
								? HypitJson.stringValue(HypitJson.mapValue(command.result()).get("hash"), "")
								: "")
						// C107F2-38（TC-F2-38-01/02 round-6 实录）：sidecar 不可达 ≠
						// 文件不存在——旧写法 onErrorResume 把断网读失败伪装成 absent
						// 基线，resume 重放时 canonical 变了而 requestId（只由 input
						// 决定）没变 → 必然 hypit_idempotency_conflict 烧光修复轮。
						// 如实上抛 503：worker 按 hypit_maintenance 同类暂缓，恢复后
						// 重读同 hash → 同 canonical 幂等重放收敛。带根因 message，
						// 排障不必再靠线程.dump（round-8 教训）。
						.onErrorResume(error -> Mono.error(new IntelligenceException(503, "hypit_broker_unreachable",
								"sidecar 不可达：文件基线 hash 未知，暂后续跑（不以 absent 伪装）· " + String.valueOf(error.getMessage())))))
				.collectList().flatMap(hashes -> {
					java.util.Iterator<String> resolved = hashes.iterator();
					List<FileChange> based = new ArrayList<>(changes.size());
					for (FileChange change : changes) {
						if (needsBase.contains(change) && resolved.hasNext()) {
							String hash = resolved.next();
							based.add(new FileChange(change.path(), change.action(), change.content(),
									hash.isBlank() ? null : hash));
						} else {
							based.add(change);
						}
					}
					UUID requestId = idempotentId(call, "changeset.create");
					return changesets
							.create(call.accountId(), call.projectId(), requestId, call.baseRevision(),
									applyMode == null ? "save" : applyMode, based)
							.flatMap((ChangesetRow row) -> changesets
									.apply(call.accountId(), call.projectId(), row.id(),
											idempotentId(call, "changeset.apply"), call.baseRevision())
									.map((ApplyResult applied) -> {
										Map<String, Object> result = new LinkedHashMap<>();
										result.put("changesetId", row.id().toString());
										result.put("revision", applied.revision());
										result.put("manifestHash", applied.manifestHash());
										result.put("appliedPaths", applied.appliedPaths());
										return result;
									}));
				});
	}

	private static Mono<Map<String, Object>> feedbackReceipt(SidecarCommand command) {
		if ("failed".equals(command.state())) {
			Map<String, Object> error = command.error() == null ? Map.of() : command.error();
			String code = HypitJson.stringValue(error.get("code"), "engine_error");
			String message = HypitJson.stringValue(error.get("message"), "feedback mutate failed");
			return Mono.error(new IntelligenceException(
					"feedback_conflict".equals(code)
							? HttpStatus.CONFLICT.value()
							: HttpStatus.SERVICE_UNAVAILABLE.value(),
					"feedback_conflict".equals(code) ? "hypit_revision_conflict" : "hypit_backend_unavailable",
					message));
		}
		return Mono.just(HypitJson.mapValue(command.result()));
	}

	/** 副作用工具幂等键：(jobId, tool, input) 的确定性 UUID——崩溃重放命中同一 command。 */
	private static UUID idempotentId(ToolCall call, String action) {
		try {
			String canonical = call.jobId() + "|" + action + "|"
					+ HypitJson.write(call.input() == null ? Map.of() : call.input());
			return UUID.nameUUIDFromBytes(
					MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private static String stringOr(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}

	private static String stringOrNull(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static int intOr(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static UUID uuidOf(Object value) {
		return UUID.fromString(String.valueOf(value));
	}

	private static UUID uuidOrNull(Object value) {
		return value == null ? null : UUID.fromString(String.valueOf(value));
	}
}
