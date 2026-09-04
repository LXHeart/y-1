package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.SessionPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 身份档案 + 活动身份 HTTP 入口。草场身份域 Slice 2G（开通）+ Slice 2I（活动身份 per-session/审计/多设备）。
 *
 * <ul>
 * <li>GET /api/me/identities — 列已开通身份。</li>
 * <li>POST /api/me/identities — 开通身份（2026-09-04 身份模型改版收紧：merchant 一律 403；
 * recommender 仅存量裸账号补开，已有档案 409；写 outbox {@code IdentityOpened}）。</li>
 * <li>GET /api/me/active-identity — 当前 session 活动身份（per-session；无记录=消费者；刷新
 * last_seen）。</li>
 * <li>POST /api/me/active-identity — 激活（须已开通，否则 409；按当前 session 写入 + 审计 +
 * outbox {@code ActiveIdentityChanged}）。</li>
 * <li>DELETE /api/me/active-identity — 切回消费者（当前 session active 置 NULL +
 * 审计）。</li>
 * </ul>
 *
 * <p>
 * 活动身份按 session（设备/标签）隔离（HLD D-08）：多设备互不影响；多设备清单与撤销见
 * {@code IdentitySessionController}。 开通端点经
 * {@link CurrentAccountResolver#resolve}；活动身份端点用
 * {@link CurrentAccountResolver#resolvePrincipal} 取 sid。
 */
@RestController
public class IdentityProfileController {

	private final CurrentAccountResolver accounts;
	private final IdentityProfileRepository profiles;
	private final IdentitySessionRepository sessions;
	private final IdentityAuditLogRepository audit;
	private final IdentitySessionPolicyProperties sessionPolicy;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public IdentityProfileController(CurrentAccountResolver accounts, IdentityProfileRepository profiles,
			IdentitySessionRepository sessions, IdentityAuditLogRepository audit,
			IdentitySessionPolicyProperties sessionPolicy, OutboxRepository outbox,
			TransactionalOperator transactions) {
		this.accounts = accounts;
		this.profiles = profiles;
		this.sessions = sessions;
		this.audit = audit;
		this.sessionPolicy = sessionPolicy;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	@GetMapping("/api/me/identities")
	public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
		return accounts.resolve(request)
				.flatMap(account -> profiles.findByAccount(account.id()).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::profileBody).toList()))));
	}

	/**
	 * 开通身份（2026-09-04 身份模型改版后仅「存量裸账号补开推荐官」）：
	 * <ul>
	 * <li>type=merchant → 403：商家身份唯一来源=治理台初始化，自助开通口子关死（任务书 #71 D2）。</li>
	 * <li>type=recommender 且账号已有任何身份档案 → 409（D5 双向收紧：已有档案的账号一律不可再加开）。</li>
	 * <li>type=recommender 且零档案 → 照旧建档案 + outbox {@code IdentityOpened}（D6
	 * 存量裸账号补开）。 organizationId 恒忽略（推荐官档案不挂主体）。</li>
	 * </ul>
	 */
	@PostMapping(value = "/api/me/identities", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenIdentityRequest body,
			ServerHttpRequest request) {
		return accounts.resolve(request).flatMap(account -> {
			IdentityType type = IdentityType.fromDb(body.type());
			if (type == IdentityType.MERCHANT) {
				return Mono.error(new IdentityException(403, "商家身份由平台初始化，不支持自助开通"));
			}
			return profiles.findByAccount(account.id()).collectList().flatMap(existing -> {
				if (!existing.isEmpty()) {
					return Mono.error(new IdentityException(409, "该账号已有身份档案，无需再开通"));
				}
				return transactions
						.transactional(profiles.create(account.id(), type.dbValue(), null)
								.flatMap(p -> outbox.append(new EventEnvelope(UUID.randomUUID().toString(),
										"IdentityOpened", "IdentityProfile", p.id(), 1, Instant.now(), null,
										profileEventPayload(p, account.id()))).thenReturn(p)))
						.map(p -> ResponseEntity.status(201).body(Map.of("success", true, "data", profileBody(p))));
			});
		}).onErrorResume(DataIntegrityViolationException.class,
				e -> Mono.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "该账号已有身份档案，无需再开通"))));
	}

	@GetMapping("/api/me/active-identity")
	public Mono<ResponseEntity<Map<String, Object>>> getActive(ServerHttpRequest request) {
		return accounts.resolvePrincipal(request)
				.flatMap(principal -> sessions.touchLastSeen(principal.sid())
						.then(sessions.findByToken(principal.sid()).map(s -> activeEnvelope(s.activeIdentityType()))
								.switchIfEmpty(Mono.just(activeEnvelope(null)))))
				.map(ResponseEntity::ok);
	}

	@PostMapping(value = "/api/me/active-identity", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> activate(@RequestBody ActivateIdentityRequest body,
			ServerHttpRequest request) {
		DeviceFingerprint fp = DeviceFingerprint.from(request);
		return accounts.resolvePrincipal(request).flatMap(principal -> {
			IdentityType type = IdentityType.fromDb(body.type());
			return profiles.findByAccountAndType(principal.user().id(), type.dbValue())
					.switchIfEmpty(Mono.error(new IdentityException(409, "未开通该身份，请先开通")))
					.flatMap(p -> currentActive(principal.sid())
							.flatMap(fromOpt -> applyActivate(principal, type, fromOpt.orElse(null), fp)))
					.map(s -> ResponseEntity.ok(activeEnvelope(s.activeIdentityType())));
		});
	}

	@DeleteMapping("/api/me/active-identity")
	public Mono<ResponseEntity<Map<String, Object>>> deactivate(ServerHttpRequest request) {
		DeviceFingerprint fp = DeviceFingerprint.from(request);
		return accounts.resolvePrincipal(request)
				.flatMap(principal -> currentActive(principal.sid()).flatMap(fromOpt -> {
					String fromType = fromOpt.orElse(null);
					if (fromType == null) {
						return Mono.just(activeEnvelope(null)); // 本就消费者，幂等 no-op（不审计、不发事件）
					}
					return transactions.transactional(sessions.deactivate(principal.sid())
							.then(audit.append(IdentityAuditAction.DEACTIVATE, principal.user().id(), fromType, null,
									principal.sid(), fp.deviceId(), fp.ipAddress(), fp.userAgent()))
							.then(outbox
									.append(new EventEnvelope(UUID.randomUUID().toString(), "ActiveIdentityChanged",
											"Account", principal.user().id(), 1, Instant.now(), null,
											Map.of("accountId", principal.user().id(), "activeIdentityType", "consumer",
													"sessionToken", principal.sid())))
									.thenReturn(activeEnvelope(null))));
				})).map(ResponseEntity::ok);
	}

	/**
	 * 当前 session 的旧活动身份（包成 Optional：无行或已 NULL → empty；命中 → of(type)）。规避 reactor 禁发
	 * null。
	 */
	private Mono<Optional<String>> currentActive(String sid) {
		return sessions.findByToken(sid).<Optional<String>>map(s -> Optional.ofNullable(s.activeIdentityType()))
				.defaultIfEmpty(Optional.empty());
	}

	/** 写 per-session 活动身份 + 审计 + outbox（激活/切换共用）。 */
	private Mono<IdentitySession> applyActivate(SessionPrincipal principal, IdentityType type, String fromType,
			DeviceFingerprint fp) {
		return transactions.transactional(lockForPolicy(principal.user().id()).then(sessions
				.activate(principal.sid(), principal.user().id(), type.dbValue(), fp.deviceId(), fp.deviceLabel(),
						fp.ipAddress(), fp.userAgent())
				.flatMap(s -> audit
						.append(IdentityAuditAction.ACTIVATE, principal.user().id(), fromType, type.dbValue(),
								principal.sid(), fp.deviceId(), fp.ipAddress(), fp.userAgent())
						.then(outbox.append(new EventEnvelope(UUID.randomUUID().toString(), "ActiveIdentityChanged",
								"Account", principal.user().id(), 1, Instant.now(), null,
								Map.of("accountId", principal.user().id(), "activeIdentityType", type.dbValue(),
										"sessionToken", principal.sid()))))
						.then(enforceSessionPolicy(principal.user().id())).thenReturn(s))));
	}

	private Mono<Void> lockForPolicy(String accountId) {
		return sessionPolicy.limited() ? sessions.lockAccount(accountId) : Mono.empty();
	}

	/** 超限设备只切回消费者，不删除登录会话；用户仍可在该设备重新选择身份。 */
	private Mono<Void> enforceSessionPolicy(String accountId) {
		if (!sessionPolicy.limited()) {
			return Mono.empty();
		}
		return sessions.findActiveOverflow(accountId, sessionPolicy.getMaxActivePerAccount())
				.concatMap(target -> sessions.deactivate(target.sessionToken())
						.then(audit.append(IdentityAuditAction.POLICY_DEACTIVATE, accountId,
								target.activeIdentityType(), null, target.sessionToken(), target.deviceId(),
								target.ipAddress(), target.userAgent()))
						.then(outbox.append(new EventEnvelope(UUID.randomUUID().toString(), "ActiveIdentityChanged",
								"Account", accountId, 1, Instant.now(), null,
								Map.of("accountId", accountId, "activeIdentityType", "consumer", "sessionToken",
										target.sessionToken(), "reason", "session_policy")))))
				.then();
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> profileBody(IdentityProfile p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", p.id());
		m.put("identityType", p.identityType());
		m.put("organizationId", p.organizationId());
		m.put("status", p.status());
		return m;
	}

	private Map<String, Object> profileEventPayload(IdentityProfile p, String accountId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("accountId", accountId);
		m.put("identityType", p.identityType());
		m.put("organizationId", p.organizationId());
		return m;
	}

	/** 活动身份响应包络；activeIdentityType 为 null 表示消费者。 */
	private Map<String, Object> activeEnvelope(String activeIdentityType) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("activeIdentityType", activeIdentityType);
		return Map.of("success", true, "data", data);
	}
}
