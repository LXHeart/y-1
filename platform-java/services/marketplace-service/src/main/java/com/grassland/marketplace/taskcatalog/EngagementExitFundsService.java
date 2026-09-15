package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.EngagementExitOperation.EngagementExitFundLeg;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 退出资金恢复服务（任务书 #103 C103-03 / D103-02）：按冻结快照推进
 * {@code engagement_exit_operation} 的资金腿——固定顺序 deposit_refund → bounty_capture
 * → bounty_release，依赖腿不并发；金额一律取库内快照，不重算、不接受上层传入可变金额。
 *
 * <p>
 * 结果未知先核实原经济键（BR-02/E16）：任何腿的远端调用失败/超时，先经 Finance {@code exit-facts}
 * 权威回读；事实证明已落定 → 收口 succeeded；口径冲突 → needs_review； 事实不可得 → unknown +
 * 退避重试。成功腿重放回读不重复落账。零金额腿保持 not_required， 不调用任何远端操作。全部必需腿 succeeded 后操作收口
 * succeeded，协商退出补发一次完成事件 （确定性 eventId）。
 */
@Component
public class EngagementExitFundsService {

	private static final Logger log = LoggerFactory.getLogger(EngagementExitFundsService.class);

	/** 腿固定顺序（§4.1）。 */
	private static final List<String> LEG_ORDER = List.of("deposit_refund", "bounty_capture", "bounty_release");

	private final EngagementExitOperationRepository operations;
	private final EngagementExitRequestRepository exits;
	private final TaskRepository tasks;
	private final FinanceEscrowClient finance;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final Clock clock;
	private final int leaseSeconds;
	private final int maxAttempts;
	private final long backoffBaseSeconds;
	private final long backoffMaxSeconds;

	@org.springframework.beans.factory.annotation.Autowired
	public EngagementExitFundsService(EngagementExitOperationRepository operations,
			EngagementExitRequestRepository exits, TaskRepository tasks, FinanceEscrowClient finance,
			OutboxRepository outbox, TransactionalOperator transactions,
			@Value("${marketplace.engagement.exit-funds.lease-seconds:60}") int leaseSeconds,
			@Value("${marketplace.engagement.exit-funds.max-attempts:8}") int maxAttempts,
			@Value("${marketplace.engagement.exit-funds.backoff-base-seconds:60}") long backoffBaseSeconds,
			@Value("${marketplace.engagement.exit-funds.backoff-max-seconds:3600}") long backoffMaxSeconds) {
		this(operations, exits, tasks, finance, outbox, transactions, Clock.systemUTC(), leaseSeconds, maxAttempts,
				backoffBaseSeconds, backoffMaxSeconds);
	}

	EngagementExitFundsService(EngagementExitOperationRepository operations, EngagementExitRequestRepository exits,
			TaskRepository tasks, FinanceEscrowClient finance, OutboxRepository outbox,
			TransactionalOperator transactions, Clock clock, int leaseSeconds, int maxAttempts, long backoffBaseSeconds,
			long backoffMaxSeconds) {
		this.operations = operations;
		this.exits = exits;
		this.tasks = tasks;
		this.finance = finance;
		this.outbox = outbox;
		this.transactions = transactions;
		this.clock = clock;
		this.leaseSeconds = Math.max(1, leaseSeconds);
		this.maxAttempts = Math.max(1, maxAttempts);
		this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
		this.backoffMaxSeconds = Math.max(this.backoffBaseSeconds, backoffMaxSeconds);
	}

	/** 恢复入口：领取租约后逐腿核实/执行；另一 worker 持有有效租约 → 回读当前态（不重复领取）。 */
	public Mono<EngagementExitOperation> advance(String operationId, String workerId) {
		Instant now = clock.instant();
		UUID leaseToken = UUID.randomUUID();
		return operations.findById(operationId).flatMap(op -> {
			if (op.succeeded() || "needs_review".equals(op.state())) {
				return Mono.just(op);
			}
			// 另一 worker 持有有效租约 → 回读当前态，不重复领取、不重复执行腿（TC103-03-06）。
			if ("processing".equals(op.state()) && op.leaseHeldByOther(now)) {
				return Mono.just(op);
			}
			return operations.claimLease(operationId, workerId, leaseToken, now, leaseSeconds, maxAttempts)
					.flatMap(claimed -> runLegs(claimed, leaseToken)).switchIfEmpty(operations.findById(operationId));
		});
	}

	private Mono<EngagementExitOperation> runLegs(EngagementExitOperation op, UUID leaseToken) {
		AtomicReference<String> stopErrorCode = new AtomicReference<>();
		Mono<Boolean> chain = Mono.just(true);
		for (String legKind : LEG_ORDER) {
			chain = chain.flatMap(proceed -> {
				if (!proceed || stopErrorCode.get() != null) {
					return Mono.just(proceed);
				}
				return runLeg(op, legKind, leaseToken).map(outcome -> switch (outcome) {
					case DONE -> true;
					case RETRY -> {
						stopErrorCode.set("transient");
						yield false;
					}
					case REVIEW -> {
						stopErrorCode.set("conflict");
						yield false;
					}
				});
			});
		}
		return chain.flatMap(proceed -> {
			if (stopErrorCode.get() != null) {
				return "conflict".equals(stopErrorCode.get())
						? operations
								.markNeedsReview(op.id(), "legs_conflict", "funds_reconciliation_required", leaseToken)
								.then(operations.findById(op.id()))
						: operations.scheduleRetry(op.id(), "transient_failure", op.attempts(), leaseToken,
								backoffBaseSeconds, backoffMaxSeconds).then(operations.findById(op.id()));
			}
			if (!proceed) {
				return operations.findById(op.id());
			}
			// 全部必需腿落定 → 同事务收口 + 协商退出完成事件一次。
			return transactions.transactional(operations.completeSucceeded(op.id(), leaseToken)
					.flatMap(completed -> completed && "negotiated".equals(op.kind())
							? negotiatedCompletionEvent(op).flatMap(outbox::append).thenReturn(true)
							: Mono.just(completed)))
					.then(operations.findById(op.id()));
		});
	}

	private enum LegOutcome {
		DONE, RETRY, REVIEW
	}

	private Mono<LegOutcome> runLeg(EngagementExitOperation op, String legKind, UUID leaseToken) {
		EngagementExitFundLeg leg = op.legs().stream().filter(l -> l.legKind().equals(legKind)).findFirst()
				.orElse(null);
		if (leg == null || "not_required".equals(leg.state()) || "succeeded".equals(leg.state())) {
			return Mono.just(LegOutcome.DONE);
		}
		if ("needs_review".equals(leg.state())) {
			return Mono.just(LegOutcome.REVIEW);
		}
		if (leg.amountCents() <= 0) {
			return operations.markLegSucceeded(op.id(), legKind, "zero_leg", clock.instant())
					.thenReturn(LegOutcome.DONE);
		}
		Mono<Void> attempt = switch (legKind) {
			case "deposit_refund" -> finance.freebieRefund(op.organizationId(), op.applicationId()).then();
			case "bounty_capture" -> finance
					.captureVerified(op.organizationId(), op.applicationId(), snapshotLong(op, "bountyCents"),
							snapshotString(op, "payeeAccountId"), leg.amountCents())
					.flatMap(
							outcome -> outcome
									.captured()
											? Mono.<Void>empty()
											: Mono.error(new IllegalStateException(
													"capture reconciliation: " + outcome.reconciliationReason())))
					.then();
			case "bounty_release" -> finance.release(op.organizationId(), op.applicationId()).then();
			default -> Mono.error(new IllegalStateException("unknown leg " + legKind));
		};
		return attempt.then(operations.markLegSucceeded(op.id(), legKind, financeReference(legKind), clock.instant()))
				.thenReturn(LegOutcome.DONE).onErrorResume(error -> {
					log.warn("exit fund leg attempt failed op={} leg={} err={}", op.id(), legKind, error.getMessage());
					// 结果未知先核实原经济键（BR-02/E16）：exit-facts 权威回读决定收口/冲突/重试。
					return finance.exitFacts(op.organizationId(), op.applicationId())
							.flatMap(facts -> verifyAgainstFacts(op, leg, facts, legKind))
							.onErrorResume(verifyError -> operations.markLegUnknown(op.id(), legKind)
									.thenReturn(LegOutcome.RETRY));
				});
	}

	/** 用 Finance 权威事实核实本腿原键的实际结果：已落定 → succeeded；口径冲突 → REVIEW；事实缺行 → RETRY。 */
	private Mono<LegOutcome> verifyAgainstFacts(EngagementExitOperation op, EngagementExitFundLeg leg,
			Map<String, Object> facts, String legKind) {
		if (Boolean.TRUE.equals(facts.get("notFound"))) {
			return operations.markLegUnknown(op.id(), legKind).thenReturn(LegOutcome.RETRY);
		}
		Map<String, Object> bounty = section(facts, "bounty");
		Map<String, Object> deposit = section(facts, "deposit");
		boolean verified = switch (legKind) {
			case "deposit_refund" -> longOf(deposit, "refundedCents") >= leg.amountCents();
			case "bounty_capture" -> longOf(bounty, "capturedCents") >= leg.amountCents();
			case "bounty_release" -> longOf(bounty, "releasedCents") > 0;
			default -> false;
		};
		if (verified) {
			return operations.markLegSucceeded(op.id(), legKind, financeReference(legKind), clock.instant())
					.thenReturn(LegOutcome.DONE);
		}
		// 冲突细分：capture/release 方向互斥——已 capture 的预留不可再按 release-only 收口，反之亦然。
		boolean conflict = switch (legKind) {
			case "bounty_release" -> longOf(bounty, "capturedCents") > 0 && op.legs().stream()
					.anyMatch(l -> "bounty_capture".equals(l.legKind()) && "not_required".equals(l.state()));
			case "bounty_capture" -> longOf(bounty, "releasedCents") > 0 && leg.amountCents() > 0;
			default -> false;
		};
		return conflict
				? Mono.just(LegOutcome.REVIEW)
				: operations.markLegUnknown(op.id(), legKind).thenReturn(LegOutcome.RETRY);
	}

	private static String financeReference(String legKind) {
		return "finance:" + legKind;
	}

	private static long snapshotLong(EngagementExitOperation op, String key) {
		Object v = op.settlementSnapshot().get(key);
		return v instanceof Number n ? n.longValue() : 0;
	}

	private static String snapshotString(EngagementExitOperation op, String key) {
		Object v = op.settlementSnapshot().get(key);
		return v == null ? null : v.toString();
	}

	private static Map<String, Object> section(Map<String, Object> facts, String key) {
		Object v = facts.get(key);
		return v instanceof Map<?, ?> m ? castMap(m) : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	private static long longOf(Map<String, Object> section, String key) {
		Object v = section.get(key);
		return v instanceof Number n ? n.longValue() : 0;
	}

	/** 协商退出完成事件（§6.4：资金核实完成后一次；确定性 eventId 幂等）。 */
	private Mono<EventEnvelope> negotiatedCompletionEvent(EngagementExitOperation op) {
		return exits.findById(op.exitRequestId()).flatMap(request -> tasks.findById(op.taskId()).map(task -> {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("taskId", op.taskId());
			payload.put("applicationId", op.applicationId());
			payload.put("recommenderAccountId", snapshotString(op, "recommenderAccountId"));
			payload.put("exitRequestId", op.exitRequestId());
			payload.put("exitOperationId", op.id());
			payload.put("fundsState", "succeeded");
			payload.put("initiatedRole", request.initiatedRole());
			Object milestones = op.settlementSnapshot().get("confirmedMilestones");
			if (milestones != null) {
				payload.put("settlement", milestones);
			}
			payload.put("taskOwnerId", task.ownerAccountId());
			String eventId = UUID
					.nameUUIDFromBytes(
							("EngagementExitedNegotiated:" + op.exitRequestId()).getBytes(StandardCharsets.UTF_8))
					.toString();
			return new EventEnvelope(eventId, "EngagementExitedNegotiated", "TaskApplication", op.applicationId(), 1,
					clock.instant(), null, payload);
		}));
	}

	/** 运营重排入口（§6.2 retry）：仅原键重新排队；已成功 → 回读；版本冲突 → empty（调用方 409）。 */
	public Mono<EngagementExitOperation> requeue(String operationId, long expectedVersion, String reason) {
		return operations.requeue(operationId, expectedVersion, reason);
	}
}
