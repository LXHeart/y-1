package com.grassland.intelligence.hypit.client;

import com.grassland.intelligence.hypit.config.HypitProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Hypit sidecar 内部客户端（任务书 #107-1 C107-03 / K04）。
 *
 * <p>
 * 固定 baseURL + 内部 Bearer token + 30s 响应超时；错误结构化映射，绝不从响应体里读新的目标地址。 凭据/token
 * 不进日志（仅记录状态码与 commandId）。
 */
@Component
@EnableConfigurationProperties(HypitProperties.class)
public class HypitSidecarClient {

	private final HypitProperties properties;
	private final WebClient webClient;

	public HypitSidecarClient(HypitProperties properties) {
		this.properties = properties;
		// 本服务无 WebClient.Builder bean（全仓惯例：各客户端自建），固定 baseURL。
		this.webClient = WebClient.builder().baseUrl(properties.sidecarBaseUrl()).build();
	}

	public record SidecarHealth(boolean ok, String enginePort, String distributionRoot) {
	}

	public record SidecarCommand(String commandId, String kind, String state, Object result,
			Map<String, Object> error) {
	}

	public boolean configured() {
		return properties.internalToken().length() >= 32;
	}

	/** health 探测不需要 token；引擎未部署返回 false 而非抛错（capabilities 显示 disabled）。 */
	public boolean health() {
		try {
			SidecarHealth health = webClient.get().uri("/healthz").accept(MediaType.APPLICATION_JSON).retrieve()
					.bodyToMono(SidecarHealth.class).block(Duration.ofSeconds(5));
			return health != null && health.ok();
		} catch (Exception error) {
			return false;
		}
	}

	/** 内部命令派发；未配置 token 或 sidecar 不可达时如实失败（不伪造结果）。 */
	public SidecarCommand command(String commandId, String kind, Map<String, Object> payload) {
		if (!configured()) {
			throw new IllegalStateException("hypit internal token is not configured");
		}
		try {
			return webClient.post().uri("/internal/v1/commands")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.internalToken())
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("commandId", commandId, "kind", kind, "payload", payload)).retrieve()
					.bodyToMono(SidecarCommand.class).block(Duration.ofSeconds(60));
		} catch (WebClientResponseException error) {
			throw new IllegalStateException(
					"hypit sidecar rejected command " + commandId + " with status " + error.getStatusCode().value(),
					error);
		} catch (Exception error) {
			throw new IllegalStateException("hypit sidecar unreachable for command " + commandId, error);
		}
	}

	/**
	 * 反应式派发：command() 内部 block()，绝不可在事件循环线程执行；统一挪到 boundedElastic。
	 */
	public Mono<SidecarCommand> commandAsync(String commandId, String kind, Map<String, Object> payload) {
		return Mono.fromCallable(() -> command(commandId, kind, payload))
				.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
	}
}
