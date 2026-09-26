package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
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

	public HypitRuntimeController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitSidecarClient sidecar, HypitRuntimeService runtime,
			com.grassland.intelligence.hypit.build.HypitBuildService buildOps) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.sidecar = sidecar;
		this.runtime = runtime;
		this.buildOps = buildOps;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	/** TC107-03-03：登录用户即使 disabled 也 200（enabled=false）；未登录 401。 */
	@GetMapping("/api/hypit/capabilities")
	public Mono<ResponseEntity<Map<String, Object>>> capabilities(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).map(caller -> {
			boolean sidecarUp = properties.enabled() && sidecar.health();
			HypitDtos.FeatureReadiness engine = new HypitDtos.FeatureReadiness("engine", sidecarUp, sidecarUp,
					sidecarUp, sidecarUp, sidecarUp ? null : properties.enabled() ? "引擎宿主未运行" : "HYPIT_ENABLED=false",
					properties.enabled() ? "启动 hypit-backend" : "联系部署者启用 Hypit");
			HypitDtos.FeatureReadiness projects = new HypitDtos.FeatureReadiness("projects", sidecarUp, sidecarUp,
					false, false, "工程域在 C107-04 落地", null);
			HypitDtos.Capabilities data = new HypitDtos.Capabilities(properties.enabled() && sidecarUp,
					sidecarUp ? "0.2.13" : null, List.of(engine, projects), List.of());
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(data));
		});
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

	@PostMapping("/api/hypit/runtime/actions")
	public Mono<ResponseEntity<Map<String, Object>>> action(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> pending("运行时操作")).map(HypitRuntimeController::neverMap);
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

	@GetMapping("/api/hypit/runtime/paths")
	public Mono<ResponseEntity<Map<String, Object>>> paths(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> pending("运行时路径")).map(HypitRuntimeController::neverMap);
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

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
