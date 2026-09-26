package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.runtime.HypitRuntimeService;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 运行时/凭据/程序端点（任务书 #107-1 §6.2 / C107-03 + C107-07）。
 *
 * <p>
 * C107-07 落地 runtime 状态、Profile 校验、凭据三端点与 OAuth 授权流四端点：凭据与 flow 委托 sidecar
 * 命令域（providers/credentials/authflow），owner=操作者 accountId。 写操作一律 operator（§6.2
 * 全局规则）；GET /runtime 与 capabilities 同为登录可读的
 * 脱敏事实。actions/activity/logs/paths/packages 仍属 C09/C17/C23，pending 如实 503。
 * 凭据值绝不入日志（端点声明 noStore，异常只带 key 名不带值）。
 */
@RestController
public class HypitRuntimeController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitRuntimeService runtime;
	private final com.grassland.intelligence.hypit.build.HypitBuildService buildOps;
	private final org.springframework.r2dbc.core.DatabaseClient db;

	public HypitRuntimeController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitSidecarClient sidecar, HypitRuntimeService runtime,
			com.grassland.intelligence.hypit.build.HypitBuildService buildOps,
			org.springframework.r2dbc.core.DatabaseClient db) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.sidecar = sidecar;
		this.runtime = runtime;
		this.buildOps = buildOps;
		this.db = db;
	}

	/** TC107-03-03：登录用户即使 disabled 也 200（enabled=false）；未登录 401。 */
	@GetMapping("/api/hypit/capabilities")
	public Mono<ResponseEntity<Map<String, Object>>> capabilities(ServerWebExchange exchange) {
		// healthAsync：响应式链内 block 会被 Reactor 拒绝（事件循环线程），探测结果伪装成 false
		return callers.resolve(exchange.getRequest()).flatMap(caller -> sidecar.healthAsync().map(sidecarUp -> {
			boolean ready = properties.enabled() && sidecarUp;
			HypitDtos.FeatureReadiness engine = new HypitDtos.FeatureReadiness("engine", ready, ready, ready,
					ready, ready ? null : properties.enabled() ? "引擎宿主未运行" : "HYPIT_ENABLED=false",
					properties.enabled() ? "启动 hypit-backend" : "联系部署者启用 Hypit");
			HypitDtos.FeatureReadiness projects = new HypitDtos.FeatureReadiness("projects", ready, ready, false,
					false, "工程域在 C107-04 落地", null);
			HypitDtos.Capabilities data = new HypitDtos.Capabilities(ready, ready ? "0.2.13" : null,
					List.of(engine, projects), List.of());
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data));
		}));
	}

	/** C107-07：运行时事实（enabled/sidecar 健康），登录可读的脱敏状态。 */
	@GetMapping("/api/hypit/runtime")
	public Mono<ResponseEntity<Map<String, Object>>> status(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> runtime.status())
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/** C107-07：Profile 尚未持久化（C23），GET 如实返回未配置而不是伪造默认值。 */
	@GetMapping("/api/hypit/runtime/profile")
	public Mono<ResponseEntity<Map<String, Object>>> profileGet(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.map(caller -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(Map
						.of("persisted", false, "profile", Map.of(), "note", "Profile 持久化与部署绑定在 C23 部署书落地（07 仅校验）"))));
	}

	/** C107-07：PUT=校验（07.2 schema），响应带 persisted=false，不假称已生效。 */
	@PutMapping("/api/hypit/runtime/profile")
	public Mono<ResponseEntity<Map<String, Object>>> profilePut(@RequestBody ProfilePutRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (body == null || !(body.profile() instanceof Map)) {
				return Mono.<Map<String, Object>>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "profile 必须是对象"));
			}
			return runtime.validateProfile(body.profile());
		}).map(report -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(report)));
	}

	/**
	 * C107F-04（W15 / D-03）：runtime 动作实装——doctor 聚合健康（引擎/两程序/渲染容量/活跃 Build）；
	 * up/down 幂等编排两程序（down 有活跃 Build 且 hash 缺失/不匹配 → 409 影响清单 RULE-F05）；
	 * init/use/unset 显式 409 {@code hypit_runtime_managed}（Profile 由 RUNTIME_PROFILE_PUT 平台管理）。
	 */
	@PostMapping("/api/hypit/runtime/actions")
	public Mono<ResponseEntity<Map<String, Object>>> action(@RequestBody RuntimeActionRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).<ResponseEntity<Map<String, Object>>>flatMap(
				caller -> {
					if (body == null || body.action() == null) {
						return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
								"hypit_invalid_input", "action 必填"));
					}
					List<String> endpoints = endpointsOf(body.endpointIds());
					return switch (body.action()) {
						case "doctor" -> doctor(endpoints).map(data -> ok(data));
						case "up" -> programCycle("up", endpoints)
								.map(result -> ok(Map.of("action", "up", "endpoints", result)));
						case "down" -> down(body, endpoints).map(data -> ok(data));
						case "init", "use", "unset" -> Mono.<ResponseEntity<Map<String, Object>>>error(
								new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_runtime_managed",
										"runtime profile 由平台管理：请使用 PUT /runtime/profile（RUNTIME_PROFILE_PUT）"));
						default -> Mono.<ResponseEntity<Map<String, Object>>>error(new IntelligenceException(
								HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
								"action 必须是 init/use/unset/up/down/doctor 之一"));
					};
				});
	}

	private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data));
	}

	private static List<String> endpointsOf(List<String> endpointIds) {
		if (endpointIds == null || endpointIds.isEmpty()) {
			return List.of("whisperx.local", "image.opencv.local");
		}
		for (String endpoint : endpointIds) {
			if (!List.of("whisperx.local", "image.opencv.local").contains(endpoint)) {
				throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
						"endpointIds 只允许 whisperx.local / image.opencv.local：" + endpoint);
			}
		}
		return List.copyOf(endpointIds);
	}

	/** doctor：引擎就绪 + 逐程序状态 + 渲染容量（经 build.activity 的 localRender，无活跃即 null）+ 活跃 Build。 */
	private Mono<Map<String, Object>> doctor(List<String> endpoints) {
		// healthAsync：响应式链内 block 会被 Reactor 拒绝（事件循环线程），探测结果伪装成 false
		return sidecar.healthAsync().flatMap(sidecarUp -> {
			Map<String, Object> engine = new java.util.LinkedHashMap<>();
			engine.put("ready", properties.enabled() && sidecarUp);
			engine.put("version", sidecarUp ? "0.2.13" : null);
			return programStates(endpoints).flatMap(programs -> buildOps.activeBuildSnapshot()
					.flatMap(snapshot -> renderOf(snapshot).map(render -> {
						Map<String, Object> data = new java.util.LinkedHashMap<>();
						data.put("engine", engine);
						data.put("programs", programs);
						data.put("render", render.orElse(null));
						Map<String, Object> activity = new java.util.LinkedHashMap<>();
						activity.put("activeBuilds", snapshot.buildIds().size());
						activity.put("activityHash", snapshot.activityHash());
						data.put("activity", activity);
						return data;
					})));
		});
	}

	/** 渲染容量如实：有活跃 Build 时取其一的 broker localRender（进程级共享闸）；无活跃/不可达→空 不伪造。 */
	private Mono<java.util.Optional<Object>> renderOf(
			com.grassland.intelligence.hypit.build.HypitBuildService.ActiveSnapshot snapshot) {
		if (snapshot.buildIds().isEmpty() || !properties.enabled()) {
			return Mono.just(java.util.Optional.empty());
		}
		return db.sql("SELECT project_id::text AS project FROM hypit_build WHERE id = CAST(:id AS uuid)")
				.bind("id", snapshot.buildIds().get(0).toString()).map((row, meta) -> row.get("project", String.class))
				.one().flatMap(project -> sidecar
						.commandAsync("doctor-activity-" + UUID.randomUUID(), "build.activity",
								Map.of("projectId", project))
						.timeout(java.time.Duration.ofSeconds(10))
						.map(command -> java.util.Optional.<Object>ofNullable(
								command.result() == null ? null
										: com.grassland.intelligence.hypit.project.HypitJson
												.mapValue(command.result()).get("localRender")))
						.onErrorResume(error -> Mono.just(java.util.Optional.empty()))
						.defaultIfEmpty(java.util.Optional.empty()));
	}

	private Mono<Map<String, Object>> programStates(List<String> endpoints) {
		return reactor.core.publisher.Flux.fromIterable(endpoints)
				.concatMap(program -> sidecar
						.commandAsync("java-programs-status-" + program + "-" + UUID.randomUUID(), "programs.status",
								Map.of("program", program))
						.timeout(java.time.Duration.ofSeconds(10))
						.map(command -> new java.util.AbstractMap.SimpleEntry<String, Map<String, Object>>(program,
								command.result() == null ? null
										: programHealth(com.grassland.intelligence.hypit.project.HypitJson
												.mapValue(command.result()))))
						.onErrorResume(
								error -> Mono.just(new java.util.AbstractMap.SimpleEntry<>(program, null))))
				.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	private static Map<String, Object> programHealth(Map<String, Object> result) {
		Map<String, Object> health = new java.util.LinkedHashMap<>();
		health.put("state", result.get("phase"));
		health.put("health", "up".equals(result.get("phase")) && result.get("identity") != null ? "ok" : null);
		return health;
	}

	/** up/down 幂等编排（选同步幂等结果——programs 管线本身幂等且快，D-03）。 */
	private Mono<Map<String, Object>> programCycle(String verb, List<String> endpoints) {
		return reactor.core.publisher.Flux.fromIterable(endpoints)
				.concatMap(program -> sidecar
						.commandAsync("java-programs-" + verb + "-" + program + "-" + UUID.randomUUID(),
								"programs." + verb, Map.of("program", program))
						.timeout(java.time.Duration.ofSeconds(120))
						.map(command -> new java.util.AbstractMap.SimpleEntry<String, Map<String, Object>>(program,
								command.result() == null ? Map.of("state", command.state())
										: com.grassland.intelligence.hypit.project.HypitJson
												.mapValue(command.result())))
						.onErrorResume(error -> Mono.just(new java.util.AbstractMap.SimpleEntry<>(program,
								Map.of("state", "error", "detail", String.valueOf(error.getMessage()))))))
				.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	/** down：RULE-F05——活跃 Build>0 且 hash 缺失/不匹配 → 409 附影响清单；匹配/无活跃 → 两程序 down。 */
	private Mono<Map<String, Object>> down(RuntimeActionRequest body, List<String> endpoints) {
		return buildOps.activeBuildSnapshot().flatMap(snapshot -> {
			if (!snapshot.buildIds().isEmpty() && (body.expectedActivityHash() == null
					|| !body.expectedActivityHash().equals(snapshot.activityHash()))) {
				Map<String, Object> impact = new java.util.LinkedHashMap<>();
				impact.put("message", "存在活跃构建");
				impact.put("buildIds", snapshot.buildIds().stream().map(UUID::toString).toList());
				impact.put("activityHash", snapshot.activityHash());
				return Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_activity_conflict",
						HypitJson.write(impact)));
			}
			return programCycle("down", endpoints).map(result -> {
				Map<String, Object> data = new java.util.LinkedHashMap<>();
				data.put("action", "down");
				data.put("endpoints", result);
				return data;
			});
		});
	}

	public record RuntimeActionRequest(String requestId, String action, String profileId, List<String> endpointIds,
			String expectedActivityHash) {
	}

	/**
	 * C107F-04（W15 / E-i）：operator 逻辑路径清单——公网面只回逻辑键（宿主绝对路径不经此 API；真实宿主
	 * 路径获取方式见 deploy/hypit/README.md）。captureBrowserCache 由 broker 侧配置持有，J 无从读取时如实
	 * null。
	 */
	@GetMapping("/api/hypit/runtime/paths")
	public Mono<ResponseEntity<Map<String, Object>>> paths(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.map(caller -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
						.body(HypitDtos.success(Map.of("logical", logicalPaths(), "hostPathsRevealed", false))));
	}

	private static Map<String, Object> logicalPaths() {
		Map<String, Object> logical = new java.util.LinkedHashMap<>();
		logical.put("projectsRoot", "<dataRoot>/projects");
		logical.put("artifactsRoot", "<dataRoot>/artifacts");
		logical.put("importStagingRoot", "<dataRoot>/import-staging");
		logical.put("stateRoot", "<hostStateRoot>");
		logical.put("programsHome", "<dataRoot>/programs");
		logical.put("runnerSlots", "<dataRoot>/runner-slots");
		logical.put("captureBrowserCache", null);
		return logical;
	}

	/** C107-09 09.5/09.9：operator 全局活动（按工程聚合 broker 闸与原生加权预约）；非 operator 403。 */
	@GetMapping("/api/hypit/runtime/activity")
	public Mono<ResponseEntity<Map<String, Object>>> activity(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> buildOps.globalActivity())
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/** C107-09 09.9：operator 全局脱敏日志（跨可观察 Build 各取一页）；普通用户走 /builds/{id}/logs。 */
	@GetMapping("/api/hypit/runtime/logs")
	public Mono<ResponseEntity<Map<String, Object>>> logs(
			@org.springframework.web.bind.annotation.RequestParam(required = false) Integer cursor,
			@org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> buildOps.globalLogs(cursor, limit))
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/** C107-06：程序生命周期经 sidecar programs.<action> 命令（operator 专属）。 */
	@PostMapping("/api/hypit/runtime/programs/{action}")
	public Mono<ResponseEntity<Map<String, Object>>> programs(@PathVariable String action,
			@org.springframework.web.bind.annotation.RequestBody(required = false) ProgramsRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (!List.of("prepare", "up", "down", "status", "logs").contains(action)) {
				return Mono.<Boolean>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "未知程序操作：" + action));
			}
			if (body == null || body.program() == null
					|| !body.program().matches("^\\p{Alnum}+[.]\\p{Alnum}+[.]\\p{Alnum}+$")) {
				return Mono.<Boolean>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "programs 请求需要 program（如 whisperx.local）。"));
			}
			return Mono.just(true);
		}).then(Mono.defer(() -> {
			if (!properties.enabled()) {
				return Mono.<ResponseEntity<Map<String, Object>>>error(HypitAccessService.disabled());
			}
			return sidecar.commandAsync(
					"java-programs-" + action + "-" + (body == null || body.program() == null ? "x" : body.program())
							+ "-" + java.util.UUID.randomUUID(),
					"programs." + action,
					Map.of("program", body == null || body.program() == null ? "" : body.program()))
					.map(command -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos
							.success(command.result() == null ? Map.of("state", command.state()) : command.result())));
		}));
	}

	public record ProgramsRequest(String program) {
	}

	public record ProfilePutRequest(String requestId, String profileId, String baseHash, Map<String, Object> profile) {
	}

	/** GET 凭据状态：仅 configured/type/expiry，绝无 secret。 */
	@GetMapping("/api/hypit/runtime/credentials/{endpoint}/{slot}")
	public Mono<ResponseEntity<Map<String, Object>>> credentialGet(@PathVariable String endpoint,
			@PathVariable String slot, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> runtime.credentialStatus(endpoint, slot))
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/** PUT 凭据：API key 直写；OAuth 槽走 auth-flows（此处拒绝其它 kind 防误用）。 */
	@PutMapping("/api/hypit/runtime/credentials/{endpoint}/{slot}")
	public Mono<ResponseEntity<Map<String, Object>>> credentialPut(@PathVariable String endpoint,
			@PathVariable String slot, @RequestBody CredentialPutRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (body == null || body.secret() == null || body.secret().isBlank()) {
				return Mono.<Map<String, Object>>error(
						new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input", "secret 必填"));
			}
			if (body.kind() != null && !body.kind().isBlank() && !"api-key".equals(body.kind())) {
				return Mono.<Map<String, Object>>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "kind=" + body.kind() + " 不支持直写；OAuth 凭据请走 /runtime/auth-flows"));
			}
			return runtime.credentialPut(requestId(body.requestId(), exchange), endpoint, slot, body.secret());
		}).map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	public record CredentialPutRequest(String requestId, String kind, String secret) {
	}

	@DeleteMapping("/api/hypit/runtime/credentials/{endpoint}/{slot}")
	public Mono<ResponseEntity<Map<String, Object>>> credentialDelete(@PathVariable String endpoint,
			@PathVariable String slot,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> runtime.credentialDelete(idempotencyKey != null && !idempotencyKey.isBlank()
						? idempotencyKey
						: UUID.randomUUID().toString(), endpoint, slot))
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/** C107-07：OAuth 授权流。owner=操作者 accountId；acquisition 取自 sidecar catalog。 */
	@PostMapping("/api/hypit/runtime/auth-flows")
	public Mono<ResponseEntity<Map<String, Object>>> flowCreate(@RequestBody FlowCreateRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (body == null || isBlank(body.endpoint()) || isBlank(body.slot())) {
				return Mono.<Map<String, Object>>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "endpoint 与 slot 必填"));
			}
			return runtime.authFlowStart(caller.accountId(), requestId(body.requestId(), exchange), body.endpoint(),
					body.slot());
		}).map(data -> ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(HypitDtos.success(data)));
	}

	public record FlowCreateRequest(String requestId, String endpoint, String slot) {
	}

	@GetMapping("/api/hypit/runtime/auth-flows/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> flowGet(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> runtime.authFlowProgress(caller.accountId(), id))
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	@PostMapping("/api/hypit/runtime/auth-flows/{id}/complete")
	public Mono<ResponseEntity<Map<String, Object>>> flowComplete(@PathVariable String id,
			@RequestBody FlowCompleteRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (body == null || isBlank(body.codeAndState())) {
				return Mono.<Map<String, Object>>error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(),
						"hypit_invalid_input", "codeAndState 必填（格式 code#state）"));
			}
			return runtime.authFlowComplete(caller.accountId(), requestId(body.requestId(), exchange), id,
					body.codeAndState());
		}).map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	public record FlowCompleteRequest(String requestId, String codeAndState) {
	}

	@DeleteMapping("/api/hypit/runtime/auth-flows/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> flowDelete(@PathVariable String id,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> runtime.authFlowCancel(caller.accountId(),
						idempotencyKey != null && !idempotencyKey.isBlank()
								? idempotencyKey
								: UUID.randomUUID().toString(),
						id))
				.map(data -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data)));
	}

	/**
	 * C107-17：运行时包目录（operator 专属）——sidecar packages.status 列出已安装
	 * 组件/Provider/Companion 包及其 facet，不回显任何凭据。
	 */
	@GetMapping("/api/hypit/runtime/packages")
	public Mono<ResponseEntity<Map<String, Object>>> packagesList(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String projectId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (!properties.enabled()) {
				return Mono.error(HypitAccessService.disabled());
			}
			return sidecar
					.commandAsync("java-packages-status-" + UUID.randomUUID(), "packages.status",
							projectId == null || projectId.isBlank() ? Map.of() : Map.of("projectId", projectId))
					.map(command -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
							.body(HypitDtos.success(command.result() == null
									? Map.of("packages", List.of())
									: com.grassland.intelligence.hypit.project.HypitJson.mapValue(command.result()))));
		});
	}

	/**
	 * C107-17：受控包安装/打包（operator 专属）——命令面只接受本卡登记的 packages.* kind；不提供任意 shell，不接触
	 * npm install（K10.4）。
	 */
	@PostMapping("/api/hypit/runtime/packages")
	public Mono<ResponseEntity<Map<String, Object>>> packagesInstall(@RequestBody PackagesRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator).flatMap(caller -> {
			if (body == null || body.kind() == null
					|| !body.kind().matches("^packages[.](install|pack|build|status)$")) {
				return Mono.<ResponseEntity<Map<String, Object>>>error(
						new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
								"kind 必须是 packages.install|pack|build|status"));
			}
			if (!properties.enabled()) {
				return Mono.<ResponseEntity<Map<String, Object>>>error(HypitAccessService.disabled());
			}
			String commandId = requestId(body.requestId(), exchange);
			Map<String, Object> payload = new java.util.LinkedHashMap<>();
			if (body.payload() != null) {
				payload.putAll(body.payload());
			}
			if (body.projectId() != null) {
				payload.put("projectId", body.projectId());
			}
			if (body.packagePath() != null) {
				payload.put("packagePath", body.packagePath());
			}
			if (body.artifactRoot() != null) {
				payload.put("artifactRoot", body.artifactRoot());
			}
			if (body.baseRevision() != null) {
				payload.put("baseRevision", body.baseRevision());
			}
			return sidecar.commandAsync(commandId, body.kind(), payload)
					.map(command -> ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
							.body(HypitDtos.success(command.result() == null
									? Map.of("commandId", command.commandId(), "state", command.state())
									: com.grassland.intelligence.hypit.project.HypitJson.mapValue(command.result()))));
		});
	}

	public record PackagesRequest(String requestId, String kind, String projectId, String packagePath,
			String artifactRoot, Long baseRevision, Map<String, Object> payload) {
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** requestId：body 优先，其次 Idempotency-Key，缺省随机（命令域按 commandId 幂等）。 */
	private static String requestId(String bodyRequestId, ServerWebExchange exchange) {
		if (bodyRequestId != null && !bodyRequestId.isBlank()) {
			return bodyRequestId;
		}
		String header = exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
		return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
	}
}
