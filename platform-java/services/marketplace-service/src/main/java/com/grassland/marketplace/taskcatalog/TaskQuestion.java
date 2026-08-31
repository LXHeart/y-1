package com.grassland.marketplace.taskcatalog;

/**
 * 任务目标问题（任务书 #62 P4 拍板）。填写则该任务交付**知乎回答**形态：问题随 {@code task_version}
 * 冻结进创作上下文，创作端据此锁定回答模式。
 *
 * <p>
 * {@code text} = 问题原文（纯手输）；{@code ref} = 从粘贴链接本地正则提取的 questionId，
 * <b>仅溯源存档</b>——服务端<b>不对知乎发起任何请求</b>（#62 §3.7：抓取实测全 403， 逆向签名/无头浏览器/存用户 cookie
 * 全部否决，SSRF 面零新增）。
 *
 * <p>
 * 两字段都可空：{@link #none()} 表示「非回答形态任务」，与 null 等价。
 *
 * @param text
 *            问题原文，可空
 * @param ref
 *            questionId（纯数字串），可空
 */
public record TaskQuestion(String text, String ref) {

	/** 问题原文上限（与 intelligence 侧 question 入参同口径）。 */
	public static final int MAX_TEXT_LENGTH = 500;

	/** questionId 上限（纯数字，实际 19 位左右；留余量并拒绝异常长输入）。 */
	public static final int MAX_REF_LENGTH = 64;

	private static final TaskQuestion NONE = new TaskQuestion(null, null);

	public TaskQuestion {
		text = trimToNull(text);
		ref = trimToNull(ref);
		if (text != null && text.length() > MAX_TEXT_LENGTH) {
			throw new IllegalArgumentException("目标问题过长（上限 " + MAX_TEXT_LENGTH + " 字）");
		}
		if (ref != null && ref.length() > MAX_REF_LENGTH) {
			throw new IllegalArgumentException("目标问题引用过长");
		}
		if (ref != null && !ref.chars().allMatch(Character::isDigit)) {
			throw new IllegalArgumentException("目标问题引用必须是知乎 questionId（纯数字）");
		}
	}

	/** 无目标问题（非回答形态）。 */
	public static TaskQuestion none() {
		return NONE;
	}

	/** null 归一为 {@link #none()}，供仓储/控制器统一处理。 */
	public static TaskQuestion orNone(TaskQuestion question) {
		return question == null ? NONE : question;
	}

	/**
	 * 是否携带目标问题。<b>只认 text</b>——单独一个 ref 没有问题原文无法生成回答， 不构成「回答形态任务」（判据不留模糊回退，#62 全局约束
	 * 2 同姿态）。
	 */
	public boolean present() {
		return text != null;
	}

	private static String trimToNull(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
