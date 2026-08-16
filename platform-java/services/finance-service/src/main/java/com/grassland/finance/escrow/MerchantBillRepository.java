package com.grassland.finance.escrow;

import com.grassland.finance.ledger.LedgerAccount;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 商家月度账单聚合（任务书 #29+#30 Stage 1 / D1、D4）。
 *
 * <p>journal/posting 双录是资金真相源；账单 = 权威表上的按需 SQL 聚合，不建物化账单表。
 * 科目分解按 {@code journal_type} 分组（D4：实际枚举以代码为准——{@code JournalEntry.Type} 共 11 值，
 * 其中 WITHDRAW/AI_CREDIT_PURCHASE 的 organization_id 为 null，天然不出现在商家账单）。
 *
 * <p><b>flow 口径</b>：某类 journal 的 ESCROW 腿净额（credit − debit），即该事件对商家可用余额的影响。
 * 由此构造不变式「Σ flows == 该 org 该月 ESCROW 腿净额 == netEscrowDeltaCents」，由 IT 锁死。
 * CAPTURE 不动 ESCROW 腿（资金从 RESERVE 直接去钱包/平台费），故不产生 flow；商家为结算付出的
 * 平台抽成由 {@code platformFeeCents}（FEE 腿净额）单独呈现。
 */
@Component
public class MerchantBillRepository {

    private final DatabaseClient db;

    public MerchantBillRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 按 journal_type 聚合 ESCROW 腿净额（零贡献类型 SQL 层剔除）。 */
    public Flux<JournalFlow> flows(String orgId, Instant from, Instant to) {
        return db.sql("""
                SELECT j.journal_type,
                       COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                         ELSE -p.amount_cents END), 0)::bigint AS escrow_net_cents
                FROM journal j
                JOIN posting p ON p.journal_id = j.id
                WHERE j.organization_id = CAST(:orgId AS uuid)
                  AND j.created_at >= :fromTs AND j.created_at < :toTs
                  AND p.account_type = :escrowType
                GROUP BY j.journal_type
                HAVING COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                         ELSE -p.amount_cents END), 0) <> 0
                ORDER BY j.journal_type
                """)
                .bind("orgId", orgId)
                .bind("fromTs", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("toTs", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .bind("escrowType", LedgerAccount.Type.ESCROW.dbValue())
                .map(row -> new JournalFlow(
                        row.get("journal_type", String.class),
                        row.get("escrow_net_cents", Long.class)))
                .all();
    }

    /**
     * 该 org 该月平台费净额：FEE 腿 credit − debit。
     * capture/consumer-split 记 Cr FEE（商家付出），reverse/split-refund 记 Dr FEE（回冲）。
     */
    public Mono<Long> platformFee(String orgId, Instant from, Instant to) {
        return db.sql("""
                SELECT COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                         ELSE -p.amount_cents END), 0)::bigint AS fee_net_cents
                FROM journal j
                JOIN posting p ON p.journal_id = j.id
                WHERE j.organization_id = CAST(:orgId AS uuid)
                  AND j.created_at >= :fromTs AND j.created_at < :toTs
                  AND p.account_type = :feeType
                """)
                .bind("orgId", orgId)
                .bind("fromTs", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("toTs", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .bind("feeType", LedgerAccount.Type.FEE.dbValue())
                .map(row -> row.get("fee_net_cents", Long.class)).one().defaultIfEmpty(0L);
    }

    /**
     * 该 org 该月 ESCROW 腿净额（账单不变式的对照口径，也是 netEscrowDeltaCents 的独立复核路径）。
     */
    public Mono<Long> escrowDelta(String orgId, Instant from, Instant to) {
        return db.sql("""
                SELECT COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                         ELSE -p.amount_cents END), 0)::bigint AS escrow_net_cents
                FROM journal j
                JOIN posting p ON p.journal_id = j.id
                WHERE j.organization_id = CAST(:orgId AS uuid)
                  AND j.created_at >= :fromTs AND j.created_at < :toTs
                  AND p.account_type = :escrowType
                """)
                .bind("orgId", orgId)
                .bind("fromTs", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("toTs", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .bind("escrowType", LedgerAccount.Type.ESCROW.dbValue())
                .map(row -> row.get("escrow_net_cents", Long.class)).one().defaultIfEmpty(0L);
    }

    /** 单类 journal 的 ESCROW 腿净额。 */
    public record JournalFlow(String journalType, long escrowNetCents) {
    }
}
