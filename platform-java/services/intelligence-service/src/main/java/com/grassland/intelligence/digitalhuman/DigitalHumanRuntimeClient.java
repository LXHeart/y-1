package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.security.IntelligenceException;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * runtime 控制客户端（任务书 #105C C105C-01 / K07.2 INTERNAL01～04）：create/state/end +
 * offer。
 *
 * <p>
 * C01 通过<b>可替换 transport</b> + fake HTTP server 验证控制协议；mTLS 实际监听与连接由 D02
 * 交付——本阶段默认 transport 为明文 HTTP（仅指向内网 base URL，默认未配置即 fail-closed），不提前打开无认证生产端口。
 * 确定超时：create ≤5s、state ≤3s、end ≤5s；超时调用方只按原键查询 {@link #state}，不构造第二次创建。
 *
 * <p>
 * C105X-03（任务书 #105fix-1）：INTERNAL03 webrtc-offer 首次接通——{@link #webrtcOffer} 中继
 * （payloadHash 与 runtime 端 routes_internal 同款 canonical JSON SHA-256）；base-url
 * 为 https 且配齐 TLS 材料时 default transport 走 mTLS（客户端证书 intelligence-service + 服务端
 * SAN 校验）。
 */
@Component
public class DigitalHumanRuntimeClient {

	/** K07.1 RuntimeState wire（无 provider key/正文）。 */
	public record RuntimeState(String sessionId, String workerId, long leaseEpoch, long mediaEpoch, String state,
			String activeTurnId, String leaseExpiresAt, boolean mediaReady, boolean cleanupPending) {
	}

	/** K07.2 INTERNAL03 answer wire。 */
	public record RtcAnswer(String sdp, String type, long mediaEpoch) {
	}

	/**
	 * INTERNAL01 binding（契约 SessionBindingWire 的 Java 形态）：create 时会话行的权威值由
	 * SessionService 传入；bridgeBaseUrl 是 runtime→Java 桥的固定 authority（部署属性，缺省同
	 * bridge.py 的 authority 常量）。
	 */
	public record SessionBinding(String sessionId, String backendId, long leaseEpoch, long mediaEpoch,
			long profileRevision, java.time.Instant expiresAt, java.time.Instant leaseExpiresAt, long contentEpoch,
			String bridgeBaseUrl) {
	}

	/**
	 * 内部控制协议 transport（INTERNAL01 create / INTERNAL04 state / INTERNAL02 end /
	 * INTERNAL03 offer）。
	 */
	public interface Transport {

		/**
		 * INTERNAL01 契约权威形态：binding 全字段 wire（SessionBindingWire）。default 桥接旧 String
		 * 形态（既有 fake 不感知 binding 字段；真实 HTTP transport 必须覆写本方法发全字段 wire）。
		 */
		default Mono<RuntimeState> createSession(SessionBinding binding, UUID commandId) {
			return createSession(binding.sessionId(), binding.backendId(), commandId);
		}

		/** 既有 fake 兼容形态（不承载 INTERNAL01 wire 契约）。 */
		Mono<RuntimeState> createSession(String sessionId, String backendId, UUID commandId);

		Mono<RuntimeState> state(String sessionId);

		Mono<RuntimeState> end(String sessionId, UUID commandId, String reasonCode);

		/**
		 * INTERNAL03 webrtc-offer 中继。default 实现 fail-closed（503）——既有 fake 实现不感知 offer
		 * 时的既有语义保持；接入方（fake IT / default transport）按需覆写。
		 */
		default Mono<RtcAnswer> webrtcOffer(String sessionId, UUID commandId, String payloadHash, long leaseEpoch,
				long mediaEpoch, String sdp) {
			return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
		}
	}

	/** 统计副作用（IT 断言 runtime create 恰一次）。 */
	public interface Recorder {

		void onCreate(String sessionId);

		void onEnd(String sessionId);
	}

	private final Transport transport;
	private final Recorder recorder;

	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanRuntimeClient(@Value("${dh.runtime.base-url:}") String baseUrl,
			@Value("${dh.runtime.tls.ca-file:}") String caFile, @Value("${dh.runtime.tls.cert-file:}") String certFile,
			@Value("${dh.runtime.tls.key-file:}") String keyFile,
			@Value("${dh.runtime.bridge-base-url:https://intelligence-service:9143}") String bridgeBaseUrl) {
		this(buildDefaultTransport(baseUrl, caFile, certFile, keyFile, bridgeBaseUrl), new Recorder() {
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

	private static Transport buildDefaultTransport(String baseUrl, String caFile, String certFile, String keyFile,
			String bridgeBaseUrl) {
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

				@Override
				public Mono<RtcAnswer> webrtcOffer(String sessionId, UUID commandId, String payloadHash,
						long leaseEpoch, long mediaEpoch, String sdp) {
					return unavailableAnswer();
				}
			};
		}
		HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
				.responseTimeout(Duration.ofSeconds(5));
		if (baseUrl.startsWith("https://")) {
			// K13.2：internal 控制面强制 mTLS——https base-url 必须配齐三件材料（缺即启动失败，
			// 不静默降级明文）。服务端证书 SAN 由 Netty endpoint 校验（dh-runtime 服务名比对）。
			if (caFile.isBlank() || certFile.isBlank() || keyFile.isBlank()) {
				throw new IllegalStateException(
						"dh.runtime.base-url 为 https 但 TLS 材料不全（ca/cert/key 均须配置，K13.2 fail-fast）。");
			}
			try {
				SslContext ssl = SslContextBuilder.forClient().keyManager(new File(certFile), new File(keyFile))
						.trustManager(new File(caFile)).build();
				reactor.netty.tcp.SslProvider tls = reactor.netty.tcp.SslProvider.builder().sslContext(ssl)
						.handlerConfigurator(handler -> {
							var engine = handler.engine();
							var params = engine.getSSLParameters();
							params.setEndpointIdentificationAlgorithm("HTTPS");
							engine.setSSLParameters(params);
						}).build();
				httpClient = httpClient.secure(tls);
			} catch (Exception failure) {
				throw new IllegalStateException("dh runtime mTLS 材料装配失败", failure);
			}
		}
		WebClient client = WebClient.builder().baseUrl(baseUrl)
				.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
		return new Transport() {
			@Override
			public Mono<RuntimeState> createSession(String sessionId, String backendId, UUID commandId) {
				// 兼容形态： facade 已不使用；以中性兜底值桥接到契约形态（不产生另一套 wire）。
				java.time.Instant fallback = java.time.Instant.now().plusSeconds(1800);
				return createSession(new SessionBinding(sessionId, backendId, 1, 1, 0, fallback, fallback, 0, null),
						commandId);
			}

			@Override
			public Mono<RuntimeState> createSession(SessionBinding binding, UUID commandId) {
				// INTERNAL01（契约 InternalCreateSessionRequest）：binding 对象 + commandId +
				// payloadHash
				// （去 payloadHash 后 canonical JSON SHA-256）。H 时代控制面恒 503 未暴露的接线，本卡首次走到。
				java.util.TreeMap<String, Object> wireBinding = new java.util.TreeMap<>();
				wireBinding.put("sessionId", binding.sessionId());
				wireBinding.put("leaseEpoch", binding.leaseEpoch());
				wireBinding.put("mediaEpoch", binding.mediaEpoch());
				wireBinding.put("backendId", binding.backendId());
				wireBinding.put("profileRevision", binding.profileRevision());
				wireBinding.put("expiresAt", binding.expiresAt().toString());
				wireBinding.put("leaseExpiresAt", binding.leaseExpiresAt().toString());
				wireBinding.put("contentEpoch", binding.contentEpoch());
				wireBinding.put("bridgeBaseUrl",
						binding.bridgeBaseUrl() == null ? bridgeBaseUrl : binding.bridgeBaseUrl());
				java.util.TreeMap<String, Object> wire = new java.util.TreeMap<>();
				wire.put("binding", wireBinding);
				wire.put("commandId", commandId.toString());
				return client.post().uri("/internal/v1/sessions")
						.bodyValue(java.util.Map.of("binding", wireBinding, "commandId", commandId.toString(),
								"payloadHash", canonicalHash(wire)))
						.retrieve().bodyToMono(RuntimeState.class).onErrorMap(DigitalHumanRuntimeClient::upstream)
						.timeout(Duration.ofSeconds(5));
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
						.retrieve().bodyToMono(RuntimeState.class).onErrorMap(DigitalHumanRuntimeClient::upstream)
						.timeout(Duration.ofSeconds(5));
			}

			@Override
			public Mono<RtcAnswer> webrtcOffer(String sessionId, UUID commandId, String payloadHash, long leaseEpoch,
					long mediaEpoch, String sdp) {
				return client.post().uri("/internal/v1/sessions/{id}/webrtc-offer", sessionId)
						.bodyValue(offerWire(sessionId, commandId, payloadHash, leaseEpoch, mediaEpoch, sdp)).retrieve()
						.bodyToMono(RtcAnswer.class).onErrorMap(DigitalHumanRuntimeClient::upstream)
						.timeout(Duration.ofSeconds(5));
			}
		};
	}

	/** K07.2 INTERNAL03 请求体（camelCase；payloadHash 覆盖除自身外全字段）。 */
	static Map<String, Object> offerWire(String sessionId, UUID commandId, String payloadHash, long leaseEpoch,
			long mediaEpoch, String sdp) {
		return java.util.Map.of("commandId", commandId.toString(), "leaseEpoch", leaseEpoch, "mediaEpoch", mediaEpoch,
				"sdp", sdp, "type", "offer", "payloadHash", payloadHash);
	}

	/**
	 * payloadHash（K07.2 与 runtime routes_internal._payload_hash 同算法）：去 payloadHash
	 * 字段后按键排序的 canonical JSON（无空格分隔）SHA-256 hex 小写。
	 */
	public static String offerPayloadHash(UUID commandId, long leaseEpoch, long mediaEpoch, String sdp) {
		Map<String, Object> wire = new TreeMap<>();
		wire.put("commandId", commandId.toString());
		wire.put("leaseEpoch", leaseEpoch);
		wire.put("mediaEpoch", mediaEpoch);
		wire.put("sdp", sdp);
		wire.put("type", "offer");
		return canonicalHash(wire);
	}

	/** canonical JSON（按键排序、无空格分隔、ensure_ascii=False 语义）SHA-256 hex 小写；支持嵌套 map。 */
	static String canonicalHash(Map<String, Object> wire) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(digest.digest(canonical(wire).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	private static String canonical(Object value) {
		if (value instanceof String text) {
			return quoteJson(text);
		}
		if (value instanceof Map<?, ?> map) {
			StringBuilder out = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> entry : new TreeMap<>(map).entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(quoteJson(String.valueOf(entry.getKey()))).append(':').append(canonical(entry.getValue()));
			}
			return out.append('}').toString();
		}
		return String.valueOf(value);
	}

	/** JSON 字符串字面量（与 Python json.dumps ensure_ascii=False 的转义子集对齐：控制字符/引号/反斜杠）。 */
	private static String quoteJson(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	/** 真实 runtime 的 4xx/5xx → IntelligenceException（code 取响应体既有码）——不冒充主面 500。 */
	private static Throwable upstream(Throwable error) {
		if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
			String code = "dh_runtime_unavailable";
			try {
				com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(response.getResponseBodyAsString());
				if (node.hasNonNull("code")) {
					code = node.get("code").asText();
				}
			} catch (Exception ignored) {
				// 非 JSON 错误体：保留兜底码
			}
			return new IntelligenceException(response.getStatusCode().value(), code, "runtime 上游拒绝。");
		}
		return error;
	}

	private static Mono<RuntimeState> unavailable() {
		return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
	}

	private static Mono<RtcAnswer> unavailableAnswer() {
		return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
	}

	public Mono<RuntimeState> createSession(SessionBinding binding) {
		return Mono.defer(() -> {
			recorder.onCreate(binding.sessionId());
			return transport.createSession(binding, UUID.randomUUID());
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

	/** API16 中继：commandId 每请求新生成（同 commandId 重放语义由前端 requestId 层承担）。 */
	public Mono<RtcAnswer> webrtcOffer(String sessionId, long leaseEpoch, long mediaEpoch, String sdp) {
		return Mono.defer(() -> {
			UUID commandId = UUID.randomUUID();
			return transport.webrtcOffer(sessionId, commandId, offerPayloadHash(commandId, leaseEpoch, mediaEpoch, sdp),
					leaseEpoch, mediaEpoch, sdp);
		});
	}
}
