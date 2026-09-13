package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-21~25（C101-19）：公众号连接 HTTP wire。 字段白名单封闭；secret 只进不出的绑定/轮换字段。
 */
@RestController
public class WechatAccountController {

	private static final Set<String> BIND_FIELDS = Set.of("requestId", "displayName", "appId", "appSecret");
	private static final Set<String> VERIFY_FIELDS = Set.of("requestId", "expectedVersion");
	private static final Set<String> ROTATE_FIELDS = Set.of("requestId", "expectedVersion", "appSecret");
	private static final Set<String> DISCONNECT_FIELDS = Set.of("requestId", "expectedVersion");

	private final IntelligenceCallerResolver callers;
	private final WechatAccountService accounts;

	public WechatAccountController(IntelligenceCallerResolver callers, WechatAccountService accounts) {
		this.callers = callers;
		this.accounts = accounts;
	}

	@GetMapping("/api/creation-channels/wechat/accounts")
	public Mono<Map<String, Object>> list(@RequestParam(value = "limit", defaultValue = "20") int limit,
			@RequestParam(value = "cursor", required = false) String cursor, ServerWebExchange exchange) {
		if (limit < 1 || limit > 50) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "limit 范围 1~50");
		}
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> accounts.list(caller, limit, cursor))
				.map(CreationWechatBodies::success);
	}

	@PostMapping("/api/creation-channels/wechat/accounts")
	public Mono<ResponseEntity<Map<String, Object>>> bind(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, BIND_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		String displayName = StudioRequestValidator.requireString(body, "displayName", 80);
		String appId = validAppId(body);
		String appSecret = validAppSecret(body);
		var command = new WechatAccountService.BindCommand(requestId, displayName, appId, appSecret);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> accounts.bind(caller, command))
				.map(data -> ResponseEntity.status(HttpStatus.CREATED).body(CreationWechatBodies.success(data)));
	}

	/** §6.8：appId 匹配 `wx` + 16 位十六进制。 */
	private static String validAppId(Map<String, Object> body) {
		String appId = StudioRequestValidator.requireString(body, "appId", 18);
		if (!appId.matches("wx[0-9a-fA-F]{16}")) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "appId 须为 wx 加 16 位十六进制");
		}
		return appId;
	}

	/** §6.8：appSecret 为 16～512 位可见字符，不允许空格／换行／控制字符。 */
	private static String validAppSecret(Map<String, Object> body) {
		String appSecret = StudioRequestValidator.requireString(body, "appSecret", 512);
		if (appSecret.codePoints().count() < 16) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "appSecret 至少 16 位");
		}
		if (appSecret.chars().anyMatch(value -> Character.isISOControl(value) || value == ' ')) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "appSecret 不允许空格／换行／控制字符");
		}
		return appSecret;
	}

	@PostMapping("/api/creation-channels/wechat/accounts/{id}/verify")
	public Mono<Map<String, Object>> verify(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		return mutate(id, body, VERIFY_FIELDS, exchange, (caller, accountId, requestId, version, secret) -> accounts
				.verify(caller, accountId, requestId, version));
	}

	@PostMapping("/api/creation-channels/wechat/accounts/{id}/rotate")
	public Mono<Map<String, Object>> rotate(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		return mutate(id, body, ROTATE_FIELDS, exchange, (caller, accountId, requestId, version, secret) -> accounts
				.rotate(caller, accountId, requestId, version, secret));
	}

	@PostMapping("/api/creation-channels/wechat/accounts/{id}/disconnect")
	public Mono<Map<String, Object>> disconnect(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		return mutate(id, body, DISCONNECT_FIELDS, exchange, (caller, accountId, requestId, version, secret) -> accounts
				.disconnect(caller, accountId, requestId, version));
	}

	private Mono<Map<String, Object>> mutate(String id, Map<String, Object> body, Set<String> fields,
			ServerWebExchange exchange, Mutation action) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, fields);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedVersion = StudioRequestValidator.requireInt(body, "expectedVersion");
		String secret = fields.contains("appSecret") ? validAppSecret(body) : null;
		UUID accountId;
		try {
			accountId = UUID.fromString(id);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "连接不存在");
		}
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> action.run(caller, accountId, requestId, expectedVersion, secret))
				.map(CreationWechatBodies::success);
	}

	private interface Mutation {

		Mono<Map<String, Object>> run(IntelligenceCallerResolver.Caller caller, UUID accountId, UUID requestId,
				int expectedVersion, String appSecret);
	}
}
