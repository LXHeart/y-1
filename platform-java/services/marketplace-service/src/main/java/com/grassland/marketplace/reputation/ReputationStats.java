package com.grassland.marketplace.reputation;

import java.time.Instant;

/**
 * 推荐官声誉指标（PRD 六「数据面板」）。**全部从既有事实派生**，不落冗余表。
 *
 * <ul>
 * <li>{@code acceptedCount} 累计已接单数（内容履约：accepted + 后续被商家取消的 refunded，含已确认的；
 * 套餐推广报名不计入，单列 {@code promotionAcceptedCount}——分销绩效与内容完成率分开统计）。</li>
 * <li>{@code completedCount} 累计完成数（内容履约：confirmed_at 非空 = 商家确认/到期自动确认）。</li>
 * <li>{@code inProgressCount} 进行中数（内容履约：accepted 且未确认）。<b>不进入完成率分母</b>——
 * 未到期的合作不是失败，接新单不能把历史完成率拉低（业务审查 2026-09-07 C05）。</li>
 * <li>{@code promotionAcceptedCount} 套餐推广报名数（accepted/refunded）。分销无内容确认环节，
 * 只进分销绩效口径，永不进内容完成率分子/分母。</li>
 * <li>{@code merchantCancelledCount} 商家取消数（status='refunded' 且
 * exit_kind=merchant_cancel；exit_kind 为空的 存量记录沿用旧兼容口径）。交付超时 exit_kind=timeout
 * 不属于此类。</li>
 * <li>{@code rejectedCount} 被拒数（status='rejected'，商家拒绝报名）。</li>
 * <li>{@code withdrawnCount} 撤销数（status='withdrawn'，推荐官主动撤销）。</li>
 * <li>{@code averageScore} 平均评分；<b>无评分时为 null</b>，不是 0——「没人评过」与「都给 0 分」
 * 在等级判定上是两回事（0 会让人误以为口碑极差）。</li>
 * <li>{@code averageResponseSeconds} 平均响应时长：decided_at（接单）→ 首次交付物提交；无样本为
 * null。</li>
 * </ul>
 *
 * <p>
 * PRD 六的「平均曝光数据」<b>不做</b>——那要平台侧数据采集，不在本系统能证实的事实范围内。
 *
 * <p>
 * <b>完成率公式</b>（业务审查 2026-09-07 C05 口径）：完成数 / 推荐官责任范围内<b>已到终态或已到期</b>
 * 的内容履约数。商家取消（商家违约）与进行中（未到期）都从分母排除；被拒和接单前撤销从未形成接单，
 * 也不进入分母。推荐官有责终结态（履约超时/解约）随履约期限规则（任务书后续批次）落地后自然进入分母。 无责任接单时为 0。
 */
public record ReputationStats(int acceptedCount, int completedCount, int merchantCancelledCount, int rejectedCount,
		int withdrawnCount, int inProgressCount, int promotionAcceptedCount, int ratingCount, Double averageScore,
		Double averageResponseSeconds, Instant lastActiveAt) {

	public ReputationStats(int acceptedCount, int completedCount, int merchantCancelledCount, int rejectedCount,
			int withdrawnCount, int ratingCount, Double averageScore, Double averageResponseSeconds,
			Instant lastActiveAt) {
		this(acceptedCount, completedCount, merchantCancelledCount, rejectedCount, withdrawnCount, 0, 0, ratingCount,
				averageScore, averageResponseSeconds, lastActiveAt);
	}

	public ReputationStats(int acceptedCount, int completedCount, int merchantCancelledCount, int rejectedCount,
			int withdrawnCount, int ratingCount, Double averageScore, Double averageResponseSeconds) {
		this(acceptedCount, completedCount, merchantCancelledCount, rejectedCount, withdrawnCount, ratingCount,
				averageScore, averageResponseSeconds, Instant.now());
	}

	public static ReputationStats empty() {
		return new ReputationStats(0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
	}

	/**
	 * 完成率 = 完成数 / (累计已接单 - 商家取消 - 进行中)。 进行中是「未到期的合作」，进入分母会把正常接单记成失败（C05：10 完成 + 5
	 * 进行中应保持 100%）。
	 */
	public double completionRate() {
		int accountable = Math.max(0, acceptedCount - merchantCancelledCount - inProgressCount);
		return accountable == 0 ? 0.0 : (double) completedCount / accountable;
	}

	/** 终态总数（完成 + 商家取消 + 被拒 + 撤销），供外部展示用。 */
	public int terminalCount() {
		return completedCount + merchantCancelledCount + rejectedCount + withdrawnCount;
	}
}
