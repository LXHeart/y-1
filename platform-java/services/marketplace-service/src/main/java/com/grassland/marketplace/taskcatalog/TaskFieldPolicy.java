package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * 任务书 #77 卡 B（决策 D2）：platform / storeId / applicationDeadline 三字段必填。
 *
 * <p>
 * 2026-09-05 用户反馈：大厅平台/门店/截止日期空列泛滥。前端表单必填（三模式一致）之外， 五个写入端点（创建 / 草稿 / 更新草稿 /
 * publish / revise）服务端强校验——publish 校验的是 <b>存量草稿落库值</b>，防旧客户端绕过表单。校验风格照
 * {@link CreateTaskRequest} compact 构造器 IAE 先例（{@code MarketplaceErrorHandler}
 * 统一翻 400）。
 *
 * <p>
 * <b>存量任务照常显示（用户拍板）</b>：本策略只挡新建/写入路径，feed 谓词与按组织列表 均不动；存量空值经修订时由表单必填自然补齐。
 */
public final class TaskFieldPolicy {

	/** 平台白名单九值（PRD §2.2，硬编码定死）。抖音 canonical id 是 douyin 不是 tiktok（#57 陷阱）。 */
	public static final Set<String> PLATFORM_WHITELIST = Set.of("xiaohongshu", "douyin", "dianping", "kuaishou",
			"wechat-channels", "bilibili", "wechat-official", "zhihu", "moments");

	private TaskFieldPolicy() {
	}

	/**
	 * 创建 / 草稿 / publish（存量任务校验）全量口径：三字段都不可空。 知乎问题任务 platform=zhihu
	 * 与白名单兼容（TaskController.enforceQuestionPlatform 特判不破坏）。
	 */
	public static void validateRequired(String platform, String storeId, Instant applicationDeadline) {
		validatePlatformAndDeadline(platform, applicationDeadline);
		if (storeId == null || storeId.isBlank()) {
			throw new IllegalArgumentException("storeId is required");
		}
	}

	/** 更新草稿 / 修订口径：请求体不含 storeId（门店挂靠创建时定死），只校验平台与截止。 */
	public static void validatePlatformAndDeadline(String platform, Instant applicationDeadline) {
		validatePlatform(platform);
		if (applicationDeadline == null) {
			throw new IllegalArgumentException("applicationDeadline is required");
		}
		if (!applicationDeadline.isAfter(Instant.now())) {
			throw new IllegalArgumentException("applicationDeadline must be in the future");
		}
	}

	private static void validatePlatform(String platform) {
		String normalized = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
		if (!PLATFORM_WHITELIST.contains(normalized)) {
			throw new IllegalArgumentException(
					"platform 必须是九平台之一：xiaohongshu / douyin / dianping / kuaishou / wechat-channels"
							+ " / bilibili / wechat-official / zhihu / moments");
		}
	}
}
