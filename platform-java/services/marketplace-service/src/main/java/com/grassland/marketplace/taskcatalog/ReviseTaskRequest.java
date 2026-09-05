package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 修订已发布任务请求体（{@code POST /api/tasks/{id}/revise}，GL-P1-TASK-001：编辑出新版本）。
 *
 * <p>
 * 仅 published 态可修订（controller 守卫）。{@code expectedVersion} 必填（乐观锁）。全字段可改——
 * accept/结算已读 {@code task_application.bounty_cents} 快照（V14
 * snapshot-pinning），故修订 task 赏金/平台 只影响**新报名**（新 app 冻新值），已 accept 的履约仍按其 accept
 * 时的快照结算，不会被改动。
 *
 * <p>
 * 赏金变更仍受 tier 上限约束（controller {@code enforceBountyTierGate}：bounty ≤
 * 本组织单笔上限、资金型须有交易权限）， 与发布同口径——避免商家借修订把赏金抬到 tier 之上。
 */
public record ReviseTaskRequest(int expectedVersion, String title, String description, String contentForm,
		String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
		TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents, String questionText,
		String questionRef, String commercePackageId) {
	/**
	 * 目标问题值对象（任务书 #62 P4）。<b>线上契约是平铺的
	 * {@code questionText}/{@code questionRef}</b>—— Jackson 按名字绑定 record
	 * 组件，嵌套组件收不到平铺键，所以字段平铺、值对象派生。 组件已在 compact 构造里规整过，这里只是原样组装。
	 */
	public TaskQuestion question() {
		return new TaskQuestion(questionText, questionRef);
	}
	/** 便捷构造：任务书 #62 之前的全量字段签名（无目标问题）。 */
	public ReviseTaskRequest(int expectedVersion, String title, String description, String contentForm, String platform,
			Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
			TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents) {
		this(expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents, null, null, null);
	}

	/** 便捷构造：任务书 #75 之前的全量字段签名（无套餐推广）。 */
	public ReviseTaskRequest(int expectedVersion, String title, String description, String contentForm, String platform,
			Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
			TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents, String questionText,
			String questionRef) {
		this(expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents, questionText, questionRef,
				null);
	}

	public ReviseTaskRequest {
		TaskQuestion normalizedQuestion = new TaskQuestion(questionText, questionRef);
		questionText = normalizedQuestion.text();
		questionRef = normalizedQuestion.ref();
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		// 任务书 #77 卡 B（D2）：修订同口径校验平台+截止（存量空值经修订由表单必填自然补齐）。
		TaskFieldPolicy.validatePlatformAndDeadline(platform, applicationDeadline);
		if (maxSlots != null && maxSlots < 1) {
			throw new IllegalArgumentException("maxSlots must be >= 1");
		}
		if (bountyCents != null && bountyCents < 0) {
			throw new IllegalArgumentException("bountyCents must be >= 0");
		}
		if (freebieDepositCents != null && freebieDepositCents < 0) {
			throw new IllegalArgumentException("freebieDepositCents must be >= 0");
		}
		TaskCatalogFundingRules.validate(requirements, freebieDepositCents, bountyCents, commercePackageId);
		if (!TaskRequirements.isValidContentForm(contentForm)) {
			throw new IllegalArgumentException("内容形式必须是 image / video / article / interaction");
		}
		if (minRecommenderLevel != null && (minRecommenderLevel < 1 || minRecommenderLevel > 5)) {
			throw new IllegalArgumentException("minRecommenderLevel must be between 1 and 5");
		}
		if (autoAcceptMinLevel != null && (autoAcceptMinLevel < 1 || autoAcceptMinLevel > 5)) {
			throw new IllegalArgumentException("autoAcceptMinLevel must be between 1 and 5");
		}
	}
}
