package com.grassland.finance.freebie;

import com.grassland.finance.account.AccountRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 霸王餐押金托管生命周期（ADR-D12，方向镜像 {@code EscrowLifecycleService}）： 出资方是<b>推荐官</b>（不是商家
 * org）——reserve 扣推荐官钱包；达标 refund 全额退推荐官（fee=0， §8.2「全额返还」）；未达标 compensate 入商家
 * org 账户。双录账本 + 钱包流水 + outbox 同事务， journal 独立类型（FREEBIE_*），不复用商家出资语义。
 */
@Component
public class FreebieEscrowLifecycleService {

	private final FreebieEscrowRepository escrows;
	private final WalletRepository wallets;
	private final AccountRepository accounts;
	private final OutboxRepository outbox;
	private final LedgerService ledger;
	private final TransactionalOperator transactions;

	public FreebieEscrowLifecycleService(FreebieEscrowRepository escrows, WalletRepository wallets,
			AccountRepository accounts, OutboxRepository outbox, LedgerService ledger,
			TransactionalOperator transactions) {
		this.escrows = escrows;
		this.wallets = wallets;
		this.accounts = accounts;
		this.outbox = outbox;
		this.ledger = ledger;
		this.transactions = transactions;
	}

	public record Reserved(FreebieEscrow escrow, boolean created) {
	}

	/** 按 engagementRef 查托管行（controller 授权自查用）。 */
	public Mono<FreebieEscrow> find(String engagementRef) {
		return escrows.findByEngagementRef(engagementRef);
	}

	/**
	 * 预付押金：钱包原子扣减（余额不足 409）+ 托管行 + journal + 钱包流水 + outbox，同一事务；engagementRef 幂等。
	 */
	public Mono<Reserved> reserve(String engagementRef, String recommenderAccountId, String taskOwnerAccountId,
			String organizationId, long amountCents) {
		if (amountCents <= 0) {
			return Mono.error(new FinanceException(400, "押金金额必须大于 0"));
		}
		return transactions.transactional(escrows.findByEngagementRef(engagementRef)
				.flatMap(existing -> existingScopeMatches(existing, engagementRef, recommenderAccountId, organizationId,
						amountCents))
				.switchIfEmpty(Mono.defer(() -> reserveNew(engagementRef, recommenderAccountId, taskOwnerAccountId,
						organizationId, amountCents)))
				.flatMap(res -> res.created()
						? wallets
								.appendEntry(recommenderAccountId, WalletEntryType.FREEBIE_RESERVE, -amountCents, 0,
										engagementRef, "霸王餐押金预付托管")
								.then(ledger.postFreebieReserve(recommenderAccountId, engagementRef, amountCents))
								.then(outbox.append(envelope("FreebieReserved", res.escrow()))).thenReturn(res)
						: Mono.just(res)));
	}

	/**
	 * 顺序镜像 reserveWork：先扣钱、再建行；并发唯一键落败时在本事务内 credit 撤销刚做的扣减， 流水/journal/outbox 只在
	 * created 分支落——空结果提交不会留下「扣了钱没有托管行」的脏状态。
	 */
	private Mono<Reserved> reserveNew(String engagementRef, String recommenderAccountId, String taskOwnerAccountId,
			String organizationId, long amountCents) {
		return wallets.debit(recommenderAccountId, amountCents)
				.switchIfEmpty(Mono.error(new FinanceException(409, "钱包余额不足")))
				.then(escrows
						.create(engagementRef, recommenderAccountId, taskOwnerAccountId, organizationId, amountCents))
				.<Reserved>map(escrow -> new Reserved(escrow, true))
				.switchIfEmpty(wallets.credit(recommenderAccountId, amountCents)
						.then(escrows.findByEngagementRef(engagementRef))
						.flatMap(existing -> existingScopeMatches(existing, engagementRef, recommenderAccountId,
								organizationId, amountCents))
						.switchIfEmpty(Mono.error(new FinanceException(409, "幂等押金冲突"))));
	}

	private static Mono<Reserved> existingScopeMatches(FreebieEscrow existing, String engagementRef,
			String recommenderAccountId, String organizationId, long amountCents) {
		boolean sameScope = engagementRef.equals(existing.engagementRef())
				&& recommenderAccountId.equals(existing.recommenderAccountId())
				&& organizationId.equals(existing.organizationId()) && amountCents == existing.amountCents();
		return sameScope
				? Mono.just(new Reserved(existing, false))
				: Mono.error(new FinanceException(422, "engagementRef 押金范围冲突"));
	}

	/** 达标退还：reserved → refunded + 钱包全额入账（fee=0）+ journal + outbox。 */
	public Mono<FreebieEscrow> refund(String engagementRef) {
		return transactions.transactional(escrows.findByEngagementRef(engagementRef)
				.switchIfEmpty(Mono.error(new FinanceException(404, "押金托管不存在")))
				.flatMap(escrow -> escrows.markRefunded(escrow.id())
						.switchIfEmpty(Mono.error(new FinanceException(409, "该押金已处理")))
						.flatMap(refunded -> wallets.credit(escrow.recommenderAccountId(), escrow.amountCents())
								.then(wallets.appendEntry(escrow.recommenderAccountId(), WalletEntryType.FREEBIE_REFUND,
										escrow.amountCents(), 0, engagementRef, "霸王餐押金达标返还"))
								.then(ledger.postFreebieRefund(escrow.recommenderAccountId(), engagementRef,
										escrow.amountCents()))
								.then(outbox.append(envelope("FreebieRefunded", refunded))).thenReturn(refunded))));
	}

	/** 未达标补偿：reserved → compensated + 商家 org 账户余额投影更新 + journal + outbox（通知双方）。 */
	public Mono<FreebieEscrow> compensate(String engagementRef) {
		return transactions.transactional(escrows.findByEngagementRef(engagementRef)
				.switchIfEmpty(Mono.error(new FinanceException(404, "押金托管不存在")))
				.flatMap(escrow -> escrows.markCompensated(escrow.id())
						.switchIfEmpty(Mono.error(new FinanceException(409, "该押金已处理")))
						.flatMap(compensated -> accounts.credit(escrow.organizationId(), escrow.amountCents())
								.then(ledger.postFreebieCompensate(escrow.recommenderAccountId(),
										escrow.organizationId(), engagementRef, escrow.amountCents()))
								.then(outbox.append(envelope("FreebieCompensated", compensated)))
								.thenReturn(compensated))));
	}

	/**
	 * 争议终局对账（D6 矩阵）：for_merchant → 补偿商家；for_recommender → 退还推荐官。 结局语义镜像
	 * {@code EscrowLifecycleService.reconcile}（verified/repaired/conflict/missing）。
	 */
	public Mono<Outcome> reconcile(String organizationId, String engagementRef, String finalDecision) {
		return escrows.findByEngagementRef(engagementRef).flatMap(escrow -> {
			if (!organizationId.equals(escrow.organizationId())) {
				return Mono.error(new FinanceException(403, "无权操作该组织押金"));
			}
			return switch (finalDecision) {
				case "for_merchant" -> reconcileForMerchant(escrow);
				case "for_recommender" -> reconcileForRecommender(escrow);
				default -> Mono.error(new FinanceException(400, "未知终局判决"));
			};
		}).defaultIfEmpty(new Outcome("missing", "freebie_missing", null));
	}

	private Mono<Outcome> reconcileForMerchant(FreebieEscrow escrow) {
		return switch (escrow.status()) {
			case FreebieEscrow.STATUS_RESERVED ->
				compensate(escrow.engagementRef()).map(updated -> new Outcome("repaired", "compensated", updated));
			case FreebieEscrow.STATUS_COMPENSATED -> Mono.just(new Outcome("verified", "already_compensated", escrow));
			case FreebieEscrow.STATUS_REFUNDED ->
				Mono.just(new Outcome("conflict", "refunded_but_merchant_won", escrow));
			default -> Mono.just(new Outcome("conflict", "unexpected_escrow_state", escrow));
		};
	}

	private Mono<Outcome> reconcileForRecommender(FreebieEscrow escrow) {
		return switch (escrow.status()) {
			case FreebieEscrow.STATUS_RESERVED ->
				refund(escrow.engagementRef()).map(updated -> new Outcome("repaired", "refunded", updated));
			case FreebieEscrow.STATUS_REFUNDED -> Mono.just(new Outcome("verified", "already_refunded", escrow));
			case FreebieEscrow.STATUS_COMPENSATED ->
				Mono.just(new Outcome("conflict", "compensated_but_recommender_won", escrow));
			default -> Mono.just(new Outcome("conflict", "unexpected_escrow_state", escrow));
		};
	}

	/**
	 * 事件 payload：{@code recommenderAccountId}/{@code taskOwnerId} 供 identity 解析收件人
	 * （Reserved/Refunded → 推荐官；Compensated → 双方），渲染 payload 只带 engagementRef/金额。
	 */
	private EventEnvelope envelope(String eventType, FreebieEscrow escrow) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("engagementRef", escrow.engagementRef());
		payload.put("recommenderAccountId", escrow.recommenderAccountId());
		if (escrow.taskOwnerAccountId() != null) {
			payload.put("taskOwnerId", escrow.taskOwnerAccountId());
		}
		payload.put("organizationId", escrow.organizationId());
		payload.put("amountCents", escrow.amountCents());
		payload.put("status", escrow.status());
		return new EventEnvelope(UUID.randomUUID().toString(), eventType, "FreebieEscrow", escrow.id(), 1,
				Instant.now(), null, payload);
	}

	/**
	 * 对账结局（outcome/reason 语义对齐既有 ReconciliationResult，供 marketplace 对账 activity
	 * 消费）。
	 */
	public record Outcome(String outcome, String reason, FreebieEscrow escrow) {
	}
}
