package com.grassland.intelligence.hypit.build;

import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.execution.HypitExecutionRepository;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 持久 Build 服务（任务书 #107-2 C107-09 / REQ-107-09）。
 *
 * <p>
 * 09.1：owner/plan/revision/grant 校验后短事务创建公共 Build + command + job，立即 202—— HTTP 请求路径绝不等待成片。 09.4/09.6：
 * 观察收敛 lifecycle/outcome 前进式 CAS + 事件投影（checkpoint 承载上次摘要，重复观察零事件、终帧一次）。 09.7：取消幂等—— 终态重复取消返回现状；未提交即取消如实标
 * submission_incomplete+cancelled，不伪造远程取消回执。 sidecar 派发只在 worker（HypitBuildObserver）与取消/日志/刷新的 有界调用里发生。
 */
@Service
public class HypitBuildService {

	/** 观察命令的引擎侧超时（GET 刷新路径用短超时，失败回落已存事实）。 */
	private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(30);

	public static final String JOB_KIND = "hypit.build";

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitProjectRepository projects;
	private final HypitPlanService plans;
	private final HypitExecutionRepository executions;
	private final HypitCommandRepository commands;
	private final HypitBuildRepository builds;
	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;

	public HypitBuildService(HypitProperties properties, HypitSidecarClient sidecar, HypitProjectRepository projects,
			HypitPlanService plans, HypitExecutionRepository executions, HypitCommandRepository commands,
			HypitBuildRepository builds, HypitJobRepository jobs, HypitJobEventRepository events) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.projects = projects;
		this.plans = plans;
		this.executions = executions;
		this.commands = commands;
		this.builds = builds;
		this.jobs = jobs;
		this.events = events;
	}

	public record SubmitView(BuildRow build, JobRow job, boolean replayed) {
	}

	/** 09.1 提交：短事务幂等创建；重复 requestId 返回同资源。 */
	public Mono<SubmitView> submit(String accountId, UUID projectId, UUID requestId, UUID planId, UUID grantId,
			String title) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填"));
		}
		if (title != null && (title.isBlank() || title.length() > 120)) {
			return Mono.error(invalid("title 长度须在 1–120 字"));
		}
		return requireReadyProject(accountId, projectId)
				.flatMap(project -> plans.requirePlanFresh(project, planId)
						.flatMap(plan -> requireGrantIfNeeded(projectId, plan, grantId)
								.then(Mono.defer(() -> acceptCommand(accountId, project, plan, requestId, grantId,
										title)))));
	}

	/** 09.4/09.8：合并引擎观察并前进式落库；GET 与 worker 共用。 */
	public Mono<BuildRow> converge(BuildRow build) {
		return Mono.just(build)
				.flatMap(row -> terminal(row.lifecycle()) || row.engineBuildId() == null || !engineAvailable()
						? Mono.just(row)
						: observeOnce(row).flatMap(observation -> applyObservation(row, observation))
								.onErrorResume(error -> Mono.just(row)));
	}

	/** 读取当前 Build（先尝试一次有界刷新，失败回落已存事实）。 */
	public Mono<BuildRow> ownedBuild(String accountId, UUID buildId) {
		return builds.findOwned(accountId, buildId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(this::converge);
	}

	public Mono<List<BuildRow>> listOwned(String accountId, UUID projectId, int limit) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.thenMany(builds.listByProject(projectId, limit)).collectList();
	}

	/** 09.7 取消：终态重复取消零副作用；未提交直接收敛 cancelled；已提交 best-effort 远程取消。 */
	public Mono<BuildRow> cancel(String accountId, UUID buildId, UUID requestId, String reason) {
		if (reason != null && reason.length() > 500) {
			return Mono.error(invalid("reason 最长 500 字"));
		}
		return builds.findOwned(accountId, buildId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(build -> {
					if (terminal(build.lifecycle())) {
						return Mono.just(build);
					}
					if (build.engineBuildId() == null) {
						// 提交尚未到达引擎：没有远程工作可停，就地取消并投影终帧事件。
						return builds
								.advance(build.id(), build.lifecycle(), "submission_incomplete", "cancelled", null,
										Instant.now())
								.then(projectEvent(build, "submission_incomplete", "cancelled", Map.of()))
								.then(builds.findById(build.id()));
					}
					if (!engineAvailable()) {
						return Mono.error(HypitAccessService.unavailable("构建取消"));
					}
					Map<String, Object> payload = new HashMap<>();
					payload.put("projectId", build.projectId().toString());
					payload.put("engineBuildId", build.engineBuildId());
					payload.put("previousOutcome", build.outcome());
					if (reason != null) {
						payload.put("reason", reason);
					}
					return sidecar.commandAsync("build-cancel-" + UUID.randomUUID(), "build.cancel", payload)
							.timeout(CANCEL_TIMEOUT)
							.onErrorMap(error -> new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
					"hypit_backend_unavailable", "引擎不可达，取消将在重试后收敛"))
							.flatMap(command -> applyObservation(build,
									HypitJson.mapValue(requireResult(command, "build.cancel")))
									.then(builds.findById(build.id())));
				});
	}

	/** 09.9 日志：脱敏分页（sidecar 已清洗路径/签名 URL/密钥）；未提交返回空页。 */
	public Mono<Map<String, Object>> logs(String accountId, UUID buildId, Integer cursor, Integer limit) {
		return builds.findOwned(accountId, buildId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(build -> {
					if (build.engineBuildId() == null || !engineAvailable()) {
						Map<String, Object> empty = new HashMap<>();
						empty.put("records", List.of());
						empty.put("nextCursor", null);
						empty.put("total", 0);
						empty.put("sources", List.of());
						return Mono.just(empty);
					}
					Map<String, Object> payload = new HashMap<>();
					payload.put("projectId", build.projectId().toString());
					payload.put("engineBuildId", build.engineBuildId());
					payload.put("cursor", cursor == null || cursor < 0 ? 0 : cursor);
					payload.put("limit", limit == null || limit < 1 ? 100 : Math.min(limit, 1000));
					return sidecar.commandAsync("build-logs-" + UUID.randomUUID(), "build.logs", payload)
							.timeout(INSPECT_TIMEOUT)
							.map(command -> HypitJson.mapValue(requireResult(command, "build.logs")))
							.onErrorMap(error -> new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
									"hypit_backend_unavailable", "引擎不可达，日志暂不可读"));
				});
	}

	/** Build SSE 复用其 submit job 的事件流（K09.3 游标语义）。job id 与 commandId 分离，按 command 反查。 */
	public Mono<JobRow> jobFor(BuildRow build) {
		return builds.jobIdByCommand(build.commandId()).flatMap(jobs::findById);
	}

	/**
	 * 09.9 operator 全局 activity：以 DB 可观察 Build 分组工程（封顶 10 个），逐工程聚合 sidecar
	 * build.activity（含 broker 渲染闸与原生加权预约）；引擎不可达时只回 DB 事实，不伪造。
	 */
	public Mono<Map<String, Object>> globalActivity() {
		return builds.findObservable(200).collectList().flatMap(rows -> {
			List<UUID> projectIds = rows.stream().map(BuildRow::projectId).distinct().limit(10).toList();
			if (!engineAvailable()) {
				return Mono.just(Map.<String, Object>of("engine", false, "observableBuilds", rows.size(), "projects",
						List.of()));
			}
			return Flux.fromIterable(projectIds)
					.flatMap(projectId -> sidecar
							.commandAsync("build-activity-" + UUID.randomUUID(), "build.activity",
									Map.of("projectId", projectId.toString()))
							.timeout(INSPECT_TIMEOUT).onErrorResume(error -> Mono.just(null))
							.map(command -> Map.<String, Object>of("projectId", projectId.toString(), "activity",
									command != null && command.result() != null
											? HypitJson.mapValue(command.result())
											: Map.of())))
					.collectList()
					.map(activities -> Map.<String, Object>of("engine", true, "observableBuilds", rows.size(),
							"projects", (Object) activities));
		});
	}

	/**
	 * 09.9 operator 全局 logs：跨可观察且已提交引擎的 Build（封顶 8 个）各取一页脱敏日志；
	 * 单 Build 失败不拖垮整体（该条目标 error）。普通用户只能走 /builds/{id}/logs 看自己。
	 */
	public Mono<Map<String, Object>> globalLogs(Integer cursor, Integer limit) {
		int pageCursor = cursor == null || cursor < 0 ? 0 : cursor;
		int pageLimit = limit == null || limit < 1 ? 50 : Math.min(limit, 200);
		return builds.findObservable(200).filter(row -> row.engineBuildId() != null).limitRate(8).collectList()
				.flatMap(rows -> {
					if (rows.isEmpty() || !engineAvailable()) {
						return Mono.just(Map.<String, Object>of("entries", List.of(), "cursor", pageCursor));
					}
					return Flux.fromIterable(rows)
							.flatMap(build -> sidecar
									.commandAsync("build-logs-" + UUID.randomUUID(), "build.logs",
											logsPayload(build, pageCursor, pageLimit))
									.timeout(INSPECT_TIMEOUT).onErrorResume(error -> Mono.just(null))
									.map(command -> {
										Map<String, Object> entry = new HashMap<>();
										entry.put("buildId", build.id().toString());
										entry.put("projectId", build.projectId().toString());
										entry.put("lifecycle", build.lifecycle());
										if (command != null && command.result() != null) {
											entry.put("page", HypitJson.mapValue(command.result()));
										} else {
											entry.put("page", Map.of());
										}
										return (Map<String, Object>) entry;
									}))
							.collectList()
							.map(entries -> Map.<String, Object>of("entries", (Object) entries, "cursor", pageCursor));
				});
	}

	private static Map<String, Object> logsPayload(BuildRow build, int cursor, int limit) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", build.projectId().toString());
		payload.put("engineBuildId", build.engineBuildId());
		payload.put("cursor", cursor);
		payload.put("limit", limit);
		return payload;
	}

	// ---------- 内部 ----------

	private Mono<SubmitView> acceptCommand(String accountId, ProjectRow project, PlanRow plan, UUID requestId,
			UUID grantId, String title) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", project.id().toString());
		payload.put("planId", plan.id().toString());
		payload.put("revision", plan.revision());
		payload.put("runFile", plan.runFile());
		if (title != null) {
			payload.put("title", title);
		}
		String payloadJson = HypitJson.write(payload);
		String payloadHash = HypitExternalExecutionBridge.sha256Hex(payloadJson);
		return commands.insert(accountId, "build.submit", requestId, "build:" + project.id(), payloadHash, payloadJson,
				project.id()).flatMap(accepted -> {
					if (accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)) {
						return Mono.error(HypitCommandRepository.conflict(accepted.row()));
					}
					if (accepted.existing()) {
						return builds.findByCommandId(accepted.row().id())
								.flatMap(build -> builds.jobIdByCommand(accepted.row().id()).flatMap(jobs::findById)
										.map(job -> new SubmitView(build, job, true)));
					}
					UUID buildId = UUID.randomUUID();
					UUID jobId = UUID.randomUUID();
					return builds.insert(buildId, accepted.row().id(), project.id(), plan.revision(), plan.id(),
							plan.runFile()).flatMap(build -> jobs.insert(new JobRow(jobId, accepted.row().id(),
							project.id(), accountId, JOB_KIND, "queued", "pending", null, "{}", plan.revision(),
							grantId, 0, 1, null, null, 1, null, null, null, null, null, null))
							.flatMap(job -> events
									.append(jobId, "snapshot", HypitJson.write(Map.of("buildId",
											build.id().toString(), "lifecycle", build.lifecycle())))
									.map(event -> new SubmitView(build, job, false))));
				});
	}

	/** 远程付费请求必须有授权（K12/D-06）；全本地计划 grant 可空。 */
	private Mono<Void> requireGrantIfNeeded(UUID projectId, PlanRow plan, UUID grantId) {
		Map<String, Object> planDoc = HypitJson.read(plan.planJson());
		List<Map<String, Object>> providers = mapList(planDoc.get("providers"));
		List<String> missing = stringList(planDoc.get("missingCapabilities"));
		if (!missing.isEmpty()) {
			return Mono.error(new IntelligenceException(422, "hypit_unsupported_capability",
					"计划缺能力：" + String.join(",", missing)));
		}
		boolean needsRemoteGrant = providers.stream().anyMatch(provider -> "resolved".equals(provider.get("status"))
				&& provider.get("pricing") instanceof Map<?, ?> pricing && "page".equals(pricing.get("kind")));
		if (!needsRemoteGrant) {
			return grantId == null ? Mono.empty()
					: validateGrant(projectId, plan, grantId).then();
		}
		if (grantId == null) {
			return Mono.error(invalid("该计划含远程生成请求，必须携带 grantId"));
		}
		return validateGrant(projectId, plan, grantId);
	}

	private Mono<Void> validateGrant(UUID projectId, PlanRow plan, UUID grantId) {
		return executions.findGrant(grantId).switchIfEmpty(Mono.error(invalid("授权不存在"))).flatMap(grant -> {
			if (!grant.projectId().equals(projectId)) {
				return Mono.error(invalid("授权不属于当前工程"));
			}
			if (grant.revokedAt() != null) {
				return Mono.error(invalid("授权已撤销"));
			}
			if (grant.expiresAt().isBefore(Instant.now())) {
				return Mono.error(invalid("授权已过期"));
			}
			if (grant.planId() != null && !grant.planId().equals(plan.id())) {
				return Mono.error(invalid("授权绑定的计划与当前计划不一致"));
			}
			try {
				HypitPlanService.requireGrantCovers(plan, grant.scopeJson());
			} catch (IntelligenceException error) {
				return Mono.<Void>error(invalid("授权 scope 未覆盖计划目标"));
			}
			return Mono.empty();
		});
	}

	/** 一次引擎观察（fresh commandId——读命令不做幂等缓存）。 */
	public Mono<Map<String, Object>> observeOnce(BuildRow build) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", build.projectId().toString());
		payload.put("engineBuildId", build.engineBuildId());
		if (build.outcome() != null) {
			payload.put("previousOutcome", build.outcome());
		}
		return sidecar.commandAsync("build-inspect-" + UUID.randomUUID(), "build.inspect", payload)
				.timeout(INSPECT_TIMEOUT)
				.map(command -> HypitJson.mapValue(requireResult(command, "build.inspect")));
	}

	/**
	 * 前进式落库 + 事件投影。事件摘要持久在 job checkpoint（{lifecycle,outcome,outputCount}），
	 * 与 B 侧 observationDelta 同规则：重复观察零事件、终帧一次、状态不倒退。
	 */
	public Mono<BuildRow> applyObservation(BuildRow build, Map<String, Object> observation) {
		Boolean found = (Boolean) observation.get("found");
		if (found == null || !found) {
			return Mono.just(build);
		}
		String lifecycle = HypitJson.stringValue(observation.get("lifecycle"), build.lifecycle());
		String outcome = HypitJson.stringValue(observation.get("outcome"), null);
		String engineId = HypitJson.stringValue(observation.get("engineBuildId"), null);
		String finishedAt = HypitJson.stringValue(observation.get("finishedAt"), null);
		if ("finished".equals(build.lifecycle()) && !"finished".equals(lifecycle)) {
			return Mono.just(build); // 终态不倒退
		}
		Instant finished = finishedAt == null ? null : Instant.parse(finishedAt);
		return builds.advance(build.id(), build.lifecycle(), lifecycle, outcome, engineId, finished)
				.flatMap(advanced -> advanced ? projectEvent(build, lifecycle, outcome, observation)
						: Mono.just(false))
				.then(builds.findById(build.id()));
	}

	/** 事件投影 + job checkpoint 更新；终帧一并收口 job 状态（succeeded≠成片成功，详情带 outcome）。 */
	private Mono<Boolean> projectEvent(BuildRow build, String lifecycle, String outcome,
			Map<String, Object> observation) {
		int outputCount = observation.get("outputNames") instanceof List<?> list ? list.size() : 0;
		return builds.jobIdByCommand(build.commandId()).flatMap(jobId -> jobs.findById(jobId).flatMap(job -> {
			Map<String, Object> previous = job.checkpointJson() == null ? Map.of()
					: HypitJson.read(job.checkpointJson());
			String previousLifecycle = HypitJson.stringValue(previous.get("lifecycle"), null);
			String previousOutcome = HypitJson.stringValue(previous.get("outcome"), null);
			int previousOutputs = previous.get("outputCount") instanceof Number number ? number.intValue() : -1;
			boolean progressed = previousLifecycle == null
					|| HypitBuildRepository.lifecycleRank(lifecycle) > HypitBuildRepository
							.lifecycleRank(previousLifecycle)
					|| (outcome != null && previousOutcome == null)
					|| outputCount > previousOutputs;
			if (!progressed) {
				return Mono.just(false);
			}
			boolean terminal = outcome != null;
			Map<String, Object> data = new HashMap<>();
			data.put("lifecycle", lifecycle);
			data.put("outcome", outcome);
			data.put("outputCount", outputCount);
			data.put("engineBuildId", build.engineBuildId());
			String payload = HypitJson.write(data);
			var append = terminal ? events.append(job.id(), "terminal", payload)
					: events.append(job.id(), "progress", payload);
			var afterEvent = terminal ? append.then(jobs.saveCheckpoint(job.id(), payload))
					.then(jobs.updateState(job.id(), jobStateFor(lifecycle, outcome), null, null))
					: append.then(jobs.saveCheckpoint(job.id(), payload));
			return afterEvent.thenReturn(true);
		}));
	}

	/**
	 * 终帧 job 收口：cancelled→cancelled；submission_incomplete 未定→waiting_input（等运营/用户决断）；
	 * finished（complete/failed）→succeeded——只代表观察操作完成，成片成败以事件详情里的 Build outcome 为准。
	 */
	private static String jobStateFor(String lifecycle, String outcome) {
		if ("cancelled".equals(outcome)) {
			return "cancelled";
		}
		return "submission_incomplete".equals(lifecycle) ? "waiting_input" : "succeeded";
	}

	private static Object requireResult(HypitSidecarClient.SidecarCommand command, String what) {
		if (command.result() == null) {
			throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
					what + " 命令失败：" + (command.error() == null ? "无诊断"
							: command.error().get("code") + ":" + command.error().get("message")));
		}
		return command.result();
	}

	private boolean engineAvailable() {
		return properties.enabled() && sidecar.configured();
	}

	public static boolean terminal(String lifecycle) {
		return "finished".equals(lifecycle) || "submission_incomplete".equals(lifecycle);
	}

	private Mono<ProjectRow> requireReadyProject(String accountId, UUID projectId) {
		return projects.findOwned(accountId, projectId).filter(project -> !"deleted".equals(project.status()))
				.switchIfEmpty(Mono.error(HypitAccessService.notFound()));
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().map(String::valueOf).toList();
	}

	private static List<Map<String, Object>> mapList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().filter(item -> item instanceof Map<?, ?>)
				.map(item -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> casted = (Map<String, Object>) item;
					return casted;
				}).toList();
	}
}
