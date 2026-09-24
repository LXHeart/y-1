package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionContext;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextPolicy.SpeechTextSegment;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.speech.SpeechProviderRegistry;
import com.grassland.intelligence.speech.SpeechRecognitionProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 音频桥（任务书 #105D C105D-04 / 共享契约 K07、K08）：按键 STT 与流式 TTS 配音。
 *
 * <p>
 * STT：内存 WAV（16k mono s16le，44 字节头）直接给 {@link SpeechProviderRegistry} 的 byte[]
 * 接口—— <b>不</b>走 SpeechTranscriptionService（会写用户上传与任务、创建 media 引用，全部禁止）；实际时长从
 * PCM 样本数计算并与声称值核对。空/无有效文本 → 停止（不调 LLM/TTS），已派发 STT 用量按实际记录。
 *
 * <p>
 * TTS：平台 voice 解析绕开个人 own 总开关（TTS 恒平台资助）；输入只接受<b>已审</b>句段（bridge 侧
 * {@link DigitalHumanTextPolicy#check} 已过），voice 来自 catalog，模型冻结于
 * ExecutionContext。
 */
@Component
public class DigitalHumanAudioBridge {

	private static final Logger logger = LoggerFactory.getLogger(DigitalHumanAudioBridge.class);

	/** K07：16k mono s16le；WAV 头固定 44 字节。 */
	public static final int STT_SAMPLE_RATE = 16_000;
	public static final int WAV_HEADER_BYTES = 44;
	/** K07：原始 mic 总量 1.92MB/60 秒双检查（防御：Java 侧也拦）。 */
	private static final int MAX_WAV_BYTES = 1_920_000;

	private final SpeechProviderRegistry speechProviders;
	private final DigitalHumanTtsClient ttsClient;

	public DigitalHumanAudioBridge(SpeechProviderRegistry speechProviders, DigitalHumanTtsClient ttsClient) {
		this.speechProviders = speechProviders;
		this.ttsClient = ttsClient;
	}

	/**
	 * 按键转写：wav 为 wrapper 内存拼装的 16k mono s16le（44 字节头）；实际时长按 PCM 样本数计算，与
	 * claimedDurationMs 偏差超 250ms 视为坏数据拒绝（不派发 provider）。mediaId 用 invocationId——不创建
	 * 用户原音频 media 引用。
	 */
	public Mono<SpeechRecognitionProvider.Result> transcribe(PreparedInvocation invocation, byte[] wav,
			long claimedDurationMs) {
		Objects.requireNonNull(invocation, "invocation 必填");
		Objects.requireNonNull(wav, "wav 必填");
		if (wav.length <= WAV_HEADER_BYTES || wav.length > MAX_WAV_BYTES) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "音频长度不合法。"));
		}
		long actualMs = (long) ((wav.length - WAV_HEADER_BYTES) / 2.0 / STT_SAMPLE_RATE * 1000);
		if (Math.abs(actualMs - claimedDurationMs) > 250) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "音频时长与样本数不符。"));
		}
		String checksum = sha256(wav);
		String providerName = invocation.context().provider().provider();
		SpeechRecognitionProvider provider = speechProviders.require(providerName);
		// ProviderInvocation 仅在持有明文凭据时构造（sandbox 假 provider 走 null，与既有 STT 调用方一致）。
		String bearer = bearerOf(invocation.context());
		com.grassland.intelligence.ai.ProviderInvocation upstream = bearer.isBlank()
				? null
				: new com.grassland.intelligence.ai.ProviderInvocation(providerName,
						invocation.context().provider().baseUrl(), invocation.context().provider().model(), bearer,
						invocation.context().provider().isByok());
		return provider
				.transcribe(new SpeechRecognitionProvider.Command(invocation.invocationId(), checksum, "zh", actualMs,
						wav, "audio/wav", upstream))
				.doOnNext(result -> logger.info("dh stt completed: invocationId={}, billedSeconds={}",
						invocation.invocationId(), result.billedSeconds()));
	}

	/**
	 * 流式配音：只接受已审句段（TTS 前安全检查在 {@link DigitalHumanTextPolicy} 完成）；输出 signed16le mono
	 * 24000Hz PCM。协议/采样率不匹配由 {@link DigitalHumanTtsClient} 判 502。
	 */
	public Flux<byte[]> streamTts(PreparedInvocation invocation, SpeechTextSegment segment, String voiceId) {
		Objects.requireNonNull(segment, "segment 必填");
		ExecutionContext context = invocation.context();
		return ttsClient.streamPcm(context.provider().provider(), context.provider().baseUrl(), bearerOf(context),
				context.provider().model(), segment.text(), voiceId);
	}

	/** 计秒（结算映射 videoSeconds 槽位由调用方完成）：按实际样本数计。 */
	public static int pcmBytesToSeconds(int pcmBytes, int sampleRate) {
		return (int) Math.ceil(pcmBytes / 2.0 / sampleRate);
	}

	// ---------- 私有 ----------

	private static String bearerOf(ExecutionContext context) {
		// 明文 key 只在进程内拼接，绝不入日志/响应/快照。
		return context.decryptedKey() == null ? "" : context.decryptedKey();
	}

	private static String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	/** WAV 头校验用（测试与防御）：data 段样本数。 */
	static long sampleCountOf(byte[] wav) {
		ByteBuffer buffer = ByteBuffer.wrap(wav, WAV_HEADER_BYTES - 4, 4).order(ByteOrder.LITTLE_ENDIAN);
		return buffer.getInt() / 2;
	}

	/** 16k mono s16le PCM → 44 字节头 WAV（试听读出给浏览器直接播放；内容仍内存易失）。 */
	public static byte[] wavOf(byte[] pcm) {
		byte[] wav = new byte[WAV_HEADER_BYTES + pcm.length];
		ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
		buffer.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
		buffer.putInt(STT_SAMPLE_RATE).putInt(STT_SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
		buffer.put("data".getBytes()).putInt(pcm.length).put(pcm);
		return wav;
	}
}
