package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAdminService.AdminActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanAdminService.AdminConfigUpdate;
import com.grassland.intelligence.digitalhuman.DigitalHumanAdminService.ReconcileInput;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 数字人治理端点（任务书 #105G C105G-03 / K10 ADMIN01～06）。Controller 只装配与解析。
 *
 * <p>
 * 全部端点 {@code requireAdmin}（BFF 断言 platform_admin；未登录 401、角色不足 403
 * {@code dh_admin_required}）。请求体经
 * {@link DigitalHumanOperations#parseStrict}（含未定义字段拒绝—— ADMIN06 金额字段不可提交）；错误信封走
 * {@link DigitalHumanExceptionHandler}；正文一律 no-store。
 */
@RestController
public class DigitalHumanAdminController {

	private final IntelligenceCallerResolver callers;
	private final DigitalHumanAdminService admin;

	public DigitalHumanAdminController(IntelligenceCallerResolver callers, DigitalHumanAdminService admin) {
		this.callers = callers;
		this.admin = admin;
	}

	// ADMIN01
	@GetMapping("/api/admin/digital-human/config")
	public Mono<ResponseEntity<Map<String, Object>>> config(ServerWebExchange exchange) {
		return requireDhAdmin(exchange).flatMap(actor -> admin.config()).map(this::ok);
	}

	// ADMIN02
	@PutMapping("/api/admin/digital-human/config")
	public Mono<ResponseEntity<Map<String, Object>>> updateConfig(@RequestBody String body,
			ServerWebExchange exchange) {
		return requireDhAdmin(exchange).flatMap(actor -> {
			AdminConfigUpdate input = DigitalHumanOperations.parseStrict(body, AdminConfigUpdate.class);
			return admin.updateConfig(actor, input);
		}).map(this::ok);
	}

	// ADMIN03
	@GetMapping("/api/admin/digital-human/sessions")
	public Mono<ResponseEntity<Map<String, Object>>> sessions(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) String profileId,
			@RequestParam(required = false) String state, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, ServerWebExchange exchange) {
		return requireDhAdmin(exchange)
				.flatMap(actor -> admin.sessions(cursor, limit == null ? 20 : limit, profileId, state, from, to))
				.map(this::ok);
	}

	// ADMIN04
	@PostMapping("/api/admin/digital-human/sessions/{id}/terminate")
	public Mono<ResponseEntity<Map<String, Object>>> terminate(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return requireDhAdmin(exchange).flatMap(actor -> {
			TerminateRequest request = DigitalHumanOperations.parseStrict(body, TerminateRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return admin.terminate(actor, id, request.requestId(), request.reason());
		}).map(this::ok);
	}

	// ADMIN05
	@GetMapping("/api/admin/digital-human/invocations")
	public Mono<ResponseEntity<Map<String, Object>>> invocations(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, ServerWebExchange exchange) {
		return requireDhAdmin(exchange).flatMap(actor -> admin.invocations(cursor, limit == null ? 20 : limit))
				.map(this::ok);
	}

	// ADMIN06
	@PostMapping("/api/admin/digital-human/invocations/{id}/reconcile")
	public Mono<ResponseEntity<Map<String, Object>>> reconcile(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return requireDhAdmin(exchange).flatMap(actor -> {
			ReconcileInput input = DigitalHumanOperations.parseStrict(body, ReconcileInput.class);
			return admin.reconcile(actor, id, input);
		}).map(this::ok);
	}

	/** K10 ADMIN04 请求体。 */
	public record TerminateRequest(UUID requestId, String reason) {
	}

	/** 鉴权先行：403 统一映射为契约错误码 dh_admin_required（401 语义保留）。 */
	private Mono<AdminActor> requireDhAdmin(ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).map(caller -> new AdminActor(caller.accountId()))
				.onErrorMap(error -> error instanceof IntelligenceException failure && failure.status() == 403
						? new IntelligenceException(403, "dh_admin_required", "需要平台管理员权限。")
						: error);
	}

	private ResponseEntity<Map<String, Object>> ok(Object data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", data));
	}
}
