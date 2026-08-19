package com.grassland.finance.credits;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.LocalDate;
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
    private static final String CONSUME_OPERATION_COLS = """
            operation_id, account_id::text, feature, state,
            consume_transaction_id::text, refund_transaction_id::text,
            consume_balance_after, charge_source, quota_day, quota_limit,
            policy_version, ai_quota_multiplier_bps,
            quota_consume_transaction_id::text, quota_refund_transaction_id::text,
            usage_priced, credits_cents_policy_version, credits_cents_rounding,
            cents_numerator, credits_denominator, max_cents_per_operation,
            reserved_cents, reserved_credits, actual_cents, actual_credits,
            adjustment_credits, settlement_transaction_id::text, settled_at
            """;

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
        return db.sql("SELECT id::text, balance_after, account_id::text, type, feature "
                        + "FROM credits_transaction WHERE operation_id = :op LIMIT 1")
                .bind("op", operationId)
                .map(row -> new ExistingOperation(
                        row.get("id", String.class),
                        row.get("balance_after", Integer.class),
                        row.get("account_id", String.class),
                        row.get("type", String.class),
                        row.get("feature", String.class)))
                .one();
    }

    /** Create-or-lock the consume fence. Must be subscribed inside the caller's transaction. */
    public Mono<ConsumeOperation> lockOrCreateConsumeOperation(
            String accountId, String feature, String operationId, String initialState) {
        return lockOrCreateConsumeOperation(accountId, feature, operationId, initialState, null, null);
    }

    public Mono<ConsumeOperation> lockOrCreateConsumeOperation(
            String accountId, String feature, String operationId, String initialState,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        return lockOrCreateConsumeOperation(
                accountId, feature, operationId, initialState,
                aiQuotaMultiplierBps, policyVersion, null);
    }

    public Mono<ConsumeOperation> lockOrCreateConsumeOperation(
            String accountId, String feature, String operationId, String initialState,
            Integer aiQuotaMultiplierBps, Long policyVersion, UsageReservation usage) {
        GenericExecuteSpec insert = db.sql("""
                INSERT INTO credits_consume_operation(
                    operation_id, account_id, feature, state,
                    ai_quota_multiplier_bps, policy_version,
                    usage_priced, credits_cents_policy_version, credits_cents_rounding,
                    cents_numerator, credits_denominator, max_cents_per_operation,
                    reserved_cents, reserved_credits)
                VALUES (:operationId, CAST(:accountId AS uuid), :feature, :initialState,
                        :aiQuotaMultiplierBps, :policyVersion,
                        :usagePriced, :moneyPolicyVersion, :rounding,
                        :centsNumerator, :creditsDenominator, :maxCents,
                        :reservedCents, :reservedCredits)
                ON CONFLICT (operation_id) DO NOTHING
                """)
                .bind("operationId", operationId)
                .bind("accountId", accountId)
                .bind("feature", feature)
                .bind("initialState", initialState)
                .bind("usagePriced", usage != null);
        insert = bindNullable(insert, "aiQuotaMultiplierBps", aiQuotaMultiplierBps, Integer.class);
        insert = bindNullable(insert, "policyVersion", policyVersion, Long.class);
        insert = bindNullable(insert, "moneyPolicyVersion", usage == null ? null : usage.policyVersion());
        insert = bindNullable(insert, "rounding", usage == null ? null : usage.rounding());
        insert = bindNullable(insert, "centsNumerator", usage == null ? null : usage.centsNumerator(), Long.class);
        insert = bindNullable(insert, "creditsDenominator", usage == null ? null : usage.creditsDenominator(), Long.class);
        insert = bindNullable(insert, "maxCents", usage == null ? null : usage.maxCentsPerOperation(), Long.class);
        insert = bindNullable(insert, "reservedCents", usage == null ? null : usage.reservedCents(), Long.class);
        insert = bindNullable(insert, "reservedCredits", usage == null ? null : usage.reservedCredits(), Integer.class);
        return insert.fetch().rowsUpdated()
                .flatMap(inserted -> db.sql("SELECT " + CONSUME_OPERATION_COLS + """
                        FROM credits_consume_operation
                        WHERE operation_id = :operationId
                        FOR UPDATE
                        """)
                        .bind("operationId", operationId)
                        .map(row -> mapConsumeOperation(row, inserted > 0))
                        .one());
    }

    public Mono<ConsumeOperation> lockConsumeOperation(String operationId) {
        return db.sql("SELECT " + CONSUME_OPERATION_COLS + """
                FROM credits_consume_operation
                WHERE operation_id = :operationId
                FOR UPDATE
                """)
                .bind("operationId", operationId)
                .map(row -> mapConsumeOperation(row, false))
                .one();
    }

    /** Read-only authority lookup for cross-service reconciliation; never creates or locks fences. */
    public Flux<ConsumeOperation> findConsumeOperations(java.util.Collection<String> operationIds) {
        if (operationIds == null || operationIds.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("SELECT " + CONSUME_OPERATION_COLS + """
                FROM credits_consume_operation
                WHERE operation_id = ANY(CAST(:operationIds AS text[]))
                """)
                .bind("operationIds", operationIds.toArray(String[]::new))
                .map(row -> mapConsumeOperation(row, false))
                .all();
    }

    public Mono<Boolean> markConsumeOperationConsumed(
            String operationId, String transactionId, int balanceAfter) {
        return db.sql("""
                UPDATE credits_consume_operation
                SET state = 'consumed',
                    consume_transaction_id = CAST(:transactionId AS uuid),
                    consume_balance_after = :balanceAfter,
                    charge_source = 'paid',
                    updated_at = now()
                WHERE operation_id = :operationId
                  AND (state = 'open' OR (
                        state = 'consumed'
                        AND charge_source = 'paid'
                        AND consume_transaction_id = CAST(:transactionId AS uuid)))
                """)
                .bind("operationId", operationId)
                .bind("transactionId", transactionId)
                .bind("balanceAfter", balanceAfter)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markConsumeOperationQuotaConsumed(
            String operationId, String quotaTransactionId, int balanceAfter,
            LocalDate quotaDay, int quotaLimit) {
        return db.sql("""
                UPDATE credits_consume_operation
                SET state = 'consumed',
                    quota_consume_transaction_id = CAST(:transactionId AS uuid),
                    consume_balance_after = :balanceAfter,
                    charge_source = 'quota',
                    quota_day = :quotaDay,
                    quota_limit = :quotaLimit,
                    updated_at = now()
                WHERE operation_id = :operationId AND state = 'open'
                """)
                .bind("operationId", operationId)
                .bind("transactionId", quotaTransactionId)
                .bind("balanceAfter", balanceAfter)
                .bind("quotaDay", quotaDay)
                .bind("quotaLimit", quotaLimit)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markConsumeOperationFenced(String operationId) {
        return db.sql("""
                UPDATE credits_consume_operation
                SET state = 'compensated', updated_at = now()
                WHERE operation_id = :operationId AND state = 'open'
                """)
                .bind("operationId", operationId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markConsumeOperationRefunded(String operationId, String refundTransactionId) {
        return db.sql("""
                UPDATE credits_consume_operation
                SET state = 'compensated',
                    refund_transaction_id = CAST(:refundTransactionId AS uuid),
                    updated_at = now()
                WHERE operation_id = :operationId AND state = 'consumed'
                """)
                .bind("operationId", operationId)
                .bind("refundTransactionId", refundTransactionId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markConsumeOperationQuotaRefunded(
            String operationId, String refundQuotaTransactionId) {
        return db.sql("""
                UPDATE credits_consume_operation
                SET state = 'compensated',
                    quota_refund_transaction_id = CAST(:transactionId AS uuid),
                    updated_at = now()
                WHERE operation_id = :operationId AND state = 'consumed' AND charge_source = 'quota'
                """)
                .bind("operationId", operationId)
                .bind("transactionId", refundQuotaTransactionId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Boolean> markUsageSettled(
            String operationId, long actualCents, int actualCredits,
            int adjustmentCredits, String settlementTransactionId) {
        GenericExecuteSpec spec = db.sql("""
                UPDATE credits_consume_operation
                SET state = 'settled',
                    actual_cents = :actualCents,
                    actual_credits = :actualCredits,
                    adjustment_credits = :adjustmentCredits,
                    settlement_transaction_id = CAST(:settlementTransactionId AS uuid),
                    settled_at = now(),
                    updated_at = now()
                WHERE operation_id = :operationId
                  AND state = 'consumed'
                  AND usage_priced
                """)
                .bind("operationId", operationId)
                .bind("actualCents", actualCents)
                .bind("actualCredits", actualCredits)
                .bind("adjustmentCredits", adjustmentCredits);
        spec = bindNullable(spec, "settlementTransactionId", settlementTransactionId);
        return spec.fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /** Atomically consumes one free unit without ever exceeding the caller's snapshotted limit. */
    public Mono<QuotaUsage> claimQuota(String accountId, LocalDate quotaDay, int quotaLimit) {
        return db.sql("""
                INSERT INTO credits_daily_quota_usage(account_id, quota_day, used)
                SELECT CAST(:accountId AS uuid), :quotaDay, 1
                WHERE :quotaLimit > 0
                ON CONFLICT (account_id, quota_day) DO UPDATE
                SET used = credits_daily_quota_usage.used + 1, updated_at = now()
                WHERE credits_daily_quota_usage.used < :quotaLimit
                RETURNING used
                """)
                .bind("accountId", accountId)
                .bind("quotaDay", quotaDay)
                .bind("quotaLimit", quotaLimit)
                .map(row -> new QuotaUsage(quotaDay, row.get("used", Integer.class)))
                .one();
    }

    public Mono<QuotaUsage> releaseQuota(String accountId, LocalDate quotaDay) {
        return db.sql("""
                UPDATE credits_daily_quota_usage
                SET used = used - 1, updated_at = now()
                WHERE account_id = CAST(:accountId AS uuid) AND quota_day = :quotaDay AND used > 0
                RETURNING used
                """)
                .bind("accountId", accountId)
                .bind("quotaDay", quotaDay)
                .map(row -> new QuotaUsage(quotaDay, row.get("used", Integer.class)))
                .one();
    }

    public Mono<String> insertQuotaTransaction(
            String accountId, LocalDate quotaDay, int deltaUsed, int usedAfter, int quotaLimit,
            String type, String feature, String operationId, long policyVersion,
            int aiQuotaMultiplierBps, String note) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO credits_quota_transaction(
                    account_id, quota_day, delta_used, used_after, quota_limit, type,
                    feature, operation_id, policy_version, ai_quota_multiplier_bps, note)
                VALUES (CAST(:accountId AS uuid), :quotaDay, :deltaUsed, :usedAfter, :quotaLimit,
                        :type, :feature, :operationId, :policyVersion, :multiplierBps, :note)
                RETURNING id::text
                """)
                .bind("accountId", accountId)
                .bind("quotaDay", quotaDay)
                .bind("deltaUsed", deltaUsed)
                .bind("usedAfter", usedAfter)
                .bind("quotaLimit", quotaLimit)
                .bind("type", type)
                .bind("feature", feature)
                .bind("operationId", operationId)
                .bind("policyVersion", policyVersion)
                .bind("multiplierBps", aiQuotaMultiplierBps);
        spec = bindNullable(spec, "note", note);
        return spec.map(row -> row.get("id", String.class)).one();
    }

    /** 条件扣 1：余额不足（或账户不存在）→ empty。镜像 legacy {@code consumeCredit} 的 UPDATE。 */
    public Mono<CreditsAccount> consumeOne(String accountId) {
        return consumeCredits(accountId, 1);
    }

    /** Atomically reserves a bounded number of paid credits. Zero is retained as an audit transaction. */
    public Mono<CreditsAccount> consumeCredits(String accountId, int amount) {
        if (amount < 0) {
            return Mono.error(new IllegalArgumentException("积分预留不能为负数"));
        }
        return db.sql("""
                UPDATE credits_account
                SET balance = balance - :amount, total_spent = total_spent + :amount, updated_at = now()
                WHERE account_id = CAST(:acct AS uuid) AND balance >= :amount
                RETURNING %s
                """.formatted(ACCOUNT_COLS))
                .bind("acct", accountId)
                .bind("amount", amount)
                .map(CreditsRepository::mapAccount)
                .one();
    }

    /** Positive adjustment charges more; negative adjustment returns the unused reservation. */
    public Mono<CreditsAccount> adjustUsageCredits(String accountId, int adjustmentCredits) {
        return db.sql("""
                UPDATE credits_account
                SET balance = balance - :adjustment,
                    total_spent = total_spent + :adjustment,
                    updated_at = now()
                WHERE account_id = CAST(:acct AS uuid)
                  AND (:adjustment <= 0 OR balance >= :adjustment)
                RETURNING %s
                """.formatted(ACCOUNT_COLS))
                .bind("acct", accountId)
                .bind("adjustment", adjustmentCredits)
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

    /**
     * 批量取余额（admin 用户列表用，避免 N+1）。空入参 → empty，不报错。
     * 未建户的 accountId 不在结果里（调用方按缺失 = 0 余额处理）。
     */
    public Flux<CreditsAccount> findAccounts(java.util.Collection<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("SELECT " + ACCOUNT_COLS
                        + " FROM credits_account WHERE account_id = ANY(CAST(:ids AS uuid[]))")
                .bind("ids", accountIds.toArray(String[]::new))
                .map(CreditsRepository::mapAccount)
                .all();
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

    private static ConsumeOperation mapConsumeOperation(Readable row, boolean created) {
        return new ConsumeOperation(
                row.get("operation_id", String.class),
                row.get("account_id", String.class),
                row.get("feature", String.class),
                row.get("state", String.class),
                row.get("consume_transaction_id", String.class),
                row.get("refund_transaction_id", String.class),
                row.get("consume_balance_after", Integer.class),
                row.get("charge_source", String.class),
                row.get("quota_day", LocalDate.class),
                row.get("quota_limit", Integer.class),
                row.get("policy_version", Long.class),
                row.get("ai_quota_multiplier_bps", Integer.class),
                row.get("quota_consume_transaction_id", String.class),
                row.get("quota_refund_transaction_id", String.class),
                Boolean.TRUE.equals(row.get("usage_priced", Boolean.class)),
                row.get("credits_cents_policy_version", String.class),
                row.get("credits_cents_rounding", String.class),
                row.get("cents_numerator", Long.class),
                row.get("credits_denominator", Long.class),
                row.get("max_cents_per_operation", Long.class),
                row.get("reserved_cents", Long.class),
                row.get("reserved_credits", Integer.class),
                row.get("actual_cents", Long.class),
                row.get("actual_credits", Integer.class),
                row.get("adjustment_credits", Integer.class),
                row.get("settlement_transaction_id", String.class),
                toInstant(row.get("settled_at", OffsetDateTime.class)),
                created);
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

    private static <T> GenericExecuteSpec bindNullable(
            GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /** 账户余额行。 */
    public record CreditsAccount(String accountId, int balance, int totalEarned, int totalSpent) {}

    /** 流水行（history 读端）。 */
    public record CreditsTransaction(String id, int amount, int balanceAfter, String type,
                                     String feature, String note, String operationId, Instant createdAt) {}

    public record QuotaUsage(LocalDate quotaDay, int used) {}

    /** 幂等预检命中行。 */
    public record ExistingOperation(
            String transactionId, int balanceAfter, String accountId, String type, String feature) {
        public ExistingOperation(String transactionId, int balanceAfter) {
            this(transactionId, balanceAfter, null, null, null);
        }
    }

    /** Durable serialization point shared by consume and compensation. */
    public record ConsumeOperation(
            String operationId,
            String accountId,
            String feature,
            String state,
            String consumeTransactionId,
            String refundTransactionId,
            Integer consumeBalanceAfter,
            String chargeSource,
            LocalDate quotaDay,
            Integer quotaLimit,
            Long policyVersion,
            Integer aiQuotaMultiplierBps,
            String quotaConsumeTransactionId,
            String quotaRefundTransactionId,
            boolean usagePriced,
            String creditsCentsPolicyVersion,
            String creditsCentsRounding,
            Long centsNumerator,
            Long creditsDenominator,
            Long maxCentsPerOperation,
            Long reservedCents,
            Integer reservedCredits,
            Long actualCents,
            Integer actualCredits,
            Integer adjustmentCredits,
            String settlementTransactionId,
            Instant settledAt,
            boolean created) {

        public ConsumeOperation(
                String operationId, String accountId, String feature, String state,
                String consumeTransactionId, String refundTransactionId,
                Integer consumeBalanceAfter, boolean created) {
            this(operationId, accountId, feature, state, consumeTransactionId,
                    refundTransactionId, consumeBalanceAfter, null, null, null,
                    null, null, null, null, false, null, null,
                    null, null, null, null, null, null, null, null, null, null, created);
        }
    }

    public record UsageReservation(
            String policyVersion,
            String rounding,
            long centsNumerator,
            long creditsDenominator,
            long maxCentsPerOperation,
            long reservedCents,
            int reservedCredits) {}
}
