package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.security.IntelligenceException;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * runtime 控制客户端（任务书 #105C C105C-01 / K07.2 INTERNAL01～04）：create/state/end。
 *
 * <p>
 * C01 通过<b>可替换 transport</b> + fake HTTP server 验证控制协议；mTLS 实际监听与连接由 D02
 * 交付——本阶段默认 transport 为明文 HTTP（仅指向内网 base URL，默认未配置即 fail-closed），不提前打开无认证生产端口。
 * 确定超时：create ≤5s、state ≤3s、end ≤5s；超时调用方只按原键查询 {@link #state}，不构造第二次创建。
 */
@Component
public class DigitalHumanRuntimeClient {

	/** K07.1 RuntimeState wire（无 provider key/正文）。 */
	public record RuntimeState(String sessionId, String workerId, long leaseEpoch, long mediaEpoch, String state,
			String activeTurnId, String leaseExpiresAt, boolean mediaReady, boolean cleanupPending) {
	}

	/** 内部控制协议 transport（INTERNAL01 create / INTERNAL04 state / INTERNAL02 end）。 */
	public interface Transport {

		Mono<RuntimeState> createSession(String sessionId, String backendId, UUID commandId);

		Mono<RuntimeState> state(String sessionId);

		Mono<RuntimeState> end(String sessionId, UUID commandId, String reasonCode);
	}

	/** 统计副作用（IT 断言 runtime create 恰一次）。 */
	public interface Recorder {

		void onCreate(String sessionId);

		void onEnd(String sessionId);
	}

	private final Transport transport;
	private final Recorder recorder;

	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanRuntimeClient(@Value("${dh.runtime.base-url:}") String baseUrl) {
		this(buildDefaultTransport(baseUrl), new Recorder() {
			@Override
			public void onCreate(String sessionId) {
			}

			@Override
			public void onEnd(String sessionId) {
			}
		});
	}

	DigitalHumanRuntimeClient(Transport transport, Recorder recorder) {
		this.transport = transport;
		this.recorder = recorder;
	}

	private static Transport buildDefaultTransport(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			// fail-closed：runtime 未配置（C 阶段默认）→ 明确 503 语义，不静默假成功。
			return new Transport() {
				@Override
				public Mono<RuntimeState> createSession(String sessionId, String backendId, UUID commandId) {
					return unavailable();
				}

				@Override
				public Mono<RuntimeState> state(String sessionId) {
					return unavailable();
				}

				@Override
				public Mono<RuntimeState> end(String sessionId, UUID commandId, String reasonCode) {
					return unavailable();
				}
			};
		}
		WebClient client = WebClient.builder().baseUrl(baseUrl)
				.clientConnector(new ReactorClientHttpConnector(HttpClient.create()
						.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(5))))
				.build();
		return new Transport() {
			@Override
			public Mono<RuntimeState> createSession(String sessionId, String backendId, UUID commandId) {
				return client.post().uri("/internal/v1/sessions")
						.bodyValue(java.util.Map.of("sessionId", sessionId, "backendId", backendId, "commandId",
								commandId.toString(), "payloadHash", "c01"))
						.retrieve().bodyToMono(RuntimeState.class).timeout(Duration.ofSeconds(5));
			}

			@Override
			public Mono<RuntimeState> state(String sessionId) {
				return client.get().uri("/internal/v1/sessions/{id}/state", sessionId).retrieve()
						.bodyToMono(RuntimeState.class).timeout(Duration.ofSeconds(3));
			}

			@Override
			public Mono<RuntimeState> end(String sessionId, UUID commandId, String reasonCode) {
				return client.post().uri("/internal/v1/sessions/{id}/commands", sessionId)
						.bodyValue(java.util.Map.of("commandId", commandId.toString(), "command", "end", "reasonCode",
								reasonCode, "payloadHash", "c01"))
						.retrieve().bodyToMono(RuntimeState.class).timeout(Duration.ofSeconds(5));
			}
		};
	}

	private static Mono<RuntimeState> unavailable() {
		return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
	}

	public Mono<RuntimeState> createSession(String sessionId, String backendId) {
		return Mono.defer(() -> {
			recorder.onCreate(sessionId);
			return transport.createSession(sessionId, backendId, UUID.randomUUID());
		});
	}

	public Mono<RuntimeState> state(String sessionId) {
		return Mono.defer(() -> transport.state(sessionId));
	}

	public Mono<RuntimeState> end(String sessionId, String reasonCode) {
		return Mono.defer(() -> {
			recorder.onEnd(sessionId);
			return transport.end(sessionId, UUID.randomUUID(), reasonCode);
		});
	}
}
