package com.grassland.identity.recommenderprofile;

/**
 * 推荐官自报的社交账号（PRD 六「社交平台」）。
 *
 * <p>
 * {@code followers} 是**自报**粉丝量，本轮不做平台核验——真核验要接各平台开放数据， 属 PRD
 * 九「自动核实引擎」的范畴。展示时须让商家知道这是自报值，不能当成平台数据。
 *
 * <p>
 * 任务书 #98 D98-03：{@code source}/{@code collectedAt} 标注数据性质与采集时点。
 * {@code verified/platform_fact} 仅枚举预留——无平台授权接入，<b>任何路径不得产出这两种值</b> （构造器对非
 * self_reported 值一律拒绝，含客户端提交）。
 */
public record SocialAccount(String platform, String handle, Long followers, String source,
		java.time.Instant collectedAt) {

	public static final String SOURCE_SELF_REPORTED = "self_reported";

	public SocialAccount {
		if (platform == null || platform.isBlank()) {
			throw new IllegalArgumentException("platform is required");
		}
		platform = platform.trim();
		if (handle != null) {
			handle = handle.isBlank() ? null : handle.trim();
		}
		if (followers != null && followers < 0) {
			throw new IllegalArgumentException("followers must be >= 0");
		}
		if (source != null && !SOURCE_SELF_REPORTED.equals(source)) {
			throw new IllegalArgumentException("source 仅支持 self_reported（平台核验未接入，不虚标数据来源）");
		}
	}

	/** 兼容缺省：存储缺 source 的旧形态读出后按自报解释（V50 已回填，此为双保险）。 */
	public String effectiveSource() {
		return source == null ? SOURCE_SELF_REPORTED : source;
	}
}
