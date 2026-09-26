package com.grassland.intelligence.hypit.build;

import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitChangesetService;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Result 面（任务书 #107-2 C107-10）：索引、导出、展示补写、finish/discard、
 * 输出历史与显式复用。公共 UUID→engineBuildId 只在服务端映射；所有引擎读取
 * 经 sidecar 命令域（results.*），原始 plan/pricing 快照随结果详情返回（步骤 10）。
 */
@Service
public class HypitResultService {

	private static final Duration RESULTS_TIMEOUT = Duration.ofSeconds(60);

	private final HypitBuildService builds;
	private final HypitBuildRepository buildRepo;
	private final HypitOutputRepository outputs;
	private final HypitPlanRepository plans;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final HypitProjectRepository projects;
	private final HypitChangesetService changesets;

	public HypitResultService(HypitBuildService builds, HypitBuildRepository buildRepo, HypitOutputRepository outputs,
			HypitPlanRepository plans, HypitSidecarClient sidecar, HypitProperties properties,
			HypitProjectRepository projects, HypitChangesetService changesets) {
		this.builds = builds;
		this.buildRepo = buildRepo;
		this.outputs = outputs;
		this.plans = plans;
		this.sidecar = sidecar;
		this.properties = properties;
		this.projects = projects;
		this.changesets = changesets;
	}

	// ---------- 索引（步骤 1：结果 discover 后幂等入公共索引） ----------

	/** 把 sidecar results.read 的公共 Output 幂等索引进 hypit_output（可重入，UNIQUE(build,output)）。 */
	public Mono<List<OutputRow>> syncOutputs(BuildRow build) {
		if (!engineAvailable()) {
			return outputs.findByBuildList(build.id());
		}
		return sidecar
				.commandAsync("results-index-" + build.id(), "results.read",
						Map.of("projectId", build.projectId().toString(), "engineBuildId", build.engineBuildId()))
				.timeout(RESULTS_TIMEOUT)
				.map(command -> HypitJson.mapValue(requireResult(command, "results.read")))
				.flatMap(document -> indexDocument(build, document))
				.onErrorResume(error -> outputs.findByBuildList(build.id()));
	}

	private Mono<List<OutputRow>> indexDocument(BuildRow build, Map<String, Object> document) {
		Object outputsValue = document.get("outputs");
		if (!(outputsValue instanceof List<?> list)) {
			return outputs.findByBuildList(build.id());
		}
		Mono<List<OutputRow>> chain = Mono.just(new ArrayList<OutputRow>());
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> output = (Map<String, Object>) raw;
			String name = String.valueOf(output.get("name"));
			String kind = String.valueOf(output.get("kind"));
			String mediaType = output.get("mediaType") == null ? null : String.valueOf(output.get("mediaType"));
			Long size = output.get("size") instanceof Number number ? number.longValue() : null;
			Map<String, Object> summary = new HashMap<>();
			summary.put("type", output.get("type"));
			summary.put("displayName", output.get("displayName"));
			summary.put("highlighted", output.get("highlighted"));
			final String fName = name;
			final String fKind = kind;
			final String fMedia = mediaType;
			final Long fSize = size;
			final String fSummary = HypitJson.write(summary);
			chain = chain.flatMap(rows -> outputs
					.insert(UUID.randomUUID(), build.id(), fName, fKind, fMedia, fSize, fSummary)
					.map(row -> {
						rows.add(row);
						return rows;
					}));
		}
		return chain;
	}

	// ---------- Outputs 列表 + 结果详情快照（步骤 10） ----------

	public record ResultView(BuildRow build, List<OutputRow> outputs, Map<String, Object> planSnapshot) {
	}

	public Mono<ResultView> resultDetail(String accountId, UUID buildId) {
		return builds.ownedBuild(accountId, buildId).flatMap(build -> syncOutputs(build).flatMap(rows -> {
			if (build.planId() == null) {
				return Mono.just(new ResultView(build, rows, Map.of()));
			}
			return plans.findPlanById(build.planId()).map(plan -> new ResultView(build, rows, planSnapshot(build, plan)))
					.defaultIfEmpty(new ResultView(build, rows, Map.of()));
		}));
	}

	/** 原提交时的 plan/pricing 快照，绝不重读当前 Profile 改写历史成本。 */
	private static Map<String, Object> planSnapshot(BuildRow build, PlanRow plan) {
		Map<String, Object> snapshot = new HashMap<>();
		snapshot.put("planId", plan.id().toString());
		snapshot.put("planHash", plan.planHash());
		snapshot.put("revision", plan.revision());
		snapshot.put("planJson", HypitJson.read(plan.planJson()));
		if (plan.pricingJson() != null) {
			snapshot.put("pricingJson", HypitJson.read(plan.pricingJson()));
		}
		return snapshot;
	}

	// ---------- 导出（T10-1：Scalar JSON / Resource 真实字节 / Composite 打包） ----------

	public record ExportView(String kind, String mediaType, String valueJson, String resourceHandle, String sha256,
			Long sizeBytes, byte[] bytes) {
	}

	public Mono<ExportView> exportOutput(String accountId, UUID buildId, String outputName) {
		return builds.ownedBuild(accountId, buildId).flatMap(build -> {
			if (build.engineBuildId() == null) {
				return Mono.error(HypitResultService.invalid("该 Build 从未到达引擎，无输出可导出"));
			}
			Map<String, Object> payload = new HashMap<>();
			payload.put("projectId", build.projectId().toString());
			payload.put("engineBuildId", build.engineBuildId());
			payload.put("output", outputName);
			return sidecar
					.commandAsync("results-export-" + build.id() + "-" + outputName, "results.export", payload)
					.timeout(RESULTS_TIMEOUT)
					.map(command -> HypitJson.mapValue(requireResult(command, "results.export")))
					.flatMap(exported -> materializeExport(build, outputName, exported));
		});
	}

	private Mono<ExportView> materializeExport(BuildRow build, String outputName, Map<String, Object> exported) {
		String kind = String.valueOf(exported.get("kind"));
		String mediaType = exported.get("mediaType") == null ? "application/octet-stream"
				: String.valueOf(exported.get("mediaType"));
		return switch (kind) {
			case "scalar" -> Mono.just(new ExportView(kind, "application/json",
					HypitJson.write(exported.get("value")), null, null, null, null));
			case "composite" -> Mono.just(new ExportView(kind, "application/json",
					HypitJson.write(Map.of("value", HypitJson.read(String.valueOf(exported.get("valueDocument"))),
							"files", exported.get("files"))),
					null, null, null, null));
			case "resource" -> com.grassland.intelligence.hypit.asset.HypitArchiveDownloader
					.download(sidecarBaseUrl(), internalToken(), String.valueOf(exported.get("handle")),
							200L * 1024 * 1024)
					.map(bytes -> new ExportView(kind, bytes.mediaType(), null, bytes.handle(),
							String.valueOf(exported.get("sha256")),
							Long.valueOf(exported.get("size") instanceof Number number
									? number.longValue()
									: (long) bytes.value().length),
							bytes.value()));
			case "external-file" -> Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(),
					"hypit_external_unavailable", "Output " + outputName + " 依赖外部资源 "
							+ exported.get("uri") + "，当前不可达，拒绝导出空包"));
			default -> Mono.error(invalid("未知导出类型 " + kind));
		};
	}

	// ---------- 展示补写（不改 Output 身份） ----------

	public Mono<Map<String, Object>> updatePresentation(String accountId, UUID buildId, Map<String, Object> update) {
		return builds.ownedBuild(accountId, buildId).flatMap(build -> {
			if (build.engineBuildId() == null) {
				return Mono.error(invalid("该 Build 从未到达引擎"));
			}
			Map<String, Object> payload = new HashMap<>(update);
			payload.put("projectId", build.projectId().toString());
			payload.put("engineBuildId", build.engineBuildId());
			return sidecar
					.commandAsync("results-presentation-" + build.id(), "results.presentation", payload)
					.timeout(RESULTS_TIMEOUT)
					.map(command -> HypitJson.mapValue(requireResult(command, "results.presentation")));
		});
	}

	// ---------- finish / discard（适用性失败 409，禁止转 build） ----------

	public Mono<Map<String, Object>> resultAction(String accountId, UUID buildId, String action) {
		if (!"finish".equals(action) && !"discard".equals(action)) {
			return Mono.error(invalid("action 必须是 finish 或 discard"));
		}
		return builds.ownedBuild(accountId, buildId).flatMap(build -> {
			if (build.engineBuildId() == null) {
				return Mono.error(invalid("该 Build 从未到达引擎，无 submission 可操作"));
			}
			Map<String, Object> payload = new HashMap<>();
			payload.put("projectId", build.projectId().toString());
			payload.put("engineBuildId", build.engineBuildId());
			return sidecar
					.commandAsync("results-action-" + action + "-" + build.id(), "results." + action, payload)
					.timeout(RESULTS_TIMEOUT)
					.map(command -> {
						if (command.result() == null && command.error() != null
								&& "submission_not_discardable".equals(String.valueOf(command.error().get("code")))) {
							throw new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
									"submission 已激活，不能 discard");
						}
						return HypitJson.mapValue(requireResult(command, "results." + action));
					});
		});
	}

	// ---------- 输出历史（步骤 4：跨 Build 按 Output 名/Source 筛选分页） ----------

	public Mono<List<OutputRow>> outputHistory(String accountId, UUID projectId, String outputName, int limit) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.thenMany(outputs.historyByProject(projectId, outputName, Math.min(Math.max(limit, 1), 100)))
				.collectList();
	}

	// ---------- 显式复用（步骤 8：changeset，不偷偷覆盖 Run） ----------

	public Mono<HypitChangesetService.ChangesetRow> proposeReuse(String accountId, UUID projectId, UUID requestId,
			Long baseRevision, String runFile, List<Map<String, Object>> selections) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(project -> {
					Map<String, Object> payload = new HashMap<>();
					payload.put("projectId", projectId.toString());
					payload.put("runFile", runFile == null || runFile.isBlank() ? "main.svrun" : runFile);
					payload.put("selections", selections);
					return sidecar
							.commandAsync("results-reuse-" + UUID.randomUUID(), "results.reuse", payload)
							.timeout(RESULTS_TIMEOUT)
							.map(command -> HypitJson.mapValue(requireResult(command, "results.reuse")))
							.flatMap(reuse -> {
								Object changes = reuse.get("changes");
								if (!(changes instanceof List<?> list) || list.isEmpty()) {
									return Mono.error(invalid("复用提案没有产生变更"));
								}
								List<FileChange> fileChanges = new ArrayList<>();
								for (Object item : list) {
									if (item instanceof Map<?, ?> raw) {
										fileChanges.add(new FileChange(String.valueOf(raw.get("path")),
												String.valueOf(raw.get("action")), String.valueOf(raw.get("content")),
												null));
									}
								}
								return changesets.create(accountId, projectId, requestId,
										baseRevision == null ? 0L : baseRevision, "save", fileChanges);
							});
				});
	}

	// ---------- helpers ----------

	private boolean engineAvailable() {
		return properties.enabled() && sidecar.configured();
	}

	private String sidecarBaseUrl() {
		return properties.sidecarBaseUrl();
	}

	private String internalToken() {
		return properties.internalToken();
	}

	private static Object requireResult(HypitSidecarClient.SidecarCommand command, String what) {
		if (command.result() == null) {
			throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
					what + " 命令失败：" + (command.error() == null ? "无诊断"
							: command.error().get("code") + ":" + command.error().get("message")));
		}
		return command.result();
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}
}
