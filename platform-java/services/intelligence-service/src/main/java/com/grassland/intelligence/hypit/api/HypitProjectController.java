package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.api.HypitDtos.Project;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitJobService;
import com.grassland.intelligence.hypit.project.HypitChangesetService;
import com.grassland.intelligence.hypit.project.HypitChangesetService.ChangesetRow;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.project.HypitProjectService;
import com.grassland.intelligence.hypit.project.HypitProjectService.CreateResult;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 工程面端点（任务书 #107-1 §6.2；C107-03 声明、C107-04 落地本卡范围：工程 CRUD、
 * 文件树/单文件、变更集；jobs 查询/SSE 同步交付）。 契约：contracts/hypit-api.v1.json；未实现端点仍如实
 * 503，不伪造数据。
 */
@RestController
public class HypitProjectController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;
	private final HypitProjectService projects;
	private final HypitChangesetService changesets;
	private final HypitJobService jobService;
	private final com.grassland.intelligence.hypit.build.HypitResultService results;
	private final com.grassland.intelligence.hypit.agent.HypitClonePlanService clonePlans;
	private final com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar;
	private final com.grassland.intelligence.hypit.variant.HypitVariantService variants;
	private final com.grassland.intelligence.hypit.template.HypitProjectPackageService packageService;

	public HypitProjectController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitProjectService projects, HypitChangesetService changesets,
			HypitJobService jobService, com.grassland.intelligence.hypit.build.HypitResultService results,
			com.grassland.intelligence.hypit.agent.HypitClonePlanService clonePlans,
			com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar,
			com.grassland.intelligence.hypit.variant.HypitVariantService variants,
			com.grassland.intelligence.hypit.template.HypitProjectPackageService packageService) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.projects = projects;
		this.changesets = changesets;
		this.jobService = jobService;
		this.results = results;
		this.clonePlans = clonePlans;
		this.sidecar = sidecar;
		this.variants = variants;
		this.packageService = packageService;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	private static UUID requireUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new com.grassland.intelligence.security.IntelligenceException(400, "hypit_invalid_input", "id 格式非法。");
		}
	}

	// ------------------------------------------------------------------
	// 工程列表 / 创建
	// ------------------------------------------------------------------

	@GetMapping("/api/hypit/projects")
	public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam(defaultValue = "20") int limit,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> projects.list(caller.accountId(), limit))
				.map(items -> ResponseEntity.ok(HypitDtos.success(Map.of("items", items))));
	}

	@PostMapping("/api/hypit/projects")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> projects.create(caller.accountId(), body.requestId(), body.title(), body.mode(),
						body.templateId(), sourceContextJson(body.sourceContext())))
				.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(HypitDtos.success(
								Map.of("project", result.project(), "job", Map.of("jobId", result.jobId().toString(),
										"state", result.jobState(), "resourceId", result.project().id())))));
	}

	/** C107-22：契约 wire 形态是对象 {kind,id,label}；服务层核验用 canonical JSON 文本。 */
	static String sourceContextJson(Map<String, Object> sourceContext) {
		return sourceContext == null || sourceContext.isEmpty()
				? null
				: com.grassland.intelligence.hypit.project.HypitJson.write(sourceContext);
	}

	public record CreateRequest(UUID requestId, String title, String mode, String templateId,
			Map<String, Object> sourceContext) {
	}

	@GetMapping("/api/hypit/projects/{projectId}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String projectId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> projects.get(caller.accountId(), requireUuid(projectId)))
				.map(project -> ResponseEntity.ok(HypitDtos.success(project)));
	}

	@PatchMapping("/api/hypit/projects/{projectId}")
	public Mono<ResponseEntity<Map<String, Object>>> patch(@PathVariable String projectId,
			@RequestBody PatchRequest body, ServerWebExchange exchange) {
		return callers
				.resolve(exchange.getRequest()).flatMap(caller -> projects.patchTitle(caller.accountId(),
						requireUuid(projectId), body.title(), body.baseVersion()))
				.map(project -> ResponseEntity.ok(HypitDtos.success(project)));
	}

	public record PatchRequest(UUID requestId, String title, Long baseVersion) {
	}

	@DeleteMapping("/api/hypit/projects/{projectId}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String projectId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> projects.delete(caller.accountId(), requireUuid(projectId)))
				.map(ignored -> ResponseEntity.ok(HypitDtos.success(Map.of("deleted", true))));
	}

	// ------------------------------------------------------------------
	// 文件 / 变更集
	// ------------------------------------------------------------------

	@GetMapping("/api/hypit/projects/{projectId}/files")
	public Mono<ResponseEntity<Map<String, Object>>> files(@PathVariable String projectId,
			@RequestParam(required = false) String path, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> projects.files(caller.accountId(), requireUuid(projectId)))
				.map(result -> ResponseEntity.ok(HypitDtos.success(result)));
	}

	@GetMapping("/api/hypit/projects/{projectId}/file")
	public Mono<ResponseEntity<Map<String, Object>>> file(@PathVariable String projectId, @RequestParam String path,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> projects.file(caller.accountId(), requireUuid(projectId), path))
				.map(result -> ResponseEntity.ok(HypitDtos.success(result)));
	}

	public record ChangesetRequest(UUID requestId, Long baseRevision, String applyMode, List<FileChange> changes) {
	}

	@PostMapping("/api/hypit/projects/{projectId}/changesets")
	public Mono<ResponseEntity<Map<String, Object>>> createChangeset(@PathVariable String projectId,
			@RequestBody ChangesetRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> changesets.create(caller.accountId(), requireUuid(projectId), body.requestId(),
						body.baseRevision() == null ? 0L : body.baseRevision(), body.applyMode(), body.changes()))
				.map(row -> ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(changesetView(row))));
	}

	@PostMapping("/api/hypit/projects/{projectId}/changesets/{id}/apply")
	public Mono<ResponseEntity<Map<String, Object>>> applyChangeset(@PathVariable String projectId,
			@PathVariable String id, @RequestBody ApplyRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> changesets.apply(caller.accountId(), requireUuid(projectId), requireUuid(id),
						body.requestId(), body.baseRevision() == null ? 0L : body.baseRevision()))
				.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(HypitDtos.success(Map.of("revision", result.revision(), "manifestHash",
								result.manifestHash(), "appliedPaths", result.appliedPaths()))));
	}

	public record ApplyRequest(UUID requestId, Long baseRevision) {
	}

	private static Map<String, Object> changesetView(ChangesetRow row) {
		return Map.of("changesetId", row.id().toString(), "baseRevision", row.baseRevision(), "applyMode",
				row.applyMode(), "checkStatus", row.checkStatus(), "state", row.state());
	}

	// ------------------------------------------------------------------
	// 任务查询 / SSE（C04 范围；动作类端点仍 503——卡 14 接）
	// ------------------------------------------------------------------

	@GetMapping("/api/hypit/projects/{projectId}/jobs/{jobId}")
	public Mono<ResponseEntity<Map<String, Object>>> projectJob(@PathVariable String projectId,
			@PathVariable String jobId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> jobService.ownedJob(caller.accountId(), requireUuid(projectId), requireUuid(jobId)))
				.map(job -> ResponseEntity.ok(HypitDtos.success(HypitJobService.toDto(job))));
	}

	@GetMapping("/api/hypit/projects/{projectId}/jobs/{jobId}/events")
	public reactor.core.publisher.Flux<ServerSentEvent<String>> projectJobEvents(@PathVariable String projectId,
			@PathVariable String jobId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMapMany(caller -> jobService.tailing(caller.accountId(),
				requireUuid(projectId), requireUuid(jobId), lastEventId));
	}

	@GetMapping("/api/hypit/jobs/{jobId}")
	public Mono<ResponseEntity<Map<String, Object>>> globalJob(@PathVariable String jobId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> jobService.jobById(caller, access, requireUuid(jobId)))
				.map(job -> ResponseEntity.ok(HypitDtos.success(HypitJobService.toDto(job))));
	}

	// 以下端点属后续卡（16/14/10/11/12/19/20），维持 C03 禁用态如实 503。

	/** C107-16：工程最近克隆方案（材料缺口/status 随行）。 */
	@GetMapping("/api/hypit/projects/{projectId}/clone-plan")
	public Mono<ResponseEntity<Map<String, Object>>> getClonePlan(@PathVariable String projectId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(clonePlans.latestPlan(requireUuid(projectId))))
				.map(plan -> ResponseEntity.ok(HypitDtos.success(plan)));
	}

	/** C107-16：保存克隆方案（分析须已完成；缺口如实 WAITING_INPUT；幂等 requestId）。 */
	@PutMapping("/api/hypit/projects/{projectId}/clone-plan")
	public Mono<ResponseEntity<Map<String, Object>>> putClonePlan(@PathVariable String projectId,
			@RequestBody ClonePlanRequest body, ServerWebExchange exchange) {
		java.util.Map<String, Object> analysisMap = new java.util.HashMap<>();
		analysisMap.put("analysisId", body.analysisId());
		analysisMap.put("mediaHash", body.mediaHash());
		analysisMap.put("durationSeconds", body.durationSeconds());
		analysisMap.put("language", body.language());
		analysisMap.put("aspectRatio", body.aspectRatio());
		analysisMap.put("segments", body.segments());
		analysisMap.put("systems", body.systems());
		analysisMap.put("events", body.events());
		analysisMap.put("gaps", body.gaps());
		analysisMap.put("openQuestions", body.openQuestions());
		analysisMap.put("status", body.status());
		return callers.resolve(exchange.getRequest())
				.flatMap(
						caller -> access.requireProjectOwner(caller, projectId)
								.then(clonePlans.save(requireUuid(projectId), body.requestId(),
										com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService
												.fromMap(analysisMap),
										body.steps(), body.materialGaps())))
				.map(plan -> {
					java.util.Map<String, Object> saved = new java.util.HashMap<>();
					saved.put("planId", plan.planId());
					saved.put("status", plan.status().name());
					saved.put("materialGaps", plan.materialGaps());
					return ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(saved));
				});
	}

	public record ClonePlanRequest(UUID requestId, String analysisId, String mediaHash, double durationSeconds,
			String language, String aspectRatio, List<Object> segments, List<Object> systems, List<Object> events,
			List<Object> gaps, List<String> openQuestions, String status,
			List<com.grassland.intelligence.hypit.agent.HypitClonePlan.PlanStep> steps,
			List<com.grassland.intelligence.hypit.agent.HypitClonePlan.MaterialGap> materialGaps) {
	}

	@GetMapping("/api/hypit/projects/{projectId}/agent-jobs")
	public Mono<ResponseEntity<Map<String, Object>>> agentJobs(@PathVariable String projectId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).then(pending("Agent 任务列表")))
				.map(HypitProjectController::neverMap);
	}

	@PostMapping("/api/hypit/projects/{projectId}/agent-jobs")
	public Mono<ResponseEntity<Map<String, Object>>> createAgentJob(@PathVariable String projectId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).then(pending("Agent 任务创建")))
				.map(HypitProjectController::neverMap);
	}

	@GetMapping("/api/hypit/projects/{projectId}/jobs/{jobId}/actions")
	public Mono<ResponseEntity<Map<String, Object>>> projectJobActions(@PathVariable String projectId,
			@PathVariable String jobId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).then(pending("任务动作日志")))
				.map(HypitProjectController::neverMap);
	}

	@PostMapping("/api/hypit/projects/{projectId}/jobs/{jobId}/actions")
	public Mono<ResponseEntity<Map<String, Object>>> projectJobActionSubmit(@PathVariable String projectId,
			@PathVariable String jobId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).then(pending("任务动作提交")))
				.map(HypitProjectController::neverMap);
	}

	/** C107-10（§13.3 辅助接线）：跨 Build 输出历史，按名筛选分页；服务在 build 域。 */
	@GetMapping("/api/hypit/projects/{projectId}/output-history")
	public Mono<ResponseEntity<Map<String, Object>>> outputHistory(@PathVariable String projectId,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(name = "name", required = false) String name,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(results.outputHistory(caller.accountId(), requireUuid(projectId),
								name == null ? "" : name, limit)))
				.map(rows -> ResponseEntity
						.ok(HypitDtos.success(Map.of("items", rows
								.stream().map(row -> Map.of("buildId", row.buildId().toString(), "name",
										row.outputName(), "kind", row.kind(), "archiveState", row.archiveState()))
								.toList()))));
	}

	/** C107-10（§13.3 辅助接线）：显式复用→原生 build-record/satisfy changeset，不偷偷覆盖 Run。 */
	@PostMapping("/api/hypit/projects/{projectId}/reuse")
	public Mono<ResponseEntity<Map<String, Object>>> reuse(@PathVariable String projectId,
			@RequestBody ReuseRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(results.proposeReuse(caller.accountId(), requireUuid(projectId), body.requestId(),
								body.baseRevision(), body.runFile(), body.selections())))
				.map(row -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(HypitDtos.success(Map.of("changesetId", row.id().toString(), "baseRevision",
								row.baseRevision(), "applyMode", row.applyMode(), "state", row.state()))));
	}

	public record ReuseRequest(UUID requestId, Long baseRevision, String runFile,
			java.util.List<java.util.Map<String, Object>> selections) {
	}

	/**
	 * C107-18：审片评论读取——sidecar feedback.read 桥接上游 FEEDBACK.json（唯一 可编辑评论真相，上游格式原样返回
	 * + 全文档 hash 供 CAS）。
	 */
	@GetMapping("/api/hypit/projects/{projectId}/feedback")
	public Mono<ResponseEntity<Map<String, Object>>> feedback(@PathVariable String projectId,
			@RequestParam(required = false) String run, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).flatMap(ignored -> {
					if (!properties.enabled()) {
						return Mono.error(HypitAccessService.disabled());
					}
					Map<String, Object> payload = run == null || run.isBlank()
							? Map.of("projectId", projectId)
							: Map.of("projectId", projectId, "run", run);
					return sidecar.commandAsync("java-feedback-read-" + UUID.randomUUID(), "feedback.read", payload);
				}))
				.map(command -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(
								com.grassland.intelligence.hypit.project.HypitJson.mapValue(command.result()))));
	}

	/**
	 * C107-18：评论 add/edit/delete/resolve/reopen——上游 FEEDBACK mutation 格式，
	 * expectedHash CAS 冲突 409 只拒当前修改、输入保留。
	 */
	@PostMapping("/api/hypit/projects/{projectId}/feedback")
	public Mono<ResponseEntity<Map<String, Object>>> feedbackMutate(@PathVariable String projectId,
			@RequestBody FeedbackMutateRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).flatMap(ignored -> {
					if (!properties.enabled()) {
						return Mono.error(HypitAccessService.disabled());
					}
					Map<String, Object> payload = new java.util.LinkedHashMap<>();
					payload.put("projectId", projectId);
					if (body.run() != null && !body.run().isBlank()) {
						payload.put("run", body.run());
					}
					if (body.expectedHash() != null && !body.expectedHash().isBlank()) {
						payload.put("expectedHash", body.expectedHash());
					}
					payload.put("mutations", body.mutations() == null ? List.of() : body.mutations());
					return sidecar
							.commandAsync(body.requestId() == null || body.requestId().isBlank()
									? "java-feedback-mutate-" + UUID.randomUUID()
									: body.requestId(), "feedback.mutate", payload)
							.map(c -> (com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand) c);
				})).map(command -> {
					if ("failed".equals(command.state())) {
						Map<String, Object> error = command.error() == null ? Map.of() : command.error();
						String code = com.grassland.intelligence.hypit.project.HypitJson.stringValue(error.get("code"),
								"engine_error");
						String message = com.grassland.intelligence.hypit.project.HypitJson
								.stringValue(error.get("message"), "feedback mutate failed");
						if ("feedback_conflict".equals(code)) {
							throw new com.grassland.intelligence.security.IntelligenceException(409,
									"hypit_revision_conflict", message);
						}
						throw new com.grassland.intelligence.security.IntelligenceException(503,
								"hypit_backend_unavailable", message);
					}
					return ResponseEntity.status(HttpStatus.ACCEPTED)
							.cacheControl(org.springframework.http.CacheControl.noStore()).body(HypitDtos.success(
									com.grassland.intelligence.hypit.project.HypitJson.mapValue(command.result())));
				});
	}

	public record FeedbackMutateRequest(String requestId, String run, String expectedHash,
			List<Map<String, Object>> mutations) {
	}

	/** C107-19：变体列表（按批次分组字段随行；只读）。 */
	@GetMapping("/api/hypit/projects/{projectId}/variants")
	public Mono<ResponseEntity<Map<String, Object>>> variants(@PathVariable String projectId,
			@RequestParam(defaultValue = "100") int limit, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(variants.listProjectVariants(requireUuid(projectId), Math.min(Math.max(limit, 1), 100))))
				.map(rows -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(Map.of("items",
								rows.stream().map(row -> Map.of("id", row.id().toString(), "batchJobId",
										row.batchJobId().toString(), "ordinal", row.ordinal(), "runFile", row.runFile(),
										"state", row.state(), "attempt", row.attempt(), "parameters",
										com.grassland.intelligence.hypit.project.HypitJson.read(row.parametersJson())))
										.toList()))));
	}

	/** C107-19：创建变体批次（axes 校验 1–100、clientKey 唯一、键已声明）。 */
	@PostMapping("/api/hypit/projects/{projectId}/variants")
	public Mono<ResponseEntity<Map<String, Object>>> createVariants(@PathVariable String projectId,
			@RequestBody VariantsCreateRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> access.requireProjectOwner(caller, projectId)
				.then(variants.create(caller.accountId(), requireUuid(projectId),
						new com.grassland.intelligence.hypit.variant.HypitVariantService.CreateRequest(body.requestId(),
								body.baseRunFile() == null ? "main.svrun" : body.baseRunFile(),
								body.axes() == null ? List.of() : body.axes()))))
				.map(batch -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(Map.of("batchJobId", batch.batchJobId().toString(), "baseRevision",
								batch.baseRevision(), "status", batch.status(), "items",
								batch.items().stream()
										.map(item -> Map.of("id", item.id().toString(), "ordinal", item.ordinal(),
												"runFile", item.runFile(), "state", item.state(), "parameters",
												item.parameters()))
										.toList()))));
	}

	public record VariantsCreateRequest(UUID requestId, String baseRunFile,
			List<com.grassland.intelligence.hypit.variant.HypitVariantService.Axis> axes) {
	}

	/** C107-19：单项构建——先 plan 后 submit（grant 由调用方通过 execution-grants 持有）。 */
	@PostMapping("/api/hypit/projects/{projectId}/variants/{id}/build")
	public Mono<ResponseEntity<Map<String, Object>>> buildVariant(@PathVariable String projectId,
			@PathVariable String id, @RequestBody VariantBuildRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> access.requireProjectOwner(caller, projectId)
				.then(variants.planVariant(caller.accountId(), requireUuid(projectId), requireUuid(id)))
				.flatMap(row -> variants.buildVariant(caller.accountId(), requireUuid(projectId), requireUuid(id),
						body == null || body.requestId() == null ? UUID.randomUUID() : body.requestId(),
						body == null ? null : body.grantId())))
				.map(build -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.cacheControl(org.springframework.http.CacheControl.noStore()).body(HypitDtos
								.success(Map.of("buildId", build.id().toString(), "lifecycle", build.lifecycle()))));
	}

	public record VariantBuildRequest(UUID requestId, UUID grantId) {
	}

	/** C107-19（§13.3 辅助接线）：失败项重试——新 attempt，显式复用，成功项拒绝。 */
	@PostMapping("/api/hypit/projects/{projectId}/variants/{id}/retry")
	public Mono<ResponseEntity<Map<String, Object>>> retryVariant(@PathVariable String projectId,
			@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(variants.retryVariant(caller.accountId(), requireUuid(projectId), requireUuid(id))))
				.map(row -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(
								Map.of("id", row.id().toString(), "state", row.state(), "attempt", row.attempt()))));
	}

	/** C107-19（§13.3 辅助接线）：取消单项；终态项与已产生媒体保留。 */
	@PostMapping("/api/hypit/projects/{projectId}/variants/{id}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancelVariant(@PathVariable String projectId,
			@PathVariable String id, @RequestBody VariantCancelRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(variants.cancelVariant(caller.accountId(), requireUuid(projectId), requireUuid(id),
								body == null || body.requestId() == null ? UUID.randomUUID() : body.requestId())))
				.map(row -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(Map.of("id", row.id().toString(), "state", row.state()))));
	}

	public record VariantCancelRequest(UUID requestId, String reason) {
	}

	/** C107-20：工程导出——sidecar project-package.export；artifactRoot 供下载/重导入。 */
	@PostMapping("/api/hypit/projects/{projectId}/export")
	public Mono<ResponseEntity<Map<String, Object>>> export(@PathVariable String projectId,
			@org.springframework.web.bind.annotation.RequestBody(required = false) ExportRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(packageService.export(caller.accountId(), requireUuid(projectId),
								body == null || body.requestId() == null ? UUID.randomUUID() : body.requestId(),
								body == null ? null : body.title(), null, body == null ? null : body.runFile())))
				.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.cacheControl(org.springframework.http.CacheControl.noStore()).body(HypitDtos.success(result)));
	}

	public record ExportRequest(UUID requestId, String title, String runFile) {
	}

	/** pending() 恒 error，此映射仅为满足响应类型，不会执行。 */
	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}

}
