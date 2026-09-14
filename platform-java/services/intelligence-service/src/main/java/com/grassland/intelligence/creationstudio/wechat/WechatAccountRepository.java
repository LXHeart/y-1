package com.grassland.intelligence.creationstudio.wechat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19：公众号连接持久层。 owner+appId 唯一（重绑恢复同一行）；断开清密文；版本乐观锁（CAS）。
 */
@Component
public class WechatAccountRepository {

	private final DatabaseClient db;

	public WechatAccountRepository(DatabaseClient db) {
		this.db = db;
	}

	public record AccountRow(UUID id, String ownerAccountId, String displayName, String appId, String encryptedSecret,
			String secretKeyVersion, String state, int version, OffsetDateTime verifiedAt, String errorCode,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	private static final String COLS = "id, owner_account_id, display_name, app_id, encrypted_secret,"
			+ " secret_key_version, state, version, verified_at, error_code, created_at, updated_at";

	public Mono<AccountRow> insertOrRestore(String ownerAccountId, String displayName, String appId,
			String encryptedSecret, String secretKeyVersion) {
		return db.sql("""
				INSERT INTO creation_wechat_account (id, owner_account_id, display_name, app_id,
				    encrypted_secret, secret_key_version, state, version)
				VALUES (CAST(:id AS uuid), :owner, :name, :appId, :secret, :keyVersion, 'unverified', 1)
				ON CONFLICT (owner_account_id, app_id) DO UPDATE SET
				    encrypted_secret = EXCLUDED.encrypted_secret,
				    display_name = EXCLUDED.display_name,
				    secret_key_version = EXCLUDED.secret_key_version,
				    state = 'unverified', error_code = NULL, verified_at = NULL,
				    version = creation_wechat_account.version + 1, updated_at = now()
				WHERE creation_wechat_account.state = 'disconnected'
				""").bind("id", UUID.randomUUID().toString()).bind("owner", ownerAccountId).bind("name", displayName)
				.bind("appId", appId).bind("secret", encryptedSecret).bind("keyVersion", secretKeyVersion).fetch()
				.rowsUpdated()
				.flatMap(count -> count > 0
						? findByOwnerAndAppId(ownerAccountId, appId)
						: Mono.error(new com.grassland.intelligence.security.IntelligenceException(409,
								"STUDIO_OPERATION_CONFLICT", "该 AppID 已连接，请使用轮换凭据操作")));
	}

	public Mono<AccountRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM creation_wechat_account WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).map(WechatAccountRepository::map).one();
	}

	public Mono<AccountRow> findByIdAndOwner(UUID id, String ownerAccountId) {
		return db
				.sql("SELECT " + COLS + " FROM creation_wechat_account"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", ownerAccountId).map(WechatAccountRepository::map).one();
	}

	public Mono<AccountRow> findByOwnerAndAppId(String ownerAccountId, String appId) {
		return db
				.sql("SELECT " + COLS + " FROM creation_wechat_account"
						+ " WHERE owner_account_id = :owner AND app_id = :appId")
				.bind("owner", ownerAccountId).bind("appId", appId).map(WechatAccountRepository::map).one();
	}

	public Mono<Boolean> existsByAppIdOtherOwner(String appId, String ownerAccountId) {
		return db.sql("SELECT COUNT(*) FROM creation_wechat_account"
				+ " WHERE lower(app_id) = lower(:appId) AND owner_account_id <> :owner AND state <> 'disconnected'")
				.bind("appId", appId).bind("owner", ownerAccountId).map(row -> row.get(0, Long.class)).one()
				.map(count -> count != null && count > 0);
	}

	public Flux<AccountRow> listByOwner(String ownerAccountId, int limit, OffsetDateTime cursorAt, UUID cursorId) {
		StringBuilder sql = new StringBuilder(
				"SELECT " + COLS + " FROM creation_wechat_account" + " WHERE owner_account_id = :owner");
		if (cursorAt != null && cursorId != null) {
			sql.append(" AND (created_at < :cursorAt OR (created_at = :cursorAt AND id < :cursorId))");
		}
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
		var spec = db.sql(sql.toString()).bind("owner", ownerAccountId).bind("limit", limit);
		if (cursorAt != null && cursorId != null) {
			spec = spec.bind("cursorAt", cursorAt).bind("cursorId", cursorId);
		}
		return spec.map(WechatAccountRepository::map).all();
	}

	/** 状态 CAS（version 不符返回 empty——服务层映射 409）。单语句原子更新。 */
	public Mono<AccountRow> casUpdate(UUID id, int expectedVersion, String state, String errorCode,
			OffsetDateTime verifiedAt, String encryptedSecret, String secretKeyVersion) {
		StringBuilder sql = new StringBuilder(
				"UPDATE creation_wechat_account SET state = :state, error_code = :code, version = version + 1,"
						+ " updated_at = now()");
		if (verifiedAt != null) {
			sql.append(", verified_at = :verifiedAt");
		} else {
			sql.append(", verified_at = NULL");
		}
		if (encryptedSecret != null) {
			sql.append(", encrypted_secret = :secret, secret_key_version = :keyVersion");
		}
		sql.append(" WHERE id = CAST(:id AS uuid) AND version = :expected");
		sql.append(" RETURNING ").append(COLS);
		var spec = db.sql(sql.toString()).bind("id", id.toString()).bind("expected", expectedVersion).bind("state",
				state);
		if (verifiedAt != null) {
			spec = spec.bind("verifiedAt", verifiedAt);
		}
		if (encryptedSecret != null) {
			spec = spec.bind("secret", encryptedSecret).bind("keyVersion", secretKeyVersion);
		}
		spec = errorCode == null ? spec.bindNull("code", String.class) : spec.bind("code", errorCode);
		return spec.map(WechatAccountRepository::map).one();
	}

	public Mono<AccountRow> disconnect(UUID id, int expectedVersion) {
		return db
				.sql("""
						UPDATE creation_wechat_account SET state = 'disconnected', encrypted_secret = NULL,
						    secret_key_version = NULL, verified_at = NULL, error_code = NULL, version = version + 1, updated_at = now()
						WHERE id = CAST(:id AS uuid) AND version = :expected AND state <> 'disconnected'
						RETURNING id, owner_account_id, display_name, app_id, encrypted_secret, secret_key_version,
						    state, version, verified_at, error_code, created_at, updated_at
						""")
				.bind("id", id.toString()).bind("expected", expectedVersion).map(WechatAccountRepository::map).one();
	}

	private static AccountRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		try {
			return new AccountRow(row.get("id", UUID.class), row.get("owner_account_id", String.class),
					row.get("display_name", String.class), row.get("app_id", String.class),
					row.get("encrypted_secret", String.class), row.get("secret_key_version", String.class),
					row.get("state", String.class), row.get("version", Integer.class),
					row.get("verified_at", OffsetDateTime.class), row.get("error_code", String.class),
					row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class));
		} catch (Exception error) {
			throw new IllegalStateException("map-fail: " + error, error);
		}
	}
}
