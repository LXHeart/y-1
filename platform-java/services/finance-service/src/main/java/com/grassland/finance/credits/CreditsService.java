package com.grassland.finance.credits;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.ConsumeOperation;
import com.grassland.finance.credits.CreditsRepository.CreditsTransaction;
import com.grassland.finance.credits.CreditsRepository.ExistingOperation;
import com.grassland.finance.credits.CreditsRepository.QuotaUsage;
import com.grassland.financial.CreditsCentsPolicyProperties;
import com.grassland.financial.CreditsCentsPolicySnapshot;
import com.grassland.finance.security.FinanceException;
import io.r2dbc.spi.R2dbcException;
import java.util.Objects;
import java.util.function.Supplier;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 积分扣减/退款/赠送编排（GL-P3-AI-001 下属切片）。逐字移植 legacy {@code credit.service.ts} 的幂等闭环：
 *
 * <ol>
 * <li>事务内 {@code findOperation} 预检 → 命中既有 operation_id 即
 * {@code deduplicated}，不改余额；</li>
 * <li>余额改 + 流水插 {@code 同事务}（GL-P0-CRED-001）；扣减用条件 UPDATE，余额不足 → 402；</li>
 * <li>并发兜底：预检与插入之间另一请求写入同 operation_id 时，唯一索引抛 23505，事务回滚， 事务外重读胜出行作
 * {@code deduplicated}（镜像 legacy {@code readOperationAfterConflict}）。</li>
 * </ol>
 *
 * <p>
 * <b>operation_id 契约</b>：本服务<b>原样存储</b>调用方传入的 operation_id（不做 {@code refund:}
 * 派生）。 consume 行 opId = X；对应的失败退款行 opId =
 * {@code refund:X}，由<b>调用方</b>（intelligence {@code FinanceCreditsClient}、legacy
 * Express {@code createCharge}）派生后传入——保证一次扣减至多一次退款， 且 admin 的一次性赠送（无 opId）不受影响。
 *
 * <p>
 * 写工作用 {@link Mono#defer} 包裹：预检命中时不装配写 Mono（避免 eager assembly 在测试桩返回 null 时
 * NPE， 也保证写 SQL 仅在事务订阅期执行）。
 */
@Service
public class CreditsService {

	private final CreditsRepository repo;
	private final TransactionalOperator transactions;
	private final AiQuotaPolicy aiQuotaPolicy;
	private final CreditsCentsPolicyProperties creditsCentsPolicy;

	@Autowired
	public CreditsService(CreditsRepository repo, TransactionalOperator transactions, AiQuotaPolicy aiQuotaPolicy,
			CreditsCentsPolicyProperties creditsCentsPolicy) {
		this.repo = repo;
		this.transactions = transactions;
		this.aiQuotaPolicy = aiQuotaPolicy;
		this.creditsCentsPolicy = creditsCentsPolicy;
	}

	/**
	 * Unit-test/backward-compatible constructor: no free quota unless an
	 * entitlement is supplied.
	 */
	public CreditsService(CreditsRepository repo, TransactionalOperator transactions) {
		this(repo, transactions,
				new AiQuotaPolicy(0, java.time.ZoneId.of("Asia/Shanghai"), java.time.Clock.systemUTC()),
				new CreditsCentsPolicyProperties(null, null, null, null, null, null));
	}

	/**
	 * Reserve the policy-converted credits for a priced AI run before provider
	 * execution.
	 */
	public Mono<UsageReservationResult> reserveUsage(String accountId, String feature, String operationId,
			long estimatedCents, String expectedMoneyPolicyVersion, Integer aiQuotaMultiplierBps,
			Long entitlementPolicyVersion) {
		if (operationId == null || operationId.isBlank()) {
			return Mono.error(new FinanceException(400, "AI 用量预留必须提供 operationId"));
		}
		Mono<Void> entitlementValidation = validateEntitlement(operationId, aiQuotaMultiplierBps,
				entitlementPolicyVersion);
		return entitlementValidation.then(Mono.defer(() -> {
			CreditsCentsPolicySnapshot snapshot = CreditsPolicyGuards.requireActive(creditsCentsPolicy,
					expectedMoneyPolicyVersion);
			int reservedCredits = CreditsPolicyGuards.creditsFor(snapshot, estimatedCents);
			var usage = new CreditsRepository.UsageReservation(snapshot.version(), snapshot.rounding().name(),
					snapshot.centsNumerator(), snapshot.creditsDenominator(), snapshot.maxCentsPerOperation(),
					estimatedCents, reservedCredits);
			Mono<ConsumeOperation> locked = repo.lockOrCreateConsumeOperation(accountId, feature, operationId, "open",
					aiQuotaMultiplierBps, entitlementPolicyVersion, usage);
			Mono<UsageReservationResult> body = locked
					.flatMap(operation -> validateUsageScope(operation, accountId, feature, aiQuotaMultiplierBps,
							entitlementPolicyVersion, usage).then(Mono.defer(() -> switch (operation.state()) {
								case "consumed", "settled" -> repo.findAccount(accountId)
										.map(account -> usageReservationResult(operation, account.balance(), true));
								case "compensated" -> Mono.error(new FinanceException(409, "该 AI 用量预留已被补偿，拒绝迟到扣费"));
								case "open" -> performUsageReservation(accountId, feature, operationId,
										aiQuotaMultiplierBps, entitlementPolicyVersion, usage);
								default -> Mono.error(new IllegalStateException("未知积分扣减状态: " + operation.state()));
							})));
			return repo.ensureAccount(accountId).then(transactions.transactional(body));
		}));
	}

	/**
	 * Idempotently reconcile a priced reservation to the provider's actual cost.
	 */
	public Mono<UsageSettlementResult> settleUsage(String accountId, String feature, String operationId,
			long actualCents, String expectedMoneyPolicyVersion) {
		Mono<UsageSettlementResult> body = repo.lockConsumeOperation(operationId)
				.switchIfEmpty(Mono.error(new FinanceException(404, "AI 用量预留不存在")))
				.flatMap(operation -> validateScope(operation, accountId, feature)
						.then(Mono.defer(() -> settleLockedUsage(operation, actualCents, expectedMoneyPolicyVersion))));
		return transactions.transactional(body);
	}

	/** 扣 1 积分（consume）。operationId 非空即幂等；余额不足 → 402。 */
	public Mono<MutationResult> consume(String accountId, String feature, String operationId) {
		return consume(accountId, feature, operationId, null, null);
	}

	/**
	 * Consumes one AI charge using a marketplace policy snapshot, preferring the
	 * daily free quota.
	 */
	public Mono<MutationResult> consume(String accountId, String feature, String operationId,
			Integer aiQuotaMultiplierBps, Long policyVersion) {
		Mono<Void> entitlementValidation = validateEntitlement(operationId, aiQuotaMultiplierBps, policyVersion);
		if (aiQuotaMultiplierBps != null && (operationId == null || operationId.isBlank())) {
			return Mono.error(new FinanceException(400, "AI 权益扣减必须提供 operationId"));
		}
		if (operationId != null && !operationId.isBlank()) {
			return entitlementValidation
					.then(fencedConsume(accountId, feature, operationId, aiQuotaMultiplierBps, policyVersion));
		}
		return entitlementValidation.then(idempotent(accountId, operationId,
				() -> repo.consumeOne(accountId).switchIfEmpty(Mono.error(new FinanceException(402, "积分不足")))
						.flatMap(acct -> repo
								.insertTransaction(accountId, -1, acct.balance(), "consume", feature, null, operationId)
								.map(txnId -> MutationResult.paid(acct.balance(), txnId, false, policyVersion)))));
	}

	/**
	 * Atomically fence or reverse a consume whose HTTP result may be unknown. The
	 * caller supplies only the original consume operation; refund identity is
	 * derived here.
	 */
	public Mono<CompensationResult> compensateConsume(String accountId, String feature, String operationId,
			String note) {
		Mono<CompensationResult> body = repo
				.lockOrCreateConsumeOperation(accountId, feature, operationId, "compensated")
				.flatMap(operation -> validateScope(operation, accountId, feature)
						.then(compensateLocked(operation, note)));
		return repo.ensureAccount(accountId).then(transactions.transactional(body));
	}

	/**
	 * 退还积分（refund）。amount 为正幅度；operationId 非空即幂等（调用方传 {@code refund:<consumeId>}）。
	 */
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
					.map(result -> new MutationResult(result.balance(), result.transactionId(),
							"deduplicated".equals(result.action()) || "fenced".equals(result.action()), result.source(),
							result.policyVersion(), result.quotaLimit()));
		}
		// deltaBalance=+amount, deltaEarned=0, deltaSpent=-amount —— 镜像 legacy
		// refundCredit。
		return idempotent(accountId, operationId,
				() -> mutate(accountId, amount, 0, -amount, "refund", feature, note, operationId));
	}

	/** 赠送积分（reward，注册赠送 / admin 正向调整）。operationId 可空（一次性动作不参与幂等）。 */
	public Mono<MutationResult> award(String accountId, int amount, String note, String operationId) {
		return award(accountId, amount, note, operationId, "reward");
	}

	/**
	 * 任务书 #31 / ADR-D15：审判官投票奖励（type=judge_reward，与注册赠送/运营调账区分对账口径）。 账务语义同
	 * award（deltaBalance/deltaEarned 同增）；operationId 必填
	 * （{@code judge-reward:{disputeId}:{round}:{judgeAccountId}}，唯一索引吸收 Kafka
	 * at-least-once 重放）。
	 */
	public Mono<MutationResult> awardJudgeReward(String accountId, int amount, String note, String operationId) {
		if (operationId == null || operationId.isBlank()) {
			return Mono.error(new FinanceException(400, "审判奖励 operationId 必填"));
		}
		return award(accountId, amount, note, operationId, "judge_reward");
	}

	private Mono<MutationResult> award(String accountId, int amount, String note, String operationId, String type) {
		if (amount <= 0) {
			return Mono.error(new FinanceException(400, "赠送金额必须为正"));
		}
		// deltaBalance=+amount, deltaEarned=+amount, deltaSpent=0 —— 镜像 legacy
		// awardFreeCredits。
		return idempotent(accountId, operationId,
				() -> mutate(accountId, amount, amount, 0, type, null, note, operationId));
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
		return repo.findAccount(accountId).defaultIfEmpty(new CreditsAccount(accountId, 0, 0, 0));
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

	private Mono<MutationResult> fencedConsume(String accountId, String feature, String operationId,
			Integer aiQuotaMultiplierBps, Long policyVersion) {
		Mono<ConsumeOperation> locked = aiQuotaMultiplierBps == null
				? repo.lockOrCreateConsumeOperation(accountId, feature, operationId, "open")
				: repo.lockOrCreateConsumeOperation(accountId, feature, operationId, "open", aiQuotaMultiplierBps,
						policyVersion);
		Mono<MutationResult> body = locked
				.flatMap(operation -> validateScope(operation, accountId, feature, aiQuotaMultiplierBps, policyVersion)
						.then(Mono.defer(() -> switch (operation.state()) {
							case "consumed" -> Mono.just(resultFrom(operation, true));
							case "compensated" -> Mono.error(new FinanceException(409, "该积分扣减已被补偿，拒绝迟到扣费"));
							case "open" -> performFencedConsume(accountId, feature, operationId, aiQuotaMultiplierBps,
									policyVersion);
							default -> Mono.error(new IllegalStateException("未知积分扣减状态: " + operation.state()));
						})));
		return repo.ensureAccount(accountId).then(transactions.transactional(body));
	}

	private Mono<MutationResult> performFencedConsume(String accountId, String feature, String operationId,
			Integer aiQuotaMultiplierBps, Long policyVersion) {
		if (aiQuotaMultiplierBps != null) {
			int quotaLimit = aiQuotaPolicy.limitFor(aiQuotaMultiplierBps);
			java.time.LocalDate quotaDay = aiQuotaPolicy.quotaDay();
			return repo.claimQuota(accountId, quotaDay, quotaLimit)
					.flatMap(usage -> performQuotaConsume(accountId, feature, operationId, aiQuotaMultiplierBps,
							policyVersion, quotaLimit, usage))
					.switchIfEmpty(
							Mono.defer(() -> performPaidConsume(accountId, feature, operationId, policyVersion)));
		}
		return performPaidConsume(accountId, feature, operationId, null);
	}

	private Mono<MutationResult> performPaidConsume(String accountId, String feature, String operationId,
			Long policyVersion) {
		return repo.consumeOne(accountId).switchIfEmpty(Mono.error(new FinanceException(402, "积分不足")))
				.flatMap(acct -> repo
						.insertTransaction(accountId, -1, acct.balance(), "consume", feature, null, operationId)
						.flatMap(transactionId -> repo
								.markConsumeOperationConsumed(operationId, transactionId, acct.balance())
								.flatMap(updated -> updated
										? Mono.just(MutationResult.paid(acct.balance(), transactionId, false,
												policyVersion))
										: Mono.error(new IllegalStateException("积分扣减 fence 状态更新失败")))));
	}

	private Mono<UsageReservationResult> performUsageReservation(String accountId, String feature, String operationId,
			Integer aiQuotaMultiplierBps, Long entitlementPolicyVersion, CreditsRepository.UsageReservation usage) {
		if (aiQuotaMultiplierBps != null) {
			int quotaLimit = aiQuotaPolicy.limitFor(aiQuotaMultiplierBps);
			java.time.LocalDate quotaDay = aiQuotaPolicy.quotaDay();
			return repo.claimQuota(accountId, quotaDay, quotaLimit)
					.flatMap(quota -> performQuotaConsume(accountId, feature, operationId, aiQuotaMultiplierBps,
							entitlementPolicyVersion, quotaLimit, quota)
							.map(result -> new UsageReservationResult(result.balance(), result.transactionId(), false,
									result.source(), result.policyVersion(), result.quotaLimit(), usage.policyVersion(),
									usage.reservedCents(), usage.reservedCredits())))
					.switchIfEmpty(Mono.defer(() -> performPaidUsageReservation(accountId, feature, operationId,
							entitlementPolicyVersion, usage)));
		}
		return performPaidUsageReservation(accountId, feature, operationId, null, usage);
	}

	private Mono<UsageReservationResult> performPaidUsageReservation(String accountId, String feature,
			String operationId, Long entitlementPolicyVersion, CreditsRepository.UsageReservation usage) {
		return repo.consumeCredits(accountId, usage.reservedCredits())
				.switchIfEmpty(Mono.error(new FinanceException(402, "积分不足")))
				.flatMap(account -> repo
						.insertTransaction(accountId, -usage.reservedCredits(), account.balance(), "consume", feature,
								"AI 用量积分预留", operationId)
						.flatMap(transactionId -> repo
								.markConsumeOperationConsumed(operationId, transactionId, account.balance())
								.flatMap(updated -> updated
										? Mono.just(new UsageReservationResult(account.balance(), transactionId, false,
												"paid", entitlementPolicyVersion, null, usage.policyVersion(),
												usage.reservedCents(), usage.reservedCredits()))
										: Mono.error(new IllegalStateException("AI 用量预留 fence 状态更新失败")))));
	}

	private Mono<UsageSettlementResult> settleLockedUsage(ConsumeOperation operation, long actualCents,
			String expectedMoneyPolicyVersion) {
		if (!operation.usagePriced()) {
			return Mono.error(new FinanceException(409, "该积分操作不是按用量计价预留"));
		}
		if (!Objects.equals(operation.creditsCentsPolicyVersion(), expectedMoneyPolicyVersion)) {
			return Mono.error(new FinanceException(409, "AI 用量结算 policy 版本冲突"));
		}
		CreditsCentsPolicySnapshot snapshot;
		try {
			snapshot = new CreditsCentsPolicySnapshot(operation.creditsCentsPolicyVersion(),
					RoundingMode.valueOf(operation.creditsCentsRounding()), operation.centsNumerator(),
					operation.creditsDenominator(), operation.maxCentsPerOperation());
		} catch (RuntimeException invalidSnapshot) {
			return Mono.error(new FinanceException(500, "冻结的 credits↔cents policy 非法"));
		}
		int actualCredits = CreditsPolicyGuards.creditsFor(snapshot, actualCents);
		if ("settled".equals(operation.state())) {
			if (!Objects.equals(operation.actualCents(), actualCents)
					|| !Objects.equals(operation.actualCredits(), actualCredits)) {
				return Mono.error(new FinanceException(409, "AI 用量结算重放参数冲突"));
			}
			return repo.findAccount(operation.accountId())
					.map(account -> usageSettlementResult(operation, account.balance(), true));
		}
		if ("compensated".equals(operation.state())) {
			return Mono.error(new FinanceException(409, "已补偿的 AI 用量预留不能结算"));
		}
		if (!"consumed".equals(operation.state())) {
			return Mono.error(new FinanceException(409, "AI 用量预留尚未完成"));
		}
		int adjustmentCredits = "quota".equals(operation.chargeSource())
				? 0
				: Math.subtractExact(actualCredits, operation.reservedCredits());
		if ("quota".equals(operation.chargeSource())) {
			return markUsageSettled(operation, actualCents, actualCredits, adjustmentCredits, null);
		}
		return repo.adjustUsageCredits(operation.accountId(), adjustmentCredits)
				.switchIfEmpty(Mono.error(new FinanceException(402, "实际 AI 用量超出预留且积分不足")))
				.flatMap(account -> adjustmentCredits == 0
						? markUsageSettled(operation, actualCents, actualCredits, 0, null)
						: repo.insertTransaction(operation.accountId(), -adjustmentCredits, account.balance(),
								"usage_adjustment", operation.feature(), "AI 实际用量差额结算",
								"settle:" + operation.operationId())
								.flatMap(transactionId -> markUsageSettled(operation, actualCents, actualCredits,
										adjustmentCredits, transactionId)));
	}

	private Mono<UsageSettlementResult> markUsageSettled(ConsumeOperation operation, long actualCents,
			int actualCredits, int adjustmentCredits, String transactionId) {
		return repo
				.markUsageSettled(operation.operationId(), actualCents, actualCredits, adjustmentCredits, transactionId)
				.flatMap(updated -> updated
						? repo.findAccount(operation.accountId())
								.map(account -> new UsageSettlementResult(account.balance(), transactionId, false,
										operation.chargeSource(), operation.creditsCentsPolicyVersion(),
										operation.reservedCents(), operation.reservedCredits(), actualCents,
										actualCredits, adjustmentCredits))
						: Mono.error(new IllegalStateException("AI 用量结算状态更新失败")));
	}

	private Mono<MutationResult> performQuotaConsume(String accountId, String feature, String operationId,
			int multiplierBps, long policyVersion, int quotaLimit, QuotaUsage usage) {
		return repo.findAccount(accountId)
				.flatMap(account -> repo
						.insertQuotaTransaction(accountId, usage.quotaDay(), 1, usage.used(), quotaLimit, "consume",
								feature, operationId, policyVersion, multiplierBps, null)
						.flatMap(transactionId -> repo
								.markConsumeOperationQuotaConsumed(operationId, transactionId, account.balance(),
										usage.quotaDay(), quotaLimit)
								.flatMap(updated -> updated
										? Mono.just(new MutationResult(account.balance(), transactionId, false, "quota",
												policyVersion, quotaLimit))
										: Mono.error(new IllegalStateException("免费额度扣减 fence 状态更新失败")))));
	}

	private Mono<CompensationResult> compensateLocked(ConsumeOperation operation, String note) {
		return switch (operation.state()) {
			case "compensated" ->
				repo.findAccount(operation.accountId()).map(account -> new CompensationResult("compensated",
						operation.created() ? "fenced" : "deduplicated", account.balance(), operation.chargeSource(),
						operation.policyVersion(), operation.quotaLimit(), refundTransactionId(operation)));
			case "open" -> repo.markConsumeOperationFenced(operation.operationId())
					.flatMap(updated -> updated
							? repo.findAccount(operation.accountId())
									.map(account -> new CompensationResult("compensated", "fenced", account.balance(),
											null, operation.policyVersion(), operation.quotaLimit(), null))
							: Mono.error(new IllegalStateException("积分补偿 fence 状态更新失败")));
			case "consumed" -> "quota".equals(operation.chargeSource())
					? refundQuotaOperation(operation, note)
					: refundConsumedOperation(operation, note);
			case "settled" -> Mono.error(new FinanceException(409, "已成功结算的 AI 用量不能退款"));
			default -> Mono.error(new IllegalStateException("未知积分扣减状态: " + operation.state()));
		};
	}

	private Mono<CompensationResult> refundConsumedOperation(ConsumeOperation operation, String note) {
		String refundOperationId = "refund:" + operation.operationId();
		return repo.findOperation(refundOperationId).flatMap(existing -> reconcileExistingRefund(operation, existing))
				.switchIfEmpty(Mono.defer(() -> createCompensationRefund(operation, note, refundOperationId)));
	}

	/**
	 * Rolling upgrades may have an old client refund before it starts calling the
	 * fenced endpoint.
	 */
	private Mono<CompensationResult> reconcileExistingRefund(ConsumeOperation operation, ExistingOperation existing) {
		if (!operation.accountId().equals(existing.accountId()) || !"refund".equals(existing.type())
				|| !Objects.equals(operation.feature(), existing.feature())) {
			return Mono.error(new FinanceException(409, "既有积分退款作用域冲突"));
		}
		return repo.markConsumeOperationRefunded(operation.operationId(), existing.transactionId())
				.flatMap(updated -> updated
						? repo.findAccount(operation.accountId())
								.map(account -> new CompensationResult("compensated", "deduplicated", account.balance(),
										"paid", operation.policyVersion(), operation.quotaLimit(),
										existing.transactionId()))
						: Mono.error(new IllegalStateException("既有积分退款 fence 收敛失败")));
	}

	private Mono<CompensationResult> createCompensationRefund(ConsumeOperation operation, String note,
			String refundOperationId) {
		int refundCredits = operation.usagePriced() ? operation.reservedCredits() : 1;
		return repo.creditAccount(operation.accountId(), refundCredits, 0, -refundCredits).flatMap(account -> repo
				.insertTransaction(operation.accountId(), refundCredits, account.balance(), "refund",
						operation.feature(), note, refundOperationId)
				.flatMap(transactionId -> repo.markConsumeOperationRefunded(operation.operationId(), transactionId)
						.flatMap(updated -> updated
								? Mono.just(new CompensationResult("compensated", "refunded", account.balance(), "paid",
										operation.policyVersion(), operation.quotaLimit(), transactionId))
								: Mono.error(new IllegalStateException("积分补偿状态更新失败")))));
	}

	private Mono<CompensationResult> refundQuotaOperation(ConsumeOperation operation, String note) {
		String refundOperationId = "refund:" + operation.operationId();
		return repo.releaseQuota(operation.accountId(), operation.quotaDay())
				.switchIfEmpty(Mono.error(new IllegalStateException("免费额度退款缺少原始用量")))
				.flatMap(usage -> repo
						.insertQuotaTransaction(operation.accountId(), operation.quotaDay(), -1, usage.used(),
								operation.quotaLimit(), "refund", operation.feature(), refundOperationId,
								operation.policyVersion(), operation.aiQuotaMultiplierBps(), note)
						.flatMap(transactionId -> repo
								.markConsumeOperationQuotaRefunded(operation.operationId(), transactionId)
								.flatMap(updated -> updated
										? repo.findAccount(operation.accountId())
												.map(account -> new CompensationResult("compensated", "refunded",
														account.balance(), "quota", operation.policyVersion(),
														operation.quotaLimit(), transactionId))
										: Mono.error(new IllegalStateException("免费额度补偿状态更新失败")))));
	}

	private static Mono<Void> validateScope(ConsumeOperation operation, String accountId, String feature) {
		if (!operation.accountId().equals(accountId) || !operation.feature().equals(feature)) {
			return Mono.error(new FinanceException(409, "积分 operationId 作用域冲突"));
		}
		return Mono.empty();
	}

	private Mono<Void> validateEntitlement(String operationId, Integer aiQuotaMultiplierBps, Long policyVersion) {
		boolean hasMultiplier = aiQuotaMultiplierBps != null;
		boolean hasVersion = policyVersion != null;
		if (hasMultiplier != hasVersion) {
			return Mono.error(new FinanceException(400, "AI 权益快照字段不完整"));
		}
		if (!hasMultiplier) {
			return Mono.empty();
		}
		if (operationId == null || operationId.isBlank()) {
			return Mono.error(new FinanceException(400, "AI 权益扣减必须提供 operationId"));
		}
		if (policyVersion < 1) {
			return Mono.error(new FinanceException(400, "policyVersion 必须大于等于 1"));
		}
		try {
			aiQuotaPolicy.limitFor(aiQuotaMultiplierBps);
			return Mono.empty();
		} catch (IllegalArgumentException invalid) {
			return Mono.error(new FinanceException(400, invalid.getMessage()));
		}
	}

	private static Mono<Void> validateUsageScope(ConsumeOperation operation, String accountId, String feature,
			Integer aiQuotaMultiplierBps, Long entitlementPolicyVersion, CreditsRepository.UsageReservation usage) {
		return validateScope(operation, accountId, feature, aiQuotaMultiplierBps, entitlementPolicyVersion)
				.then(Mono.defer(() -> operation.usagePriced()
						&& Objects.equals(operation.creditsCentsPolicyVersion(), usage.policyVersion())
						&& Objects.equals(operation.creditsCentsRounding(), usage.rounding())
						&& Objects.equals(operation.centsNumerator(), usage.centsNumerator())
						&& Objects.equals(operation.creditsDenominator(), usage.creditsDenominator())
						&& Objects.equals(operation.maxCentsPerOperation(), usage.maxCentsPerOperation())
						&& Objects.equals(operation.reservedCents(), usage.reservedCents())
						&& Objects.equals(operation.reservedCredits(), usage.reservedCredits())
								? Mono.empty()
								: Mono.error(new FinanceException(409, "AI 用量预留幂等参数冲突"))));
	}

	private static UsageReservationResult usageReservationResult(ConsumeOperation operation, int balance,
			boolean deduplicated) {
		String transactionId = "quota".equals(operation.chargeSource())
				? operation.quotaConsumeTransactionId()
				: operation.consumeTransactionId();
		return new UsageReservationResult(balance, transactionId, deduplicated, operation.chargeSource(),
				operation.policyVersion(), operation.quotaLimit(), operation.creditsCentsPolicyVersion(),
				operation.reservedCents(), operation.reservedCredits());
	}

	private static UsageSettlementResult usageSettlementResult(ConsumeOperation operation, int balance,
			boolean deduplicated) {
		return new UsageSettlementResult(balance, operation.settlementTransactionId(), deduplicated,
				operation.chargeSource(), operation.creditsCentsPolicyVersion(), operation.reservedCents(),
				operation.reservedCredits(), operation.actualCents(), operation.actualCredits(),
				operation.adjustmentCredits());
	}

	private static Mono<Void> validateScope(ConsumeOperation operation, String accountId, String feature,
			Integer aiQuotaMultiplierBps, Long policyVersion) {
		return validateScope(operation, accountId, feature)
				.then(Mono.defer(() -> Objects.equals(operation.aiQuotaMultiplierBps(), aiQuotaMultiplierBps)
						&& Objects.equals(operation.policyVersion(), policyVersion)
								? Mono.empty()
								: Mono.error(new FinanceException(409, "积分 operationId 权益快照冲突"))));
	}

	/** 改余额 + 插流水（type 由调用方定）。不含预检——预检由 {@link #idempotent} 组装。 */
	private Mono<MutationResult> mutate(String accountId, int amount, int deltaEarned, int deltaSpent, String type,
			String feature, String note, String operationId) {
		return repo.creditAccount(accountId, amount, deltaEarned, deltaSpent)
				.flatMap(acct -> repo
						.insertTransaction(accountId, amount, acct.balance(), type, feature, note, operationId)
						.map(txnId -> MutationResult.paid(acct.balance(), txnId, false, null)));
	}

	/**
	 * 幂等写入闭环：ensureAccount（事务外，镜像 legacy）→ 事务内「预检 or 写工作」→ 捕获 23505 唯一冲突在事务外重读。
	 * operationId 非空时先 {@code findOperation} 预检（命中即 dedup，不调写工作）；为空时跳过预检（一次性动作）。
	 */
	private Mono<MutationResult> idempotent(String accountId, String operationId,
			Supplier<Mono<MutationResult>> writeWork) {
		boolean dedup = operationId != null && !operationId.isBlank();
		Mono<MutationResult> body = dedup
				? repo.findOperation(operationId).<MutationResult>map(CreditsService::dedup)
						.switchIfEmpty(Mono.defer(writeWork))
				: Mono.defer(writeWork);

		return repo.ensureAccount(accountId).then(transactions.transactional(body))
				.onErrorResume(e -> dedup && isUniqueViolation(e) ? reRead(operationId) : Mono.error(e));
	}

	/** 冲突后重读胜出行（事务已回滚，故事务外读）。读不到属意外 → 409（镜像 legacy）。 */
	private Mono<MutationResult> reRead(String operationId) {
		return repo.findOperation(operationId).<MutationResult>map(CreditsService::dedup)
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

	/**
	 * 是否 Postgres unique_violation（23505）：Spring R2DBC 包成
	 * DataIntegrityViolationException，需解包到 R2dbcException。
	 */
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
	public record MutationResult(int balance, String transactionId, boolean deduplicated, String source,
			Long policyVersion, Integer quotaLimit) {

		public MutationResult(int balance, String transactionId, boolean deduplicated) {
			this(balance, transactionId, deduplicated, "paid", null, null);
		}

		static MutationResult paid(int balance, String transactionId, boolean deduplicated, Long policyVersion) {
			return new MutationResult(balance, transactionId, deduplicated, "paid", policyVersion, null);
		}
	}

	/** Conditional consume compensation result. */
	public record CompensationResult(String state, String action, int balance, String source, Long policyVersion,
			Integer quotaLimit, String transactionId) {

		public CompensationResult(String state, String action, int balance) {
			this(state, action, balance, null, null, null, null);
		}
	}

	public record UsageReservationResult(int balance, String transactionId, boolean deduplicated, String source,
			Long entitlementPolicyVersion, Integer quotaLimit, String creditsCentsPolicyVersion, long reservedCents,
			int reservedCredits) {
	}

	public record UsageSettlementResult(int balance, String transactionId, boolean deduplicated, String source,
			String creditsCentsPolicyVersion, long reservedCents, int reservedCredits, long actualCents,
			int actualCredits, int adjustmentCredits) {
	}
}
