package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 编辑任务草稿请求体（{@code PUT /api/tasks/{id}}，GL-P1-TASK-001 Stage 1）。
 *
 * <p>
 * 仅 draft 态可编辑（controller 守卫）。{@code expectedVersion} 必填（乐观锁：等于客户端读取时的
 * task.version）， 服务端 guarded UPDATE {@code WHERE version=:expected}，冲突 →
 * 409。{@code title} 必填；其余可空字段 null=清空。
 */
public record UpdateTaskRequest(int expectedVersion, String title, String description, String contentForm,
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
	public UpdateTaskRequest(int expectedVersion, String title, String description, String contentForm, String platform,
			Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
			TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents) {
		this(expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents, null, null, null);
	}

	/** 便捷构造：任务书 #75 之前的全量字段签名（无套餐推广）。 */
	public UpdateTaskRequest(int expectedVersion, String title, String description, String contentForm, String platform,
			Integer maxSlots, Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
			TaskRequirements requirements, Integer autoAcceptMinLevel, Long freebieDepositCents, String questionText,
			String questionRef) {
		this(expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents, applicationDeadline,
				minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents, questionText, questionRef,
				null);
	}

	public UpdateTaskRequest {
		TaskQuestion normalizedQuestion = new TaskQuestion(questionText, questionRef);
		questionText = normalizedQuestion.text();
		questionRef = normalizedQuestion.ref();
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
