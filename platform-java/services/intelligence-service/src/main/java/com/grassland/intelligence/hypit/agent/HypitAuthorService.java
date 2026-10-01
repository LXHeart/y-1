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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 编写代理服务（任务书 #107-3 C107-17 / K10.4）：按克隆方案生成多文件 changeset（SVML/SVS/Run +
 * 必要的组件包源码），经「check → 确定性修复（最多 2 轮）」后再建草稿。坏生成保留 diff/诊断、head 不动；check 通过走既有
 * validated 变更集（CAS 409 / compile 422 语义复用 C04）。确定性回放路径绝不 发起收费生成；真实 LLM 编写
 * REAL_NOT_RUN，prompt 见 JR hypit/prompts/author.md。
 *
 * <p>
 * 确定性生成器产出的组件包以源码文件随变更集交付（activation 指向 dist， 由 packages.build
 * 受控编译后生效），main.svml 不 import 未编译包，保证 check 的引用闭包真实可编译——先建包、后绑定的顺序与 17.4/17.8
 * 一致。
 */
@Service
public class HypitAuthorService {

	private static final int MAX_FILES = 200;
	private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
	private static final int MAX_CHANGESET_BYTES = 16 * 1024 * 1024;
	private static final int MAX_FIX_ROUNDS = 2;

	private final HypitCommandRepository commands;
	private final HypitChangesetService changesets;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final org.springframework.r2dbc.core.DatabaseClient db;

	public HypitAuthorService(HypitCommandRepository commands, HypitChangesetService changesets,
			HypitSidecarClient sidecar, HypitProperties properties, org.springframework.r2dbc.core.DatabaseClient db) {
		this.commands = commands;
		this.changesets = changesets;
		this.sidecar = sidecar;
		this.properties = properties;
		this.db = db;
	}

	public record DraftInput(UUID requestId, UUID projectId, long baseRevision, String title,
			List<Map<String, Object>> planSteps, List<String> knowledgeRefs) {
	}

	public record DraftResult(String status, int rounds, String changesetId, long baseRevision, List<String> paths,
			List<Map<String, Object>> diagnostics, List<String> missingMaterials) {

		public boolean waitingInput() {
			return "WAITING_INPUT".equals(status);
		}
	}

	/**
	 * C107F2-17（步骤 5 / TC-04）：模型/生成输出的安全闸——路径必须是工程内相对路径（拒绝绝对路径、 `..`
	 * 穿越、反斜杠与控制字符），内容不得携带 shell 执行原语。违规整批拒绝、保留原源码。
	 */
	public static void validateChangesetSafety(List<FileChange> changes) {
		for (FileChange change : changes) {
			String path = change.path();
			if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")
					|| path.chars().anyMatch(character -> character < 0x20)) {
				throw new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), "hypit_unsafe_changeset",
						"拒绝越界文件路径：" + path);
			}
			String content = change.content() == null ? "" : change.content();
			String lowered = content.toLowerCase();
			for (String forbidden : List.of("exec(", "spawn(", "child_process", "os.system", "process.env")) {
				if (lowered.contains(forbidden)) {
					throw new IntelligenceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), "hypit_unsafe_changeset",
							"拒绝 shell 执行内容：" + change.path() + " 含 " + forbidden);
				}
			}
		}
	}

	/**
	 * C107F2-17（步骤 2 / TC-03）：材料绑定检查——方案步骤声明了 assetId 但工程缺该素材（或未就绪） 时返回缺口清单；存在缺口即
	 * waiting_input，零 command/changeset/收费副作用。
	 */
	public Mono<List<String>> missingMaterials(UUID projectId, List<Map<String, Object>> planSteps) {
		List<String> required = new ArrayList<>();
		for (Map<String, Object> step : planSteps == null ? List.<Map<String, Object>>of() : planSteps) {
			String assetId = HypitJson.stringValue(step.get("assetId"), null);
			if (assetId != null && !assetId.isBlank() && !"null".equals(assetId)) {
				required.add(assetId);
			}
		}
		if (required.isEmpty()) {
			return Mono.just(List.of());
		}
		String list = required.stream().map(id -> "CAST('" + id + "' AS uuid)")
				.collect(java.util.stream.Collectors.joining(","));
		return db
				.sql("SELECT id::text AS id FROM hypit_asset WHERE project_id = CAST('" + projectId
						+ "' AS uuid) AND status = 'ready' AND id IN (" + list + ")")
				.map((row, meta) -> row.get("id", String.class)).all().collectList().map(known -> {
					List<String> missing = new ArrayList<>(required);
					missing.removeAll(known);
					return List.copyOf(missing);
				});
	}

	/** 方案 → 多文件 changeset（确定性回放；prompt 规则 1–5 的机器版）。 */
	public List<FileChange> generateChangeset(DraftInput input) {
		List<FileChange> changes = new ArrayList<>();
		List<Map<String, Object>> steps = input.planSteps() == null ? List.of() : input.planSteps();
		List<String> packageNames = new ArrayList<>();
		for (Map<String, Object> step : steps) {
			Object capability = step.get("capability");
			if (capability instanceof String text && text.startsWith("package.")) {
				packageNames.add(text.substring("package.".length()));
			}
		}
		changes.add(new FileChange("main.svml", "put", mainSvml(steps, input.title()), null));
		changes.add(new FileChange("style.svs", "put", STYLE_SVS, null));
		changes.add(new FileChange("main.svrun", "put", MAIN_SVRUN, null));
		changes.add(new FileChange("plan.json", "put", planJson(input, steps), null));
		for (String packageName : packageNames) {
			changes.addAll(customPackageFiles(packageName));
		}
		assertLimits(changes);
		return List.copyOf(changes);
	}

	/** 17.2：路径/大小 schema 限制——超出即拒绝，绝不截断执行。 */
	private void assertLimits(List<FileChange> changes) {
		if (changes.size() > MAX_FILES) {
			throw new IntelligenceException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "hypit_too_large",
					"changeset 文件数超过上限 " + MAX_FILES + "，不截断执行。");
		}
		int total = 0;
		for (FileChange change : changes) {
			byte[] bytes = change.content() == null ? new byte[0] : change.content().getBytes(StandardCharsets.UTF_8);
			if (bytes.length > MAX_FILE_BYTES) {
				throw new IntelligenceException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "hypit_too_large",
						"单文件超过 2MiB：" + change.path() + "，不截断执行。");
			}
			total += bytes.length;
		}
		if (total > MAX_CHANGESET_BYTES) {
			throw new IntelligenceException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "hypit_too_large",
					"变更集总量超过 16MiB，不截断执行。");
		}
	}

	/**
	 * 编写草稿：生成 → check（≤2 轮：第 2 轮重发规范化骨架）→ 通过建 validated 草稿；两轮仍坏则保留 save
	 * 草稿与完整诊断，head 永不移动。
	 */
	public Mono<DraftResult> draft(String accountId, DraftInput input) {
		if (input.requestId() == null) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "requestId 必填。"));
		}
		return missingMaterials(input.projectId(), input.planSteps()).flatMap(missing -> missing.isEmpty()
				? draftWithMaterials(accountId, input)
				: Mono.just(new DraftResult("WAITING_INPUT", 0, null, input.baseRevision(), List.of(), List.of(),
						missing)));
	}

	private Mono<DraftResult> draftWithMaterials(String accountId, DraftInput input) {
		List<FileChange> changes;
		try {
			changes = generateChangeset(input);
			validateChangesetSafety(changes);
		} catch (IntelligenceException error) {
			return Mono.error(error);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("projectId", input.projectId().toString());
		payload.put("planSteps", input.planSteps() == null ? List.of() : input.planSteps());
		payload.put("knowledgeRefs", input.knowledgeRefs() == null ? List.of() : input.knowledgeRefs());
		String payloadJson = HypitJson.write(payload);
		String payloadHash = HypitAuthorService.sha256Hex(payloadJson);
		return commands
				.insert(accountId, "author.draft", input.requestId(), "author:" + input.projectId(), payloadHash,
						payloadJson, input.projectId())
				.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
						? Mono.<HypitCommandRepository.CommandRow>error(HypitCommandRepository.conflict(accepted.row()))
						: Mono.just(accepted.row()))
				.flatMap(command -> checkWithRepair(input, changes)
						.flatMap(outcome -> persistDraft(accountId, input, command.id(), outcome)));
	}

	private record CheckOutcome(boolean ok, int rounds, List<Map<String, Object>> diagnostics,
			List<FileChange> changes) {
	}

	/** check → 确定性修复（最多 MAX_FIX_ROUNDS 轮），诊断全量保留。 */
	private Mono<CheckOutcome> checkWithRepair(DraftInput input, List<FileChange> changes) {
		return check(input.projectId(), input.requestId(), changes).flatMap(first -> {
			if (first.ok()) {
				return Mono.just(new CheckOutcome(true, 1, first.diagnostics(), changes));
			}
			List<FileChange> repaired = repair(changes, first.diagnostics());
			return check(input.projectId(), input.requestId(), repaired).map(second -> new CheckOutcome(second.ok(), 2,
					second.ok() ? List.of() : union(first.diagnostics(), second.diagnostics()), repaired));
		});
	}

	private record SidecarCheck(boolean ok, List<Map<String, Object>> diagnostics) {
	}

	/** 真实 check：sidecar 在临时副本上编译，不经任何生成 Provider（K06.3.8）。 */
	private Mono<SidecarCheck> check(UUID projectId, UUID requestId, List<FileChange> changes) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(503, "hypit_disabled", "Hypit 引擎未启用。"));
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", projectId.toString());
		payload.put("commandId", requestId.toString());
		payload.put("baseRevision", 0);
		payload.put("applyMode", "validated");
		payload.put("changes", changes.stream().map(change -> Map.of("path", change.path(), "action", change.action(),
				"content", change.content() == null ? "" : change.content())).toList());
		return sidecar
				.commandAsync("java-author-check-" + requestId + "-" + UUID.randomUUID(), "workspace.check", payload)
				.map(receipt -> {
					if ("failed".equals(receipt.state())) {
						String message = receipt.error() == null
								? "sidecar check failed"
								: HypitJson.stringValue(receipt.error().get("message"), "sidecar check failed");
						return new SidecarCheck(false,
								List.of(Map.of("file", "main.svml", "severity", "error", "message", message)));
					}
					Map<String, Object> result = HypitJson.mapValue(receipt.result());
					List<Map<String, Object>> diagnostics = new ArrayList<>();
					Object raw = result.get("diagnostics");
					if (raw instanceof List<?> list) {
						for (Object item : list) {
							if (item instanceof Map<?, ?> map) {
								Map<String, Object> diagnostic = new HashMap<>();
								map.forEach((key, value) -> diagnostic.put(String.valueOf(key), value));
								diagnostics.add(diagnostic);
							}
						}
					}
					return new SidecarCheck(
							Boolean.TRUE.equals(result.get("ok"))
									&& diagnostics.stream().noneMatch(entry -> "error".equals(entry.get("severity"))),
							List.copyOf(diagnostics));
				});
	}

	/** 确定性修复：main.svml 重发规范化骨架（保留其余文件），绝不发明行号。 */
	private List<FileChange> repair(List<FileChange> changes, List<Map<String, Object>> diagnostics) {
		List<FileChange> repaired = new ArrayList<>();
		for (FileChange change : changes) {
			if ("main.svml".equals(change.path())) {
				repaired.add(new FileChange(change.path(), "put", MAIN_SVML_CANONICAL, null));
			} else {
				repaired.add(change);
			}
		}
		return repaired;
	}

	private List<Map<String, Object>> union(List<Map<String, Object>> first, List<Map<String, Object>> second) {
		List<Map<String, Object>> all = new ArrayList<>(first);
		all.addAll(second);
		return List.copyOf(all);
	}

	/** 通过 → validated 草稿（自带 check）；仍坏 → save 草稿保留诊断。 */
	private Mono<DraftResult> persistDraft(String accountId, DraftInput input, UUID commandId, CheckOutcome outcome) {
		String applyMode = outcome.ok() ? "validated" : "save";
		return changesets.create(accountId, input.projectId(), UUID.randomUUID(), input.baseRevision(), applyMode,
				outcome.changes()).flatMap(row -> {
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("status", outcome.ok() ? "DRAFT_PASSED" : "DRAFT_DIAGNOSTICS");
					result.put("rounds", outcome.rounds());
					result.put("changesetId", row.id().toString());
					result.put("applyMode", applyMode);
					result.put("diagnostics", outcome.diagnostics());
					return commands.saveResult(commandId, "succeeded", HypitJson.write(result))
							.then(Mono.just(new DraftResult(outcome.ok() ? "DRAFT_PASSED" : "DRAFT_DIAGNOSTICS",
									outcome.rounds(), row.id().toString(), input.baseRevision(),
									outcome.changes().stream().map(FileChange::path).toList(), outcome.diagnostics(),
									List.of())));
				});
	}

	// ------------------------------------------------------------------
	// 生成模板（真实可编译闭包；film appearance 由 style.svs 提供）
	// ------------------------------------------------------------------

	private String planJson(DraftInput input, List<Map<String, Object>> steps) {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("title", input.title() == null ? "" : input.title());
		plan.put("planSteps", steps);
		plan.put("knowledgeRefs", input.knowledgeRefs() == null ? List.of() : input.knowledgeRefs());
		return HypitJson.write(plan) + "\n";
	}

	/** 主文档：每一步映射一条 SVML 注释锚点（系统证据可回溯，17.3 词/时钟分离）。 */
	/**
	 * C107F2-17（步骤 3/RULE-10 写侧）：方案内容真实落进文档——Timeline 总时长、每步画面窗 （ColorWash
	 * start/end=方案锚点）与字幕文本（text:Value）都随方案变化，绝不输出固定 2s 模板 或只写注释；未绑定素材的步骤如实留 MISSING
	 * 材料 gap（不编造句柄）。
	 */
	private String mainSvml(List<Map<String, Object>> steps, String title) {
		double total = 0.0;
		StringBuilder body = new StringBuilder();
		int wash = 0;
		for (Map<String, Object> step : steps) {
			double start = HypitJson.doubleValue(step.get("anchorSeconds"), 0.0);
			double end = HypitJson.doubleValue(step.get("endSeconds"), start + 2.0);
			end = Math.max(end, start);
			total = Math.max(total, end);
			// 画面窗：顺序/时长随方案实际变化（首段深底、后续交替，可区分不同方案）。
			body.append("    <overlay:ColorWash id=\"step-").append(wash).append("\" z=\"0\" start=\"")
					.append(formatSeconds(start)).append("s\" end=\"").append(formatSeconds(end))
					.append("s\" color=\"#")
					.append(Integer.toHexString((0x101418 * (wash + 1)) & 0xffffff | 0x100000).substring(0, 6))
					.append("\" opacity=\"0.85\"/>\n");
			// 字幕：真实文本进文档（title 前缀 + 步骤描述），非注释差异。
			String caption = HypitJson.stringValue(step.get("caption"),
					HypitJson.stringValue(step.get("description"), ""));
			String prefix = title == null || title.isBlank() ? "" : title + "：";
			String text = prefix
					+ (caption == null || caption.isBlank() || "null".equals(caption) ? "（无字幕步骤）" : caption);
			body.append("  <text:Value id=\"caption-").append(wash).append("\">").append(escapeXml(text))
					.append("</text:Value>\n");
			// 素材绑定：方案 assetId 实际进文档（缺绑定的步骤显式 MISSING，不伪造）。
			String assetId = HypitJson.stringValue(step.get("assetId"), null);
			body.append(assetId == null || assetId.isBlank() || "null".equals(assetId)
					? "  <!-- material: MISSING（该步未绑定素材，等待补充） -->\n"
					: "  <text:Value id=\"material-").append(wash).append("\"").append(">asset:").append(assetId)
					.append("</text:Value>\n");
			wash++;
		}
		if (total <= 0.0) {
			total = 2.0;
		}
		return MAIN_SVML_TEMPLATE.formatted(formatSeconds(total), body.toString());
	}

	private static String formatSeconds(double seconds) {
		return seconds == Math.floor(seconds)
				? String.valueOf((long) seconds)
				: String.format(java.util.Locale.ROOT, "%.2f", seconds);
	}

	private static String escapeXml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static final String MAIN_SVML_CANONICAL = """
			<?svml using="@hypit/markup@1"?>
			<svml>
			  <import as="time" from="@hypit/timeline-author@1"/>
			  <import as="spatial" from="@hypit/spatial@1"/>
			  <import as="film" from="@hypit/film@1"/>
			  <import as="render" from="@hypit/render-hyperframes@1"/>
			  <import as="recipes" source="./style.svs"/>

			  <time:Clock id="clock" frame-rate="30"/>
			  <time:Timeline id="animation" clock={clock} end="2s"/>
			  <spatial:Canvas id="canvas" width="540" height="960"/>
			  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.badge}/>
			  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
			</svml>
			""";

	private static final String MAIN_SVML_TEMPLATE = """
			<?svml using="@hypit/markup@1"?>
			<svml>
			  <import as="time" from="@hypit/timeline-author@1"/>
			  <import as="spatial" from="@hypit/spatial@1"/>
			  <import as="film" from="@hypit/film@1"/>
			  <import as="render" from="@hypit/render-hyperframes@1"/>
			  <import as="overlay" from="@hypit/screen-overlay@1"/>
			  <import as="recipes" source="./style.svs"/>

			  <time:Clock id="clock" frame-rate="30"/>
			  <time:Timeline id="animation" clock={clock} end="%ss"/>
			  <spatial:Canvas id="canvas" width="540" height="960"/>
			%s  <overlay:Track id="steps" canvas={canvas} timeline={animation.timeline}/>
			  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.badge}/>
			  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
			</svml>
			""";

	private static final String STYLE_SVS = """
			<?svml using="@hypit/svs@1"?>
			<sheet version="1">
			  film.badge { background: #101418; }
			</sheet>
			""";

	private static final String MAIN_SVRUN = """
			<?svml using="@hypit/run-markup@1"?>

			<svrun version="1">
			  <author source="./main.svml"/>
			  <target output="final.video"/>
			</svrun>
			""";

	/** 17.2/17.5：自定义组件包以真实 TS 源码随变更集交付（含 Companion 声明位）。 */
	private List<FileChange> customPackageFiles(String packageName) {
		String safe = packageName.replaceAll("[^a-z0-9-]", "");
		if (safe.isBlank()) {
			throw new IntelligenceException(400, "hypit_invalid_input", "包名非法：" + packageName);
		}
		String dir = "packages/" + safe;
		String json;
		json = """
				{
				  "name": "@clone/%s",
				  "version": "1.0.0",
				  "private": true,
				  "type": "module",
				  "hypit": { "activation": "./dist/index.js", "companion": "./dist/companion.js" },
				  "exports": { ".": "./dist/index.js", "./companion": "./dist/companion.js" }
				}
				""".formatted(safe);
		String index = """
				// Generated by the C107-17 author replay: a real, compilable
				// project-package skeleton. Editable in Studio via the Companion
				// facet declared in companion.ts (built by packages.build).
				export const palette = { brand: "#2456d8" } as const;
				export const hypitPackage = { format: "hypit.node-package@1" as const };
				export default hypitPackage;
				""";
		String companion = """
				// Companion facet skeleton: declares the Inspector surface the
				// Studio panel renders for this package's entities.
				export const companions = [{ id: "%s", inspector: [] }] as const;
				""".formatted(safe);
		return List.of(new FileChange(dir + "/package.json", "put", json, null),
				new FileChange(dir + "/src/index.ts", "put", index, null),
				new FileChange(dir + "/src/companion.ts", "put", companion, null));
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
}
