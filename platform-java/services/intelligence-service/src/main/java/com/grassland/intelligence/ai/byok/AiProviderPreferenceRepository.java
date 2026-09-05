package com.grassland.intelligence.ai.byok;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 个人 BYOK 偏好仓储（任务书 #47 S5）。形状对齐 {@link AiOrgByokPolicyRepository}：小表 + version
 * 乐观锁。
 *
 * <p>
 * <b>任务书 #78 卡 B 起</b>：路由只读「模型来源总开关」主行 {@code capability='*'}（任务书 #78 D3，
 * 一个总开关取代 per-capability 碎片开关）——{@link #isOwnModelSource} <b>无主行 =
 * platform</b>（默认）。 per-capability 行保留不删，但路由不再读取。
 */
@Component
public class AiProviderPreferenceRepository {

	private static final String COLS = "account_id, capability, use_own_key, version, updated_at";

	/** 模型来源总开关主行的 capability 值（照 ai_model_budget 全局行 '*' + '/' + '*' 的先例）。 */
	public static final String MASTER_CAPABILITY = "*";

	private final DatabaseClient db;

	public AiProviderPreferenceRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 模型来源总开关是否为 own（自带密钥）。<b>无主行 = false（platform，默认）</b>。
	 *
	 * <p>
	 * 运行时热路径入口（个人段路由与冻结快照共用）：master=platform → 跳过个人段直落平台段； master=own →
	 * 逐能力取个人密钥，未命中按 own_key_missing 拒绝（不回退平台）。
	 */
	public Mono<Boolean> isOwnModelSource(String accountId) {
		return db.sql("""
				SELECT use_own_key FROM ai_provider_preference
				WHERE account_id = :accountId AND capability = '*'
				""").bind("accountId", accountId)
				.map((row, meta) -> Boolean.TRUE.equals(row.get("use_own_key", Boolean.class))).one()
				.defaultIfEmpty(false);
	}

	/** 某账号已显式配置的全部偏好（未配置的能力不出现，由调用方补默认）。 */
	public Flux<AiProviderPreference> findByAccount(String accountId) {
		return db
				.sql("SELECT " + COLS + " FROM ai_provider_preference"
						+ " WHERE account_id = :accountId ORDER BY capability")
				.bind("accountId", accountId).map(AiProviderPreferenceRepository::map).all();
	}

	public Mono<AiProviderPreference> find(String accountId, String capability) {
		return db
				.sql("SELECT " + COLS + " FROM ai_provider_preference"
						+ " WHERE account_id = :accountId AND capability = :capability")
				.bind("accountId", accountId).bind("capability", capability).map(AiProviderPreferenceRepository::map)
				.one();
	}

	/**
	 * 乐观锁 upsert。{@code expectedVersion=0} 表示「预期无行」；其余表示预期的当前版本。 版本不符（含并发插入撞 PK）→ 空
	 * Mono，由 controller 转 409。
	 */
	public Mono<AiProviderPreference> upsert(String accountId, String capability, boolean useOwnKey,
			long expectedVersion) {
		if (expectedVersion == 0L) {
			return db.sql("""
					INSERT INTO ai_provider_preference(account_id, capability, use_own_key, version)
					VALUES (:accountId, :capability, :useOwnKey, 1)
					ON CONFLICT (account_id, capability) DO NOTHING
					RETURNING """ + " " + COLS).bind("accountId", accountId).bind("capability", capability)
					.bind("useOwnKey", useOwnKey).map(AiProviderPreferenceRepository::map).one();
		}
		return db.sql("""
				UPDATE ai_provider_preference
				SET use_own_key = :useOwnKey, version = version + 1, updated_at = now()
				WHERE account_id = :accountId AND capability = :capability
				  AND version = :expectedVersion
				RETURNING """ + " " + COLS).bind("accountId", accountId).bind("capability", capability)
				.bind("useOwnKey", useOwnKey).bind("expectedVersion", expectedVersion)
				.map(AiProviderPreferenceRepository::map).one();
	}

	private static AiProviderPreference map(Row row, RowMetadata meta) {
		return new AiProviderPreference(row.get("account_id", String.class), row.get("capability", String.class),
				Boolean.TRUE.equals(row.get("use_own_key", Boolean.class)), row.get("version", Long.class),
				toInstant(row.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
