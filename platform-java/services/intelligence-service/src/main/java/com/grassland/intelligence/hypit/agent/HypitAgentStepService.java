package com.grassland.intelligence.hypit.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.hypit.agent.HypitKnowledgeService.SearchHit;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.project.HypitJson;
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
 */
@Service
public class HypitAgentStepService {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(20);

	private final HypitKnowledgeService knowledge;
	private final HypitBuildRepository builds;
	private final HypitBuildService buildService;
	private final HypitJobActionRepository actions;

	public HypitAgentStepService(HypitKnowledgeService knowledge, HypitBuildRepository builds,
			HypitBuildService buildService, HypitJobActionRepository actions) {
		this.knowledge = knowledge;
		this.builds = builds;
		this.buildService = buildService;
		this.actions = actions;
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
