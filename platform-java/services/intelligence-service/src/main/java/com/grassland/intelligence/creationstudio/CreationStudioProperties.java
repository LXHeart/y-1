package com.grassland.intelligence.creationstudio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务书 #101 §7.6：创作工作台（studio）服务端开关。默认全部关闭；写入开关关闭时 读取既有记录仍允许，回退顺序为「关写 → 排空 → 停
 * worker → 关 Edge」。
 *
 * <p>
 * 公众号渠道开关（{@code creation.wechat.*}）由 C101-19 的 WechatProperties 绑定， 本类不预占。
 */
@Component
@ConfigurationProperties(prefix = "creation.studio")
public class CreationStudioProperties {

	/** 拒绝新 source／建议／plan／job／采用／新格式导出写入；读取既有记录仍允许。 */
	private boolean writesEnabled = false;

	/** 视觉 worker：false 不领新视觉 item；已有网络请求允许持久化与结算收尾。 */
	private boolean visualWorkerEnabled = false;

	public boolean isWritesEnabled() {
		return writesEnabled;
	}

	public void setWritesEnabled(boolean writesEnabled) {
		this.writesEnabled = writesEnabled;
	}

	public boolean isVisualWorkerEnabled() {
		return visualWorkerEnabled;
	}

	public void setVisualWorkerEnabled(boolean visualWorkerEnabled) {
		this.visualWorkerEnabled = visualWorkerEnabled;
	}
}
