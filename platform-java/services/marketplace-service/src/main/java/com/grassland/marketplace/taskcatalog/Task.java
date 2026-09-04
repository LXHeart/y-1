package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推广任务（task-catalog）。草场 Epic 4 Slice 4A（HLD 5.3）+ GL-P1-TASK-001 Stage 1 生命周期。
 *
 * <p>
 * {@code ownerAccountId} = 发布者（断言 caller，merchant）；{@code organizationId} 逻辑引用
 * identity 的 organization （跨服务无 FK，HLD
 * database-per-service）；{@code status}/{@code contentForm}/{@code platform}
 * 存小写字符串。 {@code maxSlots} 为名额上限（null=不限，Slice 4B）。
 *
 * <p>
 * {@code freebieDepositCents} = 霸王餐押金（ADR-D12；null/0=无）。与 {@code bountyCents}
 * 互斥（XOR）： 押金由推荐官钱包预付、达标全额退还，资金方向与商家出资的 bounty 相反；accept/结算读 application 快照。
 *
 * <p>
 * Stage 1 生命周期字段：
 * <ul>
 * <li>{@code version} 乐观锁计数器（draft 编辑 / publish / close / cancel 每次 +1）；</li>
 * <li>{@code applicationDeadline} 仅建模 PRD「指定时间」截止（apply 时判，null=无时间截止）；</li>
 * <li>{@code publishedAt} 进入 published 的时刻（发布额度/月度统计按它计）；</li>
 * <li>{@code cancelledAt} 取消时刻。</li>
 * </ul>
 * 不可变要求快照见 {@code task_version} 表（publish 时落一行），不在此 record。
 *
 * <p>
 * 任务书 #62 {@code question}：可选目标问题（填写则交付知乎回答形态；null 等价
 * {@link TaskQuestion#none()}）。仅 platform=zhihu 允许携带（controller 422 守卫）。
 *
 * <p>
 * 任务书 #53
 * 审核视图字段：{@code lastReviewAction}/{@code lastReviewNote}/{@code lastReviewAt}
 * 仅由携带 LATERAL join {@code task_review} 的查询（审核队列 rejected 视图、商家端组织级列表）填充，
 * 其余查询路径恒 null。商家端展示时由 controller 按「最新一条记录为 rejected 且任务仍 draft」收紧。
 */
public record Task(String id, String ownerAccountId, String organizationId, String title, String description,
		String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
		Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
		int minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
		Long freebieDepositCents, String lastReviewAction, String lastReviewNote, Instant lastReviewAt,
		TaskQuestion question, String commercePackageId) {
	public Task {
		requirements = TaskRequirements.normalize(requirements);
		question = TaskQuestion.orNone(question);
	}

	/** 便捷构造：任务书 #75 之前的全量字段签名（无套餐推广关联，commercePackageId=null）。 */
	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, String lastReviewAction, String lastReviewNote, Instant lastReviewAt,
			TaskQuestion question) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, freebieDepositCents, lastReviewAction,
				lastReviewNote, lastReviewAt, question, null);
	}

	/** 是否套餐推广任务（任务书 #75 D1：佣金来自套餐版本快照，任务侧 bounty/freebie 恒 0）。 */
	public boolean isCommercePromotion() {
		return commercePackageId != null;
	}

	/** 便捷构造：无目标问题（任务书 #62 之前的全部构造路径）。 */
	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, String lastReviewAction, String lastReviewNote, Instant lastReviewAt) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, freebieDepositCents, lastReviewAction,
				lastReviewNote, lastReviewAt, TaskQuestion.none());
	}

	/** 便捷构造：审核视图字段恒 null（非 LATERAL join 查询路径）。 */
	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, freebieDepositCents, null, null, null);
	}

	/** 是否霸王餐押金任务（ADR-D12：deposit &gt; 0 即押金型；与 bounty XOR）。 */
	public boolean isFreebie() {
		return freebieDepositCents != null && freebieDepositCents > 0;
	}

	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt, 1, null,
				TaskRequirements.empty(), null, null);
	}

	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, null, TaskRequirements.empty(), null, null);
	}

	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel, String storeId) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, storeId, TaskRequirements.empty(), null, null);
	}

	public Task(String id, String ownerAccountId, String organizationId, String title, String description,
			String status, String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant createdAt,
			Instant updatedAt, int version, Instant applicationDeadline, Instant publishedAt, Instant cancelledAt,
			int minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel) {
		this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
				bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
				minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, null);
	}
}
