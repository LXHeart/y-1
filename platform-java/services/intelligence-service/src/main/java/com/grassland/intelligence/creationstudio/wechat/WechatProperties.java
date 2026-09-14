package com.grassland.intelligence.creationstudio.wechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务书 #101 C101-19：公众号渠道配置。 只绑定 C101-01 已登记的服务端开关；不新增可自定义 host—— 微信 API
 * 域名固定（api.weixin.qq.com），测试经 WireMock 以 @DynamicPropertySource 覆盖 baseUrl。
 */
@Component
public class WechatProperties {

	private final boolean writesEnabled;
	private final boolean workerEnabled;

	public WechatProperties(@Value("${creation.wechat.writes-enabled:false}") boolean writesEnabled,
			@Value("${creation.wechat.worker-enabled:false}") boolean workerEnabled) {
		this.writesEnabled = writesEnabled;
		this.workerEnabled = workerEnabled;
	}

	public boolean isWritesEnabled() {
		return writesEnabled;
	}

	public boolean isWorkerEnabled() {
		return workerEnabled;
	}

}
