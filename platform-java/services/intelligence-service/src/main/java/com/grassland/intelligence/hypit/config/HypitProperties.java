package com.grassland.intelligence.hypit.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hypit 引擎接入配置（任务书 #107-1 C107-03）。默认整体关闭（K12.5：开发默认
 * HYPIT_ENABLED=false）；sidecar 内部 token 只经服务端配置注入，不回显。
 */
@ConfigurationProperties(prefix = "hypit")
public record HypitProperties(boolean enabled, String sidecarBaseUrl, String internalToken, String operatorAccountIds) {

	public HypitProperties {
		sidecarBaseUrl = sidecarBaseUrl == null || sidecarBaseUrl.isBlank() ? "http://127.0.0.1:9240" : sidecarBaseUrl;
		internalToken = internalToken == null ? "" : internalToken;
		operatorAccountIds = operatorAccountIds == null ? "" : operatorAccountIds;
	}

	/** HYPIT_OPERATOR_ACCOUNT_IDS：逗号分隔的部署管理账号；空列表=无人可写运行时配置。 */
	public List<String> operators() {
		return operatorAccountIds.lines().flatMap(line -> java.util.Arrays.stream(line.split(","))).map(String::trim)
				.filter(id -> !id.isEmpty()).toList();
	}
}
