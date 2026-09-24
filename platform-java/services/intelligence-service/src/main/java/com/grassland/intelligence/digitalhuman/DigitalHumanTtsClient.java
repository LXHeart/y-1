package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * 数字人 TTS PCM 流客户端（任务书 #105D C105D-04 / 共享契约 K08）。
 *
 * <p>
 * 专用 Java 适配器：<b>不复用</b> MiniMax 异步轮询（K08：端点不支持流式 PCM 则 catalog 不可选，不改造成 异步）。固定
 * {@code audio/speech} 相对路径（OpenAI 语音形状：model/voice/input/response_format=pcm），
 * 输出约定 signed16le mono 24000Hz；平台目的地经受信 origin 校验 + DNS pinning（与文本客户端同模式）。
 * 奇数字节尾包（半 sample）与超时长流直接失败，不静默吞。
 */
@Component
public class DigitalHumanTtsClient {

	/** K08：PCM 采样率由批准配置固定 24000Hz。 */
	public static final int PCM_SAMPLE_RATE = 24_000;
	/** K07.1：TTS PCM 每 chunk ≤48KiB；超过视为协议异常。 */
	private static final int MAX_CHUNK_BYTES = 48 * 1024;
	/** K08.1：TTS 每段预留 90 秒上界 → 24000Hz×2B×90s 字节上限。 */
	private static final long MAX_TOTAL_BYTES = (long) PCM_SAMPLE_RATE * 2 * 90;

	private final DnsPinningResolver dnsPinning;
	private final PlatformProviderPolicy platformProviderPolicy;

	public DigitalHumanTtsClient(DnsPinningResolver dnsPinning, PlatformProviderPolicy platformProviderPolicy) {
		this.dnsPinning = dnsPinning;
		this.platformProviderPolicy = platformProviderPolicy;
	}

	/**
	 * 流式合成：首块实时到达（非整段缓冲）；chunk 奇数字节或总量超 90 秒音频 → 502（不吞半 sample）。
	 *
	 * @param provider
	 *            平台 provider 名（方言即目的地标签）
	 * @param baseUrl
	 *            冻结平台端点
	 * @param bearer
	 *            平台凭据明文（只在进程内使用）
	 * @param model
	 *            冻结模型
	 */
	public Flux<byte[]> streamPcm(String provider, String baseUrl, String bearer, String model, String text,
			String voiceId) {
		return Flux.defer(() -> {
			platformProviderPolicy.validate(provider, baseUrl);
			WebClient client = com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory.pinnedPlatformClient(
					DigitalHumanTtsClient.class, baseUrl, dnsPinning, java.time.Duration.ofSeconds(90),
					2 * 1024 * 1024);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("model", model);
			payload.put("input", text);
			payload.put("voice", voiceId);
			payload.put("response_format", "pcm");
			java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
			return client.post().uri("audio/speech").contentType(MediaType.APPLICATION_JSON)
					.headers(headers -> headers.setBearerAuth(bearer)).bodyValue(payload).retrieve()
					.onStatus(status -> status.is4xxClientError(), r -> Mono_error(400, "TTS 上游拒绝请求"))
					.onStatus(status -> status.is5xxServerError(), r -> Mono_error(502, "TTS 上游暂不可用"))
					.bodyToFlux(byte[].class).concatMap(chunk -> {
						if (chunk.length == 0) {
							return Flux.empty();
						}
						if (chunk.length % 2 != 0) {
							// 奇数字节=半 sample：协议损坏，不能静默丢字节继续。
							return Flux.error(new IntelligenceException(502, "dh_tts_protocol_error", "TTS 音频流损坏。"));
						}
						if (chunk.length > MAX_CHUNK_BYTES) {
							return Flux.error(new IntelligenceException(502, "dh_tts_protocol_error", "TTS 音频块超限。"));
						}
						long cumulative = total.addAndGet(chunk.length);
						if (cumulative > MAX_TOTAL_BYTES) {
							return Flux
									.error(new IntelligenceException(502, "dh_tts_protocol_error", "TTS 音频超出每段时长上限。"));
						}
						return Flux.just(chunk);
					});
		});
	}

	private static reactor.core.publisher.Mono<Throwable> Mono_error(int status, String message) {
		return reactor.core.publisher.Mono.error(new IntelligenceException(status, message));
	}
}
