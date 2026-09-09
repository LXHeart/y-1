package com.grassland.trust.adjudication;

import com.grassland.trust.dispute.DisputeCase;
import java.time.Duration;
import java.time.Instant;

/**
 * 开庭（evidence/open → voting）条件判定（审查修复 02 / R06）。
 *
 * <p>
 * 仅 court 案件可进入面板；待质证案件只有满足以下任一条件才可变为 voting：
 * <ol>
 * <li>质证截止时间（冻结的 {@code evidence_deadline}）已到；或</li>
 * <li>{@code claimant_done_at} 与 {@code respondent_done_at} 均已落定。</li>
 * </ol>
 *
 * <p>
 * 本类是服务端口径的<b>预判</b>（工作流计时锚点、手动入口响应分支）；唯一的权威转换在
 * {@code DisputeCaseRepository.startAdjudication} 的 guarded-UPDATE——同一条件以 SQL
 * 再验一遍， 双方 done / 到期 / 手动重试并发时最多翻一次状态、提交一个面板。两边条件必须保持同口径。
 *
 * <p>
 * 存量兼容（空 {@code evidence_deadline}）：锚点退化为 {@code created_at + 质证窗}—— 不把 null
 * 当作「没有期限、立即开庭」，也不重新给整个窗口（重试只看既有锚点剩多少）。
 */
public final class HearingConditions {

	private HearingConditions() {
	}

	/** 计时锚点：冻结的质证截止；空 deadline 的存量行走 created_at + 窗口 的兼容锚点。 */
	public static Instant anchor(DisputeCase dispute, long legacyWindowSeconds) {
		if (dispute.evidenceDeadline() != null) {
			return dispute.evidenceDeadline();
		}
		return dispute.createdAt() == null ? null : dispute.createdAt().plusSeconds(legacyWindowSeconds);
	}

	/** 当前时刻是否已满足开庭条件（见类 javadoc；无锚点 fail-closed 不开庭）。 */
	public static boolean met(DisputeCase dispute, Instant now, long legacyWindowSeconds) {
		if (dispute.claimantDoneAt() != null && dispute.respondentDoneAt() != null) {
			return true;
		}
		Instant deadline = anchor(dispute, legacyWindowSeconds);
		return deadline != null && !deadline.isAfter(now);
	}

	/** 距开庭还需等待的秒数（已满足 → 0；无锚点 fail-closed 按整个窗口等）。 */
	public static long remainingSeconds(DisputeCase dispute, Instant now, long legacyWindowSeconds) {
		if (met(dispute, now, legacyWindowSeconds)) {
			return 0;
		}
		Instant deadline = anchor(dispute, legacyWindowSeconds);
		if (deadline == null) {
			return legacyWindowSeconds;
		}
		return Math.max(0, Duration.between(now, deadline).toSeconds());
	}
}
