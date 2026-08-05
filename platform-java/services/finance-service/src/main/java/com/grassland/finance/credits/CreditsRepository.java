package com.grassland.finance.credits;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 积分账户与流水数据访问（R2DBC 手写 SQL，house style 同 {@code wallet/WalletRepository}）。
 *
 * <p>逻辑逐字移植 legacy {@code server/src/services/credit.service.ts}：
 * <ul>
 *   <li>账户懒创建（{@code ensureAccount} upsert ON CONFLICT DO NOTHING）；</li>
 *   <li>扣减用条件 {@code UPDATE … WHERE balance >= 1 RETURNING}（语句级行锁防并发双花，0 行 = 余额不足）；</li>
 *   <li>流水 append-only，幂等靠 {@code operation_id} 部分唯一索引（V6）；</li>
 *   <li>{@code findOperation} 供 service 做事务内预检与冲突后重读。</li>
 * </ul>
 *
 * <p>本类只做单条 SQL；余额改 + 流水插是否同事务由 {@link CreditsService} 用 {@code TransactionalOperator} 组合保证。
 */
@Component
public class CreditsRepository {

    private static final String ACCOUNT_COLS = "account_id::text, balance, total_earned, total_spent";
    private static final String TXN_COLS =
            "id::text, account_id::text, amount, balance_after, type, feature, note, operation_id, created_at";

    private final DatabaseClient db;

    public CreditsRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 懒创建账户（无则建 0 余额，有则不动）。镜像 legacy {@code ensureCreditAccount}。 */
    public Mono<Void> ensureAccount(String accountId) {
        return db.sql("""
                INSERT INTO credits_account(account_id, balance, total_earned, total_spent)
                VALUES (CAST(:acct AS uuid), 0, 0, 0)
                ON CONFLICT (account_id) DO NOTHING
                """)
                .bind("acct", accountId)
                .then();
    }

    /** 幂等预检：命中既有 operation_id 的流水则返回它（balance_after 即当时余额）。null opId → empty。 */
    public Mono<ExistingOperation> findOperation(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return Mono.empty();
        }
        return db.sql("SELECT id::text, balance_after FROM credits_transaction WHERE operation_id = :op LIMIT 1")
                .bind("op", operationId)
                .map(row -> new ExistingOperation(
                        row.get("id", String.class),
                        row.get("balance_after", Integer.class)))
                .one();
    }

    /** 条件扣 1：余额不足（或账户不存在）→ empty。镜像 legacy {@code consumeCredit} 的 UPDATE。 */
    public Mono<CreditsAccount> consumeOne(String accountId) {
        return db.sql("""
                UPDATE credits_account
                SET balance = balance - 1, total_spent = total_spent + 1, updated_at = now()
                WHERE account_id = CAST(:acct AS uuid) AND balance >= 1
                RETURNING %s
                """.formatted(ACCOUNT_COLS))
                .bind("acct", accountId)
                .map(CreditsRepository::mapAccount)
                .one();
    }

    /**
     * 加减余额（退款/赠送/admin 调整）：无余额门槛（账户已由 {@link #ensureAccount} 保证存在）。
     * deltaEarn/deltaSpent 由调用方按语义传入（退款 = (amount, 0, -amount)、赠送 = (amount, amount, 0)）。
     */
    public Mono<CreditsAccount> creditAccount(String accountId, int deltaBalance, int deltaEarned, int deltaSpent) {
        return db.sql("""
                UPDATE credits_account
                SET balance = balance + :db, total_earned = total_earned + :de, total_spent = total_spent + :ds, updated_at = now()
                WHERE account_id = CAST(:acct AS uuid)
                RETURNING %s
                """.formatted(ACCOUNT_COLS))
                .bind("acct", accountId).bind("db", deltaBalance)
                .bind("de", deltaEarned).bind("ds", deltaSpent)
                .map(CreditsRepository::mapAccount)
                .one();
    }

    /** 插流水（append-only），返回新生成的事务 id。 */
    public Mono<String> insertTransaction(String accountId, int amount, int balanceAfter, String type,
                                          String feature, String note, String operationId) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO credits_transaction(account_id, amount, balance_after, type, feature, note, operation_id)
                VALUES (CAST(:acct AS uuid), :amt, :bal, :type, :feature, :note, :op)
                RETURNING id::text
                """)
                .bind("acct", accountId).bind("amt", amount).bind("bal", balanceAfter).bind("type", type);
        spec = bindNullable(spec, "feature", feature);
        spec = bindNullable(spec, "note", note);
        spec = bindNullable(spec, "op", operationId);
        return spec.map(row -> row.get("id", String.class)).one();
    }

    public Mono<CreditsAccount> findAccount(String accountId) {
        return db.sql("SELECT " + ACCOUNT_COLS + " FROM credits_account WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .map(CreditsRepository::mapAccount)
                .one();
    }

    public Flux<CreditsTransaction> history(String accountId, int limit) {
        return db.sql("SELECT " + TXN_COLS + " FROM credits_transaction"
                + " WHERE account_id = CAST(:acct AS uuid) ORDER BY created_at DESC LIMIT :lim")
                .bind("acct", accountId).bind("lim", limit)
                .map(CreditsRepository::mapTransaction)
                .all();
    }

    private static CreditsAccount mapAccount(Readable row) {
        return new CreditsAccount(
                row.get("account_id", String.class),
                nonNull(row.get("balance", Integer.class)),
                nonNull(row.get("total_earned", Integer.class)),
                nonNull(row.get("total_spent", Integer.class)));
    }

    private static CreditsTransaction mapTransaction(Readable row) {
        return new CreditsTransaction(
                row.get("id", String.class),
                row.get("amount", Integer.class),
                row.get("balance_after", Integer.class),
                row.get("type", String.class),
                row.get("feature", String.class),
                row.get("note", String.class),
                row.get("operation_id", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static int nonNull(Integer v) {
        return v == null ? 0 : v;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    /** 账户余额行。 */
    public record CreditsAccount(String accountId, int balance, int totalEarned, int totalSpent) {}

    /** 流水行（history 读端）。 */
    public record CreditsTransaction(String id, int amount, int balanceAfter, String type,
                                     String feature, String note, String operationId, Instant createdAt) {}

    /** 幂等预检命中行。 */
    public record ExistingOperation(String transactionId, int balanceAfter) {}
}
