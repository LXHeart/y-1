package com.grassland.marketplace.commerce;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-05：异常订单暂扣队列（ops_order_hold）数据访问。
 *
 * <p>
 * flagged=自动标记候选（不碰钱）；held=人工确认（结算挂起 + 处理期限）；released/dismissed=终态。
 * 幂等：insertFlagged 撞 {@code uq_ops_order_hold_open} 唯一索引即吞掉（重复扫描零副作用）。
 */
@Component
public class OpsOrderHoldRepository {

	private final DatabaseClient db;

	public OpsOrderHoldRepository(DatabaseClient db) {
		this.db = db;
	}

	public record HoldRow(UUID id, UUID orderId, String rule, String reason, String status, Instant flaggedAt,
			UUID confirmedBy, Instant confirmedAt, Instant holdDeadlineAt, UUID releasedBy, Instant releasedAt,
			String releasedReason) {
	}

	private static final String COLS = "id, order_id, rule, reason, status, flagged_at, confirmed_by, confirmed_at,"
			+ " hold_deadline_at, released_by, released_at, released_reason";

	/** 自动标记落行（幂等：已有未终态同 (order, rule) 行 → 唯一冲突吞掉返回 empty）。 */
	public Mono<HoldRow> insertFlagged(UUID orderId, String rule, String reason) {
		return db
				.sql("INSERT INTO ops_order_hold(id, order_id, rule, reason, status)"
						+ " VALUES (CAST(:id AS uuid), CAST(:order AS uuid), :rule, :reason, 'flagged')"
						+ " ON CONFLICT (order_id, rule) WHERE status IN ('flagged', 'held') DO NOTHING" + " RETURNING "
						+ COLS)
				.bind("id", UUID.randomUUID().toString()).bind("order", orderId.toString()).bind("rule", rule)
				.bind("reason", reason).map(OpsOrderHoldRepository::map).one();
	}

	/** 队列：flagged/held 分组视图（最新在前）；dismissed/released 仅审计查询用。 */
	public Flux<HoldRow> listQueue(String status, int limit) {
		String safe = switch (status == null ? "" : status) {
			case "held" -> "held";
			case "flagged" -> "flagged";
			default -> "flagged', 'held";
		};
		return db
				.sql("SELECT " + COLS + " FROM ops_order_hold WHERE status IN ('" + safe + "')"
						+ " ORDER BY (status = 'held') DESC, flagged_at DESC LIMIT :lim")
				.bind("lim", Math.max(1, Math.min(limit, 200))).map(OpsOrderHoldRepository::map).all();
	}

	/** 结算闸：该订单是否存在生效中的 held（attemptSplit 执行前重查，#97 C97-01 防御纵深同构）。 */
	public Mono<Boolean> hasActiveHold(String orderId) {
		return db
				.sql("SELECT 1 AS ok FROM ops_order_hold WHERE order_id = CAST(:order AS uuid)"
						+ " AND status = 'held' LIMIT 1")
				.bind("order", orderId).map(row -> true).one().defaultIfEmpty(false);
	}

	/**
	 * 人工确认：flagged → held（写原因期限与操作者）；0 行 → empty（调用方 409/404）。 审查修复 01（C01-C）：NOT
	 * EXISTS splitting——分账占位（资金在途）期间确认暂扣会谎称已阻断出款， 与分账 claim
	 * 竞争同一状态位单边胜出；占位释放（收尾/归还）后重试即成功。
	 */
	public Mono<HoldRow> confirm(UUID holdId, UUID operator, Instant deadline) {
		return db
				.sql("UPDATE ops_order_hold SET status = 'held', confirmed_by = CAST(:op AS uuid),"
						+ " confirmed_at = now(), hold_deadline_at = CAST(:deadline AS timestamptz)"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'flagged'"
						+ " AND NOT EXISTS (SELECT 1 FROM consumer_order o WHERE o.id = ops_order_hold.order_id"
						+ " AND o.status = 'splitting')" + " RETURNING " + COLS)
				.bind("op", operator.toString()).bind("deadline", deadline.atOffset(java.time.ZoneOffset.UTC))
				.bind("id", holdId.toString()).map(OpsOrderHoldRepository::map).one();
	}

	/** 解除（结算恢复）：held → released；附带解除说明（审计）。 */
	public Mono<HoldRow> release(UUID holdId, UUID operator, String note) {
		org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec = db
				.sql("UPDATE ops_order_hold SET status = 'released', released_by = CAST(:op AS uuid),"
						+ " released_at = now(), released_reason = :note"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'held' RETURNING " + COLS)
				.bind("op", operator.toString()).bind("id", holdId.toString());
		spec = note == null || note.isBlank() ? spec.bindNull("note", String.class) : spec.bind("note", note.trim());
		return spec.map(OpsOrderHoldRepository::map).one();
	}

	/** 驳回标记（不构成暂扣）：flagged → dismissed。 */
	public Mono<HoldRow> dismiss(UUID holdId, UUID operator) {
		return db
				.sql("UPDATE ops_order_hold SET status = 'dismissed', released_by = CAST(:op AS uuid),"
						+ " released_at = now(), released_reason = 'dismissed_by_review'"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'flagged' RETURNING " + COLS)
				.bind("op", operator.toString()).bind("id", holdId.toString()).map(OpsOrderHoldRepository::map).one();
	}

	public Mono<HoldRow> find(UUID holdId) {
		return db.sql("SELECT " + COLS + " FROM ops_order_hold WHERE id = CAST(:id AS uuid)")
				.bind("id", holdId.toString()).map(OpsOrderHoldRepository::map).one();
	}

	/** 处理期限超时视图（AC-98-22 期限；复用 #96 C96-06 超时机制语义）。 */
	public Flux<HoldRow> listOverdue(Instant now) {
		return db.sql("SELECT " + COLS + " FROM ops_order_hold WHERE status = 'held' AND hold_deadline_at < :now")
				.bind("now", now.atOffset(java.time.ZoneOffset.UTC)).map(OpsOrderHoldRepository::map).all();
	}

	private static HoldRow map(Readable row) {
		return new HoldRow(row.get("id", UUID.class), row.get("order_id", UUID.class), row.get("rule", String.class),
				row.get("reason", String.class), row.get("status", String.class), instant(row, "flagged_at"),
				row.get("confirmed_by", UUID.class), instant(row, "confirmed_at"), instant(row, "hold_deadline_at"),
				row.get("released_by", UUID.class), instant(row, "released_at"),
				row.get("released_reason", String.class));
	}

	private static Instant instant(Readable row, String name) {
		java.time.OffsetDateTime value = row.get(name, java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
