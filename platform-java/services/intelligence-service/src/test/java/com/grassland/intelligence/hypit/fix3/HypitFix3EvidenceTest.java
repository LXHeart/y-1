package com.grassland.intelligence.hypit.fix3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService;
import com.grassland.intelligence.hypit.asset.HypitAssetRepository.AssetRow;
import com.grassland.intelligence.hypit.asset.HypitAssetService;
import com.grassland.intelligence.hypit.asset.HypitAssetUploadService;
import com.grassland.intelligence.hypit.asset.HypitAssetRepository;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.asset.HypitReferenceEvidenceService;
import com.grassland.intelligence.hypit.asset.HypitReferenceEvidenceService.Evidence;
import com.grassland.intelligence.hypit.asset.HypitReferenceEvidenceService.Frame;
import com.grassland.intelligence.hypit.asset.HypitReferenceEvidenceService.Word;
import com.grassland.intelligence.hypit.asset.HypitResourceService;
import com.grassland.intelligence.hypit.asset.HypitResourceService.ResourceResponse;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;

/**
 * C107F3-07/08（审计 A02/A04/A03 反例回归，任务书 107-fix-3 §12.2）：probe/转写契约与真实图像取证单元反例。
 * W14 只消费 Node 真实 wire（嵌套
 * probe、frames{handle,timestampSeconds}、passages[].words[] 16kHz 样本锚点）；旧扁平形状（顶层
 * durationSeconds/顶层 text）必须被拒绝而不是读成 0 秒/无声。
 *
 * <ul>
 * <li>TC-F3-07-01 真实 probe 与音轨分支：六中点 1/3/5/7/9/11s、宽高比 probe 派生、无声零转写调用。</li>
 * <li>TC-F3-07-02 逐词时间与空语音：词序/文本保留、样本/16000 换算秒、缺锚点如实 null 不伪造 0、 passages=[]
 * 是已测无语音（转写就绪）。</li>
 * <li>TC-F3-07-03 probe 与词边界拒绝：duration 缺失/0/-1/NaN、hasAudio 缺失/null/字符串、
 * hasVideo=false、缺帧/错位/越界、无 passages 顶层 text——全部按未就绪拒绝；W21 无效时长/缺观察 不产
 * SUCCEEDED。</li>
 * <li>TC-F3-08-01（单元半边）：modelParts 产出 1 文本 + 6 图像 parts，图像真实可解码、顺序对应
 * 时间锚点、sha256/MIME/字节与事实表一致；W13 多模态消息经 executeIndependentPrepared、
 * 解析不再补造「综合模型分段」。</li>
 * <li>TC-F3-08-02 帧预算与无效资源：单帧 4MiB 边界/4MiB+1 拒、合计 24MiB 累加器独立验证、7 帧/
 * 空列表/重复句柄/越界时间/坏 PNG/text/plain/404；失败零模型调用、无假分析。</li>
 * <li>TC-F3-08-03 取消与响应式资源清理：第三帧屏障取消后零后续读取、已收 buffer 全释放、 NonBlocking（event
 * loop）线程不执行复制/解码；第三帧 I/O 错误如实透传且 buffer 释放。</li>
 * </ul>
 * 末端依赖（sidecar/资源服务/命令仓储/平台执行入口）按 §12.2 共享前提在单元层 mock； wire 形状即 Node
 * 真实返回，帧字节为真实可解码 PNG。
 */
class HypitFix3EvidenceTest {

	private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-1073-4000-8000-000000000001");
	private static final UUID ASSET_ID = UUID.fromString("aaaaaaaa-1073-4000-8000-0000000000a1");
	private static final String ACCOUNT = "aaaaaaaa-1073-4000-8000-000000000001";
	private static final UUID RUN_ID = UUID.fromString("eeeeeeee-1083-4000-8000-000000000008");

	private HypitSidecarClient sidecar;
	private HypitResourceService resources;
	private HypitReferenceEvidenceService service;
	private HypitReferenceAnalysisService analyses;
	private HypitCommandRepository commands;
	private final List<io.netty.buffer.ByteBuf> allocated = new ArrayList<>();

	@BeforeEach
	void setUp() {
		sidecar = Mockito.mock(HypitSidecarClient.class);
		resources = Mockito.mock(HypitResourceService.class);
		service = new HypitReferenceEvidenceService(sidecar, resources);
		commands = Mockito.mock(HypitCommandRepository.class);
		// 幂等仓储 stub：existing=true 走幂等空路径，单元层不触发 saveResult。
		when(commands.insert(any(), anyString(), any(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Mono.just(new HypitCommandRepository.Accepted(true, null)));
		analyses = new HypitReferenceAnalysisService(commands);
		// 默认资源桩：任意句柄 → 200/image/png 小尺寸可解码帧（多 buffer 分片走 join 聚合）。
		stubFramesPng(handle -> pngOf(Color.RED));
		allocated.clear();
	}

	// ── 帧字节与资源桩工具 ────────────────────────────────────────────────

	private static final NettyDataBufferFactory BUFFER_FACTORY = new NettyDataBufferFactory(
			new io.netty.buffer.UnpooledByteBufAllocator(false));

	/** 真实可解码 PNG（320×240 纯色，ImageIO 编码）。 */
	static byte[] pngOf(Color color) {
		try {
			BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D graphics = image.createGraphics();
			graphics.setColor(color);
			graphics.fillRect(0, 0, 320, 240);
			graphics.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}

	/**
	 * 精确补到目标字节数：PNG 解码器读到 IEND 即止，IEND 之后追加的尾部字节被忽略—— 补齐后的帧仍是可解码
	 * PNG，字节数精确等于预算值（边界用例需要精确体积）。
	 */
	static byte[] padPng(byte[] png, int targetBytes) {
		assertThat(targetBytes).as("padding target must exceed png length").isGreaterThan(png.length);
		byte[] padded = Arrays.copyOf(png, targetBytes);
		return padded;
	}

	private interface FramePng {
		byte[] pngFor(String handle);
	}

	/** 资源桩：所有句柄按取色函数回 200/image/png，字节切成 2 个 DataBuffer（真实聚合路径）。 */
	private void stubFramesPng(FramePng pngs) {
		when(resources.open(anyString(), any())).thenAnswer(invocation -> {
			String handle = invocation.getArgument(0);
			return Mono.just(responseOf(200, "image/png", pngs.pngFor(handle)));
		});
	}

	private ResourceResponse responseOf(int status, String contentType, byte[] bytes) {
		HttpHeaders headers = new HttpHeaders();
		if (contentType != null) {
			headers.setContentType(MediaType.parseMediaType(contentType));
		}
		return new ResourceResponse(status, headers, bodyOf(bytes));
	}

	/**
	 * 字节 → 2 个池化 DataBuffer 的 Flux（记录 ByteBuf 供释放断言）。 Flux.defer：Mockito 对
	 * when(open(anyString())) 重打桩时会以匹配器默认参数执行旧 answer
	 * （幽灵调用、从不订阅）——惰性分配保证幽灵调用零分配，释放断言只统计真实订阅的 buffer。
	 */
	private Flux<DataBuffer> bodyOf(byte[] bytes) {
		return Flux.defer(() -> {
			int split = Math.max(1, bytes.length / 2);
			return Flux.fromIterable(List.of(bufferOf(bytes, 0, Math.min(split, bytes.length)),
					bufferOf(bytes, Math.min(split, bytes.length), bytes.length)));
		});
	}

	private DataBuffer bufferOf(byte[] bytes, int from, int to) {
		io.netty.buffer.ByteBuf byteBuf = io.netty.buffer.Unpooled.buffer(to - from);
		byteBuf.writeBytes(bytes, from, to - from);
		allocated.add(byteBuf);
		return BUFFER_FACTORY.wrap(byteBuf);
	}

	private static void assertReleased(List<io.netty.buffer.ByteBuf> buffers) {
		for (io.netty.buffer.ByteBuf buffer : buffers) {
			assertThat(buffer.refCnt()).as("buffer 必须全部释放").isZero();
		}
	}

	private static SidecarCommand ok(String kind, String resultJson) {
		return new SidecarCommand("cmd-" + kind, kind, "succeeded", HypitJson.read(resultJson), null);
	}

	private static final String PROBE_AUDIO = """
			{"probe":{"duration":12.0,"hasVideo":true,"hasAudio":true,"width":320,"height":240}}""";

	private static String framesAt(double... times) {
		StringBuilder frames = new StringBuilder("\"frames\":[");
		for (int index = 0; index < times.length; index++) {
			if (index > 0) {
				frames.append(',');
			}
			frames.append("{\"handle\":\"res-src#f").append(index).append("\",\"timestampSeconds\":")
					.append(times[index]).append('}');
		}
		return frames.append("],\"totalTimes\":").append(times.length).append("}").toString();
	}

	/** TC-F3-07-02 规范回执：你好[0,16000)、草场[64000,80000)，16kHz。 */
	private static final String TRANSCRIPT_WORDS = """
			{"language":"zh","sampleFrames":192000,"durationSec":12.0,"extracted":true,
			 "passages":[{"startSample":0,"endSampleExclusive":80000,"words":[
			   {"text":"你好","startSample":0,"endSampleExclusive":16000,"score":0.9},
			   {"text":"草场","startSample":64000,"endSampleExclusive":80000}]}],
			 "diagnostics":[],"evidenceHandle":"res-src-speech"}""".replace("\n", "");

	// ── TC-F3-07-01：真实 wire nested probe / 六中点 / 音轨分支 ──────────────
	@Test
	@DisplayName("TC-F3-07-01 真实wire嵌套probe：六中点1/3/5/7/9/11s、音轨分支正确、无声零转写调用")
	void tc_f3_07_01_nested_probe_six_midpoints_and_silent_branch() {
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any()))
				.thenReturn(Mono.just(ok("media.probe", PROBE_AUDIO)));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 11) + "}")));
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", TRANSCRIPT_WORDS)));

		Evidence audio = service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block();
		assertThat(audio).isNotNull();
		assertThat(audio.durationSeconds()).as("时长取嵌套 probe.duration").isEqualTo(12.0);
		assertThat(audio.hasAudio()).isTrue();
		assertThat(audio.frames()).extracting(Frame::timestampSeconds).as("RULE-006 六中点").containsExactly(1.0, 3.0, 5.0,
				7.0, 9.0, 11.0);
		assertThat(audio.frames()).allSatisfy(frame -> assertThat(frame.handle()).startsWith("res-src#f"));
		assertThat(audio.aspectRatio()).as("宽高比由 probe 320x240 派生").isEqualTo("4:3");
		assertThat(audio.language()).isEqualTo("zh");
		assertThat(audio.transcriptionReady()).isTrue();
		assertThat(audio.transcriptEvidenceHandle()).isEqualTo("res-src-speech");

		// 无声分支：hasAudio=false → 零转写调用（音轨不存在即证据，transcriptionReady=true）。
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any())).thenReturn(Mono.just(ok("media.probe",
				"{\"probe\":{\"duration\":12.0,\"hasVideo\":true,\"hasAudio\":false,\"width\":320,\"height\":240}}")));
		Evidence silent = service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block();
		assertThat(silent).isNotNull();
		assertThat(silent.hasAudio()).isFalse();
		assertThat(silent.words()).as("无声零词").isEmpty();
		assertThat(silent.transcriptEvidenceHandle()).as("无声无转写证据").isNull();
		assertThat(silent.transcriptionReady()).as("无声即证据，转写就绪").isTrue();
		verify(sidecar, times(1)).commandAsync(anyString(), eq("speech.transcribe"), any());
	}

	// ── TC-F3-07-02：逐词样本时间与空语音 ─────────────────────────────────
	@Test
	@DisplayName("TC-F3-07-02 词序/文本保留、样本/16000换算秒、缺锚点null缺口、passages=[]已测无语音")
	void tc_f3_07_02_word_order_sample_conversion_and_empty_speech() {
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any()))
				.thenReturn(Mono.just(ok("media.probe", PROBE_AUDIO)));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 11) + "}")));
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", TRANSCRIPT_WORDS)));

		Evidence evidence = service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block();
		assertThat(evidence).isNotNull();
		// 词序/文本/样本索引保留（16kHz：0..16000 → 0~1s；64000..80000 → 4~5s）。
		assertThat(evidence.words()).containsExactly(new Word("你好", 0L, 16000L), new Word("草场", 64000L, 80000L));
		String facts = modelPartsText(evidence);
		assertThat(facts).as("词秒换算进模型事实").contains("\"text\":\"你好\"").contains("\"startSeconds\":0.0")
				.contains("\"endSeconds\":1.0").contains("\"text\":\"草场\"").contains("\"startSeconds\":4.0")
				.contains("\"endSeconds\":5.0");

		// 缺锚点词：保留词与 null 缺口，transcriptionReady=false（转写不完整），绝不伪造 0。
		String missingAnchor = """
				{"language":"zh","sampleFrames":192000,"durationSec":12.0,"extracted":true,
				 "passages":[{"words":[{"text":"缺口词"}]}],"diagnostics":[],"evidenceHandle":"res-gap"}""".replace("\n",
				"");
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", missingAnchor)));
		Evidence gap = service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block();
		assertThat(gap).isNotNull();
		assertThat(gap.words()).containsExactly(new Word("缺口词", null, null));
		assertThat(gap.transcriptionReady()).as("缺锚点=转写未完整").isFalse();
		assertThat(modelPartsText(gap)).as("缺口如实 null").contains("\"text\":\"缺口词\"").contains("\"startSeconds\":null")
				.contains("\"endSeconds\":null");

		// 空 passages（命令成功）：已测无语音，不是转写失败。
		String emptySpeech = """
				{"language":"zh","sampleFrames":192000,"durationSec":12.0,"extracted":true,
				 "passages":[],"diagnostics":[],"evidenceHandle":"res-empty"}""".replace("\n", "");
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", emptySpeech)));
		Evidence empty = service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block();
		assertThat(empty).isNotNull();
		assertThat(empty.words()).isEmpty();
		assertThat(empty.transcriptionReady()).as("空语音=已检测无词").isTrue();
	}

	// ── TC-F3-07-03：probe 与词边界拒绝 ───────────────────────────────────
	@Test
	@DisplayName("TC-F3-07-03 无效probe/缺帧/错位/顶层text全拒绝不默认0秒无声；W21无效时长与缺观察不判成功")
	void tc_f3_07_03_invalid_probe_and_word_boundary_rejection() {
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 11) + "}")));
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", TRANSCRIPT_WORDS)));

		// 旧扁平形状（顶层 durationSeconds/hasAudio）必须拒绝——按旧实现会读成 0 秒/无声假成功。
		assertNotReady("{\"durationSeconds\":12.0,\"hasAudio\":true}", TRANSCRIPT_WORDS, "旧扁平 probe 读取必须失败");
		// RULE-005 矩阵：每次只变一个字段。
		List<String> invalidProbes = List.of("{\"probe\":{\"hasVideo\":true,\"hasAudio\":true}}",
				"{\"probe\":{\"duration\":0,\"hasVideo\":true,\"hasAudio\":true}}",
				"{\"probe\":{\"duration\":-1,\"hasVideo\":true,\"hasAudio\":true}}",
				"{\"probe\":{\"duration\":12,\"hasVideo\":false,\"hasAudio\":true}}",
				"{\"probe\":{\"duration\":12,\"hasVideo\":true}}",
				"{\"probe\":{\"duration\":12,\"hasVideo\":true,\"hasAudio\":null}}",
				"{\"probe\":{\"duration\":12,\"hasVideo\":true,\"hasAudio\":\"false\"}}", "{}");
		for (String probe : invalidProbes) {
			assertNotReady(probe, TRANSCRIPT_WORDS, "非法 probe 必须按未就绪拒绝：" + probe);
		}
		// NaN 用单元对象注入（合法 JSON 不支持 NaN 字面量）。
		Map<String, Object> nanProbe = new HashMap<>();
		nanProbe.put("duration", Double.NaN);
		nanProbe.put("hasVideo", Boolean.TRUE);
		nanProbe.put("hasAudio", Boolean.TRUE);
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any())).thenReturn(
				Mono.just(new SidecarCommand("cmd", "media.probe", "succeeded", Map.of("probe", nanProbe), null)));
		assertThatThrownBy(() -> service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block())
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));

		// RULE-006：合法 probe 下帧异常不补造证据——缺帧 / 错位 / 越界。
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any()))
				.thenReturn(Mono.just(ok("media.probe", PROBE_AUDIO)));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9) + "}")));
		assertThatThrownBy(() -> service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block())
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(2, 3, 5, 7, 9, 11) + "}")));
		assertThatThrownBy(() -> service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block())
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 13) + "}")));
		assertThatThrownBy(() -> service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block())
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));

		// 以上所有拒绝路径（非法 probe/帧异常）零转写副作用（无声默认被禁）。
		verify(sidecar, never()).commandAsync(anyString(), eq("speech.transcribe"), any());

		// RULE-007：无 passages 顶层 text 旧形状必须拒绝（合法 probe 下仍会调转写、但回执被拒）。
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 11) + "}")));
		assertNotReady(PROBE_AUDIO, "{\"language\":\"zh\",\"text\":\"开场介绍\"}", "顶层 text 旧形状必须拒绝");
		verify(sidecar, times(1)).commandAsync(anyString(), eq("speech.transcribe"), any());

		// W21 守卫：无效时长/缺观察不得判成功。
		List<HypitReferenceAnalysis.Segment> fullCoverage = List
				.of(new HypitReferenceAnalysis.Segment(0, 0, 12, "全覆盖", List.of()));
		List<HypitReferenceAnalysis.Segment> emptyObservations = List.of();
		assertThat(analyses.analyze(input(0.0, fullCoverage)).block().status()).as("duration=0 不得借 coverageGaps 假绿")
				.isEqualTo(HypitReferenceAnalysis.Status.WAITING_INPUT);
		assertThat(analyses.analyze(input(-1.0, fullCoverage)).block().status())
				.isEqualTo(HypitReferenceAnalysis.Status.WAITING_INPUT);
		assertThat(analyses.analyze(input(Double.NaN, fullCoverage)).block().status()).as("NaN 时长不得判成功")
				.isEqualTo(HypitReferenceAnalysis.Status.WAITING_INPUT);
		assertThat(analyses.analyze(input(12.0, emptyObservations)).block().status()).as("缺观察=全片 gap，PROVISIONAL")
				.isEqualTo(HypitReferenceAnalysis.Status.PROVISIONAL);
	}

	/** 旧形态兼容构造器入口（audioTrack=PRESENT、转写就绪、全覆盖观察）。 */
	private static HypitReferenceAnalysisService.AnalysisInput input(double duration,
			List<HypitReferenceAnalysis.Segment> segments) {
		return new HypitReferenceAnalysisService.AnalysisInput("op-" + UUID.randomUUID(), "a".repeat(64), duration,
				"zh", "4:3", HypitReferenceAnalysis.AudioTrack.PRESENT, true, segments, List.of(), List.of(),
				List.of());
	}

	/** stub 一次 collect 并断言以 NOT_READY_CODE 拒绝。 */
	private void assertNotReady(String probeJson, String transcriptJson, String description) {
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any()))
				.thenReturn(Mono.just(ok("media.probe", probeJson)));
		if (transcriptJson != null) {
			when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
					.thenReturn(Mono.just(ok("speech.transcribe", transcriptJson)));
		}
		assertThatThrownBy(() -> service.collect(PROJECT_ID, "res-src", UUID.randomUUID()).block()).as(description)
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));
	}

	/** modelParts 文本内容拼接（W13 消费路径的等价读取）。 */
	private String modelPartsText(Evidence evidence) {
		List<ContentPart> parts = service.modelParts(evidence).block();
		StringBuilder text = new StringBuilder();
		for (ContentPart part : parts) {
			if (part instanceof ContentPart.Text textPart) {
				text.append(textPart.text()).append('\n');
			}
		}
		return text.toString();
	}

	/** 直造证据（绕过 collect）：帧/词/音轨完全由用例控制（TC-F3-08 预算与无效资源矩阵）。 */
	private static Evidence evidenceOf(List<Frame> frames, double durationSeconds) {
		return new Evidence(durationSeconds, false, "unknown", "4:3", true, List.of(), frames, null);
	}

	private static List<Frame> sixFrames(String handlePrefix) {
		List<Frame> frames = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			frames.add(new Frame(handlePrefix + "#f" + (2 * index + 1), 2 * index + 1));
		}
		return frames;
	}

	/** 句柄 res-…#fN → 帧序号 N/2（对 Mockito 幽灵调用的空/怪句柄回 0，不抛错）。 */
	private static int frameIndexOf(String handle) {
		int hash = handle == null ? -1 : handle.indexOf('#');
		if (hash < 0 || hash + 2 > handle.length()) {
			return 0;
		}
		try {
			return Integer.parseInt(handle.substring(hash + 2)) / 2;
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(digest.digest(bytes));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	// ── TC-F3-08-01（单元半边）：图像 parts 真实可解码、时间/sha 对应 ──────

	@Test
	@DisplayName("TC-F3-08-01 modelParts六帧→1文本+6图像parts：可解码、顺序对应时间锚点、sha/MIME/字节一致；不同帧不同sha")
	void tc_f3_08_01_model_parts_images_decode_and_match_anchors() {
		Map<String, byte[]> frameBytes = new LinkedHashMap<>();
		List<Color> colors = List.of(Color.RED, Color.RED, Color.GREEN, Color.GREEN, Color.BLUE, Color.BLUE);
		stubFramesPng(handle -> {
			byte[] png = pngOf(colors.get(frameIndexOf(handle)));
			frameBytes.put(handle, png);
			return png;
		});

		List<ContentPart> parts = service.modelParts(evidenceOf(sixFrames("res-vision"), 12.0)).block();
		assertThat(parts).isNotNull();
		assertThat(parts.size()).as("1 事实文本 + 6 图像 parts").isEqualTo(7);
		assertThat(parts.get(0)).isInstanceOf(ContentPart.Text.class);
		List<ContentPart> images = parts.stream().filter(part -> part instanceof ContentPart.Image).toList();
		assertThat(images.size()).isEqualTo(6);
		String facts = ((ContentPart.Text) parts.get(0)).text();
		List<?> rawAnchors = (List<?>) HypitJson.read(facts).get("frames");
		assertThat(rawAnchors).isNotNull();
		List<Map<String, Object>> anchors = rawAnchors.stream().map(HypitJson::mapValue).toList();
		assertThat(anchors.size()).isEqualTo(6);
		for (int index = 0; index < 6; index++) {
			ContentPart.Image image = (ContentPart.Image) images.get(index);
			String url = image.url();
			assertThat(url).as("第 %d 帧为 data URI", index).startsWith("data:image/png;base64,");
			byte[] decoded = java.util.Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
			// 真实解码：可解码、尺寸正确、字节与取证读取一致（sha 对应时间锚点）。
			BufferedImage restored = assertDecodable(decoded);
			assertThat(restored.getWidth()).isEqualTo(320);
			assertThat(restored.getHeight()).isEqualTo(240);
			Map<String, Object> anchor = anchors.get(index);
			String handle = (String) anchor.get("handle");
			assertThat(decoded).as("图像字节与资源读取一致").isEqualTo(frameBytes.get(handle));
			assertThat(anchor.get("sha256")).isEqualTo(sha256Hex(decoded));
			assertThat(anchor.get("mimeType")).isEqualTo("image/png");
			assertThat(((Number) anchor.get("bytes")).longValue()).isEqualTo(decoded.length);
			assertThat(((Number) anchor.get("timestampSeconds")).doubleValue()).as("图像顺序对应时间锚点")
					.isEqualTo(2 * index + 1);
		}
		// 不同帧输入（红红绿绿蓝蓝 vs 蓝蓝绿绿红红）→ 不同 sha 集合（观察随输入变化）。
		List<String> firstShas = anchors.stream().map(anchor -> (String) anchor.get("sha256")).toList();
		stubFramesPng(handle -> pngOf(List.of(Color.BLUE, Color.BLUE, Color.GREEN, Color.GREEN, Color.RED, Color.RED)
				.get(frameIndexOf(handle))));
		List<ContentPart> swapped = service.modelParts(evidenceOf(sixFrames("res-vision"), 12.0)).block();
		assertThat(swapped).isNotNull();
		String swappedFacts = ((ContentPart.Text) swapped.get(0)).text();
		List<?> swappedRaw = (List<?>) HypitJson.read(swappedFacts).get("frames");
		assertThat(swappedRaw).isNotNull();
		List<String> secondShas = swappedRaw.stream().map(item -> (String) HypitJson.mapValue(item).get("sha256"))
				.toList();
		assertThat(firstShas).as("同位置帧 sha 随输入变化").isNotEqualTo(secondShas);
		assertReleased(allocated);
	}

	private static BufferedImage assertDecodable(byte[] png) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
			assertThat(image).as("帧必须真实可解码").isNotNull();
			return image;
		} catch (IOException error) {
			throw new AssertionError("帧解码抛错：" + error.getMessage(), error);
		}
	}

	// ── TC-F3-08-02：帧预算与无效资源（逐项单变；失败零模型调用） ──────────

	@Test
	@DisplayName("TC-F3-08-02 帧预算矩阵：4MiB边界过/4MiB+1拒/24MiB边界过/累加器独立拒/7帧/空列表/重复句柄/越界时间/坏PNG/text-plain/404")
	void tc_f3_08_02_frame_budget_and_invalid_resources_matrix() throws Exception {
		byte[] png = pngOf(Color.RED);
		// ① 单帧 4MiB 边界 + 合计 24MiB 边界（6×4MiB 恰好达上限，全部放行）。
		byte[] exactFrame = padPng(png, HypitReferenceEvidenceService.MAX_FRAME_BYTES);
		stubFramesPng(handle -> exactFrame);
		List<ContentPart> boundary = service.modelParts(evidenceOf(sixFrames("res-budget"), 12.0))
				.block(java.time.Duration.ofSeconds(60));
		assertThat(boundary.size()).as("4MiB×6=24MiB 合法边界放行").isEqualTo(7);
		assertReleased(allocated);

		// ② 单帧 4MiB+1：聚合层先拒（确定性无效，模型调用前失败）。
		resetResourceStubs();
		byte[] oversizeFrame = padPng(png, HypitReferenceEvidenceService.MAX_FRAME_BYTES + 1);
		when(resources.open(anyString(), any())).thenReturn(Mono.just(responseOf(200, "image/png", oversizeFrame)));
		assertNotReady(evidenceOf(sixFrames("res-budget"), 12.0), "单帧 4MiB+1 必须拒绝");
		assertReleased(allocated);

		// ③ 合计预算累加器独立验证（不被单帧先拒掩盖）：每帧均 ≤4MiB，累计 24MiB 后再 +1KiB 越界。
		HypitReferenceEvidenceService.FrameBudget budget = new HypitReferenceEvidenceService.FrameBudget();
		for (int index = 0; index < 6; index++) {
			budget.accept(HypitReferenceEvidenceService.MAX_FRAME_BYTES);
		}
		assertThat(budget.totalBytes()).isEqualTo(HypitReferenceEvidenceService.MAX_TOTAL_FRAME_BYTES);
		assertThatThrownBy(() -> budget.accept(1024)).as("合计 24MiB+1KiB 越界（单帧仅 1KiB，先过单帧闸）")
				.isInstanceOf(IntelligenceException.class);
		assertThatThrownBy(() -> budget.accept(HypitReferenceEvidenceService.MAX_FRAME_BYTES + 1)).as("单帧上限独立生效")
				.isInstanceOf(IntelligenceException.class);

		// ④ 7 帧：帧数上限拒绝。
		resetResourceStubs();
		stubFramesPng(handle -> png);
		List<Frame> seven = new ArrayList<>(sixFrames("res-count"));
		seven.add(new Frame("res-count#f13", 13));
		assertNotReady(evidenceOf(seven, 14.0), "7 帧必须拒绝");

		// ⑤ 空列表：拒绝（模型调用前失败）。
		assertNotReady(evidenceOf(List.of(), 12.0), "空帧列表必须拒绝");

		// ⑥ 重复句柄：拒绝。
		List<Frame> duplicated = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			duplicated.add(new Frame("res-dup#same", 2 * index + 1));
		}
		assertNotReady(evidenceOf(duplicated, 12.0), "重复句柄必须拒绝");

		// ⑦ 越界时间（timestamp > durationSeconds）：拒绝。
		List<Frame> outOfRange = new ArrayList<>(sixFrames("res-range"));
		outOfRange.set(5, new Frame("res-range#f99", 99));
		assertNotReady(evidenceOf(outOfRange, 12.0), "越界时间必须拒绝");

		// ⑧ 坏 PNG（可 base64 解码但不可解码的伪 PNG 字节）。
		resetResourceStubs();
		when(resources.open(anyString(), any()))
				.thenReturn(Mono.just(responseOf(200, "image/png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9})));
		assertNotReady(evidenceOf(sixFrames("res-badpng"), 12.0), "坏 PNG 必须拒绝");
		assertReleased(allocated);

		// ⑨ text/plain：类型拒绝（响应体排空并释放）。
		resetResourceStubs();
		when(resources.open(anyString(), any()))
				.thenReturn(Mono.just(responseOf(200, "text/plain", "not an image".getBytes())));
		assertNotReady(evidenceOf(sixFrames("res-textplain"), 12.0), "text/plain 必须拒绝");
		assertReleased(allocated);

		// ⑩ 资源 404：基础设施类失败（可重试），不吞成未就绪。
		resetResourceStubs();
		when(resources.open(anyString(), any())).thenReturn(Mono.just(responseOf(404, null, new byte[0])));
		assertThatThrownBy(() -> service.modelParts(evidenceOf(sixFrames("res-404"), 12.0)).block()).as("资源 404 按不可用透传")
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo("hypit_backend_unavailable"));
		// 注：30s 读取超时属 W15.open 既有 block 预算（§9.4），单元层以 404/I-O 错误覆盖同一映射路径。
	}

	/** 独立断言（矩阵内复用）：NOT_READY_CODE 拒绝。 */
	private void assertNotReady(Evidence evidence, String description) {
		assertThatThrownBy(() -> service.modelParts(evidence).block()).as(description).isInstanceOfSatisfying(
				IntelligenceException.class,
				error -> assertThat(error.code()).isEqualTo(HypitReferenceEvidenceService.NOT_READY_CODE));
	}

	private void resetResourceStubs() {
		Mockito.reset(resources);
	}

	// ── TC-F3-08-02（W13 半边）：预算失败零模型调用、无假分析 ──────────────

	@Test
	@DisplayName("TC-F3-08-02 超限帧经W13：executeIndependentPrepared零调用、不产假分析（未就绪如实返回）")
	void tc_f3_08_02_over_limit_reaches_no_model_call() {
		FrozenTextExecutionService frozen = Mockito.mock(FrozenTextExecutionService.class);
		HypitAssetService assetService = assetServiceWith(frozen);
		// 资源层单帧超限：collect 正常回 6 帧，但读取第 1 帧即 4MiB+1 越界。
		when(resources.open(anyString(), any())).thenReturn(Mono.just(responseOf(200, "image/png",
				padPng(pngOf(Color.RED), HypitReferenceEvidenceService.MAX_FRAME_BYTES + 1))));
		HypitAssetService.ReferenceAnalysisOutcome outcome = assetService
				.analyzeReference(ACCOUNT, PROJECT_ID, ASSET_ID, UUID.randomUUID()).block();
		assertThat(outcome).isNotNull();
		assertThat(outcome.ready()).as("超限帧不得产分析").isFalse();
		assertThat(outcome.notReadyReason()).contains("未就绪");
		assertThat(outcome.aiRunId()).as("未执行模型无 runId").isNull();
		verify(frozen, never()).executeIndependentPrepared(any(), any(), any(), anyInt(), any(), any(), any(), any());
		verify(commands, never()).insert(any(), eq("reference.analyze"), any(), anyString(), anyString(), anyString(),
				any());
		assertReleased(allocated);
	}

	// ── TC-F3-08-01（W13 半边）：多模态消息经 executeIndependentPrepared；解析不补造 ──

	@Test
	@DisplayName("TC-F3-08-01 W13多模态消息直传平台入口（2文本+6图像parts）；模型无evidence段保留空证据不补造；runId可关联")
	void tc_f3_08_01_w13_multimodal_message_and_no_fabricated_evidence() {
		FrozenTextExecutionService frozen = Mockito.mock(FrozenTextExecutionService.class);
		UUID runId = RUN_ID;
		String noEvidenceSynthesis = """
				{"segments":[
				 {"index":0,"startSeconds":0,"endSeconds":12,"summary":"全覆盖但模型未给证据"}],
				 "systems":[],"events":[],"openQuestions":["模型未提供帧级证据"]}
				""";
		when(frozen.executeIndependentPrepared(any(), any(), any(), anyInt(), any(), any(), any(), any())).thenAnswer(
				invocation -> Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(
						// Traced.value 是 transform 之后的结果（completion.content()），即分段 JSON 字符串。
						noEvidenceSynthesis.replace("\n", " "), runId, "qwen", "qwen-plus", null, false)));
		HypitAssetService assetService = assetServiceWith(frozen);

		HypitAssetService.ReferenceAnalysisOutcome outcome = assetService
				.analyzeReference(ACCOUNT, PROJECT_ID, ASSET_ID, UUID.randomUUID()).block();
		assertThat(outcome).isNotNull();
		assertThat(outcome.ready()).as("合法链路产分析").isTrue();
		assertThat(outcome.aiRunId()).as("onPrepared 捕获的 runId 并入可追踪证据").isEqualTo(runId);

		// 平台入口收到的用户消息：多模态 parts = 2 文本（事实 JSON + assetId）+ 6 图像。
		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> messages = org.mockito.ArgumentCaptor
				.forClass(List.class);
		verify(frozen, times(1)).executeIndependentPrepared(any(), any(), messages.capture(), anyInt(), any(), any(),
				any(), any());
		List<com.grassland.intelligence.ai.ChatMessage> sent = messages.getValue();
		assertThat(sent.get(0).content()).as("W62 系统提示生效").contains("参考素材分析师").contains("gap");
		com.grassland.intelligence.ai.ChatMessage user = sent.get(1);
		assertThat(user.multimodal()).as("用户消息为多模态 parts").isTrue();
		List<ContentPart> userParts = user.parts();
		assertThat(userParts.stream().filter(part -> part instanceof ContentPart.Image).count()).isEqualTo(6);
		assertThat(userParts.stream().filter(part -> part instanceof ContentPart.Text).count()).isEqualTo(2);

		// W62/W13 解析纪律：模型未给 evidence 的段保留空证据（不补造「综合模型分段」）。
		assertThat(outcome.analysis().segments()).hasSize(1);
		assertThat(outcome.analysis().segments().get(0).evidence()).as("不补造假证据").isEmpty();
		assertThat(outcome.analysis().openQuestions()).contains("模型未提供帧级证据");
		assertReleased(allocated);
	}

	// ── TC-F3-08-03：取消与响应式资源清理 ────────────────────────────────

	/** 模拟 Netty event-loop 线程（实现 NonBlocking——解码/复制绝不能跑在它上面）。 */
	static final class EventLoopLikeThread extends Thread implements NonBlocking {

		EventLoopLikeThread(Runnable action) {
			super(action, "event-loop-like");
		}
	}

	@Test
	@DisplayName("TC-F3-08-03 第三帧屏障取消：后续帧零读取、已收buffer全释放、取消不产出parts")
	void tc_f3_08_03_cancel_at_barrier_releases_buffers_and_stops_reads() throws Exception {
		byte[] png = pngOf(Color.RED);
		AtomicInteger opened = new AtomicInteger();
		AtomicInteger produced = new AtomicInteger();
		List<io.netty.buffer.ByteBuf> beforeBarrier = new ArrayList<>();

		when(resources.open(anyString(), any())).thenAnswer(invocation -> {
			int index = opened.getAndIncrement();
			if (index < 2) {
				io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer(png.length);
				buf.writeBytes(png);
				beforeBarrier.add(buf);
				return Mono.just(new ResourceResponse(200, pngHeaders(), Flux.just(BUFFER_FACTORY.wrap(buf))));
			}
			if (index == 2) {
				// 屏障：第三帧字节流挂起，直到订阅方取消/超时——模拟慢资源。
				return Mono.just(new ResourceResponse(200, pngHeaders(), Flux.never()));
			}
			throw new IllegalStateException("取消后不应读取第 4+ 帧，index=" + index);
		});
		Disposable disposable = service.modelParts(evidenceOf(sixFrames("res-cancel"), 12.0))
				.doOnNext(parts -> produced.incrementAndGet()).subscribe();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (opened.get() < 3 && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(opened.get()).as("顺序读取推进到第 3 帧").isEqualTo(3);
		disposable.dispose();
		Thread.sleep(200);
		assertThat(opened.get()).as("取消即停止后续帧读取").isEqualTo(3);
		assertThat(produced.get()).as("取消不产出 parts（不执行后续模型）").isZero();
		assertReleased(beforeBarrier);
	}

	@Test
	@DisplayName("TC-F3-08-03 第三帧I/O错误：错误如实透传、前序buffer全释放、无后续帧读取；解码线程非NonBlocking")
	void tc_f3_08_03_io_error_propagates_and_releases() throws Exception {
		byte[] png = pngOf(Color.RED);
		AtomicInteger opened = new AtomicInteger();
		List<io.netty.buffer.ByteBuf> beforeError = new ArrayList<>();
		when(resources.open(anyString(), any())).thenAnswer(invocation -> {
			int index = opened.getAndIncrement();
			if (index < 2) {
				io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer(png.length);
				buf.writeBytes(png);
				beforeError.add(buf);
				// 在 NonBlocking（event-loop 形状）线程上发射：复制/解码必须被切到 boundedElastic。
				return Mono.just(
						new ResourceResponse(200, pngHeaders(), Flux.create(sink -> new EventLoopLikeThread(() -> {
							sink.next(BUFFER_FACTORY.wrap(buf));
							sink.complete();
						}).start())));
			}
			if (index == 2) {
				return Mono.just(new ResourceResponse(200, pngHeaders(), Flux.error(new IOException("帧流 I/O 中断"))));
			}
			throw new IllegalStateException("错误后不应读取第 4+ 帧，index=" + index);
		});
		assertThatThrownBy(() -> service.modelParts(evidenceOf(sixFrames("res-ioerror"), 12.0)).block())
				.as("I/O 错误如实透传（不吞成成功）").hasMessageContaining("I/O 中断");
		assertThat(opened.get()).as("错误即停止后续帧读取").isEqualTo(3);
		assertReleased(beforeError);
		// 成功路径同构验证调度纪律：event-loop 形状线程发射的帧，最终值在 boundedElastic 组装
		// （publishOn 切换存在——复制/解码/组装都不在 NonBlocking 线程上执行）。
		resetResourceStubs();
		when(resources.open(anyString(), any())).thenAnswer(invocation -> {
			io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer(png.length);
			buf.writeBytes(png);
			return Mono.just(new ResourceResponse(200, pngHeaders(), Flux.create(sink -> new EventLoopLikeThread(() -> {
				sink.next(BUFFER_FACTORY.wrap(buf));
				sink.complete();
			}).start())));
		});
		AtomicReference<Thread> valueThread = new AtomicReference<>();
		List<ContentPart> parts = service.modelParts(evidenceOf(sixFrames("res-thread"), 12.0))
				.doOnNext(value -> valueThread.set(Thread.currentThread())).block();
		assertThat(parts).isNotNull();
		assertThat(valueThread.get().getName()).as("复制/解码不在 event loop（NonBlocking）线程上").contains("boundedElastic");
		assertReleased(allocated);
	}

	private static HttpHeaders pngHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType("image/png"));
		return headers;
	}

	// ── W13 装配（真实 W14 + mock 终端依赖） ──────────────────────────────

	/** 组装 HypitAssetService：owner/asset/collect 真实（sidecar 桩），平台入口可注入 mock。 */
	private HypitAssetService assetServiceWith(FrozenTextExecutionService frozen) {
		HypitProjectRepository projects = Mockito.mock(HypitProjectRepository.class);
		when(projects.findOwnerStatus(anyString(), any())).thenReturn(Mono.just("ready"));
		HypitAssetRepository assets = Mockito.mock(HypitAssetRepository.class);
		when(assets.findById(any(), any())).thenReturn(Mono.just(new AssetRow(ASSET_ID, PROJECT_ID, null,
				"res-src-video", "reference", "upload", null, "a".repeat(64), "video/mp4", 1024, null, null, null,
				"ready", 1, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
		// collect 真实 wire（probe/frames/transcribe）。
		when(sidecar.commandAsync(anyString(), eq("media.probe"), any()))
				.thenReturn(Mono.just(ok("media.probe", PROBE_AUDIO)));
		when(sidecar.commandAsync(anyString(), eq("media.frames"), any()))
				.thenReturn(Mono.just(ok("media.frames", "{" + framesAt(1, 3, 5, 7, 9, 11) + "}")));
		when(sidecar.commandAsync(anyString(), eq("speech.transcribe"), any()))
				.thenReturn(Mono.just(ok("speech.transcribe", TRANSCRIPT_WORDS)));
		return new HypitAssetService(assets, projects, commands, Mockito.mock(HypitJobRepository.class),
				Mockito.mock(HypitJobEventRepository.class), resources, sidecar,
				new HypitProperties(true, "http://127.0.0.1:9240", "unit-internal-token-0123456789abcdef", "", "", ""),
				Mockito.mock(TransactionalOperator.class), Mockito.mock(DatabaseClient.class), frozen, analyses,
				Mockito.mock(HypitAssetUploadService.class), service);
	}
}
