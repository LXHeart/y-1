package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitChangesetService;
import com.grassland.intelligence.hypit.project.HypitChangesetService.ChangesetRow;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.studio.HypitSessionRepository;
import com.grassland.intelligence.hypit.studio.HypitSessionRepository.SessionRow;
import com.grassland.intelligence.hypit.studio.HypitStudioSessionService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Studio 会话接入端点（107-fix-2 C107F2-19 / §6.9 API-10）。
 *
 * <p>
 * {@code GET /api/hypit/sessions/{sid}/access}：Nginx auth_request（经 Edge
 * 注入站内身份）调用； 校验 owner/active/expiry 后签 30 秒内部断言（HMAC 用途 {@code assertion|}，aud
 * {@code hypit-session-proxy}），204 无 JSON、no-store。带 {@code ticket}
 * 参数时先验票并一次性核销 （nonce 单槽 CAS）——本端点是用户身份入口，不吃内部 Bearer。
 *
 * <p>
 * {@code POST /internal/hypit/sessions/{sid}/writeback}：broker 写回桥入口（内部
 * Bearer）。 以会话 owner 身份走既有 changeset create+apply（版本控制权威在 Java，Studio 不直写），
 * 成功后推进会话基线 revision；CAS 冲突原样 409（页面侧拒收且文件零变更）。
 */
@RestController
public class HypitSessionAccessController {

	static final String ASSERTION_HEADER = "X-Hypit-Session-Assertion";
	private static final String ASSERTION_AUDIENCE = "hypit-session-proxy";
	private static final Duration ASSERTION_TTL = Duration.ofSeconds(30);

	private final IntelligenceCallerResolver callers;
	private final HypitProperties properties;
	private final HypitSessionRepository sessions;
	private final HypitChangesetService changesets;

	public HypitSessionAccessController(IntelligenceCallerResolver callers, HypitProperties properties,
			HypitSessionRepository sessions, HypitChangesetService changesets) {
		this.callers = callers;
		this.properties = properties;
		this.sessions = sessions;
		this.changesets = changesets;
	}

	@GetMapping("/api/hypit/sessions/{sessionId}/access")
	public Mono<ResponseEntity<Void>> access(@PathVariable String sessionId,
			@RequestParam(value = "ticket", required = false) String ticket, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> sessions.findById(sessionId)
						.switchIfEmpty(Mono.error(HypitAccessErrors.sessionNotFound()))
						.flatMap(session -> requireUsable(session).then(Mono.defer(() -> {
							if (!session.accountId().equals(caller.accountId())) {
								throw HypitAccessErrors.forbidden();
							}
							if (ticket != null && !ticket.isBlank()) {
								return redeemTicket(session, ticket).map(this::assertionOf);
							}
							return Mono.just(assertionOf(session));
						}))))
				.map(assertion -> ResponseEntity.noContent().header(ASSERTION_HEADER, assertion)
						.cacheControl(CacheControl.noStore()).build());
	}

	public record WritebackBody(String path, String content, Long baseRevision, String baseHash, String requestId) {
	}

	@PostMapping("/internal/hypit/sessions/{sessionId}/writeback")
	public Mono<ResponseEntity<java.util.Map<String, Object>>> writeback(@PathVariable String sessionId,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody WritebackBody body) {
		return Mono.fromCallable(() -> {
			requireInternalToken(authorization);
			if (body == null || isBlank(body.path()) || body.content() == null) {
				throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
						"writeback 需要 path 与 content。");
			}
			if (body.baseRevision() == null || body.baseRevision() <= 0 || body.requestId() == null) {
				throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
						"writeback 需要 baseRevision 与 requestId。");
			}
			return UUID.fromString(body.requestId());
		}).flatMap(
				requestId -> sessions.findById(sessionId).switchIfEmpty(Mono.error(HypitAccessErrors.sessionNotFound()))
						.flatMap(session -> requireUsable(session).then(Mono.defer(() -> {
							if (session.readOnly()) {
								throw HypitAccessErrors.forbidden();
							}
							FileChange change = new FileChange(body.path(), "put", body.content(), body.baseHash());
							return changesets
									.create(session.accountId(), session.projectId(), requestId, body.baseRevision(),
											"save", List.of(change))
									.flatMap(draft -> applyWriteback(session, draft, requestId));
						}))));
	}

	private Mono<ResponseEntity<java.util.Map<String, Object>>> applyWriteback(SessionRow session, ChangesetRow draft,
			UUID requestId) {
		return changesets
				.apply(session.accountId(), session.projectId(), draft.id(), UUID.randomUUID(), draft.baseRevision())
				.flatMap(result -> sessions.advanceRevision(session.id(), result.revision()).then(Mono.just(result)))
				.map(result -> ResponseEntity.ok().body(java.util.Map.of("revision", result.revision(), "manifestHash",
						result.manifestHash() == null ? "" : result.manifestHash())));
	}

	private Mono<Void> requireUsable(SessionRow session) {
		if ("revoked".equals(session.state()) || session.revokedAt() != null) {
			return Mono.error(HypitAccessErrors.forbidden());
		}
		if (!"active".equals(session.state())) {
			return Mono.error(
					new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict", "会话已失效，请重新打开。"));
		}
		if (session.expiresAt() != null && !session.expiresAt().isAfter(Instant.now())) {
			// 惰性转 expired（§7.1 终态），再拒绝。
			return sessions.markExpired(session.id()).then(Mono.error(
					new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict", "会话已过期，请重新打开。")));
		}
		return Mono.empty();
	}

	private void requireInternalToken(String authorization) {
		if (!properties.enabled() || properties.internalToken().length() < 32) {
			throw new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled", "hypit 写回桥未启用");
		}
		if (authorization == null || !authorization.equals("Bearer " + properties.internalToken())) {
			throw new IntelligenceException(HttpStatus.UNAUTHORIZED.value(), "hypit_unauthenticated", "写回桥 token 无效");
		}
	}

	/** 验票：sid/expMilli/nonce/HMAC（用途 {@code ticket|}）全对 + 未过期，再核销 nonce 单槽。 */
	private Mono<SessionRow> redeemTicket(SessionRow session, String ticket) {
		String[] parts = ticket.split("\\.");
		if (parts.length != 4 || !session.id().equals(parts[0])) {
			throw HypitAccessErrors.forbidden();
		}
		long expMilli;
		try {
			expMilli = Long.parseLong(parts[1]);
		} catch (NumberFormatException error) {
			throw HypitAccessErrors.forbidden();
		}
		String payload = parts[0] + "." + parts[1] + "." + parts[2];
		if (expMilli < Instant.now().toEpochMilli()
				|| !MessageDigest.isEqual(hmac("ticket|" + payload).getBytes(StandardCharsets.UTF_8),
						parts[3].getBytes(StandardCharsets.UTF_8))) {
			throw HypitAccessErrors.forbidden();
		}
		return sessions.redeemTicket(session.id(), HypitStudioSessionService.sha256Hex(parts[2]))
				.switchIfEmpty(Mono.error(HypitAccessErrors.forbidden()));
	}

	/**
	 * 30 秒断言：base64url(claims).hexHMAC("assertion|"+segment)——与 broker
	 * verifySessionAssertion 对齐。
	 */
	private String assertionOf(SessionRow session) {
		String nonce = UUID.randomUUID().toString().replace("-", "");
		long exp = Instant.now().plus(ASSERTION_TTL).toEpochMilli();
		String claims = "{\"sid\":\"" + session.id() + "\",\"projectId\":\"" + session.projectId()
				+ "\",\"ownerAccountId\":\"" + session.accountId() + "\",\"revision\":" + session.revision()
				+ ",\"readOnly\":" + session.readOnly() + ",\"exp\":" + exp + ",\"aud\":\"" + ASSERTION_AUDIENCE
				+ "\",\"nonce\":\"" + nonce + "\"}";
		String segment = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
		return segment + "." + hmac("assertion|" + segment);
	}

	private String hmac(String payloadWithPurpose) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.sessionAssertionSecret().getBytes(StandardCharsets.UTF_8),
					"HmacSHA256"));
			return hex(mac.doFinal(payloadWithPurpose.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("session assertion signing failed", error);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			out.append(String.format("%02x", b));
		}
		return out.toString();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 会话面专用错误（403 不区分原因，避免探测面给会话状态泄露）。 */
	static final class HypitAccessErrors {

		static IntelligenceException forbidden() {
			return new IntelligenceException(HttpStatus.FORBIDDEN.value(), "hypit_forbidden", "无权访问该会话。");
		}

		static IntelligenceException sessionNotFound() {
			return new IntelligenceException(HttpStatus.NOT_FOUND.value(), "hypit_not_found", "会话不存在。");
		}

		private HypitAccessErrors() {
		}
	}
}
