package com.grassland.identity.auth;

import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.AuthUser;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * GET /api/auth/me — 返回当前登录账号。
 *
 * <p>
 * 身份解析委托 {@link CurrentAccountResolver}（Slice 2K 起 assertion 优先、cookie 回退）， 故
 * /api/auth/me 也能消费 edge-bff 签发的 {@code X-Grassland-Identity} 断言——不再各自重复 cookie
 * 解析逻辑。 账号停用守卫由 {@link CurrentAccountResolver} 统一执行，所有受保护端点口径一致。
 */
@RestController
public class MeController {
	private final CurrentAccountResolver accounts;
	private final AccountFlagRepository accountFlags;
	private final com.grassland.identity.user.UserLookup users;
	private final AuthUserResponseBuilder userResponses;

	public MeController(CurrentAccountResolver accounts, AccountFlagRepository accountFlags,
			com.grassland.identity.user.UserLookup users, AuthUserResponseBuilder userResponses) {
		this.accounts = accounts;
		this.accountFlags = accountFlags;
		this.users = users;
		this.userResponses = userResponses;
	}

	@GetMapping("/api/auth/me")
	public Mono<ResponseEntity<Map<String, Object>>> me(ServerHttpRequest request) {
		return accounts.resolve(request).flatMap(this::toResponse);
	}

	private Mono<ResponseEntity<Map<String, Object>>> toResponse(AuthUser user) {
		Mono<Boolean> mustChange = accountFlags.mustChangePassword(user.id()).defaultIfEmpty(Boolean.FALSE);
		// 任务书 #49 D11：子账号带登录名与 hasEmail（占位邮箱不当真邮箱展示）
		Mono<String> username = users.findUsernameById(user.id()).defaultIfEmpty("");
		return Mono.zip(mustChange, username)
				.flatMap(pair -> userResponses.build(user, Boolean.TRUE.equals(pair.getT1()), pair.getT2()))
				.map(userInfo -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("user", userInfo))));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}
}
