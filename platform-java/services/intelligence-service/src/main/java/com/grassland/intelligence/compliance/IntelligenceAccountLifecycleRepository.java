package com.grassland.intelligence.compliance;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 账号生命周期 gate（任务书 #103 C103-08 / §4.3 / §7.2）：注销协作的本地屏障。
 *
 * <p>
 * prepare：gate 行排他锁内复查活动任务（同一事务内新任务先提交则本方法看不见 active → 本注销被挡； 冻结先提交则新任务的 INSERT
 * 被 V85 触发器拒绝）。同 closureRequestId 幂等重入回同 revision； 不同仍活动请求 → 409 语义（empty
 * 由调用方分类）。release 仅取消尚未进入 retention 的同请求冻结 （Identity 协调器证明未软删后调用）；对
 * erasing/erased 不生效。锁序统一 account gate → 业务行。
 */
@Component
public class IntelligenceAccountLifecycleRepository {

	public record Lifecycle(String accountId, String closureRequestId, String state, long revision, Instant frozenAt,
			Instant erasedAt) {
	}

	private final DatabaseClient db;
	private final IntelligenceJobInventory inventory;

	public IntelligenceAccountLifecycleRepository(DatabaseClient db, IntelligenceJobInventory inventory) {
		this.db = db;
		this.inventory = inventory;
	}

	public Mono<Lifecycle> find(String accountId) {
		return db.sql("SELECT account_id, closure_request_id::text, state, revision,"
				+ " frozen_at, erased_at FROM intelligence_account_lifecycle" + " WHERE account_id = :a FOR SHARE")
				.bind("a", accountId)
				.map((r) -> new Lifecycle(r.get("account_id", String.class), r.get("closure_request_id", String.class),
						r.get("state", String.class),
						r.get("revision", Long.class) == null ? 0 : r.get("revision", Long.class),
						toInstant(r.get("frozen_at", java.time.OffsetDateTime.class)),
						toInstant(r.get("erased_at", java.time.OffsetDateTime.class))))
				.one();
	}

	/**
	 * 无 gate 行首次注册用 INSERT ON CONFLICT 再取锁（「无记录」不得成为可绕过屏障的永久通道）。 随后在行锁内复查活动任务：无活动 →
	 * frozen（revision+1）；有活动 → active 保持（不冻结）。
	 */
	public Mono<Lifecycle> prepare(String accountId, UUID closureRequestId) {
		return db.sql("""
				INSERT INTO intelligence_account_lifecycle(account_id, closure_request_id, state)
				VALUES (:a, :req, 'active') ON CONFLICT (account_id) DO NOTHING
				""").bind("a", accountId).bind("req", closureRequestId).fetch().rowsUpdated()
				.then(db.sql("SELECT account_id, closure_request_id::text, state, revision,"
						+ " frozen_at, erased_at FROM intelligence_account_lifecycle"
						+ " WHERE account_id = :a FOR UPDATE").bind("a", accountId).map(this::mapRow).one())
				.flatMap(locked -> switch (locked.state()) {
					// 幂等重入：同请求已冻结 → 回同 revision。
					case "frozen", "erasing",
							"erased" ->
						locked.closureRequestId() != null
								&& locked.closureRequestId().equals(closureRequestId.toString())
										? Mono.just(locked)
										: Mono.<Lifecycle>empty();
					default -> inventory.countByKind(accountId).flatMap(counts -> {
						if (!counts.isEmpty()) {
							return Mono.error(new IllegalStateException("ACTIVE_JOBS:" + counts.keySet()));
						}
						return freeze(locked, closureRequestId);
					});
				});
	}

	private Mono<Lifecycle> freeze(Lifecycle locked, UUID closureRequestId) {
		return db
				.sql("UPDATE intelligence_account_lifecycle"
						+ " SET state = 'frozen', closure_request_id = :req, frozen_at = now(),"
						+ " revision = revision + 1, updated_at = now()"
						+ " WHERE account_id = :a AND state = 'active' AND revision = :rev"
						+ " RETURNING account_id, closure_request_id::text, state, revision, frozen_at, erased_at")
				.bind("a", locked.accountId()).bind("req", closureRequestId).bind("rev", locked.revision())
				.map(this::mapRow).one();
	}

	/** 仅取消尚未进入 retention/清理态的同请求冻结；active/erasing/erased 不受影响。 */
	public Mono<Boolean> release(String accountId, UUID closureRequestId) {
		return db.sql("""
				UPDATE intelligence_account_lifecycle
				   SET state = 'active', closure_request_id = NULL, frozen_at = NULL,
				       revision = revision + 1, updated_at = now()
				 WHERE account_id = :a AND closure_request_id = :req AND state = 'frozen'
				""").bind("a", accountId).bind("req", closureRequestId).fetch().rowsUpdated().map(n -> n > 0)
				.defaultIfEmpty(false);
	}

	/** 清理阶段推进（C103-09）：frozen → erasing → erased；同请求幂等。 */
	public Mono<Lifecycle> markErasing(String accountId, UUID closureRequestId) {
		return db.sql("""
				UPDATE intelligence_account_lifecycle
				   SET state = 'erasing', revision = revision + 1, updated_at = now()
				 WHERE account_id = :a AND closure_request_id = :req AND state IN ('frozen', 'erasing')
				RETURNING account_id, closure_request_id::text, state, revision, frozen_at, erased_at
				""").bind("a", accountId).bind("req", closureRequestId).map(this::mapRow).one();
	}

	public Mono<Lifecycle> markErased(String accountId, UUID closureRequestId) {
		return db.sql("""
				UPDATE intelligence_account_lifecycle
				   SET state = 'erased', erased_at = now(), revision = revision + 1, updated_at = now()
				 WHERE account_id = :a AND closure_request_id = :req AND state = 'erasing'
				RETURNING account_id, closure_request_id::text, state, revision, frozen_at, erased_at
				""").bind("a", accountId).bind("req", closureRequestId).map(this::mapRow).one();
	}

	private Lifecycle mapRow(io.r2dbc.spi.Readable r) {
		return new Lifecycle(r.get("account_id", String.class), r.get("closure_request_id", String.class),
				r.get("state", String.class), r.get("revision", Long.class) == null ? 0 : r.get("revision", Long.class),
				toInstant(r.get("frozen_at", java.time.OffsetDateTime.class)),
				toInstant(r.get("erased_at", java.time.OffsetDateTime.class)));
	}

	private static Instant toInstant(java.time.OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	public static Optional<String> activeKindError(IllegalStateException barrier) {
		String message = barrier.getMessage();
		if (message != null && message.startsWith("ACTIVE_JOBS:")) {
			return Optional.of(message.substring("ACTIVE_JOBS:".length()));
		}
		return Optional.empty();
	}
}
