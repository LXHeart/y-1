package com.grassland.finance.wallet;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 推荐官钱包与流水数据访问（R2DBC 手写 SQL，house style）。
 *
 * <p>钱包**懒创建**：入账用 upsert（推荐官不需要「开户」这一步，第一笔结算自然建户）；
 * 出账用条件 UPDATE（{@code WHERE balance_cents >= :amt}，语句级行锁保证并发安全），0 行 = 余额不足。
 */
@Component
public class WalletRepository {

    private static final String WALLET_COLS = "account_id::text, balance_cents, updated_at";
    private static final String ENTRY_COLS =
            "id::text, account_id::text, entry_type, amount_cents, fee_cents, commission_bonus_cents,"
                    + " engagement_ref, memo, created_at";

    private final DatabaseClient db;

    public WalletRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Wallet> findByAccount(String accountId) {
        return db.sql("SELECT " + WALLET_COLS + " FROM recommender_wallet WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .map(WalletRepository::mapWallet).one();
    }

    /** 入账（懒创建钱包）：原子 {@code balance += :amt}，返回更新后余额。 */
    public Mono<Wallet> credit(String accountId, long amountCents) {
        return db.sql("""
                INSERT INTO recommender_wallet(account_id, balance_cents)
                VALUES (CAST(:acct AS uuid), :amt)
                ON CONFLICT (account_id) DO UPDATE
                  SET balance_cents = recommender_wallet.balance_cents + :amt, updated_at = now()
                RETURNING %s
                """.formatted(WALLET_COLS))
                .bind("acct", accountId).bind("amt", amountCents)
                .map(WalletRepository::mapWallet).one();
    }

    /**
     * 出账：条件扣减，余额不足（或钱包不存在）→ empty。
     *
     * <p>用条件 UPDATE 而不是「先查后扣」——后者在并发提现下会双花，CHECK 约束只会让第二笔以 500 崩掉，
     * 而不是给出「余额不足」这种可解释的结果。
     */
    public Mono<Wallet> debit(String accountId, long amountCents) {
        return db.sql("""
                UPDATE recommender_wallet SET balance_cents = balance_cents - :amt, updated_at = now()
                WHERE account_id = CAST(:acct AS uuid) AND balance_cents >= :amt
                RETURNING %s
                """.formatted(WALLET_COLS))
                .bind("acct", accountId).bind("amt", amountCents)
                .map(WalletRepository::mapWallet).one();
    }

    /** 追加流水（append-only，不做更新/删除）。 */
    public Mono<WalletEntry> appendEntry(String accountId, WalletEntryType type, long amountCents,
                                         long feeCents, String engagementRef, String memo) {
        return appendEntry(accountId, type, amountCents, feeCents, 0L, engagementRef, memo);
    }

    /** 追加含平台补贴拆分的流水（append-only）。 */
    public Mono<WalletEntry> appendEntry(String accountId, WalletEntryType type, long amountCents,
                                         long feeCents, long commissionBonusCents,
                                         String engagementRef, String memo) {
        var spec = db.sql("""
                INSERT INTO wallet_ledger(id, account_id, entry_type, amount_cents, fee_cents,
                                          commission_bonus_cents, engagement_ref, memo)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :type, :amt, :fee, :bonus, :ref, :memo)
                RETURNING %s
                """.formatted(ENTRY_COLS))
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
                .bind("type", type.dbValue()).bind("amt", amountCents).bind("fee", feeCents)
                .bind("bonus", commissionBonusCents);
        spec = bindNullable(spec, "ref", engagementRef);
        spec = bindNullable(spec, "memo", memo);
        return spec.map(WalletRepository::mapEntry).one();
    }

    public Flux<WalletEntry> findEntries(String accountId, int limit) {
        return db.sql("SELECT " + ENTRY_COLS + " FROM wallet_ledger"
                + " WHERE account_id = CAST(:acct AS uuid) ORDER BY created_at DESC LIMIT :lim")
                .bind("acct", accountId).bind("lim", limit)
                .map(WalletRepository::mapEntry).all();
    }

    public Flux<WalletEntry> exportEntries(String accountId, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(ENTRY_COLS).append(" FROM wallet_ledger")
                .append(" WHERE account_id = CAST(:acct AS uuid)");
        if (from != null) sql.append(" AND created_at >= :fromAt");
        if (to != null) sql.append(" AND created_at < :toAt");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        GenericExecuteSpec spec = db.sql(sql.toString()).bind("acct", accountId)
                .bind("limit", Math.max(1, Math.min(limit, 10_000)));
        if (from != null) spec = spec.bind("fromAt", from.atOffset(ZoneOffset.UTC));
        if (to != null) spec = spec.bind("toAt", to.atOffset(ZoneOffset.UTC));
        return spec.map(WalletRepository::mapEntry).all();
    }

    private static Wallet mapWallet(Readable row) {
        return new Wallet(
                row.get("account_id", String.class),
                row.get("balance_cents", Long.class),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static WalletEntry mapEntry(Readable row) {
        return new WalletEntry(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("entry_type", String.class),
                row.get("amount_cents", Long.class),
                row.get("fee_cents", Long.class),
                row.get("commission_bonus_cents", Long.class),
                row.get("engagement_ref", String.class),
                row.get("memo", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
