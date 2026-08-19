package com.grassland.finance.compliance;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class FinanceComplianceRepository {

    private final DatabaseClient db;

    public FinanceComplianceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Summary> closureSummary(String accountId) {
        return db.sql("""
                SELECT
                  COALESCE((SELECT balance_cents FROM recommender_wallet
                            WHERE account_id = CAST(:accountId AS uuid)), 0)::bigint AS wallet_balance,
                  (SELECT COUNT(*) FROM funds_reservation
                   WHERE payee_account_id = CAST(:accountId AS uuid) AND status = 'reserved')
                  +
                  (SELECT COUNT(*) FROM consumer_payment_split
                   WHERE recommender_account_id = CAST(:accountId AS uuid) AND status = 'processing')
                    AS pending_settlements
                """)
                .bind("accountId", accountId)
                .map(row -> new Summary(value(row.get("wallet_balance", Long.class)),
                        value(row.get("pending_settlements", Long.class))))
                .one();
    }

    public Flux<FinancialRecord> financialRecords(String accountId, int offset, int limit) {
        return db.sql("""
                SELECT * FROM (
                  SELECT id::text, 'wallet_' || entry_type AS type,
                         CASE WHEN amount_cents >= 0 THEN 'income' ELSE 'expenditure' END AS direction,
                         ABS(amount_cents)::bigint AS amount_cents, fee_cents::bigint,
                         'completed'::text AS status, engagement_ref AS reference, memo, created_at AS occurred_at
                  FROM wallet_ledger WHERE account_id = CAST(:accountId AS uuid)
                  UNION ALL
                  SELECT id::text, 'consumer_payment', 'expenditure', amount_cents, 0::bigint,
                         status, order_ref, channel, created_at
                  FROM consumer_payment WHERE consumer_account_id = CAST(:accountId AS uuid)
                  UNION ALL
                  SELECT refund.id::text, 'consumer_refund', 'income', refund.amount_cents, 0::bigint,
                         refund.status, refund.order_ref, refund.reason, refund.created_at
                  FROM consumer_payment_refund refund
                  JOIN consumer_payment payment ON payment.order_ref = refund.order_ref
                  WHERE payment.consumer_account_id = CAST(:accountId AS uuid)
                ) records
                ORDER BY occurred_at DESC, id DESC OFFSET :offset LIMIT :limit
                """)
                .bind("accountId", accountId)
                .bind("offset", Math.max(0, offset))
                .bind("limit", Math.max(1, Math.min(limit, 501)))
                .map(FinanceComplianceRepository::mapRecord)
                .all();
    }

    public Mono<Map<String, Long>> erasePii(String accountId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        return db.sql("UPDATE wallet_ledger SET memo = NULL"
                        + " WHERE account_id = CAST(:id AS uuid) AND memo IS NOT NULL")
                .bind("id", accountId).fetch().rowsUpdated()
                .doOnNext(value -> counts.put("walletMemos", value))
                .then(db.sql("UPDATE consumer_payment_refund refund SET reason = NULL"
                                + " FROM consumer_payment payment"
                                + " WHERE payment.order_ref = refund.order_ref"
                                + " AND payment.consumer_account_id = CAST(:id AS uuid)"
                                + " AND refund.reason IS NOT NULL")
                        .bind("id", accountId).fetch().rowsUpdated())
                .doOnNext(value -> counts.put("refundReasons", value))
                .thenReturn(counts);
    }

    private static FinancialRecord mapRecord(Readable row) {
        return new FinancialRecord(
                row.get("id", String.class), row.get("type", String.class),
                row.get("direction", String.class), value(row.get("amount_cents", Long.class)),
                value(row.get("fee_cents", Long.class)), row.get("status", String.class),
                row.get("reference", String.class), row.get("memo", String.class),
                instant(row.get("occurred_at", OffsetDateTime.class)));
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Summary(long walletBalanceCents, long pendingSettlements) {}

    public record FinancialRecord(
            String id, String type, String direction, long amountCents, long feeCents,
            String status, String reference, String memo, Instant occurredAt) {}
}
