package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.creationstudio.StudioCommandStore;
import com.grassland.intelligence.creationstudio.StudioCursor;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Explicit, owner-scoped account commands. Retrying the same intent never
 * rotates credentials twice.
 */
@Service
public class WechatAccountService {
	private final WechatAccountRepository accounts;
	private final WechatTokenService tokens;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final WechatProperties properties;
	private final WechatDraftSyncRepository draftSyncs;
	private final StudioCommandStore commands;

	public WechatAccountService(WechatAccountRepository accounts, WechatTokenService tokens,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, WechatProperties properties,
			WechatDraftSyncRepository draftSyncs, StudioCommandStore commands) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.encryptionProvider = encryptionProvider;
		this.properties = properties;
		this.draftSyncs = draftSyncs;
		this.commands = commands;
	}
	public Mono<Map<String, Object>> list(Caller caller, int limit, String cursor) {
		var key = StudioCursor.parse(cursor);
		return accounts.listByOwner(caller.accountId(), limit + 1, key.createdAt(), key.id()).collectList()
				.map(rows -> {
					var body = new LinkedHashMap<String, Object>();
					int end = Math.min(limit, rows.size());
					body.put("items", rows.subList(0, end).stream().map(this::toBody).toList());
					body.put("nextCursor",
							rows.size() > limit
									? StudioCursor.encode(rows.get(end - 1).createdAt(), rows.get(end - 1).id())
									: null);
					return body;
				});
	}
	public record BindCommand(UUID requestId, String displayName, String appId, String appSecret) {
	}
	public Mono<Map<String, Object>> bind(Caller caller, BindCommand command) {
		return bindResult(caller, command).map(StudioCommandStore.Result::body);
	}
	public Mono<StudioCommandStore.Result> bindResult(Caller caller, BindCommand command) {
		String appId = command.appId().toLowerCase(java.util.Locale.ROOT);
		String hash = PlanJson.sha256(PlanJson.json(Map.of("appId", appId, "displayName", command.displayName(),
				"secretHash", PlanJson.sha256(command.appSecret()))));
		return commands.execute(caller.accountId(), "wechat-bind", command.requestId(), hash, () -> {
			requireWrites();
			EnvelopeEncryption crypto = crypto();
			if (command.displayName().isBlank())
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "账号名称不能为空"));
			return accounts.existsByAppIdOtherOwner(appId, caller.accountId()).flatMap(taken -> {
				if (taken)
					return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "该 AppID 已被其他账号绑定"));
				String cipher = crypto.encrypt(command.appSecret());
				return accounts
						.insertOrRestore(caller.accountId(), command.displayName(), appId, cipher,
								crypto.keyVersion(cipher))
						.map(row -> new StudioCommandStore.Result(row.id(), row.version(), toBody(row)));
			});
		}, id -> loadOwned(caller, id)).onErrorMap(org.springframework.dao.DataIntegrityViolationException.class,
				error -> new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "该 AppID 已被绑定，请刷新连接列表"));
	}
	public Mono<Map<String, Object>> verify(Caller caller, UUID id, UUID requestId, int expectedVersion) {
		return mutate(caller, id, requestId, expectedVersion, "verify", "", account -> {
			if (account.encryptedSecret() == null)
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "连接已断开，请重新绑定凭据"));
			return tokens.token(account, crypto().decrypt(account.encryptedSecret()))
					.then(accounts.casUpdate(id, expectedVersion, "active", null, OffsetDateTime.now(ZoneOffset.UTC),
							null, null))
					.onErrorResume(WechatApiClient.WechatApiException.class, error -> accounts.casUpdate(id,
							expectedVersion, "invalid", "WECHAT_" + error.code(), null, null, null));
		});
	}
	public Mono<Map<String, Object>> rotate(Caller caller, UUID id, UUID requestId, int version, String secret) {
		return mutate(caller, id, requestId, version, "rotate", PlanJson.sha256(secret), account -> {
			if ("disconnected".equals(account.state()))
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "连接已断开，请重新绑定"));
			var crypto = crypto();
			String cipher = crypto.encrypt(secret);
			return accounts.casUpdate(id, version, "unverified", null, null, cipher, crypto.keyVersion(cipher))
					.flatMap(updated -> tokens.invalidate(account).thenReturn(updated));
		});
	}
	public Mono<Map<String, Object>> disconnect(Caller caller, UUID id, UUID requestId, int version) {
		return mutate(caller, id, requestId, version, "disconnect", "",
				account -> "disconnected".equals(account.state())
						? Mono.just(account)
						: accounts.disconnect(id, version).flatMap(updated -> draftSyncs.cancelPendingForAccount(id)
								.then(tokens.invalidate(account)).thenReturn(updated)));
	}
	private Mono<Map<String, Object>> mutate(Caller caller, UUID id, UUID requestId, int version, String kind,
			String secretHash,
			Function<WechatAccountRepository.AccountRow, Mono<WechatAccountRepository.AccountRow>> action) {
		String hash = PlanJson
				.sha256(PlanJson.json(Map.of("id", id.toString(), "version", version, "secretHash", secretHash)));
		return commands.execute(caller.accountId(), "wechat-" + kind, requestId, hash, () -> {
			requireWrites();
			return loadOwned(caller, id).flatMap(account -> {
				if (account.version() != version)
					return versionConflict();
				return action.apply(account).switchIfEmpty(versionConflict())
						.map(row -> new StudioCommandStore.Result(id, row.version(), toBody(row)));
			});
		}, resource -> loadOwned(caller, resource)).map(StudioCommandStore.Result::body);
	}
	private void requireWrites() {
		if (!properties.isWritesEnabled())
			throw new IntelligenceException(404, "STUDIO_DISABLED", "公众号渠道写入暂未开放");
	}
	private EnvelopeEncryption crypto() {
		var crypto = encryptionProvider.getIfAvailable();
		if (crypto == null)
			throw new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "加密依赖不可用");
		return crypto;
	}
	private Mono<WechatAccountRepository.AccountRow> loadOwned(Caller caller, UUID id) {
		return accounts.findByIdAndOwner(id, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "连接不存在")));
	}
	private static <T> Mono<T> versionConflict() {
		return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "连接版本已变化，请刷新后重试"));
	}
	public Map<String, Object> toBody(WechatAccountRepository.AccountRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
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
