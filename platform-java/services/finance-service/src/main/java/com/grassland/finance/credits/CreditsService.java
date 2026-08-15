package com.grassland.finance.credits;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.ConsumeOperation;
import com.grassland.finance.credits.CreditsRepository.CreditsTransaction;
import com.grassland.finance.credits.CreditsRepository.ExistingOperation;
import com.grassland.finance.credits.CreditsRepository.QuotaUsage;
import com.grassland.finance.security.FinanceException;
import io.r2dbc.spi.R2dbcException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 积分扣减/退款/赠送编排（GL-P3-AI-001 下属切片）。逐字移植 legacy {@code credit.service.ts} 的幂等闭环：
 *
 * <ol>
 *   <li>事务内 {@code findOperation} 预检 → 命中既有 operation_id 即 {@code deduplicated}，不改余额；</li>
 *   <li>余额改 + 流水插 {@code 同事务}（GL-P0-CRED-001）；扣减用条件 UPDATE，余额不足 → 402；</li>
 *   <li>并发兜底：预检与插入之间另一请求写入同 operation_id 时，唯一索引抛 23505，事务回滚，
 *       事务外重读胜出行作 {@code deduplicated}（镜像 legacy {@code readOperationAfterConflict}）。</li>
 * </ol>
 *
 * <p><b>operation_id 契约</b>：本服务<b>原样存储</b>调用方传入的 operation_id（不做 {@code refund:} 派生）。
 * consume 行 opId = X；对应的失败退款行 opId = {@code refund:X}，由<b>调用方</b>（intelligence
 * {@code FinanceCreditsClient}、legacy Express {@code createCharge}）派生后传入——保证一次扣减至多一次退款，
 * 且 admin 的一次性赠送（无 opId）不受影响。
 *
 * <p>写工作用 {@link Mono#defer} 包裹：预检命中时不装配写 Mono（避免 eager assembly 在测试桩返回 null 时 NPE，
 * 也保证写 SQL 仅在事务订阅期执行）。
 */
@Service
public class CreditsService {

    private final CreditsRepository repo;
    private final TransactionalOperator transactions;
    private final AiQuotaPolicy aiQuotaPolicy;

    @Autowired
    public CreditsService(CreditsRepository repo, TransactionalOperator transactions, AiQuotaPolicy aiQuotaPolicy) {
        this.repo = repo;
        this.transactions = transactions;
        this.aiQuotaPolicy = aiQuotaPolicy;
    }

    /** Unit-test/backward-compatible constructor: no free quota unless an entitlement is supplied. */
    public CreditsService(CreditsRepository repo, TransactionalOperator transactions) {
        this(repo, transactions, new AiQuotaPolicy(0, java.time.ZoneId.of("Asia/Shanghai"), java.time.Clock.systemUTC()));
    }

    /** 扣 1 积分（consume）。operationId 非空即幂等；余额不足 → 402。 */
    public Mono<MutationResult> consume(String accountId, String feature, String operationId) {
        return consume(accountId, feature, operationId, null, null);
    }

    /** Consumes one AI charge using a marketplace policy snapshot, preferring the daily free quota. */
    public Mono<MutationResult> consume(
            String accountId, String feature, String operationId,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        boolean hasMultiplier = aiQuotaMultiplierBps != null;
        boolean hasVersion = policyVersion != null;
        if (hasMultiplier != hasVersion) {
            return Mono.error(new FinanceException(400, "AI 权益快照字段不完整"));
        }
        if (hasMultiplier) {
            if (operationId == null || operationId.isBlank()) {
                return Mono.error(new FinanceException(400, "AI 权益扣减必须提供 operationId"));
            }
            if (policyVersion < 1) {
                return Mono.error(new FinanceException(400, "policyVersion 必须大于等于 1"));
            }
            try {
                aiQuotaPolicy.limitFor(aiQuotaMultiplierBps);
            } catch (IllegalArgumentException invalid) {
                return Mono.error(new FinanceException(400, invalid.getMessage()));
            }
        }
        if (operationId != null && !operationId.isBlank()) {
            return fencedConsume(accountId, feature, operationId, aiQuotaMultiplierBps, policyVersion);
        }
        return idempotent(accountId, operationId, () -> repo.consumeOne(accountId)
                .switchIfEmpty(Mono.error(new FinanceException(402, "积分不足")))
                .flatMap(acct -> repo.insertTransaction(
                        accountId, -1, acct.balance(), "consume", feature, null, operationId)
                        .map(txnId -> MutationResult.paid(acct.balance(), txnId, false, policyVersion))));
    }

    /**
     * Atomically fence or reverse a consume whose HTTP result may be unknown.
     * The caller supplies only the original consume operation; refund identity is derived here.
     */
    public Mono<CompensationResult> compensateConsume(
            String accountId, String feature, String operationId, String note) {
        Mono<CompensationResult> body = repo.lockOrCreateConsumeOperation(
                        accountId, feature, operationId, "compensated")
                .flatMap(operation -> validateScope(operation, accountId, feature)
                        .then(compensateLocked(operation, note)));
        return repo.ensureAccount(accountId).then(transactions.transactional(body));
    }

    /** 退还积分（refund）。amount 为正幅度；operationId 非空即幂等（调用方传 {@code refund:<consumeId>}）。 */
    public Mono<MutationResult> refund(String accountId, int amount, String feature, String note, String operationId) {
        if (amount <= 0) {
            return Mono.error(new FinanceException(400, "退款金额必须为正"));
        }
        if (operationId != null && operationId.startsWith("refund:") && amount == 1) {
            String consumeOperationId = operationId.substring("refund:".length());
            if (consumeOperationId.isBlank()) {
                return Mono.error(new FinanceException(400, "退款 operationId 无效"));
            }
            return compensateConsume(accountId, feature, consumeOperationId, note)
                    .map(result -> new MutationResult(
                            result.balance(), result.transactionId(),
                            "deduplicated".equals(result.action()) || "fenced".equals(result.action()),
                            result.source(), result.policyVersion(), result.quotaLimit()));
        }
        // deltaBalance=+amount, deltaEarned=0, deltaSpent=-amount —— 镜像 legacy refundCredit。
        return idempotent(accountId, operationId,
                () -> mutate(accountId, amount, 0, -amount, "refund", feature, note, operationId));
    }

    /** 赠送积分（reward，注册赠送 / admin 正向调整）。operationId 可空（一次性动作不参与幂等）。 */
    public Mono<MutationResult> award(String accountId, int amount, String note, String operationId) {
        if (amount <= 0) {
            return Mono.error(new FinanceException(400, "赠送金额必须为正"));
        }
        // deltaBalance=+amount, deltaEarned=+amount, deltaSpent=0 —— 镜像 legacy awardFreeCredits。
        return idempotent(accountId, operationId,
                () -> mutate(accountId, amount, amount, 0, "reward", null, note, operationId));
    }

    /**
     * 购买入账（AI 套餐 v1，type='purchase'）：deltaBalance/deltaEarned 同 award，
     * 但流水类型区分「花钱买」与「平台赠」，对账口径不同。operationId 必填（`purchase:<orderId>`）。
     */
    public Mono<MutationResult> purchaseCredit(String accountId, int amount, String note, String operationId) {
        if (amount <= 0) {
            return Mono.error(new FinanceException(400, "购买积分必须为正"));
        }
        if (operationId == null || operationId.isBlank()) {
            return Mono.error(new FinanceException(400, "购买入账缺少幂等键"));
        }
        return idempotent(accountId, operationId,
                () -> mutate(accountId, amount, amount, 0, "purchase", null, note, operationId));
    }

    /** 余额（账户不存在 → 0）。 */
    public Mono<CreditsAccount> balance(String accountId) {
        return repo.findAccount(accountId)
                .defaultIfEmpty(new CreditsAccount(accountId, 0, 0, 0));
    }

    /** 批量余额（admin 用户列表用，避免 N+1；未建户账号不在结果里）。空入参 → empty。 */
    public Flux<CreditsAccount> balances(java.util.Collection<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Flux.empty();
        }
        return repo.findAccounts(accountIds);
    }

    /** 流水（最近 limit 条，默认 50）。 */
    public Flux<CreditsTransaction> history(String accountId, int limit) {
        return repo.history(accountId, Math.max(1, limit));
    }

    public Flux<ConsumeOperation> consumeOperations(java.util.Collection<String> operationIds) {
        return repo.findConsumeOperations(operationIds);
    }

    // ---------- 内部 ----------

    private Mono<MutationResult> fencedConsume(
            String accountId, String feature, String operationId,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        Mono<ConsumeOperation> locked = aiQuotaMultiplierBps == null
                ? repo.lockOrCreateConsumeOperation(accountId, feature, operationId, "open")
                : repo.lockOrCreateConsumeOperation(
                        accountId, feature, operationId, "open", aiQuotaMultiplierBps, policyVersion);
        Mono<MutationResult> body = locked
                .flatMap(operation -> validateScope(
                                operation, accountId, feature, aiQuotaMultiplierBps, policyVersion)
                        .then(Mono.defer(() -> switch (operation.state()) {
                            case "consumed" -> Mono.just(resultFrom(operation, true));
                            case "compensated" -> Mono.error(new FinanceException(
                                    409, "该积分扣减已被补偿，拒绝迟到扣费"));
                            case "open" -> performFencedConsume(
                                    accountId, feature, operationId, aiQuotaMultiplierBps, policyVersion);
                            default -> Mono.error(new IllegalStateException(
                                    "未知积分扣减状态: " + operation.state()));
                        })));
        return repo.ensureAccount(accountId).then(transactions.transactional(body));
    }

    private Mono<MutationResult> performFencedConsume(
            String accountId, String feature, String operationId,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        if (aiQuotaMultiplierBps != null) {
            int quotaLimit = aiQuotaPolicy.limitFor(aiQuotaMultiplierBps);
            java.time.LocalDate quotaDay = aiQuotaPolicy.quotaDay();
            return repo.claimQuota(accountId, quotaDay, quotaLimit)
                    .flatMap(usage -> performQuotaConsume(
                            accountId, feature, operationId, aiQuotaMultiplierBps,
                            policyVersion, quotaLimit, usage))
                    .switchIfEmpty(Mono.defer(() -> performPaidConsume(
                            accountId, feature, operationId, policyVersion)));
        }
        return performPaidConsume(accountId, feature, operationId, null);
    }

    private Mono<MutationResult> performPaidConsume(
            String accountId, String feature, String operationId, Long policyVersion) {
        return repo.consumeOne(accountId)
                .switchIfEmpty(Mono.error(new FinanceException(402, "积分不足")))
                .flatMap(acct -> repo.insertTransaction(
                                accountId, -1, acct.balance(), "consume", feature, null, operationId)
                        .flatMap(transactionId -> repo.markConsumeOperationConsumed(
                                        operationId, transactionId, acct.balance())
                                .flatMap(updated -> updated
                                        ? Mono.just(MutationResult.paid(
                                                acct.balance(), transactionId, false, policyVersion))
                                        : Mono.error(new IllegalStateException("积分扣减 fence 状态更新失败")))));
    }

    private Mono<MutationResult> performQuotaConsume(
            String accountId, String feature, String operationId, int multiplierBps,
            long policyVersion, int quotaLimit, QuotaUsage usage) {
        return repo.findAccount(accountId)
                .flatMap(account -> repo.insertQuotaTransaction(
                                accountId, usage.quotaDay(), 1, usage.used(), quotaLimit,
                                "consume", feature, operationId, policyVersion, multiplierBps, null)
                        .flatMap(transactionId -> repo.markConsumeOperationQuotaConsumed(
                                        operationId, transactionId, account.balance(),
                                        usage.quotaDay(), quotaLimit)
                                .flatMap(updated -> updated
                                        ? Mono.just(new MutationResult(
                                                account.balance(), transactionId, false,
                                                "quota", policyVersion, quotaLimit))
                                        : Mono.error(new IllegalStateException(
                                                "免费额度扣减 fence 状态更新失败")))));
    }

    private Mono<CompensationResult> compensateLocked(ConsumeOperation operation, String note) {
        return switch (operation.state()) {
            case "compensated" -> repo.findAccount(operation.accountId())
                    .map(account -> new CompensationResult(
                            "compensated", operation.created() ? "fenced" : "deduplicated",
                            account.balance(), operation.chargeSource(), operation.policyVersion(),
                            operation.quotaLimit(), refundTransactionId(operation)));
            case "open" -> repo.markConsumeOperationFenced(operation.operationId())
                    .flatMap(updated -> updated
                            ? repo.findAccount(operation.accountId()).map(account -> new CompensationResult(
                                    "compensated", "fenced", account.balance(), null,
                                    operation.policyVersion(), operation.quotaLimit(), null))
                            : Mono.error(new IllegalStateException("积分补偿 fence 状态更新失败")));
            case "consumed" -> "quota".equals(operation.chargeSource())
                    ? refundQuotaOperation(operation, note)
                    : refundConsumedOperation(operation, note);
            default -> Mono.error(new IllegalStateException("未知积分扣减状态: " + operation.state()));
        };
    }

    private Mono<CompensationResult> refundConsumedOperation(ConsumeOperation operation, String note) {
        String refundOperationId = "refund:" + operation.operationId();
        return repo.findOperation(refundOperationId)
                .flatMap(existing -> reconcileExistingRefund(operation, existing))
                .switchIfEmpty(Mono.defer(() -> createCompensationRefund(
                        operation, note, refundOperationId)));
    }

    /** Rolling upgrades may have an old client refund before it starts calling the fenced endpoint. */
    private Mono<CompensationResult> reconcileExistingRefund(
            ConsumeOperation operation, ExistingOperation existing) {
        if (!operation.accountId().equals(existing.accountId())
                || !"refund".equals(existing.type())
                || !Objects.equals(operation.feature(), existing.feature())) {
            return Mono.error(new FinanceException(409, "既有积分退款作用域冲突"));
        }
        return repo.markConsumeOperationRefunded(operation.operationId(), existing.transactionId())
                .flatMap(updated -> updated
                        ? repo.findAccount(operation.accountId()).map(account -> new CompensationResult(
                                "compensated", "deduplicated", account.balance(), "paid",
                                operation.policyVersion(), operation.quotaLimit(), existing.transactionId()))
                        : Mono.error(new IllegalStateException("既有积分退款 fence 收敛失败")));
    }

    private Mono<CompensationResult> createCompensationRefund(
            ConsumeOperation operation, String note, String refundOperationId) {
        return repo.creditAccount(operation.accountId(), 1, 0, -1)
                .flatMap(account -> repo.insertTransaction(
                                operation.accountId(), 1, account.balance(), "refund",
                                operation.feature(), note, refundOperationId)
                        .flatMap(transactionId -> repo.markConsumeOperationRefunded(
                                        operation.operationId(), transactionId)
                                .flatMap(updated -> updated
                                        ? Mono.just(new CompensationResult(
                                                "compensated", "refunded", account.balance(), "paid",
                                                operation.policyVersion(), operation.quotaLimit(), transactionId))
                                        : Mono.error(new IllegalStateException("积分补偿状态更新失败")))));
    }

    private Mono<CompensationResult> refundQuotaOperation(ConsumeOperation operation, String note) {
        String refundOperationId = "refund:" + operation.operationId();
        return repo.releaseQuota(operation.accountId(), operation.quotaDay())
                .switchIfEmpty(Mono.error(new IllegalStateException("免费额度退款缺少原始用量")))
                .flatMap(usage -> repo.insertQuotaTransaction(
                                operation.accountId(), operation.quotaDay(), -1, usage.used(),
                                operation.quotaLimit(), "refund", operation.feature(), refundOperationId,
                                operation.policyVersion(), operation.aiQuotaMultiplierBps(), note)
                        .flatMap(transactionId -> repo.markConsumeOperationQuotaRefunded(
                                        operation.operationId(), transactionId)
                                .flatMap(updated -> updated
                                        ? repo.findAccount(operation.accountId()).map(account ->
                                                new CompensationResult(
                                                        "compensated", "refunded", account.balance(),
                                                        "quota", operation.policyVersion(),
                                                        operation.quotaLimit(), transactionId))
                                        : Mono.error(new IllegalStateException(
                                                "免费额度补偿状态更新失败")))));
    }

    private static Mono<Void> validateScope(
            ConsumeOperation operation, String accountId, String feature) {
        if (!operation.accountId().equals(accountId) || !operation.feature().equals(feature)) {
            return Mono.error(new FinanceException(409, "积分 operationId 作用域冲突"));
        }
        return Mono.empty();
    }

    private static Mono<Void> validateScope(
            ConsumeOperation operation, String accountId, String feature,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        return validateScope(operation, accountId, feature)
                .then(Mono.defer(() -> Objects.equals(
                                        operation.aiQuotaMultiplierBps(), aiQuotaMultiplierBps)
                                && Objects.equals(operation.policyVersion(), policyVersion)
                        ? Mono.empty()
                        : Mono.error(new FinanceException(409, "积分 operationId 权益快照冲突"))));
    }

    /** 改余额 + 插流水（type 由调用方定）。不含预检——预检由 {@link #idempotent} 组装。 */
    private Mono<MutationResult> mutate(String accountId, int amount, int deltaEarned, int deltaSpent,
                                        String type, String feature, String note, String operationId) {
        return repo.creditAccount(accountId, amount, deltaEarned, deltaSpent)
                .flatMap(acct -> repo.insertTransaction(accountId, amount, acct.balance(), type, feature, note, operationId)
                        .map(txnId -> MutationResult.paid(acct.balance(), txnId, false, null)));
    }

    /**
     * 幂等写入闭环：ensureAccount（事务外，镜像 legacy）→ 事务内「预检 or 写工作」→ 捕获 23505 唯一冲突在事务外重读。
     * operationId 非空时先 {@code findOperation} 预检（命中即 dedup，不调写工作）；为空时跳过预检（一次性动作）。
     */
    private Mono<MutationResult> idempotent(String accountId, String operationId, Supplier<Mono<MutationResult>> writeWork) {
        boolean dedup = operationId != null && !operationId.isBlank();
        Mono<MutationResult> body = dedup
                ? repo.findOperation(operationId)
                        .<MutationResult>map(CreditsService::dedup)
                        .switchIfEmpty(Mono.defer(writeWork))
                : Mono.defer(writeWork);

        return repo.ensureAccount(accountId)
                .then(transactions.transactional(body))
                .onErrorResume(e -> dedup && isUniqueViolation(e) ? reRead(operationId) : Mono.error(e));
    }

    /** 冲突后重读胜出行（事务已回滚，故事务外读）。读不到属意外 → 409（镜像 legacy）。 */
    private Mono<MutationResult> reRead(String operationId) {
        return repo.findOperation(operationId)
                .<MutationResult>map(CreditsService::dedup)
                .switchIfEmpty(Mono.error(new FinanceException(409, "积分操作冲突，请稍后重试")));
    }

    private static MutationResult dedup(ExistingOperation op) {
        return MutationResult.paid(op.balanceAfter(), op.transactionId(), true, null);
    }

    private static MutationResult resultFrom(ConsumeOperation operation, boolean deduplicated) {
        String transactionId = "quota".equals(operation.chargeSource())
                ? operation.quotaConsumeTransactionId()
                : operation.consumeTransactionId();
        return new MutationResult(operation.consumeBalanceAfter(), transactionId, deduplicated,
                operation.chargeSource(), operation.policyVersion(), operation.quotaLimit());
    }

    private static String refundTransactionId(ConsumeOperation operation) {
        return "quota".equals(operation.chargeSource())
                ? operation.quotaRefundTransactionId()
                : operation.refundTransactionId();
    }

    /** 是否 Postgres unique_violation（23505）：Spring R2DBC 包成 DataIntegrityViolationException，需解包到 R2dbcException。 */
    private static boolean isUniqueViolation(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof R2dbcException r && "23505".equals(r.getSqlState())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 一次积分写入结果。{@code deduplicated=true} 表示命中既有 operation_id，本次未改余额。 */
    public record MutationResult(
            int balance, String transactionId, boolean deduplicated,
            String source, Long policyVersion, Integer quotaLimit) {

        public MutationResult(int balance, String transactionId, boolean deduplicated) {
            this(balance, transactionId, deduplicated, "paid", null, null);
        }

        static MutationResult paid(int balance, String transactionId, boolean deduplicated, Long policyVersion) {
            return new MutationResult(balance, transactionId, deduplicated,
                    "paid", policyVersion, null);
        }
    }

    /** Conditional consume compensation result. */
    public record CompensationResult(
            String state, String action, int balance, String source,
            Long policyVersion, Integer quotaLimit, String transactionId) {

        public CompensationResult(String state, String action, int balance) {
            this(state, action, balance, null, null, null, null);
        }
    }
}
