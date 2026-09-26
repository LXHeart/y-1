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
import com.grassland.intelligence.security.IntelligenceCallerResolver;
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

	public HypitBuildController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitGrantService grants, HypitPlanService plans,
			HypitProjectRepository projects, HypitBuildService builds, HypitJobService jobService,
			HypitResultService results, HypitArchiveService archiveOps) {
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
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(builds.listOwned(caller.accountId(), UUID.fromString(projectId),
								Math.min(Math.max(limit, 1), 100))))
				.map(rows -> ResponseEntity.ok(HypitDtos
						.success(Map.of("items", rows.stream().map(HypitBuildController::buildDto).toList()))));
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
						.body(HypitDtos.success(Map.of("build", buildDto(view.build()), "job",
								HypitJobService.toDto(view.job()), "replayed", view.replayed()))));
	}

	public record BuildCreateRequest(UUID requestId, UUID planId, UUID grantId, String title) {
	}

	/** C107-09 09.4：详情=合并观察后的 lifecycle/outcome/resultReady（终态一次有界刷新，失败回落已存事实）。 */
	@GetMapping("/api/hypit/builds/{buildId}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String buildId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.ownedBuild(caller.accountId(), UUID.fromString(buildId)))
				.map(build -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
						.body(HypitDtos.success(buildDto(build))));
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
				.map(build -> ResponseEntity.ok(HypitDtos.success(buildDto(build))));
	}

	public record CancelRequest(UUID requestId, String reason) {
	}

	private static Map<String, Object> buildDto(
			com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow row) {
		Map<String, Object> dto = new java.util.LinkedHashMap<>();
		dto.put("id", row.id().toString());
		dto.put("projectId", row.projectId().toString());
		dto.put("planId", row.planId() == null ? "" : row.planId().toString());
		dto.put("revision", row.revision());
		dto.put("engineBuildId", row.engineBuildId() == null ? "" : row.engineBuildId());
		dto.put("lifecycle", row.lifecycle());
		dto.put("outcome", row.outcome() == null ? "" : row.outcome());
		// resultReady 与 outcome 分离：字节/manifest 落位是 C10 结果面的事实，未落位不假成功。
		dto.put("resultReady", "finished".equals(row.lifecycle()) && row.resultLocationJson() != null);
		dto.put("submittedAt", row.submittedAt() == null ? "" : row.submittedAt().toString());
		dto.put("finishedAt", row.finishedAt() == null ? "" : row.finishedAt().toString());
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

	/** C107-10：公共 Outputs 列表（结果发现后幂等入索引；详情带原 plan/pricing 快照）。 */
	@GetMapping("/api/hypit/builds/{buildId}/outputs")
	public Mono<ResponseEntity<Map<String, Object>>> outputs(@PathVariable String buildId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.resultDetail(caller.accountId(), UUID.fromString(buildId)))
				.map(view -> ResponseEntity.ok(HypitDtos.success(Map.of("build", buildDto(view.build()), "outputs",
						view.outputs().stream().map(HypitBuildController::outputDto).toList(), "planSnapshot",
						view.planSnapshot()))));
	}

	/** C107-10：单 Output 导出——Scalar JSON、Resource 真实字节、Composite 打包信封。 */
	@GetMapping("/api/hypit/builds/{buildId}/output")
	public Mono<ResponseEntity<Map<String, Object>>> output(@PathVariable String buildId, @RequestParam String name,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> results.exportOutput(caller.accountId(), UUID.fromString(buildId), name))
				.map(export -> {
					if (export.bytes() != null) {
						return ResponseEntity.ok().header("Content-Type", export.mediaType())
								.header("X-Hypit-Sha256", export.sha256() == null ? "" : export.sha256())
								.body(HypitDtos.success(Map.of("kind", export.kind(), "mediaType", export.mediaType(),
										"size", export.bytes().length, "dataBase64",
										java.util.Base64.getEncoder().encodeToString(export.bytes()))));
					}
					return ResponseEntity.ok().header("Content-Type", export.mediaType())
							.body(HypitDtos.success(Map.of("kind", export.kind(), "mediaType", export.mediaType(),
									"value", export.valueJson() == null ? "" : export.valueJson())));
				});
	}

	/** C107-10 步骤 6：服务端归档——发现可用 Output 后即可调用，不等页面。 */
	@PostMapping("/api/hypit/builds/{buildId}/archive")
	public Mono<ResponseEntity<Map<String, Object>>> archive(@PathVariable String buildId,
			@RequestBody ArchiveRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> builds.ownedBuild(caller.accountId(), UUID.fromString(buildId))
						.thenMany(archiveOps.archiveByNames(UUID.fromString(buildId), body.outputNames()))
						.collectList())
				.map(rows -> ResponseEntity.ok(HypitDtos
						.success(Map.of("outputs", rows.stream().map(HypitBuildController::outputDto).toList()))));
	}

	public record ArchiveRequest(String requestId, java.util.List<String> outputNames) {
	}

	private static Map<String, Object> outputDto(
			com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow row) {
		Map<String, Object> dto = new java.util.LinkedHashMap<>();
		dto.put("id", row.id().toString());
		dto.put("buildId", row.buildId().toString());
		dto.put("name", row.outputName());
		dto.put("kind", row.kind());
		dto.put("mediaType", row.mediaType() == null ? "" : row.mediaType());
		dto.put("sizeBytes", row.sizeBytes() == null ? 0 : row.sizeBytes());
		dto.put("archiveState", row.archiveState());
		dto.put("mediaId", row.mediaId() == null ? "" : row.mediaId().toString());
		return dto;
	}

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
