package com.grassland.intelligence.videoproduction;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次视频/配音 provider 调用的连接参数（任务书 #64 卡2）。
 *
 * <p>
 * 控制面解析（platform_model_config + platform_provider_credential）后由
 * {@code VideoGenerationProviderResolver} 构造——vendor adapter 不再读全局
 * {@code VideoGenerationProperties} 的 env 型字段，改为按解析结果实例化的纯对象。 {@code apiKey}
 * 是解密后的明文，只活在进程内调用链，绝不入日志/响应/落库。
 */
public record VideoProviderEndpoint(String baseUrl, String apiKey, String createPath, String pollPath,
		String retrievePath, Duration requestTimeout) {

	public VideoProviderEndpoint {
		Objects.requireNonNull(baseUrl, "baseUrl");
		Objects.requireNonNull(createPath, "createPath");
		Objects.requireNonNull(pollPath, "pollPath");
		requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
	}

	/** MiniMax 异步任务默认路径（模型 id 之外的协议常量，控制面不存路径）。 */
	public static VideoProviderEndpoint minimax(String baseUrl, String apiKey, Duration timeout) {
		return new VideoProviderEndpoint(baseUrl, apiKey, "/v1/video_generation", "/v1/query/video_generation",
				"/v1/files/retrieve", timeout);
	}

	/** Volcengine Ark/Seedance 默认路径；retrievePath 无用占位（该协议无文件取回步）。 */
	public static VideoProviderEndpoint seedance(String baseUrl, String apiKey, Duration timeout) {
		return new VideoProviderEndpoint(baseUrl, apiKey, "/api/v3/contents/generations/tasks",
				"/api/v3/contents/generations/tasks/{taskId}", "/api/v3/contents/generations/tasks/{taskId}", timeout);
	}

	/** xAI Grok Imagine 默认路径（docs.x.ai /v1/videos）；retrievePath 无用占位（无文件取回步）。 */
	public static VideoProviderEndpoint xai(String baseUrl, String apiKey, Duration timeout) {
		return new VideoProviderEndpoint(baseUrl, apiKey, "/v1/videos/generations", "/v1/videos/{taskId}",
				"/v1/videos/{taskId}", timeout);
	}

	/** 覆盖路径（WireMock IT 用）：null/空白段回落默认值。 */
	public VideoProviderEndpoint withPathOverrides(String createPath, String pollPath, String retrievePath) {
		return new VideoProviderEndpoint(baseUrl, apiKey, overrideOrDefault(createPath, this.createPath),
				overrideOrDefault(pollPath, this.pollPath), overrideOrDefault(retrievePath, this.retrievePath),
				requestTimeout);
	}

	private static String overrideOrDefault(String override, String fallback) {
		return override == null || override.isBlank() ? fallback : override;
	}
}
