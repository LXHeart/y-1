package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.asset.HypitArchiveService;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitPlanService;
import com.grassland.intelligence.hypit.build.HypitResultService;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.execution.HypitGrantService;
import com.grassland.intelligence.hypit.job.HypitJobService;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 构建/计划/结果面端点（任务书 #107-1 §6.2 / C107-03 声明；C107-08 落地
 * check/plan/pricing/plans 与 execution-grants；C107-09/10 接 builds/outputs）。
 */
@RestController
public class HypitBuildController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;
	private final HypitGrantService grants;
	private final HypitPlanService plans;
	private final HypitProjectRepository projects;
	private final HypitBuildService builds;
	private final HypitJobService jobService;
	private final HypitResultService results;
	private final HypitArchiveService archiveOps;
	/** C107F2-12（§6.5）：build 聚合统计（outputCount/archiveState）来源。 */
	private final HypitOutputRepository outputIndex;

	public HypitBuildController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitGrantService grants, HypitPlanService plans,
			HypitProjectRepository projects, HypitBuildService builds, HypitJobService jobService,
			HypitResultService results, HypitArchiveService archiveOps, HypitOutputRepository outputIndex) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.grants = grants;
		this.plans = plans;
		this.projects = projects;
		this.builds = builds;
		this.jobService = jobService;
		this.results = results;
		this.archiveOps = archiveOps;
		this.outputIndex = outputIndex;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	/** C107-08：check 只读编译诊断（诊断路径转工程相对、不伪造列号——sidecar 侧保证）。 */
	@PostMapping("/api/hypit/projects/{projectId}/check")
	public Mono<ResponseEntity<Map<String, Object>>> check(@PathVariable String projectId,
			@RequestBody CheckRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(plans.check(caller.accountId(), UUID.fromString(projectId),
								body == null || body.entryFile() == null || body.entryFile().isBlank()
										? "main.svml"
										: body.entryFile())))
				.map(view -> ResponseEntity
						.ok(HypitDtos.success(Map.of("ok", view.ok(), "sourceKind", view.sourceKind(), "frontend",
								view.frontend(), "diagnostics", view.diagnostics(), "modules", view.modules(),
								"sourceClosureHash", view.sourceClosureHash(), "targetNames", view.targetNames()))));
	}

	public record CheckRequest(String entryFile) {
	}

	/** C107-08：plan——完整 Run 图 + 内容寻址 planHash；同内容幂等返回原行。 */
	@PostMapping("/api/hypit/projects/{projectId}/plan")
	public Mono<ResponseEntity<Map<String, Object>>> plan(@PathVariable String projectId, @RequestBody PlanRequest body,
			ServerWebExchange exchange) {
		String runFile = body == null || body.runFile() == null || body.runFile().isBlank()
				? "main.svrun"
				: body.runFile();
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(plans.plan(caller.accountId(), UUID.fromString(projectId), runFile)))
				.map(view -> ResponseEntity.ok(HypitDtos.success(Map.of("plan", planDto(view.row()), "ok", view.ok(),
						"missingCapabilities", view.missingCapabilities(), "unresolvedRequests",
						view.unresolvedRequests(), "unsupportedRequests", view.unsupportedRequests(), "providers",
						view.providers(), "reuse", view.reuse()))));
	}

	public record PlanRequest(String requestId, String runFile) {
	}

	/** C107-08：pricing——只读该 plan 精确 requests 的原 provider 报价；unknown=null。 */
	@PostMapping("/api/hypit/projects/{projectId}/pricing")
	public Mono<ResponseEntity<Map<String, Object>>> pricing(@PathVariable String projectId,
			@RequestBody PricingRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(plans.pricing(caller.accountId(), UUID.fromString(projectId), body.planId())))
				.map(view -> ResponseEntity.ok(HypitDtos.success(Map.of("pricingId", view.snapshot().id().toString(),
						"planId", view.snapshot().planId().toString(), "pricingHash", view.snapshot().pricingHash(),
						"rows", view.rows(), "knownCosts", view.knownCosts(), "unknownRequests",
						view.unknownRequests()))));
	}

	public record PricingRequest(UUID requestId, UUID planId) {
	}

	/** 计划历史（不可变行，新→旧）。 */
	@GetMapping("/api/hypit/projects/{projectId}/plans")
	public Mono<ResponseEntity<Map<String, Object>>> plans(@PathVariable String projectId,
			@RequestParam(defaultValue = "20") int limit, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(projects.findOwned(caller.accountId(), UUID.fromString(projectId))
								.switchIfEmpty(Mono.error(HypitAccessService.notFound())))
						.thenMany(plans.listPlansAsFlux(UUID.fromString(projectId), Math.min(Math.max(limit, 1), 100)))
						.collectList())
				.map(rows -> ResponseEntity.ok(
						HypitDtos.success(Map.of("items", rows.stream().map(HypitBuildController::planDto).toList()))));
	}

	private static Map<String, Object> planDto(com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow row) {
		return Map.of("id", row.id().toString(), "revision", row.revision(), "runFile", row.runFile(), "planHash",
				row.planHash(), "createdAt", row.createdAt().toString());
	}

	/**
	 * C107-07：创建执行授权。scope 按 canonical JSON 取 hash 绑定；缺价且未显式 allowUnknownCost
	 * 拒绝（K12.7）；响应不含任何凭据（§6.2）。
	 */
	@PostMapping("/api/hypit/projects/{projectId}/execution-grants")
	public Mono<ResponseEntity<Map<String, Object>>> grant(@PathVariable String projectId,
			@RequestBody GrantCreateRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(grants.createGrant(caller.accountId(),
								new HypitGrantService.GrantRequest(body.requestId(), UUID.fromString(projectId),
										body.planId(), body.pricingId(), body.scope(), body.maxCost(), body.currency(),
										body.allowUnknownCost(), body.variantCount(), body.expiresAt()))))
				.map(grant -> ResponseEntity.status(HttpStatus.CREATED)
						.body(HypitDtos.success(Map.of("grant",
								Map.of("id", grant.id().toString(), "projectId", grant.projectId().toString(),
										"scopeHash", grant.scopeHash(), "maxCost",
										grant.maxCost() == null ? "" : grant.maxCost().toPlainString(), "currency",
										grant.currency(), "allowUnknownCost", grant.allowUnknown(), "variantCount",
										grant.variantCount(), "expiresAt", grant.expiresAt().toString(), "revokedAt",
										grant.revokedAt() == null ? "" : grant.revokedAt().toString())))));
	}

	public record GrantCreateRequest(UUID requestId, UUID planId, UUID pricingId, Object scope,
			java.math.BigDecimal maxCost, String currency, boolean allowUnknownCost, int variantCount,
			java.time.Instant expiresAt) {
	}

	/** C107-09：工程下 Build 列表（新→旧，含终态）。 */
	@GetMapping("/api/hypit/projects/{projectId}/builds")
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String projectId,
			@RequestParam(defaultValue = "20") int limit, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> access.requireProjectOwner(caller, projectId)
				.then(builds.listOwned(caller.accountId(), UUID.fromString(projectId),
						Math.min(Math.max(limit, 1), 100)))
				.flatMap(rows -> outputIndex
						.statsByBuilds(rows.stream()
								.map(com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow::id).toList())
						.map(stats -> rows.stream().map(row -> buildDto(row, stats.getOrDefault(row.id(), ZERO_STATS)))
								.toList())))
				.map(items -> ResponseEntity.ok(HypitDtos.success(Map.of("items", items))));
	}

	/** C107-09 09.1/09.2：提交即 202，公共 Build 先于引擎存在；同 requestId 幂等回原资源。 */
	@PostMapping("/api/hypit/projects/{projectId}/builds")
	public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String projectId,
			@RequestBody BuildCreateRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(builds.submit(caller.accountId(), UUID.fromString(projectId), body.requestId(),
								body.planId(), body.grantId(), body.title())))
				.map(view -> ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
						.body(HypitDtos.success(Map.of("build", buildDto(view.build(), ZERO_STATS), "job",
								HypitJobService.toDto(view.job()), "replayed", view.replayed()))));
	}

	public record BuildCreateRequest(UUID requestId, UUID planId, UUID grantId, String title) {
	}

	/** 单 build 统计查询后出 DTO（get/cancel 等单资源路径共用）。 */
	private Mono<Map<String, Object>> buildDtoWithStats(
			com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow row) {
		return outputIndex.statsByBuilds(java.util.List.of(row.id()))
				.map(stats -> buildDto(row, stats.getOrDefault(row.id(), ZERO_STATS)));
	}

	/** C107-09 09.4：详情=合并观察后的 lifecycle/outcome/resultReady（终态一次有界刷新，失败回落已存事实）。 */
	@GetMapping("/api/hypit/builds/{buildId}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String buildId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.ownedBuild(caller.accountId(), UUID.fromString(buildId)))
				.flatMap(this::buildDtoWithStats)
				.map(dto -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(dto)));
	}

	/** C107-09 09.6：SSE 复用 submit job 事件流，Last-Event-ID 游标恢复（K09.3）。 */
	@GetMapping("/api/hypit/builds/{buildId}/events")
	public reactor.core.publisher.Flux<ServerSentEvent<String>> events(@PathVariable String buildId,
			@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMapMany(caller -> builds
				.ownedBuild(caller.accountId(), UUID.fromString(buildId))
				.flatMapMany(build -> builds.jobFor(build).flatMapMany(
						job -> jobService.tailing(caller.accountId(), build.projectId(), job.id(), lastEventId))));
	}

	/** C107-09 09.9：本人 Build 脱敏分页日志（durable result + live runtime 两源去重）。 */
	@GetMapping("/api/hypit/builds/{buildId}/logs")
	public Mono<ResponseEntity<Map<String, Object>>> logs(@PathVariable String buildId,
			@RequestParam(required = false) Integer cursor, @RequestParam(required = false) Integer limit,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.logs(caller.accountId(), UUID.fromString(buildId), cursor, limit))
				.map(page -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(page)));
	}

	/** C107-09 09.7：幂等取消——终态重复取消零副作用；未提交就地 incomplete+cancelled。 */
	@PostMapping("/api/hypit/builds/{buildId}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancel(@PathVariable String buildId,
			@RequestBody(required = false) CancelRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.cancel(caller.accountId(), UUID.fromString(buildId),
						body == null ? null : body.requestId(), body == null ? null : body.reason()))
				.flatMap(this::buildDtoWithStats).map(dto -> ResponseEntity.ok(HypitDtos.success(dto)));
	}

	public record CancelRequest(UUID requestId, String reason) {
	}

	private static final com.grassland.intelligence.hypit.build.HypitOutputRepository.BuildStats ZERO_STATS = new com.grassland.intelligence.hypit.build.HypitOutputRepository.BuildStats(
			0, 0, 0, 0);

	/**
	 * §6.5（C107F2-12）：Build DTO 实际声明字段全量输出；unknown=null 不用空串/0 冒充。 createdAt 兼容别名
	 * submittedAt；resultReady 与 outcome 分离不假映射。
	 */
	private static Map<String, Object> buildDto(
			com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow row,
			com.grassland.intelligence.hypit.build.HypitOutputRepository.BuildStats stats) {
		Map<String, Object> dto = new java.util.LinkedHashMap<>();
		dto.put("id", row.id().toString());
		dto.put("engineBuildId", row.engineBuildId());
		dto.put("projectId", row.projectId().toString());
		dto.put("revision", row.revision());
		dto.put("planId", row.planId() == null ? null : row.planId().toString());
		dto.put("runFile", row.runFile());
		dto.put("lifecycle", row.lifecycle());
		dto.put("outcome", row.outcome());
		// resultReady 与 outcome 分离：字节/manifest 落位是结果面的事实，未落位不假成功。
		dto.put("resultReady", "finished".equals(row.lifecycle()) && row.resultLocationJson() != null);
		dto.put("operations", java.util.List.of());
		dto.put("receiptSummary",
				row.resultLocationJson() == null
						? null
						: com.grassland.intelligence.hypit.project.HypitJson.read(row.resultLocationJson()));
		dto.put("archiveState", stats.archiveState());
		dto.put("outputCount", stats.outputCount());
		dto.put("createdAt", row.submittedAt() == null ? null : row.submittedAt().toString());
		// 兼容旧字段名（§6.5：submittedAt 作为 createdAt 别名保留）。
		dto.put("submittedAt", row.submittedAt() == null ? null : row.submittedAt().toString());
		dto.put("finishedAt", row.finishedAt() == null ? null : row.finishedAt().toString());
		return dto;
	}

	/** C107-10：finish=保存已接受事实；discard=只处理从未 active 的 submission（否则 409）。 */
	@PostMapping("/api/hypit/builds/{buildId}/result-actions")
	public Mono<ResponseEntity<Map<String, Object>>> resultAction(@PathVariable String buildId,
			@RequestBody ResultActionRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.resultAction(caller.accountId(), UUID.fromString(buildId), body.action()))
				.map(data -> ResponseEntity.ok(HypitDtos.success(data)));
	}

	public record ResultActionRequest(String requestId, String action) {
	}

	/** C107-10：title/note/highlights/displayName 写回原 Result 仓库，不改 Output 身份。 */
	@PatchMapping("/api/hypit/builds/{buildId}/presentation")
	public Mono<ResponseEntity<Map<String, Object>>> presentation(@PathVariable String buildId,
			@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.updatePresentation(caller.accountId(), UUID.fromString(buildId), body))
				.map(data -> ResponseEntity.ok(HypitDtos.success(data)));
	}

	/**
	 * C107-10 / §6.5（C107F2-12）：公共 Outputs 列表（结果发现后幂等入索引；详情带原 plan/pricing 快照）。
	 * HypitOutputListData 全量形状：items 恒为数组、nextCursor=null 不伪造分页、outputs=items 兼容别名。
	 */
	@GetMapping("/api/hypit/builds/{buildId}/outputs")
	public Mono<ResponseEntity<Map<String, Object>>> outputs(@PathVariable String buildId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.resultDetail(caller.accountId(), UUID.fromString(buildId))).map(view -> {
					List<Map<String, Object>> items = view.outputs().stream().map(HypitBuildController::outputDto)
							.toList();
					long archived = view.outputs().stream().filter(row -> "archived".equals(row.archiveState()))
							.count();
					long failed = view.outputs().stream().filter(row -> "failed".equals(row.archiveState())).count();
					long archiving = view.outputs().stream().filter(row -> "archiving".equals(row.archiveState()))
							.count();
					Map<String, Object> data = new java.util.LinkedHashMap<>();
					data.put("items", items);
					// 当前结果规模沿既有 build 输出集合，不伪造分页。
					data.put("nextCursor", null);
					data.put("build",
							buildDto(view.build(),
									new com.grassland.intelligence.hypit.build.HypitOutputRepository.BuildStats(
											view.outputs().size(), archived, failed, archiving)));
					data.put("planSnapshot", view.planSnapshot());
					data.put("outputs", items);
					return ResponseEntity.ok(HypitDtos.success(data));
				});
	}

	/** C107-10：单 Output 导出——Scalar JSON、Resource 真实字节、Composite 打包信封。 */
	@GetMapping("/api/hypit/builds/{buildId}/output")
	public Mono<ResponseEntity<Map<String, Object>>> output(@PathVariable String buildId, @RequestParam String name,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.exportOutput(caller.accountId(), UUID.fromString(buildId), name))
				.map(export -> {
					if (export.bytes() != null) {
						// 信封是 JSON（dataBase64）——Content-Type 不得写成 export.mediaType()
						// （video/mp4 无 message writer，writeBody 直接 500）；真实类型在 body 字段。
						return ResponseEntity.ok()
								.header("X-Hypit-Sha256", export.sha256() == null ? "" : export.sha256())
								.body(HypitDtos.success(Map.of("kind", export.kind(), "mediaType", export.mediaType(),
										"size", export.bytes().length, "dataBase64",
										java.util.Base64.getEncoder().encodeToString(export.bytes()))));
					}
					return ResponseEntity.ok().body(HypitDtos.success(Map.of("kind", export.kind(), "mediaType",
							export.mediaType(), "value", export.valueJson() == null ? "" : export.valueJson())));
				});
	}

	/**
	 * C107-10 步骤 6 / §6.5（C107F2-12）：服务端归档——POST /builds/{id}/archive 携 {requestId,
	 * outputNames}（非空、名字存在、重复名去重）；幂等复用已归档 media。
	 */
	@PostMapping("/api/hypit/builds/{buildId}/archive")
	public Mono<ResponseEntity<Map<String, Object>>> archive(@PathVariable String buildId,
			@RequestBody ArchiveRequest body, ServerWebExchange exchange) {
		if (body == null || body.requestId() == null || body.outputNames() == null || body.outputNames().isEmpty()
				|| body.outputNames().stream().anyMatch(name -> name == null || name.isBlank())) {
			return Mono.error(new com.grassland.intelligence.security.IntelligenceException(
					HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input", "requestId 与非空 outputNames 必填。"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.ownedBuild(caller.accountId(), UUID.fromString(buildId))
						.thenMany(archiveOps.archiveByNames(UUID.fromString(buildId), body.outputNames()))
						.collectList())
				.map(rows -> ResponseEntity.ok(HypitDtos
						.success(Map.of("outputs", rows.stream().map(HypitBuildController::outputDto).toList()))));
	}

	public record ArchiveRequest(UUID requestId, java.util.List<String> outputNames) {
	}

	/**
	 * §6.5（C107F2-12）：Output DTO 实际声明字段全量输出；displayName/typeRef 取自索引的
	 * value_summary；未知 size/duration=null 而非 0；mediaType/mediaId 未知=null 非空串。
	 */
	private static Map<String, Object> outputDto(
			com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow row) {
		Map<String, Object> summary = row.valueSummaryJson() == null
				? null
				: com.grassland.intelligence.hypit.project.HypitJson.read(row.valueSummaryJson());
		Map<String, Object> dto = new java.util.LinkedHashMap<>();
		dto.put("id", row.id().toString());
		dto.put("buildId", row.buildId().toString());
		dto.put("name", row.outputName());
		dto.put("displayName", summary == null ? null : summary.get("displayName"));
		dto.put("kind", row.kind());
		dto.put("typeRef", summary == null ? null : summary.get("type"));
		dto.put("mediaType", row.mediaType());
		dto.put("sizeBytes", row.sizeBytes());
		// 引擎尚未提供时长事实：未知=null，不用 0 冒充（§6.5）。
		dto.put("durationSeconds", null);
		dto.put("valueSummary", summary);
		dto.put("archiveState", row.archiveState());
		dto.put("mediaId", row.mediaId() == null ? null : row.mediaId().toString());
		dto.put("dependencies", java.util.List.of());
		dto.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
		return dto;
	}

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
