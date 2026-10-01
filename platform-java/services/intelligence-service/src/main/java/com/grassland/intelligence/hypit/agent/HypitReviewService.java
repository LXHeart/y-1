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
			Long revision, String changesetId, List<String> conflicts, List<String> diagnostics) {
	}

	/**
	 * C107F2-25（F10/F22 / §6.11）：按评论做语义源码修改并关联解决状态。
	 *
	 * <p>
	 * commentIds → feedback.read（上游唯一真相）→ 逐条分类并提取显式数值锚点 （字幕 px/增益 dB；无锚点=需要澄清
	 * waiting，禁止固定 48px/-6 猜测）；同 (kind,file) 冲突值→waiting 并列明冲突（不后写覆盖前写）。可定位意见经
	 * workspace.read 读原源码做最小属性替换（其余字节原样保留；baseHash=原文
	 * sha256），同文件多评论合成一次变更；validated 变更集 CAS apply——未过检查 抛
	 * hypit_compile_failed（head/评论不动，诊断可见）。apply 成功后：记录 comment→job→revision 映射进
	 * review 命令回执，并批量 resolve 已落地评论 （feedback.mutate replace resolved:true；失败保持
	 * open 不阻断）。
	 */
	public Mono<ReviseResult> revise(String accountId, UUID projectId, UUID requestId, long baseRevision,
			List<String> commentIds, String run, UUID jobId) {
		if (commentIds == null || commentIds.isEmpty()) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "commentIds 不能为空。"));
		}
		return readComments(projectId, run).flatMap(comments -> {
			Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
			for (Map<String, Object> comment : comments) {
				byId.put(HypitJson.stringValue(comment.get("id"), ""), comment);
			}
			List<Map<String, Object>> targets = new ArrayList<>();
			List<String> unknownIds = new ArrayList<>();
			for (String commentId : commentIds) {
				Map<String, Object> comment = byId.get(commentId);
				if (comment == null || Boolean.TRUE.equals(comment.get("resolved"))) {
					continue;
				}
				targets.add(comment);
			}
			if (unknownIds.isEmpty() && targets.isEmpty()) {
				return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "没有可修订的未解决评论。"));
			}
			// 步骤 2：逐条分类 + 显式锚点提取；无锚点 waiting。
			List<String> waiting = new ArrayList<>();
			List<String> conflicts = new ArrayList<>();
			Map<String, Target> actionable = new LinkedHashMap<>();
			for (Map<String, Object> comment : targets) {
				String commentId = HypitJson.stringValue(comment.get("id"), "");
				String text = HypitJson.stringValue(comment.get("text"), "");
				Target target = locate(text);
				if (target == null) {
					waiting.add(commentId);
					continue;
				}
				Target existing = actionable.get(target.kind() + "@" + target.file());
				if (existing == null) {
					actionable.put(target.kind() + "@" + target.file(), target.withComment(commentId));
					continue;
				}
				if (!existing.value().equals(target.value())) {
					// 步骤 4：同对象冲突意见——明确冲突待用户选择，不随机覆盖。
					conflicts.add(commentId);
					waiting.add(commentId);
				} else {
					existing.merge(commentId);
				}
			}
			if (actionable.isEmpty() || !conflicts.isEmpty()) {
				// 步骤 4：存在冲突意见 → 整批等待用户选择（不应用任何一方，不随机覆盖）。
				return Mono.just(new ReviseResult("WAITING_INPUT", List.of(), List.copyOf(waiting), null, null,
						List.copyOf(conflicts), List.of()));
			}
			// 步骤 2/4：读原源码做最小替换；读不到/锚不存在 → waiting（禁止猜测）。
			List<FileChange> changes = new ArrayList<>();
			List<String> diagnostics = new ArrayList<>();
			Map<String, Target> applied = new LinkedHashMap<>();
			for (Target target : actionable.values()) {
				String original = readSource(projectId, target.file());
				if (original == null) {
					waiting.add(target.commentIds().get(0));
					diagnostics.add("无法读取源文件 " + target.file());
					continue;
				}
				String next = target.apply(original);
				if (next.equals(original)) {
					waiting.add(target.commentIds().get(0));
					diagnostics.add("在 " + target.file() + " 未找到 " + target.patternDescription());
					continue;
				}
				changes.add(new FileChange(target.file(), "put", next, HypitReviewService.sha256Hex(original)));
				applied.put(target.kind() + "@" + target.file(), target);
			}
			if (changes.isEmpty()) {
				return Mono.just(new ReviseResult("WAITING_INPUT", List.of(), List.copyOf(waiting), null, null,
						List.copyOf(conflicts), List.copyOf(diagnostics)));
			}
			// 步骤 3：validated 变更集 + CAS apply；未过检查抛 hypit_compile_failed（head/评论不动）。
			List<String> appliedIds = applied.values().stream().flatMap(target -> target.commentIds().stream())
					.toList();
			return changesets.create(accountId, projectId, requestId, baseRevision, "validated", changes)
					.flatMap(row -> "passed".equals(row.checkStatus())
							? changesets.apply(accountId, projectId, row.id(), UUID.randomUUID(), baseRevision)
									.flatMap(result -> resolveApplied(projectId, byId, appliedIds)
											.onErrorResume(error -> Mono.empty())
											.then(recordMapping(projectId, jobId, row.id(), result.revision(),
													appliedIds))
											.thenReturn(new ReviseResult("REVISED", appliedIds, List.copyOf(waiting),
													result.revision(), row.id().toString(), List.copyOf(conflicts),
													List.copyOf(diagnostics))))
							: Mono.error(new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
									"hypit_compile_failed", "修改未通过检查，草稿保留。")));
		});
	}

	/** apply 成功后批量 resolve 已落地评论（feedback.mutate replace；失败保持 open）。 */
	private Mono<Void> resolveApplied(UUID projectId, Map<String, Map<String, Object>> byId, List<String> appliedIds) {
		List<Map<String, Object>> mutations = new ArrayList<>();
		for (String commentId : appliedIds) {
			Map<String, Object> before = byId.get(commentId);
			if (before == null) {
				continue;
			}
			Map<String, Object> next = new LinkedHashMap<>(before);
			next.put("resolved", true);
			mutations.add(Map.of("type", "replace", "before", before, "comment", next));
		}
		if (mutations.isEmpty()) {
			return Mono.empty();
		}
		return sidecar.commandAsync("java-feedback-resolve-" + UUID.randomUUID(), "feedback.mutate",
				Map.of("projectId", projectId.toString(), "mutations", mutations)).then();
	}

	/** comment→job→revision 映射落 review 命令回执（审计可重读）；job 面缺失时自建 review.revise 命令行。 */
	private Mono<Void> recordMapping(UUID projectId, UUID jobId, UUID changesetId, long revision,
			List<String> appliedIds) {
		Map<String, Object> mapping = new LinkedHashMap<>();
		if (jobId != null) {
			mapping.put("jobId", jobId.toString());
		}
		mapping.put("changesetId", changesetId.toString());
		mapping.put("revision", revision);
		mapping.put("commentIds", appliedIds);
		Mono<Void> persisted = jobId != null
				? commands.saveResult(jobId, "succeeded", HypitJson.write(mapping)).then()
				: commands.insert("system", "review.revise", UUID.randomUUID(), "review:" + projectId,
						HypitReviewService.sha256Hex(HypitJson.write(mapping)), HypitJson.write(mapping), projectId)
						.flatMap(accepted -> commands.saveResult(accepted.row().id(), "succeeded",
								HypitJson.write(mapping)))
						.then();
		return persisted.onErrorResume(error -> Mono.empty());
	}

	/** sidecar workspace.read：读当前 head 源文件内容（非 owner 面由调用方已校验）。 */
	private String readSource(UUID projectId, String path) {
		try {
			SidecarCommand command = sidecar
					.commandAsync("java-review-read-" + UUID.randomUUID(), "workspace.read",
							Map.of("projectId", projectId.toString(), "path", path))
					.block(java.time.Duration.ofSeconds(30));
			if (command == null || command.result() == null) {
				return null;
			}
			Object content = HypitJson.mapValue(command.result()).get("content");
			return content == null ? null : String.valueOf(content);
		} catch (Exception error) {
			return null;
		}
	}

	/** 结构化修改目标：kind/file/显式值 + 锚正则。 */
	private static final class Target {

		private final String kind;
		private final String file;
		private final String value;
		private final java.util.regex.Pattern anchor;
		private final String replacement;
		private final List<String> commentIds;

		private Target(String kind, String file, String value, java.util.regex.Pattern anchor, String replacement,
				List<String> commentIds) {
			this.kind = kind;
			this.file = file;
			this.value = value;
			this.anchor = anchor;
			this.replacement = replacement;
			this.commentIds = commentIds;
		}

		String kind() {
			return kind;
		}

		String file() {
			return file;
		}

		String value() {
			return value;
		}

		List<String> commentIds() {
			return commentIds;
		}

		String patternDescription() {
			return kind.equals("caption.size") ? "font-size 属性" : "gain 属性";
		}

		Target withComment(String commentId) {
			List<String> next = new ArrayList<>(commentIds);
			next.add(commentId);
			return new Target(kind, file, value, anchor, replacement, List.copyOf(next));
		}

		Target merge(String commentId) {
			return withComment(commentId);
		}

		/** 最小替换：仅首个锚值换成显式目标值，其余字节原样。 */
		String apply(String original) {
			java.util.regex.Matcher matcher = anchor.matcher(original);
			if (!matcher.find()) {
				return original;
			}
			return matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
		}
	}

	/**
	 * 意见 → 显式锚点（结构化修改匹配字幕样式/音量属性）： 字幕类须含「字幕/字号」与「N px」；音量类须含「音效/声音/gain」与「N dB」。
	 * 缺任一即无法定位（waiting），绝不用固定值猜。
	 */
	private Target locate(String text) {
		String lower = text == null ? "" : text.toLowerCase();
		java.util.regex.Matcher px = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*px").matcher(lower);
		if ((lower.contains("字幕") || lower.contains("字号")) && px.find()) {
			String value = px.group(1) + "px";
			return new Target("caption.size", "style.svs", value,
					java.util.regex.Pattern.compile("font-size\\s*:\\s*[^;\"}]+"), "font-size: " + value,
					new ArrayList<>());
		}
		java.util.regex.Matcher db = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:db|分贝)")
				.matcher(lower);
		if ((lower.contains("音效") || lower.contains("声音") || lower.contains("gain")) && db.find()) {
			String value = db.group(1) + "dB";
			return new Target("sound.gain", "style.svs", value,
					java.util.regex.Pattern.compile("gain(?:-db)?\\s*:\\s*[^;\"}]+"), "gain-db: " + db.group(1),
					new ArrayList<>());
		}
		return null;
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
