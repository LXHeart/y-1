package com.grassland.trust.workflow;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.dispute.DisputeCaseStatus;
import com.grassland.trust.precedent.PrecedentService;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.trust.judge.JudgeEligibilityService;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import com.grassland.trust.security.TrustException;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 审判 workflow 活动实现（草场 Epic 6 Slice 6C Phase C / HLD §5.5、§9.3）。
 *
 * <p>
 * 每个活动幂等 + 执行前重验状态（终态短路、面板已存在短路、判决已记短路）。DB 写入用 guarded-UPDATE； outbox 用确定性
 * type-3 {@code eventId}（{@code eventType:disputeId:round}）保 activity 重试
 * exactly-once。 {@code assignPanel} 抽签失败（无可用审判官）抛 {@link TrustException}(503) →
 * Temporal 重试。
 *
 * <p>
 * <b>Slice 7C-2</b>：写活动的「领域写 + outbox append」绑进同一 R2DBC 事务——否则崩溃落在两次提交之间时，
 * 重试会被幂等守卫（「面板已存在」/「非 voting」）短路，事件**永久丢失**。状态迁移（startAdjudication/reopen）、
 * 条件面板分配和 outbox 必须原子提交；抽签远端复验与跨服务 {@code finance.*} 调用留在事务外。
 *
 * <p>
 * worker = {@code trust-adjudication}（application.yml 显式定义）。
 */
@Component
@ActivityImpl(workers = DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
public class AdjudicationActivityImpl implements AdjudicationActivity {

	private final DisputeCaseRepository disputes;
	private final JudgeRepository judges;
	private final JudgeEligibilityService judgeEligibility;
	private final OutboxRepository outbox;
	private final AdjudicationProperties props;
	private final FinanceDecisionClient finance;
	private final TransactionalOperator transactions;
	private final PrecedentService precedents;
	private final com.grassland.trust.judge.JudgeAdmissionAuditRepository auditRepo;

	public AdjudicationActivityImpl(DisputeCaseRepository disputes, JudgeRepository judges,
			JudgeEligibilityService judgeEligibility, OutboxRepository outbox, AdjudicationProperties props,
			FinanceDecisionClient finance, TransactionalOperator transactions, PrecedentService precedents,
			com.grassland.trust.judge.JudgeAdmissionAuditRepository auditRepo) {
		this.disputes = disputes;
		this.judges = judges;
		this.judgeEligibility = judgeEligibility;
		this.outbox = outbox;
		this.props = props;
		this.finance = finance;
		this.transactions = transactions;
		this.precedents = precedents;
		this.auditRepo = auditRepo;
	}

	@Override
	public void assignPanel(String disputeId, int round) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null || "final".equals(d.status())) {
			return;
		}
		int panelSize = props.panelSize();
		int observedCount = judges.countPanel(disputeId, round).block();
		if (observedCount == panelSize) {
			return;
		}
		if (observedCount > panelSize) {
			throw new TrustException(503, "审判面板人数异常，请联系平台处理");
		}
		List<String> observedAccounts = judges.findPanelAccountIds(disputeId, round).collectList().block();
		if (observedAccounts.size() != observedCount) {
			throw new TrustException(503, "审判面板状态已变化，请重试");
		}
		// 任务书 #74 卡 D：垂类硬配额分池抽签（涉案平台熟手 ≥platform-quota 席，不足降级不 503）
		// + 卡 E：见习席位上限（池尽容忍超出并计数）。
		List<JudgeEligibilityService.PanelPick> picks = judgeEligibility.drawVerifiedPanel(
				panelSize - observedCount, d.organizationId(), d.taskPlatform(),
				props.platformQuota(), props.platformCompletionsMin(), props.probationSeatsPerPanel(),
				Set.copyOf(observedAccounts)).block();
		List<String> newAccounts = picks.stream().map(JudgeEligibilityService.PanelPick::accountId).toList();
		Set<String> matchedAccounts = picks.stream().filter(JudgeEligibilityService.PanelPick::matchedPlatform)
				.map(JudgeEligibilityService.PanelPick::accountId).collect(java.util.stream.Collectors.toSet());
		List<String> finalPanelAccounts = java.util.stream.Stream
				.concat(observedAccounts.stream(), newAccounts.stream()).toList();
		// Identity is authoritative for all organization memberships. Re-fetch after
		// selection and
		// immediately before the local write transaction; any timeout or conflict
		// aborts the draw.
		judgeEligibility.validateNoOrganizationConflicts(finalPanelAccounts, d.organizationId()).block();
		// 状态迁移 + 条件面板分配 + outbox 同事务；任何候选在提交前失去本地资格都会整体回滚。
		transactions.transactional(
				judges.lockPanel(disputeId, round).then(judges.findPanelAccountIds(disputeId, round).collectList())
						.flatMap(currentAccounts -> completePanelUnderLock(d, round, observedAccounts, currentAccounts,
								newAccounts, matchedAccounts, panelSize)))
				.block();
	}

	private Mono<Void> completePanelUnderLock(DisputeCase dispute, int round, List<String> observedAccounts,
			List<String> currentAccounts, List<String> newAccounts, Set<String> matchedAccounts, int panelSize) {
		if (currentAccounts.size() == panelSize) {
			return Mono.empty();
		}
		if (currentAccounts.size() > panelSize || !sameAccounts(observedAccounts, currentAccounts)) {
			return Mono.error(new TrustException(503, "审判面板状态已变化，请重试"));
		}
		return transitionForAssignment(dispute, round).flatMap(fresh -> judges
				.assignPanel(dispute.id(), round, newAccounts, matchedAccounts)
				.flatMap(inserted -> inserted == newAccounts.size()
						? Mono.empty()
						: Mono.error(new TrustException(503, "审判官资格已变化，请重试抽签")))
				.then(judges.countPanel(dispute.id(), round))
				.flatMap(count -> count == panelSize
						? Mono.empty()
						: Mono.error(new TrustException(503, "审判面板人数异常，请重试")))
				.then(Mono.defer(() -> outbox.append(
						envelope(round == 1 ? "DisputeAssigned" : "AdjudicationReopened", fresh, round, panelSize)))));
	}

	private static boolean sameAccounts(List<String> left, List<String> right) {
		return left.size() == right.size() && Set.copyOf(left).equals(Set.copyOf(right));
	}

	private Mono<DisputeCase> transitionForAssignment(DisputeCase dispute, int round) {
		// 任务书 #74 卡 B：受理期（open|evidence）→ voting；open 为存量兼容视同 evidence。
		if (round == 1 && DisputeCaseStatus.isEvidencePending(dispute.status())) {
			return disputes.startAdjudication(dispute.id(), 1)
					.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试抽签")));
		}
		if (round > 1 && dispute.round() < round) {
			return disputes.reopen(dispute.id(), round)
					.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试抽签")));
		}
		return Mono.just(dispute);
	}

	@Override
	public TallyResult tallyVotes(String disputeId, int round) {
		// 保留历史 Workflow 的 tallyVotes Activity 命令名，同时把计票与状态迁移收进同一事务。
		// 后续历史命令 recordDecision/escalate 会因状态已迁移而幂等 no-op。
		return closeVotingRound(disputeId, round, round >= props.maxRounds());
	}

	public TallyResult closeVotingRound(String disputeId, int round, boolean finalRound) {
		TallyResult result = transactions
				.transactional(
						disputes.findByIdForUpdate(disputeId)
								.switchIfEmpty(Mono.error(new TrustException(404, "争议不存在")))
								.flatMap(dispute -> judges.tallyVotes(disputeId, round)
										.flatMap(tally -> closeVotingRoundLocked(dispute, round, finalRound, tally))))
				.block();
		return result == null ? TallyResult.undecided(0, 0, 0, 0) : result;
	}

	private Mono<TallyResult> closeVotingRoundLocked(DisputeCase dispute, int round, boolean finalRound,
			VoteTally tally) {
		TallyResult result = toTallyResult(tally);
		if (!"voting".equals(dispute.status()) || dispute.round() != round
				|| "escalated".equals(dispute.appealState())) {
			return Mono.just(result);
		}
		if (result.decided()) {
			return disputes.recordDecision(dispute.id(), result.winner())
					.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试计票")))
					// ADR-D15 D2：与 DisputeDecided 同事务逐审判官 append 发奖事件（回滚即都不发）。
					.flatMap(updated -> appendVoteRewards(dispute, round)
							.then(outbox.append(envelope("DisputeDecided", updated, round, null)).thenReturn(result)));
		}
		if (finalRound) {
			return disputes.markEscalated(dispute.id())
					.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试计票"))).flatMap(updated -> outbox
							.append(envelope("AdjudicationEscalated", updated, round, null)).thenReturn(result));
		}
		return disputes.reopen(dispute.id(), round + 1)
				.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试计票"))).thenReturn(result);
	}

	private static TallyResult toTallyResult(VoteTally t) {
		if (t == null) {
			return TallyResult.undecided(0, 0, 0, 0);
		}
		if (t.hasMajorityForMerchant()) {
			return TallyResult.decided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize(), "for_merchant");
		}
		if (t.hasMajorityForRecommender()) {
			return TallyResult.decided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize(),
					"for_recommender");
		}
		return TallyResult.undecided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize());
	}

	@Override
	public void recordDecision(String disputeId, String winner) {
		// 领域写（voting→decided）+ outbox 同事务：outbox 失败则回滚状态迁移。
		// ADR-D15 D2：发奖事件同事务（当前轮实际投票者逐人；客服终审无投票行 → 天然不发）。
		DisputeCase updated = transactions
				.transactional(disputes.recordDecision(disputeId, winner)
						.flatMap(u -> appendVoteRewards(u, u.round())
								.then(outbox.append(envelope("DisputeDecided", u, u.round(), null)).thenReturn(u))))
				.block();
		if (updated == null) {
			return; // 非 voting（已判决/终局）→ 幂等跳过（empty Mono，无写无事件）
		}
	}

	@Override
	public void escalate(String disputeId) {
		// 领域写（appeal_state=escalated，保持 voting）+ outbox 同事务：outbox 失败则回滚。
		DisputeCase updated = transactions
				.transactional(disputes.markEscalated(disputeId).flatMap(
						u -> outbox.append(envelope("AdjudicationEscalated", u, u.round(), null)).thenReturn(u)))
				.block();
		if (updated == null) {
			return; // 非 voting → 幂等跳过（empty Mono，无写无事件）
		}
	}

	@Override
	public boolean isFinal(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		return d != null && "final".equals(d.status());
	}

	@Override
	public boolean hasAppealOrEscalation(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null) {
			return false;
		}
		boolean appealFiled = Boolean.TRUE.equals(disputes.hasAppeal(disputeId).block());
		String as = d.appealState();
		return appealFiled || "filed".equals(as) || "escalated".equals(as);
	}

	@Override
	public void applyPanelDecision(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null || "final".equals(d.status())) {
			return;
		}
		// 用面板判决（decision 列）finalize（decided→final）；decidedBy=null（非客服终审）。
		disputes.finalize(disputeId, d.decision() != null ? d.decision() : "no_decision", null).block();
	}

	@Override
	public void releaseHoldAndApplyDecision(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null || !"final".equals(d.status())) {
			return; // 未终局 → 不动钱
		}
		String decision = d.finalDecision() != null ? d.finalDecision() : d.decision();
		if (decision == null) {
			return; // 无判决（理论上不应发生：escalate 必经客服 final-decision 带 decision）
		}
		String org = d.organizationId();
		String ref = d.engagementRef();
		// 判决×reservation 状态矩阵（D-06）：每步幂等，finance 404（无 reservation=非资金型）/409（非目标态）→
		// noop。
		if ("for_merchant".equalsIgnoreCase(decision)) {
			// 商家方胜诉：资金退还商家。先尝试 release（reserved）；非 reserved 再 reverse（captured）。
			Boolean released = finance.releaseIfReserved(org, ref).block();
			if (!Boolean.TRUE.equals(released)) {
				finance.reverseIfCaptured(org, ref).block();
			}
		} else if ("for_recommender".equalsIgnoreCase(decision)) {
			// 推荐官方胜诉：资金判给推荐官（= capture 确认扣款）。已 captured/released → noop。
			finance.captureIfReserved(org, ref).block();
		}
	}

	@Override
	public void publishFinalStatus(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null) {
			return;
		}
		outbox.append(envelope("DisputeFinalized", d, d.round(), null)).block();
		// 任务书 #74 卡 G：终局即判例入库（UNIQUE(dispute_id) 幂等；panel 经由）。
		precedents.record(disputeId).onErrorResume(e -> {
			org.slf4j.LoggerFactory.getLogger(AdjudicationActivityImpl.class)
					.warn("precedent generation failed disputeId={}", disputeId, e);
			return Mono.empty();
		}).block();
	}

	/**
	 * 任务书 #74 卡 A：客服直裁 SLA 到点自动终局。已终局/非 cs_direct → 幂等 no-op；
	 * 默认裁决 = 维持系统核实结果 for_recommender（照 merchant_rejection auto-finalize 语义）。
	 */
	@Override
	public void autoFinalizeCsDirect(String disputeId) {
		DisputeCase d = disputes.findById(disputeId).block();
		if (d == null || "final".equals(d.status()) || !"cs_direct".equals(d.effectiveChannel())) {
			return;
		}
		DisputeCase fin = transactions
				.transactional(disputes.forceFinalize(disputeId, "for_recommender", null)
						.switchIfEmpty(Mono.empty())
						.flatMap(updated -> outbox.append(csAutoEnvelope(updated)).thenReturn(updated)))
				.block();
		if (fin != null) {
			precedents.record(disputeId).onErrorResume(e -> {
				org.slf4j.LoggerFactory.getLogger(AdjudicationActivityImpl.class)
						.warn("precedent generation failed disputeId={}", disputeId, e);
				return Mono.empty();
			}).block();
		}
	}

	/** cs_direct SLA 自动终局事件：DisputeFinalized + {@code auto: true}（确定性 eventId 同名防重）。 */
	private EventEnvelope csAutoEnvelope(DisputeCase d) {
		String eventId = UUID
				.nameUUIDFromBytes(("DisputeFinalized:" + d.id() + ":" + d.round()).getBytes(StandardCharsets.UTF_8))
				.toString();
		Map<String, Object> payload = new LinkedHashMap<>(envelopePayload(d, d.round(), null));
		payload.put("auto", true);
		return new EventEnvelope(eventId, "DisputeFinalized", "DisputeCase", d.id(), d.version(), Instant.now(), null,
				payload);
	}

	/**
	 * ADR-D15 / ADR-D18：对该轮实际投票的审判官逐人 append 激励事件（与轮终局状态变更同事务）。
	 * 积分 {@code JudgeVoteRewarded}（平坦 credits-per-vote，默认 20）与现金
	 * {@code JudgeVoteCommissionRewarded}（平坦 cents-per-vote，默认 0=关闭）各发各的——
	 * 事件类型分离使既有载荷契约零变更，也避开跨版本 activity 重试的 payload canonical-hash 边缘冲突。
	 * 0/负 = 关闭对应激励（不发事件）。确定性 event_id 前缀区分
	 * （{@code JudgeVoteRewarded:}/{@code JudgeVoteCommission:} + disputeId:round:judgeId）——
	 * activity 重试时 outbox ON CONFLICT 去重，重开轮（round 递增）天然各自计发。
	 */
	private Mono<Void> appendVoteRewards(DisputeCase dispute, int round) {
		int credits = props.judgeRewardCreditsPerVote();
		int cashCents = props.judgeCommissionCentsPerVote();
		if (credits <= 0 && cashCents <= 0) {
			return Mono.empty();
		}
		return judges.findVoterAccountIds(dispute.id(), round)
				.flatMap(judgeAccountId -> {
					Mono<Void> creditPart = credits > 0
							? outbox.append(rewardEnvelope(dispute, round, judgeAccountId, credits)).then()
							: Mono.empty();
					Mono<Void> cashPart = cashCents > 0
							? outbox.append(commissionEnvelope(dispute, round, judgeAccountId, cashCents)).then()
							: Mono.empty();
					return creditPart.then(cashPart);
				})
				.then();
	}

	private EventEnvelope rewardEnvelope(DisputeCase dispute, int round, String judgeAccountId, int credits) {
		String eventId = UUID
				.nameUUIDFromBytes(("JudgeVoteRewarded:" + dispute.id() + ":" + round + ":" + judgeAccountId)
						.getBytes(StandardCharsets.UTF_8))
				.toString();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("disputeId", dispute.id());
		payload.put("round", round);
		payload.put("judgeAccountId", judgeAccountId);
		payload.put("credits", credits);
		return new EventEnvelope(eventId, "JudgeVoteRewarded", "DisputeCase", dispute.id(), dispute.version(),
				Instant.now(), null, payload);
	}

	/** ADR-D18：现金佣金事件（分，finance 消费端入钱包 + journal/posting 双录账本）。 */
	private EventEnvelope commissionEnvelope(DisputeCase dispute, int round, String judgeAccountId, int cashCents) {
		String eventId = UUID
				.nameUUIDFromBytes(("JudgeVoteCommission:" + dispute.id() + ":" + round + ":" + judgeAccountId)
						.getBytes(StandardCharsets.UTF_8))
				.toString();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("disputeId", dispute.id());
		payload.put("round", round);
		payload.put("judgeAccountId", judgeAccountId);
		payload.put("amountCents", cashCents);
		return new EventEnvelope(eventId, "JudgeVoteCommissionRewarded", "DisputeCase", dispute.id(),
				dispute.version(), Instant.now(), null, payload);
	}

	/**
	 * 任务书 #74 卡 C（D2 抢先 4/7 达票）：投票请求侧收尾——voting 未升级态下已达多数 → recordDecision +
	 * 逐官发奖 + DisputeDecided（与满窗计票 closeVotingRoundLocked 同语义同事务；行锁串行化并发，
	 * 输家读到 decided 直接 no-op）。返回本调用是否完成翻案。
	 */
	public Mono<Boolean> concludeOnMajority(String disputeId, int round) {
		return transactions
				.transactional(disputes.findByIdForUpdate(disputeId)
						.switchIfEmpty(Mono.error(new TrustException(404, "争议不存在")))
						.flatMap(dispute -> {
							if (!"voting".equals(dispute.status()) || dispute.round() != round
									|| "escalated".equals(dispute.appealState())) {
								return Mono.just(false);
							}
							return judges.tallyVotes(disputeId, round).flatMap(tally -> {
								if (!tally.hasMajority()) {
									return Mono.just(false);
								}
								String winner = tally.hasMajorityForMerchant() ? "for_merchant" : "for_recommender";
								return disputes.recordDecision(disputeId, winner)
										.switchIfEmpty(Mono.error(new TrustException(503, "争议状态已变化，请重试计票")))
										.flatMap(updated -> appendVoteRewards(updated, round)
												.then(outbox.append(envelope("DisputeDecided", updated, round, null))))
										.thenReturn(true);
							});
						}))
				.defaultIfEmpty(false);
	}

	/**
	 * 任务书 #74 卡 E（D4 派生）：见习转正检查（recordVote 同事务调用）。去重投票轮数 ≥
	 * {@code probation-promote-rounds}（默认 10）且仍为见习 → full + audit 'promoted'（系统自动动作，
	 * actor=零 UUID；v1 只考轮次不考方向——派生 4 红线）。
	 */
	public Mono<Boolean> promoteIfEligible(com.grassland.trust.judge.Judge judge) {
		if (judge == null || !judge.isProbation()) {
			return Mono.just(false);
		}
		return judges.countDistinctVotingRounds(judge.accountId())
				.filter(rounds -> rounds >= props.probationPromoteRounds())
				.flatMap(rounds -> judges.promote(judge.accountId())
						.flatMap(updated -> auditRepo.appendAction(updated.id(), "promoted",
								AUTO_ACTOR, "probation_auto_promotion_rounds=" + rounds,
								updated.version() - 1).thenReturn(updated)))
				.hasElement();
	}

	private static final String AUTO_ACTOR = "00000000-0000-0000-0000-000000000000";

	private EventEnvelope envelope(String eventType, DisputeCase d, int round, Integer panelSize) {
		String eventId = UUID
				.nameUUIDFromBytes((eventType + ":" + d.id() + ":" + round).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, eventType, "DisputeCase", d.id(), d.version(), Instant.now(), null,
				envelopePayload(d, round, panelSize));
	}

	private Map<String, Object> envelopePayload(DisputeCase d, int round, Integer panelSize) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("disputeId", d.id());
		payload.put("engagementRef", d.engagementRef());
		payload.put("organizationId", d.organizationId());
		payload.put("openedByAccountId", d.openedByAccountId());
		payload.put("openedByRole", d.openedByRole());
		payload.put("channel", d.effectiveChannel());
		payload.put("round", round);
		if (d.decision() != null) {
			payload.put("decision", d.decision());
		}
		if (d.finalDecision() != null) {
			payload.put("finalDecision", d.finalDecision());
		}
		if (panelSize != null) {
			payload.put("panelSize", panelSize);
		}
		return payload;
	}
}
