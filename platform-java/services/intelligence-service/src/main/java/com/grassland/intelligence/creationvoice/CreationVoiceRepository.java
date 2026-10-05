package com.grassland.intelligence.creationvoice;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.creationvoice.CreationVoiceTypes.VoiceSample;
import io.r2dbc.spi.R2dbcException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 私有文风档案仓储（任务书 #108 C-02 / W11；§7.1/§7.4）。
 *
 * <p>
 * 读写均带账号生命周期 gate 校验：PUT 在单事务内按「先 gate 行锁（FOR UPDATE，先注册缺失行）、再 profile 行锁 （FOR
 * UPDATE）」的锁序执行 CAS；直接 SQL 绕过应用层时由 V94 专用触发器拒绝。GET 用 FOR SHARE 复核 gate
 * （与冻结事务互斥，冻结提交后读取立即被拒），frozen/erasing/erased 一律 409 屏障错误。
 *
 * <p>
 * CAS 语义（TC-C02-002）：目标内容 hash 与当前行一致 → 幂等返回当前值不递增（先于 revision 比较，覆盖「响应丢失后 重发原
 * PUT」）；hash 不同但 expectedRevision 落后 → 409 冲突；首次建槽仅 expectedRevision=0，并发首建靠联合
 * 主键唯一约束只放行一个。加密经 {@link EnvelopeEncryption}（ObjectProvider 注入）；密钥缺失 fail-loud
 * 503， 不静默明文/空成功；解密失败报错，不替换成空数组。
 */
@Component
public class CreationVoiceRepository {

	/** 存储行（已解密 payload；hash 为存储的规范内容摘要）。 */
	public record StoredProfile(long revision, boolean enabled, String payloadJson, String payloadHash,
			Instant updatedAt) {
	}

	/** CAS 写结果：revision/updatedAt 为写后（或幂等命中的）当前值。 */
	public record CasOutcome(long revision, Instant updatedAt) {
	}

	/** 写事务内锁定的当前行（缺行 → empty）。 */
	private record LockedRow(long revision, String payloadHash, Instant updatedAt) {
	}

	private final DatabaseClient db;
	private final ObjectProvider<EnvelopeEncryption> encryption;
	private final TransactionalOperator transactions;

	public CreationVoiceRepository(DatabaseClient db, ObjectProvider<EnvelopeEncryption> encryption,
			TransactionalOperator transactions) {
		this.db = db;
		this.encryption = encryption;
		this.transactions = transactions;
	}

	/** 读当前槽位（缺行返回 empty）；gate 非 active 抛 409 屏障错误。解密/解析失败抛 503。 */
	public Mono<StoredProfile> find(String accountId, String role) {
		return requireActiveGateShared(accountId).then(db
				.sql("SELECT revision, enabled, encrypted_payload, payload_hash, updated_at"
						+ " FROM creation_voice_profile WHERE account_id = :a AND role = :r")
				.bind("a", accountId).bind("r", role)
				.map((row) -> new StoredProfile(nullSafe(row.get("revision", Long.class)),
						Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
						decrypt(requireCrypto(), row.get("encrypted_payload", String.class)),
						row.get("payload_hash", String.class), toInstant(row.get("updated_at", OffsetDateTime.class))))
				.one()).as(transactions::transactional);
	}

	/**
	 * CAS 写：单事务内 gate FOR UPDATE（先注册缺失行）→ profile FOR UPDATE → 幂等比较 → CAS 写入。
	 * 返回写后（或幂等命中）的 revision/updatedAt。
	 */
	public Mono<CasOutcome> compareAndSet(String accountId, String role, boolean enabled, List<String> rules,
			List<VoiceSample> samples, String canonicalContentJson, long expectedRevision) {
		String encrypted = encrypt(requireCrypto(), canonicalContentJsonForPayload(rules, samples));
		String payloadHash = sha256(canonicalContentJson);
		return transactions.transactional(
				Mono.defer(() -> lockGateAndWrite(accountId, role, enabled, encrypted, payloadHash, expectedRevision)));
	}

	private Mono<CasOutcome> lockGateAndWrite(String accountId, String role, boolean enabled, String encrypted,
			String payloadHash, long expectedRevision) {
		return registerAndLockGate(accountId).then(db
				.sql("SELECT revision, payload_hash, updated_at"
						+ " FROM creation_voice_profile WHERE account_id = :a AND role = :r FOR UPDATE")
				.bind("a", accountId).bind("r", role)
				.map((row) -> new LockedRow(nullSafe(row.get("revision", Long.class)),
						row.get("payload_hash", String.class), toInstant(row.get("updated_at", OffsetDateTime.class))))
				.one().flatMap((current) -> {
					// 幂等：相同目标内容（含 enabled 的规范 hash）重放返回当前值，不递增（§7.4）。
					if (payloadHash.equals(current.payloadHash())) {
						return Mono.just(new CasOutcome(current.revision(), current.updatedAt()));
					}
					if (current.revision() != expectedRevision) {
						return Mono.error(CreationVoiceTypes.conflict("档案已在其他地方更新（当前 revision=" + current.revision()
								+ "，请求期望 " + expectedRevision + "），请读取最新版本后重试"));
					}
					return updateRow(accountId, role, enabled, encrypted, payloadHash, current.revision());
				}).switchIfEmpty(Mono.defer(() -> {
					if (expectedRevision != 0) {
						return Mono.error(CreationVoiceTypes
								.conflict("档案槽位不存在，expectedRevision=0 才能首次建槽（当前请求期望 " + expectedRevision + "）"));
					}
					return insertRow(accountId, role, enabled, encrypted, payloadHash);
				})));
	}

	private Mono<CasOutcome> updateRow(String accountId, String role, boolean enabled, String encrypted,
			String payloadHash, long currentRevision) {
		return db
				.sql("UPDATE creation_voice_profile"
						+ " SET revision = revision + 1, enabled = :enabled, encrypted_payload = :payload,"
						+ " payload_hash = :hash, updated_at = now()"
						+ " WHERE account_id = :a AND role = :r AND revision = :expected"
						+ " RETURNING revision, updated_at")
				.bind("a", accountId).bind("r", role).bind("enabled", enabled).bind("payload", encrypted)
				.bind("hash", payloadHash).bind("expected", currentRevision)
				.map((row) -> new CasOutcome(nullSafe(row.get("revision", Long.class)),
						toInstant(row.get("updated_at", OffsetDateTime.class))))
				.one().onErrorMap(this::translateBarrier)
				.switchIfEmpty(Mono.error(() -> CreationVoiceTypes.conflict("档案已在其他地方更新，请读取最新版本后重试")));
	}

	private Mono<CasOutcome> insertRow(String accountId, String role, boolean enabled, String encrypted,
			String payloadHash) {
		return db
				.sql("INSERT INTO creation_voice_profile"
						+ " (account_id, role, revision, enabled, encrypted_payload, payload_hash)"
						+ " VALUES (:a, :r, 1, :enabled, :payload, :hash) RETURNING revision, updated_at")
				.bind("a", accountId).bind("r", role).bind("enabled", enabled).bind("payload", encrypted)
				.bind("hash", payloadHash)
				.map((row) -> new CasOutcome(nullSafe(row.get("revision", Long.class)),
						toInstant(row.get("updated_at", OffsetDateTime.class))))
				.one().onErrorMap(this::translateBarrier)
				.onErrorMap((error) -> isUniqueViolation(error)
						? CreationVoiceTypes.conflict("档案槽位已被并发创建，请读取最新版本后重试")
						: error);
	}

	/** PUT 用：注册缺失 gate 行并取 FOR UPDATE 行锁；非 active → 409（触发器同语义兜底直接 SQL）。 */
	private Mono<Void> registerAndLockGate(String accountId) {
		return db
				.sql("INSERT INTO intelligence_account_lifecycle(account_id) VALUES (:a)"
						+ " ON CONFLICT (account_id) DO NOTHING")
				.bind("a", accountId).fetch().rowsUpdated()
				.then(db.sql("SELECT state FROM intelligence_account_lifecycle WHERE account_id = :a FOR UPDATE")
						.bind("a", accountId).map((row) -> row.get("state", String.class)).one().map((state) -> {
							if (!"active".equals(state)) {
								throw CreationVoiceTypes.barrier(String.valueOf(state));
							}
							return state;
						}).onErrorMap(this::translateBarrier))
				.then();
	}

	public Mono<Void> requireActive(String accountId) {
		return requireActiveGateShared(accountId).as(transactions::transactional);
	}

	/** GET 用：FOR SHARE 复核 gate（与冻结方互斥）；无 gate 行视为 active。 */
	private Mono<Void> requireActiveGateShared(String accountId) {
		return db.sql("SELECT state FROM intelligence_account_lifecycle WHERE account_id = :a FOR SHARE")
				.bind("a", accountId).map((row) -> row.get("state", String.class)).one().map((state) -> {
					if (state != null && !"active".equals(state)) {
						throw CreationVoiceTypes.barrier(state);
					}
					return state;
				}).onErrorMap(this::translateBarrier).then();
	}

	/** V94 触发器/共享屏障拒绝的 SQL 异常 → 409 屏障错误（保持触发器原语义可辨认）。 */
	private Throwable translateBarrier(Throwable error) {
		if (error instanceof R2dbcException r2dbc && r2dbc.getMessage() != null
				&& r2dbc.getMessage().contains("account_closure_barrier")) {
			return CreationVoiceTypes.barrier(extractGateState(r2dbc.getMessage()));
		}
		return error;
	}

	private static String extractGateState(String message) {
		int index = message.lastIndexOf(" is ");
		return index >= 0 ? message.substring(index + 4) : "frozen";
	}

	private static boolean isUniqueViolation(Throwable error) {
		return error instanceof R2dbcException r2dbc && r2dbc.getErrorCode() == 23505;
	}

	private EnvelopeEncryption requireCrypto() {
		EnvelopeEncryption crypto = encryption.getIfAvailable();
		if (crypto == null) {
			throw CreationVoiceTypes.unavailable("信封加密不可用，私有文风档案拒绝读写（不降级明文）");
		}
		return crypto;
	}

	private static String encrypt(EnvelopeEncryption crypto, String plaintext) {
		try {
			return crypto.encrypt(plaintext);
		} catch (Exception e) {
			throw CreationVoiceTypes.unavailable("档案加密失败");
		}
	}

	private static String decrypt(EnvelopeEncryption crypto, String ciphertext) {
		try {
			return crypto.decrypt(ciphertext);
		} catch (Exception e) {
			throw CreationVoiceTypes.unavailable("档案解密失败");
		}
	}

	private static String canonicalContentJsonForPayload(List<String> rules, List<VoiceSample> samples) {
		return CreationVoiceTypes.payloadJson(rules, samples);
	}

	private static String sha256(String content) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw CreationVoiceTypes.unavailable("档案摘要计算失败");
		}
	}

	private static long nullSafe(Long value) {
		return value == null ? 0L : value;
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
