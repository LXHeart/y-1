package com.grassland.intelligence.hypit.asset;

import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MOD-001（任务书 107-fix-3 §6.3，C107F3-07）：参考视频取证统一服务。broker wire 保持 Node
 * 真实形状（不做扁平化适配，契约见 {@code contracts/hypit-api.v1.json} evidenceWire）：
 * <ul>
 * <li>media.probe →
 * {@code result.probe.{duration,hasVideo,hasAudio,width,height}}（嵌套）；</li>
 * <li>media.frames →
 * {@code result.frames:[{handle,timestampSeconds}],totalTimes}（显式 times， 宽高比由
 * probe 派生，不读不存在的 frames.aspectRatio）；</li>
 * <li>speech.transcribe →
 * {@code result.passages[].words[].{text,startSample,endSampleExclusive}} 16kHz
 * 样本索引（无顶层 text）。</li>
 * </ul>
 * 规则落点：RULE-005（probe 必须 hasVideo=true、有限正时长、hasAudio 布尔；缺失/0/负/NaN 按
 * 未就绪处理，不默认无声成功）、RULE-006（固定六中点 t=duration*(2i+1)/12，缺帧/重复错位/越界
 * 不补造证据）、RULE-007（16kHz 样本索引/16000 得秒；词序保留；空 passages 且命令成功 = 已测
 * 无语音而非失败；hasAudio=false 才零转写调用；缺词时间锚点记缺口，转写不完整，不伪造 0）。
 * <p>
 * {@link #collect} 只调用固定 sidecar 三命令：命令未成功（failed/维护/不可达）按既有语义如实上抛 （worker
 * 重试/维护暂缓）；命令成功但形状违反 RULE-005/006 的确定性无效按 {@link #NOT_READY_CODE} 报告，由 W13
 * 映射为分析未就绪（waiting_input），不随重试自愈。
 * <p>
 * {@link #modelParts}（C107F3-08，RULE-008/009）：复用
 * {@link HypitResourceService#open} 内部认证 依次读取本次取证返回的帧句柄（顺序读、最多
 * {@value #FRAME_COUNT} 帧、每帧 ≤4MiB、合计 ≤24MiB， 仅 image/png 与
 * image/jpeg，逐帧真实解码校验），产出证据事实文本 part + 按时间锚点排序的 {@code ContentPart.image} data
 * URI parts；DataBuffer 在成功/错误/取消路径全部释放，解码在 boundedElastic 执行（§0.2：不阻塞 event
 * loop）。日志只记计数/类型/sha256/字节数（RULE-009）。
 */
@Service
public class HypitReferenceEvidenceService {

	private static final Logger logger = LoggerFactory.getLogger(HypitReferenceEvidenceService.class);

	/** 词证据：时间锚点为 16kHz 样本索引；允许 null 表达缺口（不能拆箱成 0）。 */
	public record Word(String text, Long startSample, Long endSampleExclusive) {
	}

	/** 帧证据：handle + 真实回执时间（秒）。 */
	public record Frame(String handle, double timestampSeconds) {
	}

	/** 归一化取证产物：collect 的强类型出口，综合与分析只消费此形状。 */
	public record Evidence(double durationSeconds, boolean hasAudio, String language, String aspectRatio,
			boolean transcriptionReady, List<Word> words, List<Frame> frames, String transcriptEvidenceHandle) {
	}

	/** RULE-005/006 确定性证据无效：按未就绪处理（waiting_input），不随重试自愈。 */
	public static final String NOT_READY_CODE = "hypit_evidence_not_ready";

	/** RULE-007：转写证据固定 16kHz 样本索引；startSample/16000 得秒。 */
	public static final int AUDIO_SAMPLE_RATE = 16_000;

	/** RULE-006：固定六中点采样（同 RULE-008 的帧数上限）。 */
	static final int FRAME_COUNT = 6;

	/** RULE-008：单帧字节上限 4MiB。 */
	public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

	/** RULE-008：全部帧合计字节上限 24MiB。 */
	public static final long MAX_TOTAL_FRAME_BYTES = 24L * 1024 * 1024;

	/** RULE-008：帧读取仅接受的媒体类型。 */
	public static final Set<String> IMAGE_MIME_TYPES = Set.of("image/png", "image/jpeg");

	/** 帧回执与请求时间的容差（JSON 浮点噪声；Node 回执按请求时间原样标注）。 */
	private static final double FRAME_TIME_EPSILON = 1e-6;

	private static final byte[] PNG_SIGNATURE = {-119, 80, 78, 71, 13, 10, 26, 10};

	private final HypitSidecarClient sidecar;
	private final HypitResourceService resources;

	public HypitReferenceEvidenceService(HypitSidecarClient sidecar, HypitResourceService resources) {
		this.sidecar = sidecar;
		this.resources = resources;
	}

	/**
	 * 固定三命令取证：probe → frames（六中点）→ 转写（仅 hasAudio=true）。 命令未成功如实上抛 （503
	 * hypit_backend_unavailable，worker 重试/维护暂缓语义不变）；形状无效抛 {@link #NOT_READY_CODE}。
	 */
	public Mono<Evidence> collect(UUID projectId, String sourceHandle, UUID operationId) {
		return command(operationId, "probe", "media.probe", Map.of("handle", sourceHandle)).flatMap(this::parseProbe)
				.flatMap(probe -> command(operationId, "frames", "media.frames",
						Map.of("handle", sourceHandle, "times", midpoints(probe.durationSeconds())))
						.map(framesResult -> parseFrames(framesResult, probe.durationSeconds()))
						.flatMap(frames -> transcript(sourceHandle, operationId, probe.hasAudio())
								.map(transcript -> new Evidence(probe.durationSeconds(), probe.hasAudio(),
										transcript.language(), probe.aspectRatio(), transcript.ready(),
										transcript.words(), frames, transcript.evidenceHandle()))));
	}

	/**
	 * 证据事实 → 模型消息 parts（MOD-001）。文本 part 携带词（样本→秒，缺锚点如实 null 缺口，绝不补
	 * 0）、帧（handle+时间+MIME+字节数+sha256）、时长/音轨/宽高比与转写完整性；随后按时间锚点顺序 追加
	 * {@link ContentPart.Image}（data URI）。C107F3-08（RULE-008）：帧字节经
	 * {@link HypitResourceService#open} 内部认证顺序读取（≤6 帧、单帧 ≤4MiB、合计 ≤24MiB、仅
	 * png/jpeg、逐帧真实解码），任何超限/坏资源在模型调用前失败（确定性无效按 {@link #NOT_READY_CODE}；资源端 4xx/5xx
	 * 按 {@code hypit_backend_unavailable} 如实上抛）。
	 */
	public Mono<List<ContentPart>> modelParts(Evidence evidence) {
		return Mono.defer(() -> {
			List<Frame> frames = List.copyOf(evidence.frames());
			if (frames.isEmpty()) {
				return Mono.error(notReady("取证证据缺少帧（RULE-008：1～" + FRAME_COUNT + " 帧）"));
			}
			if (frames.size() > FRAME_COUNT) {
				return Mono.error(notReady("取证帧 " + frames.size() + " 超过上限 " + FRAME_COUNT + "（RULE-008）"));
			}
			List<String> handles = frames.stream().map(Frame::handle).toList();
			if (Set.copyOf(handles).size() != handles.size()) {
				return Mono.error(notReady("取证帧句柄重复（RULE-008：仅消费本次回执的独立帧句柄）"));
			}
			double previousTimestamp = -FRAME_TIME_EPSILON;
			for (Frame frame : frames) {
				double timestamp = frame.timestampSeconds();
				if (!Double.isFinite(timestamp) || timestamp < -FRAME_TIME_EPSILON
						|| timestamp > evidence.durationSeconds() + FRAME_TIME_EPSILON) {
					return Mono.error(
							notReady("帧时间越界：" + timestamp + "s 超出 [0," + evidence.durationSeconds() + "s]（RULE-008）"));
				}
				if (timestamp <= previousTimestamp + FRAME_TIME_EPSILON) {
					return Mono.error(notReady("帧时间未按时间锚点排序：" + timestamp + "s"));
				}
				previousTimestamp = timestamp;
			}
			FrameBudget budget = new FrameBudget();
			// concatMap：一次顺序读全部帧（§9.4），取消传播到在途帧，已复制帧的 buffer 即读即释。
			return Flux.fromIterable(frames).concatMap(frame -> readFrameImage(frame, budget)).collectList()
					.map(images -> partsOf(evidence, images));
		});
	}

	/** 已读帧：原始字节 + 派生事实（MIME/sha256），data URI 在 parts 组装期生成。 */
	record FrameImage(Frame frame, String mimeType, byte[] bytes, String sha256) {
	}

	/**
	 * RULE-008 字节预算累加器（包私有：单元直测合计上限，不被单帧先拒掩盖——6 帧×4MiB 为合法上界， 合计越界只能在累加维度独立验证）。
	 */
	public static final class FrameBudget {

		private long totalBytes;

		public void accept(long frameBytes) {
			if (frameBytes < 0) {
				throw notReady("帧字节数非法：" + frameBytes);
			}
			if (frameBytes > MAX_FRAME_BYTES) {
				throw notReady("单帧 " + frameBytes + " 字节超过 " + MAX_FRAME_BYTES + " 上限（RULE-008）");
			}
			if (totalBytes + frameBytes > MAX_TOTAL_FRAME_BYTES) {
				throw notReady("帧合计 " + (totalBytes + frameBytes) + " 字节超过 " + MAX_TOTAL_FRAME_BYTES + " 上限（RULE-008）");
			}
			totalBytes += frameBytes;
		}

		public long totalBytes() {
			return totalBytes;
		}
	}

	/**
	 * 单帧内部认证读取：{@code resources.open}（30s 既有超时）→ 200/MIME 校验 →
	 * {@link DataBufferUtils#join} 上限聚合（越限即释放并失败）→ boundedElastic 复制/解码 （byte[]
	 * 化后立刻释放 buffer，成功/错误/取消路径都不持有 DataBuffer）。
	 */
	private Mono<FrameImage> readFrameImage(Frame frame, FrameBudget budget) {
		return resources.open(frame.handle(), null).flatMap(response -> {
			if (response.status() != 200) {
				return drain(response.body()).then(Mono.<FrameImage>error(new IntelligenceException(503,
						"hypit_backend_unavailable", "帧资源读取失败：" + frame.handle() + " status=" + response.status())));
			}
			String mimeType = mediaTypeOf(response.headers());
			if (mimeType == null || !IMAGE_MIME_TYPES.contains(mimeType)) {
				return drain(response.body()).then(Mono.<FrameImage>error(
						notReady("帧 " + frame.handle() + " 媒体类型不支持（仅 image/png、image/jpeg，收到 " + mimeType + "）")));
			}
			return DataBufferUtils.join(response.body(), MAX_FRAME_BYTES)
					.switchIfEmpty(Mono.error(notReady("帧 " + frame.handle() + " 返回空字节")))
					.publishOn(Schedulers.boundedElastic()).map(HypitReferenceEvidenceService::copyAndRelease)
					.map(bytes -> decodeFrame(frame, mimeType, bytes, budget));
		}).onErrorMap(DataBufferLimitException.class,
				error -> notReady("帧 " + frame.handle() + " 超过单帧 4MiB 预算（RULE-008）"));
	}

	/** 非 200/坏 MIME 路径的响应体排空并逐个释放（不留悬挂连接，也不吞真实错误）。 */
	private static Mono<Void> drain(Flux<DataBuffer> body) {
		return body.doOnNext(DataBufferUtils::release).then();
	}

	/** join 产物 → byte[]：finally 释放聚合 buffer（复制在 boundedElastic 执行）。 */
	private static byte[] copyAndRelease(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		} finally {
			DataBufferUtils.release(buffer);
		}
	}

	/** 帧级确定性校验 + 解码：签名/MIME 一致 → 预算累加 → ImageIO 真实解码（可解码才放行）。 */
	private FrameImage decodeFrame(Frame frame, String mimeType, byte[] bytes, FrameBudget budget) {
		if (!signatureMatches(mimeType, bytes)) {
			throw notReady("帧 " + frame.handle() + " 字节与声明类型 " + mimeType + " 不符（签名校验）");
		}
		budget.accept(bytes.length);
		java.awt.image.BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException error) {
			throw notReady("帧 " + frame.handle() + " 解码失败：" + error.getMessage());
		}
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			throw notReady("帧 " + frame.handle() + " 不可解码（" + mimeType + "，" + bytes.length + " 字节）");
		}
		String sha256 = sha256Hex(bytes);
		// RULE-009 日志：只记计数/类型/sha256/字节数与时间锚点，不落 data URI 与密钥。
		logger.info("参考帧取证读取：handle={}, type={}, bytes={}, sha256={}, timestampSeconds={}", frame.handle(), mimeType,
				bytes.length, sha256, frame.timestampSeconds());
		return new FrameImage(frame, mimeType, bytes, sha256);
	}

	/** 事实文本 + 图像 parts（图像顺序=帧时间锚点顺序，RULE-009：文本带对应时间与哈希）。 */
	private static List<ContentPart> partsOf(Evidence evidence, List<FrameImage> images) {
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("durationSeconds", evidence.durationSeconds());
		facts.put("audioTrack", evidence.hasAudio() ? "PRESENT" : "ABSENT");
		facts.put("transcriptionReady", evidence.transcriptionReady());
		facts.put("language", evidence.language() == null ? "unknown" : evidence.language());
		facts.put("aspectRatio", evidence.aspectRatio() == null ? "unknown" : evidence.aspectRatio());
		List<Map<String, Object>> words = new ArrayList<>();
		for (Word word : evidence.words()) {
			Map<String, Object> fact = new LinkedHashMap<>();
			fact.put("text", word.text());
			// RULE-007：样本索引/16000 得秒；缺锚点如实 null（缺口），不伪造 0。
			fact.put("startSeconds",
					word.startSample() == null ? null : word.startSample() / (double) AUDIO_SAMPLE_RATE);
			fact.put("endSeconds",
					word.endSampleExclusive() == null ? null : word.endSampleExclusive() / (double) AUDIO_SAMPLE_RATE);
			words.add(fact);
		}
		facts.put("words", words);
		List<Map<String, Object>> frames = new ArrayList<>();
		for (FrameImage image : images) {
			Map<String, Object> fact = new LinkedHashMap<>();
			fact.put("handle", image.frame().handle());
			fact.put("timestampSeconds", image.frame().timestampSeconds());
			fact.put("mimeType", image.mimeType());
			fact.put("bytes", image.bytes().length);
			fact.put("sha256", image.sha256());
			frames.add(fact);
		}
		facts.put("frames", frames);
		if (evidence.transcriptEvidenceHandle() != null) {
			facts.put("transcriptEvidenceHandle", evidence.transcriptEvidenceHandle());
		}
		List<ContentPart> parts = new ArrayList<>(1 + images.size());
		parts.add(ContentPart.text(HypitJson.write(facts)));
		for (FrameImage image : images) {
			parts.add(ContentPart.image(
					"data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes())));
		}
		return List.copyOf(parts);
	}

	/** Content-Type → 小写媒体类型（无参数），缺失返回 null。 */
	private static String mediaTypeOf(HttpHeaders headers) {
		String value = headers.getFirst(HttpHeaders.CONTENT_TYPE);
		if (value == null || value.isBlank()) {
			return null;
		}
		int semicolon = value.indexOf(';');
		return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase();
	}

	private static boolean signatureMatches(String mimeType, byte[] bytes) {
		if ("image/png".equals(mimeType)) {
			if (bytes.length < PNG_SIGNATURE.length) {
				return false;
			}
			for (int index = 0; index < PNG_SIGNATURE.length; index++) {
				if (bytes[index] != PNG_SIGNATURE[index]) {
					return false;
				}
			}
			return true;
		}
		// JPEG：SOI FFD8FF（第三个字节常为 E0/DB/E1/FE 等标记段前缀）。
		return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	// ------------------------------------------------------------------
	// 内部：命令派发与三段归一化
	// ------------------------------------------------------------------

	private record ProbeFacts(double durationSeconds, boolean hasAudio, String aspectRatio) {
	}

	private record Transcript(String language, List<Word> words, boolean ready, String evidenceHandle) {

		static Transcript silent() {
			return new Transcript(null, List.of(), true, null);
		}
	}

	/** 命令派发 + 成功 state 校验：非 succeeded 一律 503 如实上抛（可重试），不归一化。 */
	private Mono<Map<String, Object>> command(UUID operationId, String label, String kind,
			Map<String, Object> payload) {
		return sidecar.commandAsync("java-ref-" + label + "-" + operationId, kind, payload).map(received -> {
			if (!"succeeded".equals(received.state())) {
				throw new IntelligenceException(503, "hypit_backend_unavailable",
						"参考分析工具未成功：" + kind + " state=" + received.state());
			}
			return HypitJson.mapValue(received.result());
		});
	}

	/** RULE-005：只消费嵌套 probe；hasVideo/有限正时长/布尔 hasAudio 全部强制。 */
	private Mono<ProbeFacts> parseProbe(Map<String, Object> result) {
		return Mono.fromSupplier(() -> {
			Map<String, Object> probe = HypitJson.mapValue(result.get("probe"));
			if (probe.isEmpty()) {
				throw notReady("probe 回执缺少嵌套 probe 字段（wire: result.probe）——拒绝消费旧扁平形状");
			}
			if (!Boolean.TRUE.equals(probe.get("hasVideo"))) {
				throw notReady("probe.hasVideo 必须为 true（收到 " + probe.get("hasVideo") + "）");
			}
			if (!(probe.get("duration") instanceof Number durationNumber)) {
				throw notReady("probe.duration 缺失或非数值（收到 " + probe.get("duration") + "）");
			}
			double duration = durationNumber.doubleValue();
			if (!Double.isFinite(duration) || duration <= 0) {
				throw notReady("probe.duration 必须为有限正秒数（收到 " + probe.get("duration") + "）");
			}
			if (!(probe.get("hasAudio") instanceof Boolean hasAudio)) {
				throw notReady("probe.hasAudio 必须为布尔（收到 " + probe.get("hasAudio") + "）");
			}
			Object width = probe.get("width");
			Object height = probe.get("height");
			String aspectRatio = width instanceof Number widthNumber && height instanceof Number heightNumber
					&& widthNumber.longValue() > 0 && heightNumber.longValue() > 0
							? ratioOf(widthNumber.longValue(), heightNumber.longValue())
							: "unknown";
			return new ProbeFacts(duration, hasAudio, aspectRatio);
		});
	}

	/** RULE-006：六中点 t=duration*(2i+1)/12；回执缺帧/重复/错位/越界不补造证据。 */
	private List<Frame> parseFrames(Map<String, Object> result, double durationSeconds) {
		if (!(result.get("frames") instanceof List<?> rawFrames)) {
			throw notReady("frames 回执缺少 frames 列表（wire: result.frames）");
		}
		List<Double> requested = midpoints(durationSeconds);
		if (rawFrames.size() != requested.size()) {
			throw notReady("帧回执 " + rawFrames.size() + " 帧 != 请求 " + requested.size() + " 帧（缺帧不补造）");
		}
		List<Frame> frames = new ArrayList<>();
		for (int index = 0; index < rawFrames.size(); index++) {
			Map<String, Object> raw = HypitJson.mapValue(rawFrames.get(index));
			String handle = HypitJson.stringValue(raw.get("handle"), null);
			if (handle == null || handle.isBlank()) {
				throw notReady("第 " + index + " 帧缺少 handle");
			}
			if (!(raw.get("timestampSeconds") instanceof Number timestampNumber)) {
				throw notReady("第 " + index + " 帧缺少 timestampSeconds");
			}
			double timestamp = timestampNumber.doubleValue();
			double expected = requested.get(index);
			if (Math.abs(timestamp - expected) > FRAME_TIME_EPSILON) {
				throw notReady("帧时间错位：回执 " + timestamp + "s != 请求 " + expected + "s");
			}
			if (timestamp < 0 || timestamp > durationSeconds + FRAME_TIME_EPSILON) {
				throw notReady("帧时间越界：" + timestamp + "s 超出 [0," + durationSeconds + "s]");
			}
			double duplicateCheck = timestamp;
			if (frames.stream()
					.anyMatch(frame -> Math.abs(frame.timestampSeconds() - duplicateCheck) <= FRAME_TIME_EPSILON)) {
				throw notReady("帧时间重复：" + timestamp + "s");
			}
			frames.add(new Frame(handle, timestamp));
		}
		return List.copyOf(frames);
	}

	/** RULE-007：hasAudio=false 零转写调用；passages 缺失=非法回执，空列表=已测无语音。 */
	private Mono<Transcript> transcript(String sourceHandle, UUID operationId, boolean hasAudio) {
		if (!hasAudio) {
			return Mono.just(Transcript.silent());
		}
		return command(operationId, "transcribe", "speech.transcribe", Map.of("handle", sourceHandle)).map(result -> {
			if (!(result.get("passages") instanceof List<?> rawPassages)) {
				throw notReady("transcribe 回执缺少 passages（wire: result.passages[].words[]）——拒绝消费顶层 text");
			}
			List<Word> words = new ArrayList<>();
			boolean anchored = true;
			for (Object passageItem : rawPassages) {
				Map<String, Object> passage = HypitJson.mapValue(passageItem);
				if (!(passage.get("words") instanceof List<?> rawWords)) {
					throw notReady("passage 缺少 words 列表");
				}
				for (Object wordItem : rawWords) {
					Map<String, Object> word = HypitJson.mapValue(wordItem);
					String text = HypitJson.stringValue(word.get("text"), null);
					if (text == null || text.isBlank()) {
						throw notReady("转写词缺少 text");
					}
					Long startSample = sampleIndex(word.get("startSample"));
					Long endSampleExclusive = sampleIndex(word.get("endSampleExclusive"));
					if (startSample == null || endSampleExclusive == null) {
						// 缺词时间锚点：保留词与缺口（null），转写不完整；不伪造 0。
						anchored = false;
					} else if (startSample < 0 || endSampleExclusive < startSample) {
						throw notReady("词样本区间非法：" + text + "[" + startSample + "," + endSampleExclusive + ")");
					}
					words.add(new Word(text, startSample, endSampleExclusive));
				}
			}
			String language = HypitJson.stringValue(result.get("language"), null);
			String evidenceHandle = HypitJson.stringValue(result.get("evidenceHandle"), null);
			return new Transcript(language, List.copyOf(words), anchored, evidenceHandle);
		});
	}

	/** 样本索引解析：缺失/非数值 → null（缺口）；非负整数由调用方校验。 */
	private static Long sampleIndex(Object raw) {
		return raw instanceof Number number ? number.longValue() : null;
	}

	/** RULE-006 六中点：t = duration*(2i+1)/12，i=0..5。 */
	private static List<Double> midpoints(double durationSeconds) {
		List<Double> times = new ArrayList<>(FRAME_COUNT);
		for (int index = 0; index < FRAME_COUNT; index++) {
			times.add(durationSeconds * (2 * index + 1) / 12.0);
		}
		return times;
	}

	/** 宽高比约分（probe 派生），如 1080x1920 → "9:16"。 */
	private static String ratioOf(long width, long height) {
		BigInteger widthBig = BigInteger.valueOf(width);
		BigInteger heightBig = BigInteger.valueOf(height);
		long divisor = widthBig.gcd(heightBig).max(BigInteger.ONE).longValue();
		return (width / divisor) + ":" + (height / divisor);
	}

	private static IntelligenceException notReady(String message) {
		return new IntelligenceException(503, NOT_READY_CODE, message);
	}
}
