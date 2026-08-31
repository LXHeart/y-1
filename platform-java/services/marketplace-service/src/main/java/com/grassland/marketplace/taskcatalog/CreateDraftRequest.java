package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 创建任务草稿请求体（{@code POST /api/tasks/draft}，GL-P1-TASK-001 Stage 1）。
 *
 * <p>
 * 字段与 {@link CreateTaskRequest} 同（同 compact 校验），区别仅在语义：草稿不占发布额度、不需资金权限， 草稿
 * tier（DRAFT）商家也可建。{@code applicationDeadline} 可在草稿阶段预设，发布时随快照冻结。
 */
public record CreateDraftRequest(String organizationId, String title, String description, String contentForm,
		String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
		String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents,
		String questionText, String questionRef) {
	/**
	 * 目标问题值对象（任务书 #62 P4）。<b>线上契约是平铺的
	 * {@code questionText}/{@code questionRef}</b>—— Jackson 按名字绑定 record
	 * 组件，嵌套组件收不到平铺键，所以字段平铺、值对象派生。 组件已在 compact 构造里规整过，这里只是原样组装。
	 */
	public TaskQuestion question() {
		return new TaskQuestion(questionText, questionRef);
	}
	public CreateDraftRequest(String organizationId, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel) {
		this(organizationId, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, null, TaskRequirements.empty(), null, null, null, null);
	}

	/** 便捷构造：任务书 #62 之前的全量字段签名（无目标问题）。 */
	public CreateDraftRequest(String organizationId, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		this(organizationId, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, freebieDepositCents, null, null);
	}

	public CreateDraftRequest {
		if (organizationId == null || organizationId.isBlank()) {
			throw new IllegalArgumentException("organizationId is required");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		if (maxSlots != null && maxSlots < 1) {
			throw new IllegalArgumentException("maxSlots must be >= 1");
		}
		if (bountyCents != null && bountyCents < 0) {
			throw new IllegalArgumentException("bountyCents must be >= 0");
		}
		if (freebieDepositCents != null && freebieDepositCents < 0) {
			throw new IllegalArgumentException("freebieDepositCents must be >= 0");
		}
		TaskCatalogFundingRules.validate(requirements, freebieDepositCents, bountyCents);
		if (!TaskRequirements.isValidContentForm(contentForm)) {
			throw new IllegalArgumentException("内容形式必须是 image / video / article / interaction");
		}
		TaskRequirements.validateInteractionBinding(contentForm, requirements);
		requirements = TaskRequirements.normalize(requirements);
		TaskQuestion normalizedQuestion = new TaskQuestion(questionText, questionRef);
		questionText = normalizedQuestion.text();
		questionRef = normalizedQuestion.ref();
		if (minRecommenderLevel != null && (minRecommenderLevel < 1 || minRecommenderLevel > 5)) {
			throw new IllegalArgumentException("minRecommenderLevel must be between 1 and 5");
		}
		if (autoAcceptMinLevel != null && (autoAcceptMinLevel < 1 || autoAcceptMinLevel > 5)) {
			throw new IllegalArgumentException("autoAcceptMinLevel must be between 1 and 5");
		}
	}
}
