package com.grassland.intelligence.creationstudio.wechat;

import java.util.Map;

/** 统一成功信封（渠道包内小工具，避免依赖 creationassistant 的私有 helper）。 */
final class CreationWechatBodies {

	private CreationWechatBodies() {
	}

	static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
