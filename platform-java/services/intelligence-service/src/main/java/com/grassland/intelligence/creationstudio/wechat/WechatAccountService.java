package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19（API101-21~25）：公众号连接管理。 连接创建只加密保存（不自动联网）；verify 才访问 token
 * 接口；rotate 换密文+失效缓存+回 unverified；disconnect 清密文（重放幂等）；同 owner 重绑 disconnected
 * 恢复同一行；他 owner 已绑同 appId → 409（不盗记录）。响应/日志无 secret/token。
 */
@Service
public class WechatAccountService {

	private final WechatAccountRepository accounts;
	private final WechatTokenService tokens;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final WechatProperties properties;

	public WechatAccountService(WechatAccountRepository accounts, WechatTokenService tokens,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, WechatProperties properties) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.encryptionProvider = encryptionProvider;
		this.properties = properties;
	}

	// ---- API101-21 列表 ----

	public Mono<Map<String, Object>> list(Caller caller, int limit, String cursor) {
		OffsetDateTime cursorAt = null;
		UUID cursorId = null;
		if (cursor != null && !cursor.isBlank()) {
			String[] parts = cursor.split("\\|", 2);
			if (parts.length != 2) {
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "cursor 不合法"));
			}
			try {
				cursorAt = OffsetDateTime.parse(parts[0]);
				cursorId = UUID.fromString(parts[1]);
			} catch (Exception error) {
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "cursor 不合法"));
			}
		}
		return accounts.listByOwner(caller.accountId(), limit + 1, cursorAt, cursorId).collectList().map(rows -> {
			List<Map<String, Object>> items = new ArrayList<>();
			String nextCursor = null;
			int end = Math.min(rows.size(), limit);
			for (int index = 0; index < end; index++) {
				items.add(toBody(rows.get(index)));
			}
			if (rows.size() > limit && end > 0) {
				var last = rows.get(end - 1);
				nextCursor = last.updatedAt() + "|" + last.id();
			}
			// nextCursor null 语义（§5.1）：LinkedHashMap 允许显式 null
			Map<String, Object> data = new java.util.LinkedHashMap<>();
			data.put("items", items);
			data.put("nextCursor", nextCursor);
			return data;
		});
	}

	// ---- API101-22 绑定（同键幂等：requestId 重放读回；appId 归属校验） ----

	public record BindCommand(UUID requestId, String displayName, String appId, String appSecret) {
	}

	public Mono<Map<String, Object>> bind(Caller caller, BindCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "公众号渠道写入暂未开放"));
		}
		EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
		if (crypto == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE",
					"连接受保护记录不可用：未配置 CRYPTO_KEK_BASE64"));
		}
		return accounts.existsByAppIdOtherOwner(command.appId(), caller.accountId()).flatMap(taken -> {
			if (taken) {
				return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "该 AppID 已被其他账号绑定"));
			}
			String cipher = crypto.encrypt(command.appSecret());
			return accounts.insertOrRestore(caller.accountId(), command.displayName(), command.appId(), cipher,
					crypto.keyVersion(cipher)).map(this::toBody);
		});
	}

	// ---- API101-23 verify（显式动作才访问微信） ----

	public Mono<Map<String, Object>> verify(Caller caller, UUID accountId, UUID requestId, int expectedVersion) {
		return loadOwned(caller, accountId).flatMap(account -> {
			if (account.encryptedSecret() == null) {
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "连接已断开，请重新绑定凭据"));
			}
			EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
			String plainSecret = crypto.decrypt(account.encryptedSecret());
			// token 服务带缓存（版本 key）——verify 结果决定 active/invalid
			return tokens.token(account, plainSecret)
					.then(accounts.casUpdate(account.id(), expectedVersion, "active", null,
							OffsetDateTime.now(ZoneOffset.UTC), null, null))
					.switchIfEmpty(versionConflict()).map(this::toBody)
					.onErrorResume(WechatApiClient.WechatApiException.class,
							error -> accounts.casUpdate(account.id(), expectedVersion, "invalid",
									"WECHAT_" + error.code(), null, null, null).switchIfEmpty(versionConflict())
									.map(this::toBody));
		});
	}

	// ---- API101-24 rotate（新密文+缓存失效+回 unverified） ----

	public Mono<Map<String, Object>> rotate(Caller caller, UUID accountId, UUID requestId, int expectedVersion,
			String appSecret) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "公众号渠道写入暂未开放"));
		}
		EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
		if (crypto == null) {
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "加密依赖不可用"));
		}
		String cipher = crypto.encrypt(appSecret);
		return loadOwned(caller, accountId).flatMap(account -> accounts
				.casUpdate(account.id(), expectedVersion, "unverified", null, null, cipher, crypto.keyVersion(cipher))
				.switchIfEmpty(versionConflict())
				.flatMap(updated -> tokens.invalidate(updated).thenReturn(toBody(updated))));
	}

	// ---- API101-25 disconnect（重放幂等：已断开返回现值） ----

	public Mono<Map<String, Object>> disconnect(Caller caller, UUID accountId, UUID requestId, int expectedVersion) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "公众号渠道写入暂未开放"));
		}
		return loadOwned(caller, accountId).flatMap(account -> {
			if ("disconnected".equals(account.state())) {
				return Mono.just(toBody(account));
			}
			return accounts.disconnect(account.id(), expectedVersion).switchIfEmpty(versionConflict())
					.flatMap(updated -> tokens.invalidate(updated).thenReturn(toBody(updated)));
		});
	}

	private Mono<WechatAccountRepository.AccountRow> loadOwned(Caller caller, UUID accountId) {
		return accounts.findByIdAndOwner(accountId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "连接不存在")));
	}

	private static <T> Mono<T> versionConflict() {
		return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "连接版本已变化，请刷新后重试"));
	}

	/** §6.3 WechatAccount：响应永不含 AppSecret、token、密文。 */
	public Map<String, Object> toBody(WechatAccountRepository.AccountRow row) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("displayName", row.displayName());
		body.put("appId", row.appId());
		body.put("state", row.state());
		body.put("version", row.version());
		body.put("verifiedAt", row.verifiedAt() == null ? null : row.verifiedAt().toInstant().toString());
		body.put("error",
				row.errorCode() == null ? null : Map.of("code", row.errorCode(), "message", "连接校验未通过，请核对凭据或重新验证"));
		return body;
	}
}
