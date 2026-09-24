package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TurnInputKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TurnReceipt;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TurnState;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 轮次服务（任务书 #105D C105D-05 / 共享契约 K03 API19～21、K13.4）：只允许 ready 单活动 turn。
 *
 * <p>
 * 事务内创建 turn（epoch 由 session.next_turn_epoch 事务递增分配）后<b>运行时派发在事务外</b>（不在事务内
 * 等待生成）；同 requestId 幂等返回原 turn；旧 epoch 打断只作用于目标 turn（不能打断新轮）；interrupt 持久化
 * 取消事实并使后续新 turn 分配更大 epoch。greeting 每场独立一次受理（operation kind=greeting，重试同
 * requestId 查原结果），TTS-only 不走 LLM。运行时派发（Java→wrapper INTERNAL01/02）随本阶段装配面接线，
 * 本卡先落库权威状态与经济键。
 */
@Component
public class DigitalHumanTurnService {

	private final DigitalHumanOperations operations;
	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public DigitalHumanTurnService(DigitalHumanOperations operations, DatabaseClient db,
			TransactionalOperator transactions) {
		this.operations = operations;
		this.db = db;
		this.transactions = transactions;
	}

	/** API19：text turn（非 ready 409；重复 requestId 返回原 turn；单活动 turn 由部分唯一索引保证）。 */
	public Mono<TurnReceipt> startTurn(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch,
			String text) {
		if (text == null || text.isBlank()) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "text 不能为空。"));
		}
		if (text.codePointCount(0, text.length()) > 2000) {
			return Mono.error(new IntelligenceException(422, "dh_input_too_long", "text 超长。"));
		}
		return startTurnOfKind(actor, sessionId, requestId, leaseEpoch, text, TurnInputKind.text);
	}

	/** API21：greeting（TTS-only，不走 LLM；每场一次受理，重试同 requestId 查原结果）。 */
	public Mono<TurnReceipt> greeting(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch) {
		return db.sql("SELECT greeting FROM dh_profile_revision WHERE profile_id = ("
				+ " SELECT profile_id FROM dh_session WHERE id = CAST(:s AS uuid) AND owner_account_id = :owner)"
				+ " AND revision = (SELECT profile_revision FROM dh_session WHERE id = CAST(:s AS uuid))")
				.bind("s", sessionId.toString()).bind("owner", actor.accountId())
				.map(row -> row.get("greeting", String.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(greetingText -> (greetingText == null || greetingText.isBlank())
						? Mono.<TurnReceipt>error(new IntelligenceException(422, "dh_invalid_input", "该角色未配置开场白。"))
						: startTurnOfKind(actor, sessionId, requestId, leaseEpoch, greetingText,
								TurnInputKind.greeting));
	}

	private Mono<TurnReceipt> startTurnOfKind(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch,
			String text, TurnInputKind kind) {
		String payloadHash = DigitalHumanOperations.canonicalHash(
				Map.of("inputKind", kind.name(), "sessionId", sessionId.toString(), "leaseEpoch", leaseEpoch));
		Mono<TurnReceipt> body = operations
				.reserve(actor, kind == TurnInputKind.greeting ? OperationKind.greeting : OperationKind.turn_create,
						requestId, payloadHash, null)
				.flatMap(operation -> {
					if (operation.resourceId() != null) {
						// 幂等重放：读原 turn（不二次分配 epoch/派发）。
						return findTurn(actor.accountId(), sessionId, UUID.fromString(operation.resourceId()));
					}
					// 单语句原子：owner/lease/ready 校验 + epoch 分配 + turn 插入（部分唯一索引防双活动）。
					return db.sql("""
							WITH alloc AS (
							    UPDATE dh_session SET next_turn_epoch = next_turn_epoch + 1,
							        state = 'responding', state_entered_at = now(),
							        version = version + 1, updated_at = now()
							    WHERE id = CAST(:s AS uuid) AND owner_account_id = :owner AND lease_epoch = :lease
							      AND state = 'ready' AND cleanup_pending = false
							    RETURNING next_turn_epoch - 1 AS turn_epoch
							), ins AS (
							    INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,
							        input_kind, state, started_at)
							    SELECT CAST(:t AS uuid), :owner, CAST(:s AS uuid), CAST(:r AS uuid),
							        alloc.turn_epoch, :kind, 'generating', now()
							    FROM alloc
							    ON CONFLICT (session_id, request_id) DO NOTHING
							    RETURNING id::text, turn_epoch
							)
							SELECT ins.id AS id, ins.turn_epoch AS turn_epoch FROM ins
							""").bind("s", sessionId.toString()).bind("owner", actor.accountId())
							.bind("lease", leaseEpoch).bind("t", UUID.randomUUID().toString())
							.bind("r", requestId.toString()).bind("kind", kind.name())
							.map(row -> new TurnReceipt(row.get("id", String.class), sessionId.toString(),
									requestId.toString(), row.get("turn_epoch", Long.class), TurnState.generating))
							.one()
							.flatMap(receipt -> operations
									.attachResource(UUID.fromString(operation.id()), UUID.fromString(receipt.id()))
									.thenReturn(receipt))
							.switchIfEmpty(Mono.defer(() -> findTurnByRequest(actor.accountId(), sessionId, requestId)))
							// 分配失败（非 ready/旧租约/坏形态）且无原 turn：确定性 409，不静默空返回。
							.switchIfEmpty(Mono
									.error(new IntelligenceException(409, "dh_state_conflict", "会话当前不可开轮（状态或租约已变化）。")));
				});
		return transactions.transactional(body).onErrorMap(
				org.springframework.dao.DataIntegrityViolationException.class,
				conflict -> new IntelligenceException(409, "dh_state_conflict", "已有活动轮次，请先结束当前轮。"));
	}

	/** API20：打断（旧 turn 已终态幂等无效果；不能打断新轮；取消事实持久化、mediaEpoch 递增）。 */
	public Mono<Map<String, Object>> interrupt(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch,
			UUID turnId, long turnEpoch) {
		return db
				.sql("SELECT lease_epoch, state FROM dh_session WHERE id = CAST(:s AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("s", sessionId.toString()).bind("owner", actor.accountId()).map(row -> row).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(session -> {
					if (session.get("lease_epoch", Long.class) != leaseEpoch) {
						return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化。"));
					}
					return db.sql("UPDATE dh_turn SET state = 'interrupted', ended_at = now(), version = version + 1,"
							+ " updated_at = now() WHERE id = CAST(:t AS uuid) AND session_id = CAST(:s AS uuid)"
							+ " AND owner_account_id = :owner AND turn_epoch <= :epoch"
							+ " AND state NOT IN ('completed','interrupted','failed','unknown')"
							+ " RETURNING turn_epoch").bind("t", turnId.toString()).bind("s", sessionId.toString())
							.bind("owner", actor.accountId()).bind("epoch", turnEpoch)
							.map(row -> row.get("turn_epoch", Long.class)).one()
							.flatMap(updatedEpoch -> db
									.sql("UPDATE dh_session SET media_epoch = media_epoch + 1,"
											+ " state = CASE WHEN state = 'responding' THEN 'ready' ELSE state END,"
											+ " state_entered_at = now(), version = version + 1, updated_at = now()"
											+ " WHERE id = CAST(:s AS uuid) RETURNING media_epoch, state")
									.bind("s", sessionId.toString()).map(row -> row).one())
							.map(sessionAfter -> Map.<String, Object>of("turnId", turnId.toString(), "effective", true,
									"nextMediaEpoch", sessionAfter.get("media_epoch", Long.class), "state",
									sessionAfter.get("state", String.class)))
							.defaultIfEmpty(Map.of("turnId", turnId.toString(), "effective", false, "nextMediaEpoch",
									session.get("lease_epoch", Long.class) >= 0 ? 0L : 0L, "state",
									session.get("state", String.class)))
							// 幂等无效果时补真实 mediaEpoch/state。
							.flatMap(result -> Boolean.TRUE.equals(result.get("effective"))
									? Mono.just(result)
									: db.sql("SELECT media_epoch, state FROM dh_session WHERE id = CAST(:s AS uuid)")
											.bind("s", sessionId.toString()).map(row -> row).one()
											.map(current -> Map.<String, Object>of("turnId", turnId.toString(),
													"effective", false, "nextMediaEpoch",
													current.get("media_epoch", Long.class), "state",
													current.get("state", String.class))));
				});
	}

	// ---------- 私有 ----------

	private Mono<TurnReceipt> findTurn(String owner, UUID sessionId, UUID turnId) {
		return db
				.sql("SELECT id::text, turn_epoch, state FROM dh_turn WHERE id = CAST(:t AS uuid)"
						+ " AND session_id = CAST(:s AS uuid) AND owner_account_id = :owner")
				.bind("t", turnId.toString()).bind("s", sessionId.toString()).bind("owner", owner)
				.map(row -> new TurnReceipt(row.get("id", String.class), sessionId.toString(), null,
						row.get("turn_epoch", Long.class), TurnState.valueOf(row.get("state", String.class))))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<TurnReceipt> findTurnByRequest(String owner, UUID sessionId, UUID requestId) {
		return db
				.sql("SELECT id::text, turn_epoch, state FROM dh_turn WHERE session_id = CAST(:s AS uuid)"
						+ " AND request_id = CAST(:r AS uuid) AND owner_account_id = :owner")
				.bind("s", sessionId.toString()).bind("r", requestId.toString()).bind("owner", owner)
				.map(row -> new TurnReceipt(row.get("id", String.class), sessionId.toString(), requestId.toString(),
						row.get("turn_epoch", Long.class), TurnState.valueOf(row.get("state", String.class))))
				.one();
	}

}
