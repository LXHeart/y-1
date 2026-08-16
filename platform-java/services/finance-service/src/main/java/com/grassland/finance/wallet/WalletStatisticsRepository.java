package com.grassland.finance.wallet;

import com.grassland.finance.report.MonthParam;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 推荐官收入统计聚合（任务书 #29+#30 Stage 1 / D1）。
 *
 * <p><b>聚合 = 权威表上的按需 SQL 聚合</b>，不建物化账单表——{@code wallet_ledger} 是推荐官资金的唯一真相源，
 * 实时聚合永远与明细一致（一致性由 IT「聚合 == SUM(区间明细)」锁死）。
 *
 * <p>月切按北京时间自然月（D2）：{@code date_trunc('month', created_at AT TIME ZONE 'Asia/Shanghai')}。
 * {@code amount_cents} 本身带符号（入账正/提现冲正负），聚合直接 SUM，不手工加符号；
 * 毛额 = amount + fee 只对入账类（task_payout / commerce_commission）成立。
 */
@Component
public class WalletStatisticsRepository {

    private final DatabaseClient db;

    public WalletStatisticsRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 按月聚合：四类金额 + 毛/抽成/净。{@code [from, to)} 为北京时间展开后的瞬时区间。
     * 空区间返回空 Flux（controller 层补全零值月份）。
     */
    public Flux<MonthlyIncome> monthly(String accountId, Instant from, Instant to) {
        return db.sql("""
                SELECT to_char(date_trunc('month', created_at AT TIME ZONE 'Asia/Shanghai'), 'YYYY-MM') AS month,
                       COALESCE(SUM(CASE WHEN entry_type = 'task_payout' THEN amount_cents END), 0)::bigint
                           AS task_payout_cents,
                       COALESCE(SUM(CASE WHEN entry_type = 'commerce_commission' THEN amount_cents END), 0)::bigint
                           AS commerce_commission_cents,
                       COALESCE(SUM(CASE WHEN entry_type = 'withdrawal' THEN amount_cents END), 0)::bigint
                           AS withdrawal_cents,
                       COALESCE(SUM(CASE WHEN entry_type = 'clawback' THEN amount_cents END), 0)::bigint
                           AS clawback_cents,
                       COALESCE(SUM(CASE WHEN entry_type IN ('task_payout', 'commerce_commission')
                                         THEN amount_cents + fee_cents END), 0)::bigint AS gross_cents,
                       COALESCE(SUM(CASE WHEN entry_type IN ('task_payout', 'commerce_commission')
                                         THEN fee_cents END), 0)::bigint AS fee_cents,
                       COALESCE(SUM(amount_cents), 0)::bigint AS net_cents
                FROM wallet_ledger
                WHERE account_id = CAST(:acct AS uuid) AND created_at >= :fromTs AND created_at < :toTs
                GROUP BY 1 ORDER BY 1
                """)
                .bind("acct", accountId)
                .bind("fromTs", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("toTs", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .map(WalletStatisticsRepository::mapMonthly).all();
    }

    /**
     * 区间内按 {@code engagement_ref} 聚合（入账类）：任务明细供前端 join marketplace 任务标题（D3）。
     * {@code engagement_ref} 为 null 的提现/冲正行不进该聚合；按最近入账时间倒序。
     */
    public Flux<EngagementIncome> byEngagement(String accountId, Instant from, Instant to) {
        return db.sql("""
                SELECT engagement_ref,
                       SUM(amount_cents)::bigint AS payout_cents,
                       SUM(fee_cents)::bigint AS fee_cents,
                       COUNT(*)::bigint AS entry_count,
                       MAX(created_at) AS last_at
                FROM wallet_ledger
                WHERE account_id = CAST(:acct AS uuid) AND created_at >= :fromTs AND created_at < :toTs
                  AND engagement_ref IS NOT NULL
                  AND entry_type IN ('task_payout', 'commerce_commission')
                GROUP BY engagement_ref ORDER BY MAX(created_at) DESC
                """)
                .bind("acct", accountId)
                .bind("fromTs", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("toTs", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .map(WalletStatisticsRepository::mapEngagement).all();
    }

    private static MonthlyIncome mapMonthly(Readable row) {
        return new MonthlyIncome(
                row.get("month", String.class),
                row.get("task_payout_cents", Long.class),
                row.get("commerce_commission_cents", Long.class),
                row.get("withdrawal_cents", Long.class),
                row.get("clawback_cents", Long.class),
                row.get("gross_cents", Long.class),
                row.get("fee_cents", Long.class),
                row.get("net_cents", Long.class));
    }

    private static EngagementIncome mapEngagement(Readable row) {
        return new EngagementIncome(
                row.get("engagement_ref", String.class),
                row.get("payout_cents", Long.class),
                row.get("fee_cents", Long.class),
                row.get("entry_count", Long.class),
                toInstant(row.get("last_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** 单月聚合行。金额单位分；net = SUM(amount_cents) 带符号。 */
    public record MonthlyIncome(String month, long taskPayoutCents, long commerceCommissionCents,
                                long withdrawalCents, long clawbackCents,
                                long grossCents, long feeCents, long netCents) {
    }

    /** 单个 engagement 的区间聚合行。 */
    public record EngagementIncome(String engagementRef, long payoutCents, long feeCents,
                                   long entryCount, Instant lastAt) {
    }
}
