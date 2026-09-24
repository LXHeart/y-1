package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.ai.run.ProviderKeyDecryptor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextPolicy.SpeechTextSegment;
import com.grassland.intelligence.speech.SpeechProviderRegistry;
import com.grassland.intelligence.speech.SpeechRecognitionProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 音频桥测试（任务书 #105D C105D-04 / TC105D-04-03、TC105D-04-04）：空文本转写 → LLM/TTS 零调用、STT
 * 用量按秒保留；TTS 合法首块实时到达、奇字节/坏协议不产生可用音频。STT provider 为最外层 Fake（字节 接口真实调用链）；TTS 走
 * WireMock 流。
 */
@DisplayName("DigitalHumanAudioBridge (C105D-04)")
class DigitalHumanAudioBridgeTest extends IntelligenceItSupport {

	static final org.testcontainers.containers.GenericContainer<?> REDIS = new org.testcontainers.containers.GenericContainer<>(
			org.testcontainers.utility.DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	DigitalHumanPreviewService previewService;
	@Autowired
	DigitalHumanPreviewRepository previewRepository;
	@Autowired
	DatabaseClient db;
	@Autowired
	EnvelopeEncryption encryption;

	private WireMockServer tts;
	private final AtomicInteger sttCalls = new AtomicInteger();

	@BeforeEach
	void start() {
		tts = new WireMockServer(0);
		tts.start();
		seedPreviewEnvironment();
	}

	@AfterEach
	void stop() {
		tts.stop();
	}

	/** 清理 DH/账务残留 + 种带凭据的 video_tts 平台行（指向 WireMock，受信 origin 基类已登记）。 */
	private void seedPreviewEnvironment() {
		db.sql("DELETE FROM dh_preview").then().then(db.sql("DELETE FROM dh_invocation").then())
				.then(db.sql("DELETE FROM dh_event").then()).then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_operation").then())
				.then(db.sql("DELETE FROM dh_session").then()).block(Duration.ofSeconds(10));
		// WireMock 每测换端口：自清理按固定凭据名（跨端口也能删干净，避免 name 唯一索引冲突）。
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE name = 'it-dh-tts'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name = 'it-dh-tts')"
						+ " OR capability = 'video_tts'").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-dh-tts'").then())
				.block(Duration.ofSeconds(10));
		// 受信 origin 基类只为 QWEN 登记：preview 走真实 PlatformProviderPolicy，端点必须指向 QWEN。
		String encrypted = encryption.encrypt("sk-it-dh-tts");
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				        masked_hint, enabled)
				    VALUES ('it-dh-tts', 'openai-compatible', :baseUrl, :encrypted, 'v1', 'sk-***tts', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'video_tts','primary','openai-compatible','qwen-plus',:baseUrl,'healthy',true,1,cred.id FROM cred
				""").bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted).then().block(Duration.ofSeconds(10));
		QWEN.resetRequests();
		QWEN.stubFor(post(urlEqualTo("/audio/speech"))
				.willReturn(ok().withBody(new byte[48_000]).withHeader("Content-Type", "audio/pcm")));
	}

	// ---------- Fake STT（byte[] 接口，记录调用） ----------

	private SpeechRecognitionProvider emptyTextStt() {
		return new SpeechRecognitionProvider() {
			@Override
			public String provider() {
				return "qwen";
			}

			@Override
			public Mono<Result> transcribe(Command command) {
				sttCalls.incrementAndGet();
				// 静音：空文本、按实际时长计费 3 秒。
				return Mono.just(new Result("", "zh", 0, 0, false, 3));
			}
		};
	}

	private DigitalHumanAudioBridge bridge(SpeechRecognitionProvider stt) {
		return new DigitalHumanAudioBridge(new SpeechProviderRegistry(List.of(stt)), new DigitalHumanTtsClient(
				DnsPinningResolver.create(), org.mockito.Mockito.mock(PlatformProviderPolicy.class)));
	}

	private static PreparedInvocation invocationOf(String providerName, String baseUrl, String bearer) {
		ProviderResolution resolution = ProviderResolution.platform(UUID.randomUUID(), providerName, baseUrl,
				"speech-model-x", 1, null);
		AiExecutionService.ExecutionContext context = new AiExecutionService.ExecutionContext(UUID.randomUUID(), null,
				UUID.randomUUID().toString(), "voice", resolution,
				ModelBudgetService.BudgetCheckResult.allowed(null, null, 0, 0), UUID.randomUUID(), null, null, false,
				bearer, "v1", 0, 0, null);
		return new PreparedInvocation(UUID.randomUUID(), UUID.randomUUID(), DigitalHumanRecords.InvocationStage.stt,
				UUID.randomUUID(), context, Instant.now().plusSeconds(90));
	}

	private static byte[] wavOf(int seconds) {
		byte[] pcm = new byte[16_000 * 2 * seconds];
		return DigitalHumanAudioBridge.wavOf(pcm);
	}

	// ---------- TC105D-04-03：静音但 STT 已发 ----------

	@Test
	void tc105d_04_03_emptyTranscriptStopsPipelineAndKeepsSttUsage() {
		DigitalHumanAudioBridge bridge = bridge(emptyTextStt());
		PreparedInvocation invocation = invocationOf("qwen", "http://stt.local", "key");
		SpeechRecognitionProvider.Result result = bridge
				.transcribe(invocation, wavOf(3), Duration.ofSeconds(3).toMillis()).block(Duration.ofSeconds(10));
		assertThat(result.text()).isEmpty();
		assertThat(result.billedSeconds()).isEqualTo(3);

		// 空文本：LLM/TTS 零调用（WireMock 上无任何请求）。
		assertThat(tts.findAll(postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/.*"))))
				.isEmpty();
		// STT 真实派发恰好一次（用量保留由调用方按 billedSeconds 入 ai_run）。
		assertThat(sttCalls.get()).isEqualTo(1);
		// 时长与样本数不符 → 拒绝且零 STT 派发（先验证后副作用）。
		assertThatThrownBy(() -> bridge.transcribe(invocation, wavOf(3), Duration.ofSeconds(10).toMillis())
				.block(Duration.ofSeconds(10))).hasMessageContaining("时长与样本数不符");
		assertThat(sttCalls.get()).isEqualTo(1);
	}

	@Test
	void tc105d_04_03_badWavRejectedBeforeProvider() {
		DigitalHumanAudioBridge bridge = bridge(emptyTextStt());
		PreparedInvocation invocation = invocationOf("qwen", "http://stt.local", "key");
		assertThatThrownBy(() -> bridge.transcribe(invocation, new byte[20], 0).block(Duration.ofSeconds(10)))
				.hasMessageContaining("长度不合法");
		assertThatThrownBy(() -> bridge.transcribe(invocation, new byte[0], 0).block(Duration.ofSeconds(10)))
				.isInstanceOf(Exception.class);
		assertThat(sttCalls.get()).isZero();
	}

	// ---------- TC105D-04-04：TTS 非流与坏 PCM ----------

	@Test
	void tc105d_04_04_firstChunkArrivesImmediatelyAndStreamCompletes() {
		byte[] chunk = new byte[4800]; // 100ms of 24k s16le
		tts.stubFor(post(urlEqualTo("/audio/speech"))
				.willReturn(ok().withBody(chunk).withHeader("Content-Type", "audio/pcm")));
		DigitalHumanAudioBridge bridge = bridge(emptyTextStt());
		PreparedInvocation invocation = invocationOf("qwen", tts.baseUrl(), "key");
		List<byte[]> received = bridge.streamTts(invocation, new SpeechTextSegment(0, "你好。", 0), "preset-zh-natural-01")
				.collectList().block(Duration.ofSeconds(10));
		assertThat(received).isNotEmpty();
		assertThat(received.get(0).length % 2).isZero();
		// 请求形状：audio/speech + response_format=pcm（受信端点由调用方冻结）。
		tts.verify(1, postRequestedFor(urlEqualTo("/audio/speech")).withRequestBody(
				com.github.tomakehurst.wiremock.client.WireMock.containing("\"response_format\":\"pcm\"")));
	}

	@Test
	void tc105d_04_04_oddByteChunkFailsWithoutUsableAudio() {
		// 奇数字节=半 sample：协议损坏 → 502，不能静默吞半 sample 继续出可用录制。
		tts.stubFor(post(urlEqualTo("/audio/speech"))
				.willReturn(ok().withBody(new byte[]{1, 2, 3}).withHeader("Content-Type", "audio/pcm")));
		DigitalHumanAudioBridge bridge = bridge(emptyTextStt());
		PreparedInvocation invocation = invocationOf("qwen", tts.baseUrl(), "key");
		Flux<byte[]> stream = bridge.streamTts(invocation, new SpeechTextSegment(0, "你好。", 0), "preset-zh-natural-01");
		assertThatThrownBy(() -> stream.collectList().block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(com.grassland.intelligence.security.IntelligenceException.class,
						error -> assertThat(error.code()).isEqualTo("dh_tts_protocol_error"))
				.hasMessageContaining("损坏");
	}

	@Test
	void tc105d_04_04_previewFixedSentenceLifecycle() {
		var actor = new DigitalHumanAuthorization.PersonalActor(UUID.randomUUID().toString());
		UUID requestId = UUID.randomUUID();
		var outcome = previewService.create(actor, requestId, "preset-zh-natural-01", 1).block(Duration.ofSeconds(20));
		assertThat(outcome.state()).as("固定句试听一次到位（TTS 桩即时返回 PCM）").isEqualTo("ready");
		// 读音频：44 字节头 WAV、易失（不写素材库：本 actor 的 media_reference 无新增——全表 count 在共享
		// 容器全量跑会被其他类残留行误伤，105F 已定位的顺序敏感 flaky）。
		byte[] wav = previewService.readAudio(actor, UUID.fromString(outcome.id())).block(Duration.ofSeconds(10));
		assertThat(wav.length).isGreaterThan(44);
		assertThat(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
		assertThat(db.sql("SELECT count(*) AS n FROM media_reference WHERE owner_account_id = :owner")
				.bind("owner", actor.accountId()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("试听不写素材库").isZero();
		// 计量入 ai_run（preview invocation 结算实际秒）。
		assertThat(db
				.sql("SELECT count(*) AS n FROM ai_run run JOIN dh_invocation inv ON inv.ai_run_id = run.id"
						+ " WHERE inv.stage = 'preview' AND run.status = 'completed' AND run.video_seconds >= 1")
				.map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10))).isEqualTo(1L);
		// 幂等：同 requestId 重放返回原 id、不免费重调 TTS。
		var replay = previewService.create(actor, requestId, "preset-zh-natural-01", 1).block(Duration.ofSeconds(20));
		assertThat(replay.id()).isEqualTo(outcome.id());
		QWEN.verify(1, postRequestedFor(urlEqualTo("/audio/speech")));
	}

	@Test
	void tc105d_04_04_previewRateLimitAndExpiry() {
		var actor = new DigitalHumanAuthorization.PersonalActor(UUID.randomUUID().toString());
		var first = previewService.create(actor, UUID.randomUUID(), "preset-zh-natural-01", 1)
				.block(Duration.ofSeconds(20));
		previewService.create(actor, UUID.randomUUID(), "preset-zh-natural-01", 1).block(Duration.ofSeconds(20));
		previewService.create(actor, UUID.randomUUID(), "preset-zh-natural-01", 1).block(Duration.ofSeconds(20));
		// 每分钟 3 次上限：第 4 次 429。
		assertThatThrownBy(() -> previewService.create(actor, UUID.randomUUID(), "preset-zh-natural-01", 1)
				.block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(com.grassland.intelligence.security.IntelligenceException.class,
						e -> assertThat(e.status()).isEqualTo(429));
		// 过期：expires_at 推到过去 → 状态 expired、音频 410（不重调 provider）。
		db.sql("UPDATE dh_preview SET expires_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", first.id()).then().block(Duration.ofSeconds(10));
		assertThat(previewService.get(actor, UUID.fromString(first.id())).block(Duration.ofSeconds(10)).state())
				.isEqualTo("expired");
		assertThatThrownBy(
				() -> previewService.readAudio(actor, UUID.fromString(first.id())).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(com.grassland.intelligence.security.IntelligenceException.class,
						e -> assertThat(e.status()).isEqualTo(410));
		QWEN.verify(3, postRequestedFor(urlEqualTo("/audio/speech")));
	}

	@Test
	void tc105d_04_04_upstreamErrorMapsToProviderFailure() {
		tts.stubFor(post(urlEqualTo("/audio/speech")).willReturn(aResponse().withStatus(500)));
		DigitalHumanAudioBridge bridge = bridge(emptyTextStt());
		PreparedInvocation invocation = invocationOf("qwen", tts.baseUrl(), "key");
		assertThatThrownBy(
				() -> bridge.streamTts(invocation, new SpeechTextSegment(0, "你好。", 0), "preset-zh-natural-01")
						.collectList().block(Duration.ofSeconds(10)))
				.hasMessageContaining("TTS 上游暂不可用");
	}
}
