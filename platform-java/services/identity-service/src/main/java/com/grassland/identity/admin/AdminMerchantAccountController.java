package com.grassland.identity.admin;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.IdentityProfile;
import com.grassland.identity.identityprofile.IdentityProfileRepository;
import com.grassland.identity.identityprofile.IdentityType;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.subaccount.PasswordGenerator;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 治理台「初始化商家账号」（任务书 #71 D2/D4/D5）：商家身份唯一来源。
 *
 * <p>
 * 一次事务完成「插 app_users（仅全新邮箱，冲突 409）→ account_flag 首登改密置位 → 建 merchant 身份档案（org
 * 后建）→ outbox IdentityOpened」。一次性初始密码只在本响应 出现（不写任何日志）；商家主体/KYB
 * 由商家登录后自建（D4），故不建组织、不发邀请邮件。
 *
 * <p>
 * D10：只发 IdentityOpened——不发 UserRegistered（语义=自助注册，平台初始化不该触发 新用户统计/营销）、不送 3
 * 积分注册赠礼。
 */
@RestController
public class AdminMerchantAccountController {

	private final CurrentAccountResolver accounts;
	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final Argon2PasswordHasher argon2Hasher;
	private final IdentityProfileRepository identityProfiles;
	private final AccountFlagRepository flags;
	private final OutboxRepository outbox;

	public AdminMerchantAccountController(CurrentAccountResolver accounts, DatabaseClient db,
			TransactionalOperator transactions, Argon2PasswordHasher argon2Hasher,
			IdentityProfileRepository identityProfiles, AccountFlagRepository flags, OutboxRepository outbox) {
		this.accounts = accounts;
		this.db = db;
		this.transactions = transactions;
		this.argon2Hasher = argon2Hasher;
		this.identityProfiles = identityProfiles;
		this.flags = flags;
		this.outbox = outbox;
	}

	@PostMapping(value = "/api/admin/merchant-accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> initMerchantAccount(@RequestBody MerchantAccountInitRequest body,
			ServerHttpRequest request) {
		return accounts.requireAdmin(request).flatMap(admin -> {
			String email = validateEmail(body.email());
			String displayName = validateDisplayName(body.displayName());
			String initialPassword = PasswordGenerator.generate();
			// 与 RegisterController 同款约束：Argon2 是 64MB/3 轮重操作，必须离开 Netty 事件循环。
			return Mono.fromCallable(() -> argon2Hasher.hash(initialPassword)).subscribeOn(Schedulers.boundedElastic())
					.flatMap(hash -> transactions.transactional(db
							.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
									+ "VALUES (CAST(:id AS uuid), :email, :hash, :name, 'user', 'active') "
									+ "ON CONFLICT (email) DO NOTHING RETURNING id::text")
							.bind("id", UUID.randomUUID().toString()).bind("email", email).bind("hash", hash)
							.bind("name", displayName).map(r -> r.get(0, String.class)).one()
							// 0 行 = 邮箱已注册：仅支持全新邮箱初始化（D5，防并发竞速）
							.switchIfEmpty(Mono.error(new IdentityException(409, "该邮箱已注册；商家账号仅支持全新邮箱初始化")))
							.flatMap(uid -> flags.markMustChangePassword(uid)
									.then(identityProfiles.create(uid, IdentityType.MERCHANT.dbValue(), null))
									.flatMap(profile -> appendIdentityOpened(profile)
											.thenReturn(new InitializedAccount(uid, initialPassword))))))
					.map(created -> ResponseEntity.status(201).body(successBody(email, displayName, created)));
		});
	}

	private Mono<Void> appendIdentityOpened(IdentityProfile profile) {
		return outbox.append(new EventEnvelope(UUID.randomUUID().toString(), "IdentityOpened", "IdentityProfile",
				profile.id(), 1, Instant.now(), null,
				Map.of("accountId", profile.accountId(), "identityType", profile.identityType())));
	}

	private static Map<String, Object> successBody(String email, String displayName, InitializedAccount created) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("userId", created.accountId());
		data.put("email", email);
		data.put("displayName", displayName);
		// 一次性明文：仅本响应出现，此后任何接口不可再取（同子账号直建 D2/D3）。
		data.put("initialPassword", created.initialPassword());
		data.put("mustChangePassword", true);
		return Map.of("success", true, "data", data);
	}

	private static String validateEmail(String value) {
		String email = value == null ? "" : value.trim().toLowerCase();
		if (email.isEmpty() || !email.contains("@")) {
			throw new IllegalArgumentException("email 必填且须包含 @");
		}
		return email;
	}

	private static String validateDisplayName(String value) {
		String name = value == null ? "" : value.trim();
		if (name.isEmpty() || name.length() > 80) {
			throw new IllegalArgumentException("姓名必填且不超过 80 字");
		}
		return name;
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
		return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
	}

	/** 初始化商家账号请求体：两字段都必填（可选字段包装类型的 Jackson 3 惯例此处不涉及）。 */
	public record MerchantAccountInitRequest(String email, String displayName) {
	}

	private record InitializedAccount(String accountId, String initialPassword) {
	}
}
