package com.grassland.identity.auth;

import com.grassland.identity.identityprofile.DeviceFingerprint;
import com.grassland.identity.identityprofile.IdentityAuditAction;
import com.grassland.identity.identityprofile.IdentityAuditLogRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.session.SessionWriter;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LoginUser;
import com.grassland.identity.user.UserLookup;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 跨应用一次性免登（任务书 #76 卡 A；任务书 #86 加 audience 绑定）：草场 ⇄ AI 应用双向对称，
 * 共用同一对端点。
 *
 * <p>
 * 两 origin 打的是同一个后端（会话不共享纯粹因为浏览器 cookie jar 按 origin 隔离）， 所以核销成功即在响应上
 * Set-Cookie 建立本 origin 会话。token 载荷绑定目标应用（audience ∈ grassland/ai）与签发来源
 * （source，仅溯源）；核销请求的 audience 必须与载荷匹配（错配即烧毁），且在该 audience 配置了
 * 受信 origin 列表时校验 Origin 头（不匹配不烧毁，可换正确 Origin 重试；校验顺序见各方法注释）。
 *
 * <p>
 * 审计：签发与核销各落一条（账号、时间、来源 IP/UA/设备指纹），禁止落 token 明文。
 */
@RestController
public class CrossAppTokenController {
	private static final String EXCHANGE_INVALID = "登录凭证无效或已过期，请重新从应用内跳转";
	private static final String INVALID_AUDIENCE = "无效的目标应用";

	private final CurrentAccountResolver accounts;
	private final CrossAppTokenStore store;
	private final CrossAppAudienceOrigins audienceOrigins;
	private final SessionWriter sessionWriter;
	private final UserLookup userLookup;
	private final AccountFlagRepository accountFlags;
	private final IdentityAuditLogRepository audit;

	public CrossAppTokenController(CurrentAccountResolver accounts, CrossAppTokenStore store,
			CrossAppAudienceOrigins audienceOrigins, SessionWriter sessionWriter, UserLookup userLookup,
			AccountFlagRepository accountFlags, IdentityAuditLogRepository audit) {
		this.accounts = accounts;
		this.store = store;
		this.audienceOrigins = audienceOrigins;
		this.sessionWriter = sessionWriter;
		this.userLookup = userLookup;
		this.accountFlags = accountFlags;
		this.audit = audit;
	}

	/**
	 * 签发：要求已登录会话（401 优先于 body 校验，保住「无 body 未登录 → 请先登录」既有契约）；
	 * body 必填 {@code audience}（缺失/空白/未知值 → 400）。source 由服务端按 Origin 头与配置推导，
	 * 入载荷仅作溯源。返回不透明随机串（前端拼 ?xat=… 跳转，参数名定死）。
	 */
	@PostMapping("/api/auth/cross-app-tokens")
	public Mono<ResponseEntity<Map<String, Object>>> issue(
			@RequestBody(required = false) Mono<CrossAppTokenIssueRequest> bodyMono, ServerHttpRequest request) {
		return accounts.resolve(request).flatMap(user -> {
			// 无 body（旧客户端）补默认 record 再校验；switchIfEmpty 只补纯值，无副作用，无需 defer。
			Mono<CrossAppTokenIssueRequest> body = bodyMono.switchIfEmpty(Mono.just(new CrossAppTokenIssueRequest(null)));
			return body.flatMap(requestBody -> {
				String audience = requestBody.audience() == null ? null : requestBody.audience().trim();
				if (!CrossAppAudienceOrigins.validAudience(audience)) {
					return Mono.just(build400InvalidAudience());
				}
				String source = audienceOrigins.audienceOf(request.getHeaders().getFirst(HttpHeaders.ORIGIN));
				DeviceFingerprint fingerprint = DeviceFingerprint.from(request);
				return store.issue(user.id(), source, audience)
						.flatMap(token -> audit
								.append(IdentityAuditAction.CROSS_APP_TOKEN_ISSUE, user.id(), null, null, null,
										fingerprint.deviceId(), fingerprint.ipAddress(), fingerprint.userAgent())
								.thenReturn(ResponseEntity.ok()
										.body(Map.of("success", true, "data",
												Map.of("token", token, "expiresInSeconds", store.ttlSeconds())))))
						.onErrorResume(CrossAppTokenStoreException.class, e -> Mono.just(build503()));
			});
		});
	}

	/**
	 * 核销：原子单次（GETDEL）；成功即按载荷账号建会话 cookie。失败一律 401 统一文案（前端落登录页）。
	 *
	 * <p>
	 * 校验顺序（任务书 #86 §5.3 红线，不得调整或合并分支）： ① token 形态门禁（401，不触 Redis）→
	 * ② audience 枚举校验（400，请求本身不合格）→ ③ Origin 门禁（401，不触 Redis、token 未烧毁，
	 * 可换正确 Origin 重试）→ ④ GETDEL + 载荷解析 + audience 匹配（任一失败 401，token 已烧毁）。
	 */
	@PostMapping(value = "/api/auth/cross-app-tokens/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> exchange(@RequestBody Mono<CrossAppTokenExchangeRequest> bodyMono,
			ServerHttpRequest request) {
		return bodyMono.flatMap(body -> {
			String token = body.token();
			if (!CrossAppTokenStore.wellFormed(token)) {
				return Mono.just(buildExchange401());
			}
			String audience = body.audience() == null ? null : body.audience().trim();
			if (!CrossAppAudienceOrigins.validAudience(audience)) {
				return Mono.just(build400InvalidAudience());
			}
			if (!audienceOrigins.allows(audience, request.getHeaders().getFirst(HttpHeaders.ORIGIN))) {
				return Mono.just(buildExchange401());
			}
			return store.exchange(token)
					.flatMap(payload -> audience.equals(payload.audience())
							? loginFor(payload.accountId(), request)
							: Mono.just(buildExchange401()))
					.defaultIfEmpty(buildExchange401());
		});
	}

	private Mono<ResponseEntity<Map<String, Object>>> loginFor(String accountId, ServerHttpRequest request) {
		return userLookup.findLoginUserById(accountId).flatMap(user -> {
			if (!user.isActive()) {
				return Mono.just(buildAccountBlocked(user.status()));
			}
			AuthUser authUser = new AuthUser(user.id(), user.email(), user.displayName(), user.role(), user.status());
			Mono<Boolean> mustChange = accountFlags.mustChangePassword(user.id()).defaultIfEmpty(Boolean.FALSE);
			Mono<String> username = userLookup.findUsernameById(user.id()).defaultIfEmpty("");
			DeviceFingerprint fingerprint = DeviceFingerprint.from(request);
			return sessionWriter.createSession(authUser, request)
					.flatMap(created -> Mono.zip(mustChange, username).flatMap(pair -> audit
							.append(IdentityAuditAction.CROSS_APP_TOKEN_EXCHANGE, user.id(), null, null, created.sid(),
									fingerprint.deviceId(), fingerprint.ipAddress(), fingerprint.userAgent())
							.thenReturn(buildExchange200(authUser, created.setCookieHeader(),
									Boolean.TRUE.equals(pair.getT1()), pair.getT2()))));
		})
				// switchIfEmpty 的参数在装配期求值（本项目两次实锤）：副作用路径必须包 defer。
				.switchIfEmpty(Mono.defer(() -> Mono.just(buildExchange401())));
	}

	private ResponseEntity<Map<String, Object>> buildExchange200(AuthUser user, String setCookie,
			boolean mustChangePassword, String username) {
		Map<String, Object> userInfo = new LinkedHashMap<>();
		userInfo.put("id", user.id());
		userInfo.put("email", user.email());
		if (username != null && !username.isBlank()) {
			userInfo.put("username", username);
		}
		userInfo.put("hasEmail", user.email() != null && !user.email().endsWith("@sub.grassland.invalid"));
		if (user.displayName() != null && !user.displayName().isBlank()) {
			userInfo.put("displayName", user.displayName());
		}
		userInfo.put("role", user.role());
		userInfo.put("mustChangePassword", mustChangePassword);
		return ResponseEntity.ok().header("Set-Cookie", setCookie)
				.body(Map.of("success", true, "data", Map.of("user", userInfo)));
	}

	private ResponseEntity<Map<String, Object>> buildExchange401() {
		return ResponseEntity.status(401).body(Map.of("success", false, "error", EXCHANGE_INVALID));
	}

	/** 请求侧 audience 非法（缺失/空白/未知值）：请求本身不合格，400（无探测价值，D-07）。 */
	private ResponseEntity<Map<String, Object>> build400InvalidAudience() {
		return ResponseEntity.status(400).body(Map.of("success", false, "error", INVALID_AUDIENCE));
	}

	private ResponseEntity<Map<String, Object>> buildAccountBlocked(String status) {
		String message;
		if ("pending_review".equalsIgnoreCase(status)) {
			message = "账号待商家主体审核通过后再登录";
		} else if ("suspended".equalsIgnoreCase(status)) {
			message = "账号已停用，请联系商家管理员";
		} else {
			message = "当前账号不可用";
		}
		return ResponseEntity.status(403).body(Map.of("success", false, "error", message));
	}

	private ResponseEntity<Map<String, Object>> build503() {
		return ResponseEntity.status(503).body(Map.of("success", false, "error", "免登服务暂不可用，请稍后重试"));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	@ExceptionHandler(CrossAppTokenStoreException.class)
	public ResponseEntity<Map<String, Object>> handleStoreUnavailable(CrossAppTokenStoreException error) {
		return build503();
	}

	/** Jackson record 陷阱规避：可选字段一律包装类型（primitive 缺失直接 400）。 */
	public record CrossAppTokenExchangeRequest(String token, String audience) {
	}

	public record CrossAppTokenIssueRequest(String audience) {
	}
}
