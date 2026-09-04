package com.grassland.trust.adjudication;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审判 adjudication 可配策略（草场 Epic 6 Slice 6C / HLD §5.5、§9.3、§10.5）。
 *
 * <p>
 * HLD 把「完整审判」列为 90 天非目标、客服手动裁决为生产兜底（§15.2），D-06/D-10 为 DECISION REQUIRED。 以下阈值是
 * <b>sandbox/dev 默认</b>，逐条标注 provisional：待 HLD 终审后定稿。
 *
 * <ul>
 * <li>{@code panelSize} — 审判面板人数（默认 7；7 官多数决需 ≥4 同方）。</li>
 * <li>{@code voteWindowHours} — 每轮投票窗口（默认 24h；dev/test 经 env 缩短）。</li>
 * <li>{@code maxRounds} — 平票重开上限（默认 2；超限转客服兜底）。</li>
 * <li>{@code appealWindowHours} — 判决后上诉窗口（默认 48h；过期平淡 → 终局）。</li>
 * <li>{@code judgeEligibilityTier} — 审判官资格等级阈值（GL-P2-TRUST-001：生产默认 5，仅
 * Lv5）。</li>
 * <li>{@code adjudicationWindowHours} — 争议开启后需等待的小时数才可启动审判（GL-P2-TRUST-001；默认
 * 48h）。</li>
 * <li>{@code disputeCooldownHours} — 争议终局后冷却期，防止恶意重复开争议（GL-P2-TRUST-001；默认
 * 168h=7天）。</li>
 * <li>{@code disputeOpenWindowHours} — 异议窗口（PRD §7.1：核实结果公布后 48h 内须提出异议；任务书 #70
 * 卡B；默认 48h，0=禁用）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "trust.adjudication")
public record AdjudicationProperties(int panelSize, int voteWindowHours, int maxRounds, int appealWindowHours,
		int judgeEligibilityTier, int adjudicationWindowHours, int adjudicationWindowEnabled, int disputeCooldownHours,
		int csAwaitHours, int csPollSeconds,
		/**
		 * 投票窗口秒级覆盖（>0 时优先于 {@code voteWindowHours}）。dev/e2e 用，见
		 * {@link #voteWindowSecondsEffective()}。
		 */
		long voteWindowSeconds,
		/** 上诉窗口秒级覆盖（>0 时优先于 {@code appealWindowHours}）。 */
		long appealWindowSeconds,
		/** 审判启动窗口秒级覆盖（>0 时优先于 {@code adjudicationWindowHours}）。dev/e2e 用。 */
		long adjudicationWindowSeconds,
		/**
		 * 争议冷却期秒级覆盖（>0 时优先于 {@code disputeCooldownHours}）。dev/e2e 用（T5 恢复
		 * DisputeCooldownIT）。
		 */
		long disputeCooldownSeconds,
		/** 任务书 #31 / ADR-D15：每张计入裁决的投票发放的 AI 积分（平坦，默认 20；0=关闭发奖）。 */
		int judgeRewardCreditsPerVote,
		/** ADR-D18：每票现金佣金（分，平坦；默认 0=关闭）。内部闭环入推荐官钱包，真实外币化受 D-01 门禁。 */
		int judgeCommissionCentsPerVote,
		/**
		 * 任务书 #70 卡B：异议窗口小时数（PRD §7.1——异议须在核实结果公布后 48 小时内提出；默认 48h）。 0=禁用哨兵（测试环境，照
		 * {@code disputeCooldownHours}=0 惯例），负数/缺失归默认 48。
		 */
		int disputeOpenWindowHours, /** 异议窗口秒级覆盖（&gt;0 时优先于 {@code disputeOpenWindowHours}）。dev/e2e 用。 */
		long disputeOpenWindowSeconds,
		/**
		 * 任务书 #74 卡 A：客服直裁 SLA 小时数（D6：5 天内处理完成；默认 120）。
		 * 照 {@code disputeCooldownHours} 惯例 0 = 禁用哨兵（不落 cs_due_at、不启 SLA workflow，测试用），负数归默认。
		 */
		int csDirectSlaHours,
		/** 客服直裁 SLA 秒级覆盖（&gt;0 优先于小时值），dev/e2e 用。 */
		long csDirectSlaSeconds,
		/**
		 * 任务书 #74 卡 B：质证窗小时数（D1：48h，复用原「开庭等待窗」时隙；默认 48）。
		 * 0 = 禁用哨兵（开争议 workflow 直接进投票，测试用），负数归默认。
		 */
		int evidenceWindowHours,
		/** 质证窗秒级覆盖（&gt;0 优先于小时值），dev/e2e 用。 */
		long evidenceWindowSeconds,
		/** 任务书 #74 卡 D（D3）：涉案平台熟手硬配额席位数（默认 4=4/7）。 */
		int platformQuota,
		/** 卡 D：熟手门槛=该平台完成履约数下限（默认 3）。 */
		int platformCompletionsMin,
		/** 任务书 #74 卡 E（D4）：每面板见习审判官席位上限（默认 2；池尽容忍超出并计数）。 */
		int probationSeatsPerPanel,
		/** 卡 E：见习转正所需去重投票轮数（默认 10）。 */
		int probationPromoteRounds) {

	public AdjudicationProperties {
		if (panelSize <= 0) {
			panelSize = 7;
		}
		if (voteWindowHours <= 0) {
			voteWindowHours = 24;
		}
		if (maxRounds <= 0) {
			maxRounds = 2;
		}
		if (appealWindowHours <= 0) {
			appealWindowHours = 48;
		}
		if (judgeEligibilityTier <= 0) {
			judgeEligibilityTier = 5;
		}
		if (adjudicationWindowHours <= 0) {
			adjudicationWindowHours = 48; // GL-P2-TRUST-001：争议开启后需等待 48h 才可启动审判
		}
		// 冷却期：0=禁用（测试环境），负数或缺失=默认 7 天
		if (disputeCooldownHours < 0) {
			disputeCooldownHours = 168; // GL-P2-TRUST-001：争议终局后冷却期，默认 7 天
		}
		// 保留 0 作为禁用标志（测试环境用），不设默认值
		if (csAwaitHours <= 0) {
			csAwaitHours = 168; // 客服终审最长等待（7 天，dev/test 经 env 缩短）
		}
		if (csPollSeconds <= 0) {
			csPollSeconds = 60;
		}
		// 秒级覆盖不设默认：0/负 = 未覆盖，回落小时换算（见下方 *Effective 方法）
		if (voteWindowSeconds < 0) {
			voteWindowSeconds = 0;
		}
		if (appealWindowSeconds < 0) {
			appealWindowSeconds = 0;
		}
		if (adjudicationWindowSeconds < 0) {
			adjudicationWindowSeconds = 0;
		}
		if (disputeCooldownSeconds < 0) {
			disputeCooldownSeconds = 0;
		}
		// adjudicationWindowEnabled: 1=启用（默认），0=禁用（测试环境跳过校验）。
		// GL-P2-TRUST-001 T4：非 0/1 的非法值归一为 1（启用）。注意——0 是合法的"禁用"哨兵，
		// 故"生产默认启用"由 application.yml 的 adjudication-window-enabled:1 兜底，本守卫只防显式垃圾值。
		if (adjudicationWindowEnabled != 0 && adjudicationWindowEnabled != 1) {
			adjudicationWindowEnabled = 1;
		}
		// ADR-D15：奖励积分为 0/负 = 关闭发奖（不发事件）；默认 20。
		if (judgeRewardCreditsPerVote < 0) {
			judgeRewardCreditsPerVote = 0;
		}
		// ADR-D18：现金佣金 0/负 = 关闭（哨兵语义与积分一致）。
		if (judgeCommissionCentsPerVote < 0) {
			judgeCommissionCentsPerVote = 0;
		}
		// 任务书 #70 卡B：异议窗口负数/缺失 = 默认 48h；0 是合法的"禁用"哨兵（测试环境），不归默认。
		if (disputeOpenWindowHours < 0) {
			disputeOpenWindowHours = 48;
		}
		if (disputeOpenWindowSeconds < 0) {
			disputeOpenWindowSeconds = 0;
		}
		// 任务书 #74 卡 A：客服直裁 SLA。负数/缺失 = 默认 120h（5 天）；0 = 禁用哨兵（测试环境）。
		if (csDirectSlaHours < 0) {
			csDirectSlaHours = 120;
		}
		if (csDirectSlaSeconds < 0) {
			csDirectSlaSeconds = 0;
		}
		// 任务书 #74 卡 B：质证窗。负数/缺失 = 默认 48h；0 = 禁用哨兵（测试环境）。
		if (evidenceWindowHours < 0) {
			evidenceWindowHours = 48;
		}
		if (evidenceWindowSeconds < 0) {
			evidenceWindowSeconds = 0;
		}
		// 任务书 #74 卡 D：垂类硬配额（D3 ≥4/7）与熟手门槛（≥3 单）。
		if (platformQuota <= 0) {
			platformQuota = 4;
		}
		if (platformCompletionsMin <= 0) {
			platformCompletionsMin = 3;
		}
		// 任务书 #74 卡 E：见习席位上限（D4 ≤2/面板）与转正轮数（10 轮）。
		if (probationSeatsPerPanel <= 0) {
			probationSeatsPerPanel = 2;
		}
		if (probationPromoteRounds <= 0) {
			probationPromoteRounds = 10;
		}
	}

	/**
	 * 投票窗口实际秒数：秒级覆盖优先，否则小时换算。
	 *
	 * <p>
	 * 窗口原本只有小时粒度，最小非零值 1 小时——dev/e2e 无法验证「窗口到期自动 tally → decided → 上诉窗口 →
	 * 终局」这条<b>时间驱动主链路</b>。秒级覆盖对齐 marketplace 的 {@code SETTLEMENT_WINDOW_SECONDS}
	 * 既有约定（dev 默认 5s）。生产不设该项即用小时值。
	 */
	public long voteWindowSecondsEffective() {
		return voteWindowSeconds > 0 ? voteWindowSeconds : Math.max(0, voteWindowHours) * 3600L;
	}

	/** 上诉窗口实际秒数：秒级覆盖优先，否则小时换算。 */
	public long appealWindowSecondsEffective() {
		return appealWindowSeconds > 0 ? appealWindowSeconds : Math.max(0, appealWindowHours) * 3600L;
	}

	/**
	 * 审判启动窗口实际秒数（GL-P2-TRUST-001）。
	 *
	 * <p>
	 * 争议开启后需等待此时间才可启动审判，让双方有时间自行解决/补充证据。 秒级覆盖优先，否则小时换算（默认 48h）。
	 */
	public long adjudicationWindowSecondsEffective() {
		return adjudicationWindowSeconds > 0 ? adjudicationWindowSeconds : Math.max(0, adjudicationWindowHours) * 3600L;
	}

	/**
	 * 争议冷却期实际秒数（GL-P2-TRUST-001）。
	 *
	 * <p>
	 * 争议终局后需等待此时间才可再次开争议，防止恶意重复开争议。秒级覆盖优先，否则小时换算（默认 7 天=168 小时）。
	 */
	public long disputeCooldownSecondsEffective() {
		return disputeCooldownSeconds > 0 ? disputeCooldownSeconds : Math.max(0, disputeCooldownHours) * 3600L;
	}

	/**
	 * 异议窗口实际秒数（任务书 #70 卡B，PRD §7.1）。核实结果公布（resultAnchorAt）后超过此窗口
	 * 不得再创建新争议；0=禁用。秒级覆盖优先，否则小时换算（默认 48h）。
	 */
	public long disputeOpenWindowSecondsEffective() {
		return disputeOpenWindowSeconds > 0 ? disputeOpenWindowSeconds : Math.max(0, disputeOpenWindowHours) * 3600L;
	}

	/**
	 * 任务书 #74 卡 A：客服直裁 SLA 实际秒数。秒级覆盖优先；0（禁用哨兵）→ 0（不落 cs_due_at、不启 SLA workflow）。
	 */
	public long csDirectSlaSecondsEffective() {
		if (csDirectSlaSeconds > 0) {
			return csDirectSlaSeconds;
		}
		return csDirectSlaHours > 0 ? csDirectSlaHours * 3600L : 0;
	}

	/** 任务书 #74 卡 B：质证窗实际秒数。秒级覆盖优先；0（禁用哨兵）→ 0（workflow 跳过质证段直接开庭）。 */
	public long evidenceWindowSecondsEffective() {
		if (evidenceWindowSeconds > 0) {
			return evidenceWindowSeconds;
		}
		return evidenceWindowHours > 0 ? evidenceWindowHours * 3600L : 0;
	}
}
