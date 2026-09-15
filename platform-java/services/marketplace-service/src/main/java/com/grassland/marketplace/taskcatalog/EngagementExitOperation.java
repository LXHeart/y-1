package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 退出资金恢复操作（任务书 #103 §7.2 / D103-02）：一次无责或协商确认退出的资金编排事实。
 *
 * <p>
 * settlement_snapshot 在父行锁事务内冻结（owner、合同版本、已确认里程碑金额、受款方与三腿金额）；
 * 重试只重放原经济键，绝不重新计算金额。state 是「业务终态 + 资金态」的读模型，不回写 task_application.status。
 */
public record EngagementExitOperation(String id, String applicationId, String taskId, String organizationId,
		String kind, String exitRequestId, long businessVersion, Integer contractVersion,
		Map<String, Object> settlementSnapshot, String state, int attempts, Instant nextAttemptAt, long version,
		String lastErrorCode, Instant leaseExpiresAt, Instant createdAt, Instant updatedAt, Instant completedAt,
		List<EngagementExitFundLeg> legs) {

	/** 有效租约判定（服务内部使用；不出现在对外读模型）。 */
	public boolean leaseHeldByOther(Instant now) {
		return leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
	}

	/** 资金腿（§4.1）：固定顺序 deposit_refund → bounty_capture → bounty_release。 */
	public record EngagementExitFundLeg(String operationId, String legKind, String economicKey, long amountCents,
			String state, String financeReference, Instant verifiedAt) {

		public boolean terminal() {
			return "not_required".equals(state) || "succeeded".equals(state) || "needs_review".equals(state);
		}
	}

	/** 三腿金额快照（§6.2 exitFunds.amounts）：整数分，零合法（零腿标 not_required，不调远端）。 */
	public record ExitAmounts(long depositRefundCents, long bountyCaptureCents, long bountyReleaseCents) {

		public static ExitAmounts none() {
			return new ExitAmounts(0, 0, 0);
		}
	}

	public boolean succeeded() {
		return "succeeded".equals(state);
	}
}
