package com.grassland.marketplace.commerce;

import io.r2dbc.spi.Readable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-15：已确认分账净额事实仓储（V66）。
 *
 * <p>
 * 只写 Finance 已完成的分账结果（分账成功与恢复重放都走 {@link #recordVerified}）： 幂等键 = order_id（主键）+
 * operation_id（唯一）+ source_hash（内容指纹）； 同 order 重放同 hash 为无操作（重投/回包丢失不重复累加）；同
 * order 不同 hash 不覆盖原事实， 由 {@link #hashMismatchCount} 暴露给查询层标 partial
 * 进入核对（E20：冲突可审计，不静默覆盖）。 已结金额是经确认事实，不按当前政策重算。
 */
@Component
public class CommerceSettlementFactRepository {

	private final DatabaseClient db;

	public CommerceSettlementFactRepository(DatabaseClient db) {
		this.db = db;
	}

	public record VerifiedFact(String orderId, String organizationId, String operationId, long originalPaidCents,
			long refundedBeforeSplitCents, long netTotalCents, long merchantCents, long platformCents,
			long recommenderTotalCents, Instant financeCompletedAt, Instant verifiedAt) {
	}

	public record Allocation(String recommenderAccountId, long amountCents) {
	}

	/** 内容指纹：order/operation/三方金额/退款前额——同键同额重放同 hash，改任何金额即冲突。 */
	public static String sourceHash(String orderId, String operationId, long netTotalCents, long merchantCents,
			long platformCents, long recommenderTotalCents, long refundedBeforeSplitCents) {
		String canonical = orderId + "|" + operationId + "|" + netTotalCents + "|" + merchantCents + "|" + platformCents
				+ "|" + recommenderTotalCents + "|" + refundedBeforeSplitCents;
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	/** 幂等落事实（ON CONFLICT DO NOTHING）；分配快照同事务随后写入。返回是否新插入。 */
	public Mono<Boolean> recordVerified(VerifiedFact fact, List<Allocation> allocations) {
		long allocationTotal = allocations.stream().mapToLong(Allocation::amountCents).sum();
		if (allocationTotal != fact.recommenderTotalCents()) {
			return Mono.error(new IllegalArgumentException("分配快照之和必须等于推荐官总净额"));
		}
		return db.sql("""
				INSERT INTO commerce_settlement_fact(
				    order_id, operation_id, organization_id, original_paid_cents, refunded_before_split_cents,
				    net_total_cents, merchant_cents, platform_cents, recommender_total_cents,
				    finance_completed_at, source_hash, verified_at)
				VALUES (CAST(:orderId AS uuid), :operationId, CAST(:org AS uuid), :paid, :refunded,
				        :netTotal, :merchant, :platform, :recommender, :completedAt, :hash, :verifiedAt)
				ON CONFLICT (order_id) DO NOTHING
				""").bind("orderId", fact.orderId()).bind("operationId", fact.operationId())
				.bind("org", fact.organizationId()).bind("paid", fact.originalPaidCents())
				.bind("refunded", fact.refundedBeforeSplitCents()).bind("netTotal", fact.netTotalCents())
				.bind("merchant", fact.merchantCents()).bind("platform", fact.platformCents())
				.bind("recommender", fact.recommenderTotalCents())
				.bind("completedAt", offset(fact.financeCompletedAt()))
				.bind("hash",
						sourceHash(fact.orderId(), fact.operationId(), fact.netTotalCents(), fact.merchantCents(),
								fact.platformCents(), fact.recommenderTotalCents(), fact.refundedBeforeSplitCents()))
				.bind("verifiedAt", offset(fact.verifiedAt())).fetch().rowsUpdated().flatMap(inserted -> {
					if (inserted == null || inserted == 0) {
						return Mono.just(false); // 幂等重放（E16）：同键吸收
					}
					Mono<Void> chain = Mono.empty();
					for (Allocation allocation : allocations) {
						chain = chain.then(db.sql("""
								INSERT INTO commerce_settlement_allocation_fact(
								    order_id, recommender_account_id, amount_cents)
								VALUES (CAST(:orderId AS uuid), CAST(:rec AS uuid), :amount)
								ON CONFLICT (order_id, recommender_account_id) DO NOTHING
								""").bind("orderId", fact.orderId()).bind("rec", allocation.recommenderAccountId())
								.bind("amount", allocation.amountCents()).then());
					}
					return chain.then(Mono.just(true));
				});
	}

	/** 同 order 不同 source_hash 的冲突行数（partial 核对信号；不覆盖原事实）。 */
	public Mono<Long> hashMismatchCount(java.time.Instant asOf) {
		return db.sql("""
				SELECT COUNT(*) FROM commerce_settlement_fact f
				JOIN consumer_order o ON o.id = f.order_id
				WHERE o.split_operation_id IS DISTINCT FROM f.operation_id
				""").map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
	}

	/** 某订单的已确认事实（无则 empty）。 */
	public Mono<VerifiedFact> findByOrder(String orderId) {
		return db
				.sql("SELECT order_id::text, organization_id::text, operation_id, original_paid_cents,"
						+ " refunded_before_split_cents, net_total_cents, merchant_cents, platform_cents,"
						+ " recommender_total_cents, finance_completed_at, verified_at"
						+ " FROM commerce_settlement_fact WHERE order_id = CAST(:id AS uuid)")
				.bind("id", orderId).map(CommerceSettlementFactRepository::map).one();
	}

	/** 推荐官已结分配（排行消费：按订单 cohort 聚合前逐行取，避免与订单 JOIN 倍增）。 */
	public Flux<Allocation> allocationsOfOrder(String orderId) {
		return db
				.sql("SELECT recommender_account_id::text, amount_cents"
						+ " FROM commerce_settlement_allocation_fact WHERE order_id = CAST(:id AS uuid)")
				.bind("id", orderId).map((row, meta) -> new Allocation(row.get("recommender_account_id", String.class),
						row.get("amount_cents", Long.class)))
				.all();
	}

	private static VerifiedFact map(Readable row) {
		return new VerifiedFact(row.get("order_id", String.class), row.get("organization_id", String.class),
				row.get("operation_id", String.class), row.get("original_paid_cents", Long.class),
				row.get("refunded_before_split_cents", Long.class), row.get("net_total_cents", Long.class),
				row.get("merchant_cents", Long.class), row.get("platform_cents", Long.class),
				row.get("recommender_total_cents", Long.class),
				instant(row.get("finance_completed_at", OffsetDateTime.class)),
				instant(row.get("verified_at", OffsetDateTime.class)));
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime offset(Instant value) {
		return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
	}
}
