package com.grassland.intelligence.hypit.client;

import com.grassland.intelligence.hypit.config.HypitProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	// C107F2-38（round-9 实录）：dispatch 失败的真实根因（连接超时/解析失败/解码
	// 异常等）此前只拼进 503 message、不落日志——「broker 活着却 unreachable」
	// 排障只能靠 SIGQUIT 线程转储。WARN 落全异常类型+cause 链，凭日志即可定性。
	private static final Logger logger = LoggerFactory.getLogger(HypitSidecarClient.class);

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

	/**
	 * 反应式 health 探测（C107F-04）：doctor 等响应式链内禁止 block（Reactor 会拒绝事件循环线程
	 * block，异常被吞后伪装成 health=false）。语义同 {@link #health()}：不可达如实 false。
	 */
	public Mono<Boolean> healthAsync() {
		return webClient.get().uri("/healthz").accept(MediaType.APPLICATION_JSON).retrieve()
				.bodyToMono(SidecarHealth.class).map(healthy -> healthy != null && healthy.ok())
				.timeout(Duration.ofSeconds(5)).onErrorResume(error -> Mono.just(false));
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
			logger.warn("hypit sidecar rejected command {} kind={} status={} body={}", commandId, kind,
					error.getStatusCode().value(), error.getResponseBodyAsString(), error);
			// C107F2-32（§6.15）：维护窗 503 是「暂缓」不是「失败」——结构化码让调用方
			// 保留可重试状态（worker 下一轮续跑），绝不伪装成本地引擎错误。
			if (error.getStatusCode().value() == 503
					&& String.valueOf(error.getResponseBodyAsString()).contains("maintenance mode")) {
				throw new com.grassland.intelligence.security.IntelligenceException(503, "hypit_maintenance",
						"broker 维护窗口中：新副作用暂缓，维护结束后自动续跑。");
			}
			throw new IllegalStateException(
					"hypit sidecar rejected command " + commandId + " with status " + error.getStatusCode().value(),
					error);
		} catch (Exception error) {
			// C107F2-38（round-9 实录）：dispatch 失败的真实根因（连接超时/解析失败/
			// 解码异常等）此前只拼进 503 message、不落日志——「broker 活着却报
			// unreachable」排障只能靠 SIGQUIT 线程转储。WARN 落根因类型与完整栈。
			Throwable root = error;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			logger.warn("hypit sidecar dispatch failed command={} kind={} target={} root={}: {}", commandId, kind,
					properties.sidecarBaseUrl(), root.getClass().getName(), String.valueOf(root.getMessage()), error);
			throw new HypitSidecarUnreachableException("hypit sidecar unreachable for command " + commandId, error);
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
