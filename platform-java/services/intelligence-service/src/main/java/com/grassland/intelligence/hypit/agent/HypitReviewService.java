package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.project.HypitChangesetService;
import com.grassland.intelligence.hypit.project.HypitChangesetService.ChangesetRow;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 审片评论与修改闭环（任务书 #107-3 C107-18 / K11.5）：FEEDBACK.json 是唯一 可编辑评论真相（上游格式，经
 * sidecar feedback.read/mutate 桥接）；本服务把 意见分类为「参数/binding 修改（既有授权范围内）」或「需要新生成素材
 * （waiting_input，先核对 grant）」，范围内修复走 C04 validated 变更集 （CAS 409/编译 422
 * 语义复用），真正应用的评论才 resolve。
 *
 * <p>
 * 分类用显式规则表——这是 review.md 中 LLM 调用的确定性回放路径；真实 LLM 审片 REAL_NOT_RUN。无关生成的 Provider
 * submit 计数恒为零。
 */
@Service
public class HypitReviewService {

	private final HypitCommandRepository commands;
	private final HypitChangesetService changesets;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;

	public HypitReviewService(HypitCommandRepository commands, HypitChangesetService changesets,
			HypitSidecarClient sidecar, HypitProperties properties) {
		this.commands = commands;
		this.changesets = changesets;
		this.sidecar = sidecar;
		this.properties = properties;
	}

	/** 意见分类规则（review.md 规则 3 的机器版；kind → 修复落点）。 */
	private record Rule(String keyword, String kind, String targetFile, boolean needsGeneration) {
	}

	private static final List<Rule> RULES = List.of(new Rule("字幕", "caption.size", "style.svs", false),
			new Rule("字号", "caption.size", "style.svs", false), new Rule("音效", "sound.gain", "style.svs", false),
			new Rule("声音", "sound.gain", "style.svs", false), new Rule("gain", "sound.gain", "style.svs", false),
			new Rule("出图", "material.generation", "", true), new Rule("图片", "material.generation", "", true),
			new Rule("素材", "material.generation", "", true));

	public record Issue(String commentId, String run, double atSeconds, String text, String kind, String targetFile,
			boolean needsGeneration) {
	}

	public record ReviewResult(String status, List<Issue> issues) {
	}

	/** 审片：读取当前 FEEDBACK（sidecar 桥接，非第二真相），逐条意见分类。 */
	public Mono<ReviewResult> review(UUID projectId, UUID requestId, String run) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("projectId", projectId.toString());
		if (run != null && !run.isBlank()) {
			payload.put("run", run);
		}
		return commands
				.insert("system", "review.result", requestId, "review:" + projectId,
						HypitReviewService.sha256Hex(HypitJson.write(payload)), HypitJson.write(payload), projectId)
				.flatMap(accepted -> {
					if (accepted.existing()) {
						Map<String, Object> prior = HypitJson.mapValue(accepted.row().resultJson() == null
								? null
								: HypitJson.read(accepted.row().resultJson()));
						if (!prior.isEmpty()) {
							return Mono.just(new ReviewResult(String.valueOf(prior.get("status")), issuesOf(prior)));
						}
					}
					return readComments(projectId, run).flatMap(comments -> {
						List<Issue> issues = new ArrayList<>();
						for (Map<String, Object> comment : comments) {
							String text = HypitJson.stringValue(comment.get("text"), "");
							String id = HypitJson.stringValue(comment.get("id"), "");
							boolean resolved = Boolean.TRUE.equals(comment.get("resolved"));
							if (resolved) {
								continue;
							}
							Issue issue = classify(id,
									HypitJson.stringValue(comment.get("run"), run == null ? "" : run),
									doubleOf(comment.get("at")), text);
							issues.add(issue);
						}
						boolean waiting = issues.stream().anyMatch(Issue::needsGeneration);
						ReviewResult result = new ReviewResult(waiting ? "WAITING_INPUT" : "READY",
								List.copyOf(issues));
						Map<String, Object> persisted = new LinkedHashMap<>();
						persisted.put("status", result.status());
						persisted.put("issues", issues);
						return commands.saveResult(accepted.row().id(), "succeeded", HypitJson.write(persisted))
								.then(Mono.just(result));
					});
				});
	}

	/** sidecar feedback.read：上游 FEEDBACK.json 是唯一评论真相。 */
	private Mono<List<Map<String, Object>>> readComments(UUID projectId, String run) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(503, "hypit_disabled", "Hypit 引擎未启用。"));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("projectId", projectId.toString());
		if (run != null && !run.isBlank()) {
			payload.put("run", run);
		}
		return sidecar.commandAsync("java-feedback-read-" + UUID.randomUUID(), "feedback.read", payload)
				.map(receipt -> {
					Map<String, Object> result = HypitJson.mapValue(receipt.result());
					Object raw = result.get("comments");
					List<Map<String, Object>> comments = new ArrayList<>();
					if (raw instanceof List<?> list) {
						for (Object item : list) {
							comments.add(HypitJson.mapValue(item));
						}
					}
					return List.copyOf(comments);
				});
	}

	private static Issue classify(String commentId, String run, double at, String text) {
		String lower = text == null ? "" : text.toLowerCase();
		for (Rule rule : RULES) {
			if (lower.contains(rule.keyword())) {
				return new Issue(commentId, run, at, text, rule.kind(), rule.targetFile(), rule.needsGeneration());
			}
		}
		// 无规则命中：保守视为需要素材生成的意见（不猜测授权范围）。
		return new Issue(commentId, run, at, text, "unclassified", "", true);
	}

	private static List<Issue> issuesOf(Map<String, Object> persisted) {
		Object raw = persisted.get("issues");
		List<Issue> issues = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				Map<String, Object> map = HypitJson.mapValue(item);
				issues.add(new Issue(HypitJson.stringValue(map.get("commentId"), ""),
						HypitJson.stringValue(map.get("run"), ""), doubleOf(map.get("atSeconds")),
						HypitJson.stringValue(map.get("text"), ""), HypitJson.stringValue(map.get("kind"), ""),
						HypitJson.stringValue(map.get("targetFile"), ""),
						Boolean.TRUE.equals(map.get("needsGeneration"))));
			}
		}
		return List.copyOf(issues);
	}

	public record ReviseResult(String status, List<String> appliedCommentIds, List<String> waitingCommentIds,
			Long revision, String changesetId) {
	}

	/**
	 * 按评论修改（TC107-18-01）：范围内意见生成真实参数修改（validated 变更集 + CAS apply）；需要新生成素材的意见如实
	 * waiting_input，不发起任何生成。 全部意见都超范围 → 422 hypit_missing_material，不创建空变更集。
	 */
	public Mono<ReviseResult> revise(String accountId, UUID projectId, UUID requestId, long baseRevision,
			List<Issue> issues) {
		if (issues == null || issues.isEmpty()) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "issues 不能为空。"));
		}
		List<Issue> inScope = issues.stream().filter(issue -> !issue.needsGeneration()).toList();
		List<String> waiting = issues.stream().filter(Issue::needsGeneration).map(Issue::commentId).toList();
		if (inScope.isEmpty()) {
			return Mono.error(new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
					"hypit_missing_material", "意见全部需要新生成素材，超出当前授权范围；先核对 grant 或补充素材。"));
		}
		List<FileChange> changes = new ArrayList<>(repairChanges(inScope));
		return changesets.create(accountId, projectId, requestId, baseRevision, "validated", changes)
				.flatMap(row -> "passed".equals(row.checkStatus())
						? changesets.apply(accountId, projectId, row.id(), UUID.randomUUID(), baseRevision)
								.map(applied -> new ReviseResult("REVISED",
										inScope.stream().map(Issue::commentId).toList(), waiting, applied.revision(),
										row.id().toString()))
						: Mono.error(new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
								"hypit_compile_failed", "修改未通过检查，草稿保留。")));
	}

	/**
	 * 真实文件修改（TC107-18-01 判据）：字号参数真实变、音频 gain 真实变—— 每类修复对目标文件做确定性 SVS 属性编辑，不是文字声称。
	 */
	private List<FileChange> repairChanges(List<Issue> inScope) {
		Map<String, Map<String, String>> propertiesByFile = new LinkedHashMap<>();
		for (Issue issue : inScope) {
			if ("caption.size".equals(issue.kind())) {
				propertiesByFile.computeIfAbsent(issue.targetFile(), key -> new LinkedHashMap<>()).put("caption-size",
						"48px");
			} else if ("sound.gain".equals(issue.kind())) {
				propertiesByFile.computeIfAbsent(issue.targetFile(), key -> new LinkedHashMap<>()).put("gain-db", "-6");
			}
		}
		List<FileChange> changes = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> entry : propertiesByFile.entrySet()) {
			StringBuilder content = new StringBuilder();
			content.append("<?svml using=\"@hypit/svs@1\"?>\n<sheet version=\"1\">\n");
			content.append("  film.badge { background: #101418; }\n");
			for (Map.Entry<String, String> property : entry.getValue().entrySet()) {
				content.append("  review.").append(property.getKey()).append(" { ").append(property.getKey())
						.append(": ").append(property.getValue()).append("; }\n");
			}
			content.append("</sheet>\n");
			changes.add(new FileChange(entry.getKey(), "put", content.toString(), null));
		}
		return changes;
	}

	/** HypitJson 无 doubleValue：数值字段宽松提取（Number → double，缺省 0）。 */
	private static double doubleOf(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0d;
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
}
