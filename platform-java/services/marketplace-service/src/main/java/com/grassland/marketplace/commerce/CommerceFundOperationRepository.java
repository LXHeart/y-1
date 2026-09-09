package com.grassland.marketplace.commerce;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 审查修复 01（R01/C01-A）：跨域资金操作持久化。
 *
 * <p>
 * 订单状态机能承载 resting 状态的互斥（refund_pending/splitting 都是行上占位），但两类窗口没有 可承载状态——支付 RPC
 * 在途（订单仍 pending_payment，取消随时可胜出）与取消后补偿退款 （cancelled
 * 是终态）。本表为这两类操作提供：稳定幂等键、在途/终态、退避重试时间、领取租约与 对账待办（needs_review），迟到的执行者按
 * operation_id 收尾，不覆盖后续结果。
 */
@Component
public class CommerceFundOperationRepository {

	private final DatabaseClient db;

	public CommerceFundOperationRepository(DatabaseClient db) {
		this.db = db;
	}

	public static final String TYPE_PAYMENT = "payment";
	public static final String TYPE_CANCEL_COMPENSATION = "cancel_compensation";

	private static final String COLS = "id::text, order_id::text, operation_type, operation_id, amount_cents,"
			+ " business_version, status, provider_ref, attempts, next_attempt_at, lease_owner, lease_expires_at,"
			+ " last_error, created_at, updated_at";

	public record FundOperation(String id, String orderId, String operationType, String operationId, long amountCents,
			int businessVersion, String status, String providerRef, int attempts, Instant nextAttemptAt,
			String leaseOwner, Instant leaseExpiresAt, String lastError, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * 支付操作占位（幂等）：一单一行，重复进入返回既有行——在途/失败照旧重试（finance 按 {@code payment_operation_id}
	 * 幂等），已成功直接短路。
	 */
	public Mono<FundOperation> ensurePaymentOperation(String orderId, String operationId, long amountCents,
			int businessVersion) {
		return db
				.sql("""
						INSERT INTO commerce_fund_operation(
						    id, order_id, operation_type, operation_id, amount_cents, business_version, status)
						VALUES (CAST(:id AS uuid), CAST(:order AS uuid), 'payment', :operationId, :amount, :version, 'in_flight')
						ON CONFLICT (order_id, operation_type) DO NOTHING
						RETURNING %s
						"""
						.formatted(COLS))
				.bind("id", UUID.randomUUID().toString()).bind("order", orderId).bind("operationId", operationId)
				.bind("amount", amountCents).bind("version", businessVersion).map(CommerceFundOperationRepository::map)
				.one().switchIfEmpty(findByOrderAndType(orderId, TYPE_PAYMENT));
	}

	/**
	 * 取消后补偿退款登记：仅在订单已被取消（支付已捕获）时成立，同事务把补偿幂等键与金额快照到订单行 （复用 refund_*
	 * 列，FinanceCommerceClient.refund 直接可发），由恢复驱动完成。
	 */
	public Mono<FundOperation> registerCancelCompensation(String orderId, long amountCents, int businessVersion) {
		String operationId = compensationOperationId(orderId);
		return db.sql("""
				INSERT INTO commerce_fund_operation(
				    id, order_id, operation_type, operation_id, amount_cents, business_version, status)
				VALUES (CAST(:id AS uuid), CAST(:order AS uuid), 'cancel_compensation', :operationId, :amount,
				        :version, 'in_flight')
				ON CONFLICT (order_id, operation_type) DO NOTHING
				RETURNING %s
				""".formatted(COLS)).bind("id", UUID.randomUUID().toString()).bind("order", orderId)
				.bind("operationId", operationId).bind("amount", amountCents).bind("version", businessVersion)
				.map(CommerceFundOperationRepository::map).one()
				.switchIfEmpty(findByOrderAndType(orderId, TYPE_CANCEL_COMPENSATION));
	}

	public static String compensationOperationId(String orderId) {
		return "commerce-cancel-compensation:" + orderId;
	}

	public Mono<FundOperation> findByOrderAndType(String orderId, String operationType) {
		return db
				.sql("SELECT " + COLS + " FROM commerce_fund_operation"
						+ " WHERE order_id = CAST(:order AS uuid) AND operation_type = :type")
				.bind("order", orderId).bind("type", operationType).map(CommerceFundOperationRepository::map).one();
	}

	/** 操作成功收尾（迟到的失败回调不得覆盖：status='in_flight' 守卫）。 */
	public Mono<FundOperation> succeed(String operationId, String providerRef) {
		org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE commerce_fund_operation
				   SET status = 'succeeded', provider_ref = COALESCE(:providerRef, provider_ref),
				       lease_owner = NULL, lease_expires_at = NULL, last_error = NULL,
				       next_attempt_at = now(), updated_at = now()
				 WHERE operation_id = :operationId AND status IN ('in_flight', 'failed')
				RETURNING %s
				""".formatted(COLS)).bind("operationId", operationId);
		spec = providerRef == null ? spec.bindNull("providerRef", String.class) : spec.bind("providerRef", providerRef);
		return spec.map(CommerceFundOperationRepository::map).one();
	}

	/**
	 * 失败登记：确定性拒绝（4xx）或重试耗尽 → needs_review（对账待办，不变量 4）；其余保持 in_flight
	 * 按指数退避重试。租约不释放——过期后任一实例可重新领取。
	 */
	public Mono<FundOperation> fail(String operationId, boolean definitive, String message) {
		return db.sql("""
				UPDATE commerce_fund_operation
				   SET status = CASE WHEN :definitive OR attempts + 1 >= 8 THEN 'needs_review' ELSE 'in_flight' END,
				       attempts = attempts + 1,
				       next_attempt_at = now() + LEAST(make_interval(secs => 60) * power(2, attempts + 1),
				                                      make_interval(secs => 3600)),
				       lease_owner = NULL, lease_expires_at = NULL,
				       last_error = :message, updated_at = now()
				 WHERE operation_id = :operationId AND status IN ('in_flight', 'failed')
				RETURNING %s
				""".formatted(COLS)).bind("operationId", operationId).bind("definitive", definitive)
				.bind("message", truncate(message)).map(CommerceFundOperationRepository::map).one();
	}

	private static final String COLS_UPDATE = "f.id::text, f.order_id::text, f.operation_type, f.operation_id,"
			+ " f.amount_cents, f.business_version, f.status, f.provider_ref, f.attempts, f.next_attempt_at,"
			+ " f.lease_owner, f.lease_expires_at, f.last_error, f.created_at, f.updated_at";

	/**
	 * 恢复扫描领取（多实例安全）：到期未终态的操作以租约认领——本方法一次性取回本实例应处理的行， 租约过期（默认 60s）后其他实例可接管；payment
	 * 类型仅捞订单已取消的行（未取消的由 pendingDispatch 驱动，避免双路重发）。
	 */
	public Flux<FundOperation> claimRecoverable(int limit, String owner, Duration lease) {
		return db.sql("""
				WITH due AS (
				    SELECT f.id FROM commerce_fund_operation f
				    JOIN consumer_order o ON o.id = f.order_id
				     WHERE f.status IN ('in_flight', 'failed') AND f.next_attempt_at <= now()
				       AND (f.lease_expires_at IS NULL OR f.lease_expires_at < now())
				       AND (f.operation_type = 'cancel_compensation'
				            OR (f.operation_type = 'payment' AND o.status = 'cancelled'))
				     ORDER BY f.next_attempt_at
				     FOR UPDATE OF f SKIP LOCKED LIMIT :lim
				)
				UPDATE commerce_fund_operation f
				   SET lease_owner = :owner, lease_expires_at = now() + :leaseSecs * interval '1 second',
				       attempts = attempts + 1, updated_at = now()
				  FROM due WHERE f.id = due.id
				RETURNING %s
				""".formatted(COLS_UPDATE)).bind("lim", Math.max(1, Math.min(limit, 200))).bind("owner", owner)
				.bind("leaseSecs", Math.max(1, lease.toSeconds())).map(CommerceFundOperationRepository::map).all();
	}

	public Mono<FundOperation> find(String operationId) {
		return db.sql("SELECT " + COLS + " FROM commerce_fund_operation WHERE operation_id = :operationId")
				.bind("operationId", operationId).map(CommerceFundOperationRepository::map).one();
	}

	private static FundOperation map(Readable row) {
		return new FundOperation(row.get("id", String.class), row.get("order_id", String.class),
				row.get("operation_type", String.class), row.get("operation_id", String.class),
				row.get("amount_cents", Long.class), row.get("business_version", Integer.class),
				row.get("status", String.class), row.get("provider_ref", String.class),
				row.get("attempts", Integer.class), instant(row, "next_attempt_at"),
				row.get("lease_owner", String.class), instant(row, "lease_expires_at"),
				row.get("last_error", String.class), instant(row, "created_at"), instant(row, "updated_at"));
	}

	private static Instant instant(Readable row, String name) {
		OffsetDateTime value = row.get(name, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static String truncate(String value) {
		if (value == null || value.isBlank())
			return "unknown error";
		return value.length() <= 500 ? value : value.substring(0, 500);
	}
}
