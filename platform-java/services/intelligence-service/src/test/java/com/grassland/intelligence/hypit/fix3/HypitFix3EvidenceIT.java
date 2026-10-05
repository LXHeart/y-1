package com.grassland.intelligence.hypit.fix3;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.hypit.agent.HypitAgentWorker;
import com.grassland.intelligence.hypit.asset.HypitAssetService;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C107F3-07（任务书 107-fix-3 §12.2 TC-F3-07-01～03）：探针与转写契约归一化——真实 HTTP/PG 全链。
 *
 * <p>
 * 真 PostgreSQL + 真 HTTP 端点 + WireMock sidecar 按 Node 真实 wire 形状回执（嵌套 probe /
 * frames{handle,timestampSeconds} / passages[].words[] 16kHz 样本锚点，契约
 * hypit-api.v1.json evidenceWire）+ 平台模型末跳替身（QWEN /chat/completions）捕获综合步骤
 * 实际收到的强类型事实。分析触发走既有 agent job（worker.runOnce 真实推进），回读走真实 GET
 * reference-analysis。
 *
 * <ul>
 * <li>TC-F3-07-01 有声/无声 12s：probe/frames/transcribe 各一次、六中点 [1,3,5,7,9,11]、
 * 无声零转写调用且 audioTrack=ABSENT。</li>
 * <li>TC-F3-07-02 逐词时间与空语音：你好[0,16000)、草场[64000,80000) 以秒（0~1/4~5）按序进入
 * 模型请求；passages=[] 是已测无语音（转写就绪、无伪造台词）。</li>
 * <li>TC-F3-07-03 拒绝：坏 probe（duration=0）waiting_input、无 reference.analyze 成功行、
 * 旧分析不变；词缺 startSample → 分析持久但 WAITING_INPUT ≠ SUCCEEDED。</li>
 * <li>TC-F3-08-01 真实图像到达模型：W13→W14（内部认证读帧）→FrozenTextExecutionService→ W40 严格
 * fixture（末跳真实解码每张图）；红绿蓝与蓝绿红两输入观察/分段不同；ai_run 可关联。</li>
 * <li>TC-F3-08-02 帧预算（IT 半边）：单帧超 4MiB → 未就绪、模型末跳零调用、旧分析不被覆盖。</li>
 * </ul>
 * 仅 sidecar 工具与外部商业模型末跳为替身（模型末跳=W40 fixture 子进程，真实解码图像）； owner/PG/业务服务/HTTP
 * 全真实。
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix3EvidenceIT extends IntelligenceItSupport {

	/** §12.2 共享前提 F-OWN A。 */
	private static final String OWNER_A = "aaaaaaaa-1073-4000-8000-000000000001";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	/** C107F3-08（TC-F3-08-01）：W40 严格 fixture 作为平台模型末跳子进程（真实解码图像）。 */
	private static Process PROVIDER_PROCESS;
	private static int PROVIDER_PORT;
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static final String FULL_COVERAGE_SYNTHESIS = """
			{"segments":[
			 {"index":0,"startSeconds":0,"endSeconds":4,"summary":"开场","evidence":[{"assetId":"__A__","sourceTimeSeconds":1.0,"note":"frame@1"}]},
			 {"index":1,"startSeconds":4,"endSeconds":8,"summary":"中段","evidence":[{"assetId":"__A__","sourceTimeSeconds":5.0,"note":"frame@5"}]},
			 {"index":2,"startSeconds":8,"endSeconds":12,"summary":"结尾","evidence":[{"assetId":"__A__","sourceTimeSeconds":9.0,"note":"frame@9"}]}],
			 "systems":[],"events":[],"openQuestions":[]}
			""";

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitAssetService assets;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix3-evidence-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() throws Exception {
		SIDECAR.start();
		startProvider();
	}

	@AfterAll
	static void stopSidecar() {
		stopProvider();
		SIDECAR.stop();
	}

	/** W40 严格 fixture 子进程：随机空闲端口，/__health 就绪；仅替换外部商业模型末跳。 */
	private static void startProvider() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			PROVIDER_PORT = socket.getLocalPort();
		}
		java.nio.file.Path repoRoot = java.nio.file.Path.of("").toAbsolutePath().getParent().getParent().getParent();
		ProcessBuilder builder = new ProcessBuilder("node",
				repoRoot.resolve("tests/e2e/fixtures/hypit-fix2-text-provider.mjs").toString());
		builder.environment().put("HYPIT_FIX2_PROVIDER_PORT", String.valueOf(PROVIDER_PORT));
		// Java 平台闸门拒绝 IP 字面量环回 base-url（ProviderUrlGuard），base-url 走
		// localhost 主机名；fixture 双栈监听同时接受 IPv4/IPv6 环回连接。
		builder.environment().put("HYPIT_FIX2_PROVIDER_HOST", "::");
		builder.inheritIO();
		PROVIDER_PROCESS = builder.start();
		String health = "http://127.0.0.1:" + PROVIDER_PORT + "/__health";
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (System.nanoTime() < deadline) {
			if (!PROVIDER_PROCESS.isAlive()) {
				throw new IllegalStateException("W40 provider 子进程提前退出（node 缺失或 fixture 报错）");
			}
			try {
				if (HTTP.send(HttpRequest.newBuilder(URI.create(health)).timeout(Duration.ofSeconds(2)).GET().build(),
						java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
					return;
				}
			} catch (Exception notYet) {
				// 等待 provider 就绪
			}
			Thread.sleep(200);
		}
		throw new IllegalStateException("W40 provider 未在 30s 内就绪");
	}

	private static void stopProvider() {
		if (PROVIDER_PROCESS != null) {
			PROVIDER_PROCESS.destroy();
			try {
				if (!PROVIDER_PROCESS.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
					PROVIDER_PROCESS.destroyForcibly();
					PROVIDER_PROCESS.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
				}
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				PROVIDER_PROCESS.destroyForcibly();
			}
			PROVIDER_PROCESS = null;
		}
	}

	/** 真实可解码 PNG（320×240 纯色）——W14 读取/W40 解码消费同一字节。 */
	private static byte[] pngOf(Color color) throws Exception {
		BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 320, 240);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		SIDECAR.resetAll();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix3-evidence', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER_A).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		attachPlatformTextRow();
	}

	@AfterEach
	void sweep() {
		// 先撤 provider text 行：attachPlatformTextCredential 的 NOT EXISTS 守卫要求没有
		// 其它启用的 text 配置，否则后续用例（本类与共享容器的其它 IT）挂不上 QWEN。
		disposeProviderTextModel();
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER_A).then()
				.then(db.sql(
						"DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
						.bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:o, 'system')").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER_A).then())
				.block(Duration.ofSeconds(20));
	}

	private void attachPlatformTextRow() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE base_url = :baseUrl)").bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
		attachPlatformTextCredential();
	}

	// ── Node 真实 wire 桩（契约 hypit-api.v1.json evidenceWire） ─────────────

	/** probe 桩：嵌套 result.probe；duration/hasAudio 可注入缺陷形态。 */
	private void stubProbe(double duration, boolean hasAudio) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"p\",\"state\":\"succeeded\",\"result\":{\"probe\":{\"duration\":"
								+ duration + ",\"hasVideo\":true,\"hasAudio\":" + hasAudio
								+ ",\"width\":320,\"height\":240}}}")));
	}

	/** frames 桩：六中点 {handle,timestampSeconds}，无 aspectRatio。 */
	private void stubFrames(String handle) throws Exception {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.frames"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"f\",\"state\":\"succeeded\",\"result\":{\"frames\":["
								+ "{\"handle\":\"" + handle + "#f1\",\"timestampSeconds\":1}," + "{\"handle\":\""
								+ handle + "#f3\",\"timestampSeconds\":3}," + "{\"handle\":\"" + handle
								+ "#f5\",\"timestampSeconds\":5}," + "{\"handle\":\"" + handle
								+ "#f7\",\"timestampSeconds\":7}," + "{\"handle\":\"" + handle
								+ "#f9\",\"timestampSeconds\":9}," + "{\"handle\":\"" + handle
								+ "#f11\",\"timestampSeconds\":11}" + "],\"totalTimes\":6}}")));
		// C107F3-08：W14 现在经 W15.open 逐帧 GET /internal/v1/resources/{handle}——兜底桩
		// 服务全部帧句柄（灰色可解码 PNG；TC-F3-08 用 stubFrameResource 按句柄覆盖配色）。
		SIDECAR.stubFor(get(urlMatching("/internal/v1/resources/.*"))
				.willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(pngOf(Color.GRAY))));
	}

	/** 帧句柄 → WireMock urlMatching 模式（容忍原样与百分号编码两种 wire 形态）。 */
	private static String handleUrlPattern(String handle) {
		StringBuilder pattern = new StringBuilder("/internal/v1/resources/");
		for (char c : handle.toCharArray()) {
			String encoded = java.net.URLEncoder.encode(String.valueOf(c), java.nio.charset.StandardCharsets.UTF_8);
			String literal = "\\.[]{}()*+-?^$|".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
			pattern.append(encoded.equals(String.valueOf(c)) ? literal : "(?:" + literal + '|' + encoded + ")");
		}
		return pattern.toString();
	}

	/** 指定帧句柄回执真实可解码 PNG（新桩优先于 stubFrames 的兜底桩）。 */
	private void stubFrameResource(String frameHandle, byte[] png) {
		SIDECAR.stubFor(get(urlMatching(handleUrlPattern(frameHandle)))
				.willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(png)));
	}

	private void stubFrameResources(String handle, Color... colors) throws Exception {
		String[] suffixes = {"#f1", "#f3", "#f5", "#f7", "#f9", "#f11"};
		for (int index = 0; index < suffixes.length; index++) {
			stubFrameResource(handle + suffixes[index], pngOf(colors[index]));
		}
	}

	/** 合法 PNG 垫至 target 字节（IEND 之后填充，解码器忽略——预算在解码前先拒）。 */
	private static byte[] paddedPng(byte[] png, int target) {
		return java.util.Arrays.copyOf(png, target);
	}

	/** transcribe 桩：passages[].words[] 16kHz 样本锚点（TC-F3-07-02 你好/草场）。 */
	private void stubTranscribe(String passagesJson) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("speech.transcribe"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"t\",\"state\":\"succeeded\",\"result\":{\"language\":\"zh\","
								+ "\"sampleFrames\":192000,\"durationSec\":12.0,\"extracted\":true," + "\"passages\":"
								+ passagesJson + ",\"diagnostics\":[],"
								+ "\"evidenceHandle\":\"res-fix3-speech-evidence\"}}")));
	}

	private static final String WORDS_HELLO_CAOCHANG = """
			[{"startSample":0,"endSampleExclusive":80000,"words":[
			  {"text":"你好","startSample":0,"endSampleExclusive":16000,"score":0.9},
			  {"text":"草场","startSample":64000,"endSampleExclusive":80000}]}]""".replace("\n", "");

	private void stubQwenSynthesisAndPlanner(UUID assetId) {
		String synthesis = FULL_COVERAGE_SYNTHESIS.replace("__A__", assetId.toString()).replace("\n", " ");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).withRequestBody(containing("分析师"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + JSON.valueToTree(synthesis).toString()
								+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":100}}")));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).withRequestBody(containing("执行规划器")).willReturn(aResponse()
				.withStatus(200).withHeader("Content-Type", "application/json")
				.withBody("{\"choices\":[{\"message\":{\"content\":"
						+ "\"{\\\"actions\\\":[{\\\"kind\\\":\\\"knowledge.search\\\",\\\"input\\\":{\\\"query\\\":\\\"render\\\",\\\"limit\\\":2}}]}\""
						+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
	}

	// ── C107F3-08：W40 严格 fixture 作为平台 text 模型末跳 ────────────────

	/** localhost 主机名形态（平台闸门拒绝 IP 字面量环回 base-url，与 QWEN 同口径）。 */
	private static String providerBaseUrl() {
		return "http://localhost:" + PROVIDER_PORT;
	}

	/** 撤 QWEN text 行、登记 provider 受信 origin、种带凭据的 text 行（路由真实打到子进程）。 */
	private void attachProviderTextModel() {
		String baseUrl = providerBaseUrl();
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-fix3-vision-key");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE capability = 'text')").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'text'").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :b").bind("b", baseUrl).then())
				.block(Duration.ofSeconds(10));
		db.sql("INSERT INTO platform_trusted_origin(origin, label) VALUES (:o, 'W40 严格 fixture 模型末跳')"
				+ " ON CONFLICT (origin) DO NOTHING").bind("o", baseUrl).then().then(trustedOrigins.refresh())
				.block(Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-fix3-vision', 'qwen', :b, :encrypted, 'v1', 'sk-***fix3', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:b,'healthy',true,1,cred.id
				FROM cred
				WHERE NOT EXISTS (SELECT 1 FROM platform_model_config WHERE capability='text' AND enabled=true)
				""").bind("b", baseUrl).bind("encrypted", encrypted).then().block(Duration.ofSeconds(10));
	}

	/**
	 * 撤 provider 行（保留 QWEN origin）：后续用例的 attachPlatformTextCredential 才能重挂 QWEN。
	 */
	private void disposeProviderTextModel() {
		String baseUrl = providerBaseUrl();
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE base_url = :b))").bind("b", baseUrl).then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE base_url = :b)").bind("b", baseUrl)
						.then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :b").bind("b", baseUrl).then())
				.block(Duration.ofSeconds(10));
	}

	/**
	 * provider 观测面：最近调用（family + 多模态 imageParts/textChars，RULE-009 口径不落 data URI）。
	 */
	private JsonNode providerCalls() throws Exception {
		return JSON
				.readTree(HTTP.send(
						HttpRequest.newBuilder(URI.create(providerBaseUrl() + "/__calls"))
								.timeout(Duration.ofSeconds(5)).GET().build(),
						java.net.http.HttpResponse.BodyHandlers.ofString()).body());
	}

	private int providerReferenceAnalysisCalls() throws Exception {
		int count = 0;
		for (JsonNode call : providerCalls()) {
			if ("reference-analysis".equals(call.path("family").asText())) {
				count++;
			}
		}
		return count;
	}

	private JsonNode providerLastReferenceAnalysisCall() throws Exception {
		JsonNode last = null;
		for (JsonNode call : providerCalls()) {
			if ("reference-analysis".equals(call.path("family").asText())) {
				last = call;
			}
		}
		return last;
	}

	// ── TC-F3-08-01：真实图像到达模型（IT 半边，W40 末跳真实解码） ─────────

	@Test
	@DisplayName("TC-F3-08-01 真实图像到达模型：W14内部认证读帧→6图像parts→W40真实解码；红绿蓝vs蓝绿红分段不同；ai_run可关联")
	void tc_f3_08_01_real_images_reach_model_and_differ() throws Exception {
		attachProviderTextModel();
		int callsBefore = providerReferenceAnalysisCalls(); // provider 进程类内共享：按增量断言
		// 输入一：帧主色 红 红 绿 绿 蓝 蓝（帧时间锚点 1/3/5/7/9/11）。
		String redFirstSha = "88cc".repeat(16);
		UUID redFirstAsset = seedAsset(redFirstSha);
		String redFirstHandle = "it://fix3-" + redFirstSha.substring(0, 8) + ".mp4";
		stubProbe(12.0, true);
		stubFrames(redFirstHandle);
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubFrameResources(redFirstHandle, Color.RED, Color.RED, Color.GREEN, Color.GREEN, Color.BLUE, Color.BLUE);

		HypitAssetService.ReferenceAnalysisOutcome first = assets
				.analyzeReference(OWNER_A, projectId, redFirstAsset, UUID.randomUUID()).block(Duration.ofSeconds(90));
		assertThat(first).isNotNull();
		assertThat(first.notReadyReason()).as("真实图像链就绪（帧读取/解码/末跳全通）").isNull();
		assertThat(first.aiRunId()).as("onPrepared 先落 ai_run，模型调用可关联").isNotNull();
		String capability = db.sql("SELECT capability FROM ai_run WHERE id = CAST(:id AS uuid)")
				.bind("id", first.aiRunId().toString()).map((row, meta) -> row.get("capability", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(capability).as("ai_run 行真实存在且为 text 能力").isEqualTo("text");

		// 末跳真实收到并解码 6 帧（观测面计数，非 data URI 全文）。
		assertThat(providerReferenceAnalysisCalls()).as("综合末跳恰好一次").isEqualTo(callsBefore + 1);
		JsonNode visionCall = providerLastReferenceAnalysisCall();
		assertThat(visionCall.path("imageParts").asInt()).as("六帧图像 parts 全部到达").isEqualTo(6);
		assertThat(visionCall.path("textChars").asInt()).as("事实文本随行").isPositive();

		JsonNode firstResult = rereadAnalysis(redFirstSha);
		assertThat(firstResult.path("status").asText()).isEqualTo("SUCCEEDED");
		String firstSummaries = segmentSummaries(firstResult);
		assertThat(firstSummaries).contains("主色 red").contains("主色 green").contains("主色 blue");
		assertThat(firstSummaries.indexOf("主色 red")).as("红绿蓝顺序保留").isLessThan(firstSummaries.indexOf("主色 green"))
				.isLessThan(firstSummaries.indexOf("主色 blue"));

		// 输入二：同链路新素材 蓝 蓝 绿 绿 红 红 → 分段序列不同（首段 blue）。
		String blueFirstSha = "99dd".repeat(16);
		UUID blueFirstAsset = seedAsset(blueFirstSha);
		String blueFirstHandle = "it://fix3-" + blueFirstSha.substring(0, 8) + ".mp4";
		stubFrames(blueFirstHandle);
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubFrameResources(blueFirstHandle, Color.BLUE, Color.BLUE, Color.GREEN, Color.GREEN, Color.RED, Color.RED);

		HypitAssetService.ReferenceAnalysisOutcome second = assets
				.analyzeReference(OWNER_A, projectId, blueFirstAsset, UUID.randomUUID()).block(Duration.ofSeconds(90));
		assertThat(second).isNotNull();
		assertThat(second.notReadyReason()).isNull();

		assertThat(providerReferenceAnalysisCalls()).as("两次输入两次末跳").isEqualTo(callsBefore + 2);
		JsonNode secondResult = rereadAnalysis(blueFirstSha);
		String secondSummaries = segmentSummaries(secondResult);
		assertThat(secondSummaries).contains("主色 blue").contains("主色 red");
		assertThat(secondSummaries.indexOf("主色 blue")).as("蓝绿红顺序保留").isLessThan(secondSummaries.indexOf("主色 red"));
		assertThat(secondSummaries).as("不同帧输入→不同分段").isNotEqualTo(firstSummaries);
	}

	// ── TC-F3-08-02：帧预算（IT 半边）——超限在模型调用前拒 ────────────────

	@Test
	@DisplayName("TC-F3-08-02 单帧超4MiB：未就绪、综合末跳零新调用、旧SUCCEEDED分析不被覆盖")
	void tc_f3_08_02_oversize_frame_waits_no_model_call() throws Exception {
		attachProviderTextModel();
		int callsBefore = providerReferenceAnalysisCalls(); // provider 进程类内共享：按增量断言
		// 先以合法帧完成一次 SUCCEEDED（也是「旧分析」基线）。
		String goodSha = "77ee".repeat(16);
		UUID goodAsset = seedAsset(goodSha);
		String goodHandle = "it://fix3-" + goodSha.substring(0, 8) + ".mp4";
		stubProbe(12.0, true);
		stubFrames(goodHandle);
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubFrameResources(goodHandle, Color.RED, Color.RED, Color.RED, Color.RED, Color.RED, Color.RED);
		HypitAssetService.ReferenceAnalysisOutcome good = assets
				.analyzeReference(OWNER_A, projectId, goodAsset, UUID.randomUUID()).block(Duration.ofSeconds(90));
		assertThat(good.notReadyReason()).isNull();
		String goodAnalysisId = rereadAnalysis(goodSha).path("analysisId").asText();
		assertThat(goodAnalysisId).isNotEmpty();
		assertThat(providerReferenceAnalysisCalls()).isEqualTo(callsBefore + 1);

		// 新素材：第 3 帧（#f5）4MiB+1 → 读帧阶段即拒，不冒充成功、不动旧分析。
		String fatSha = "66ff".repeat(16);
		UUID fatAsset = seedAsset(fatSha);
		String fatHandle = "it://fix3-" + fatSha.substring(0, 8) + ".mp4";
		stubFrames(fatHandle);
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		byte[] oversize = paddedPng(pngOf(Color.GREEN), 4 * 1024 * 1024 + 1);
		stubFrameResources(fatHandle, Color.RED, Color.RED, Color.RED, Color.RED, Color.RED, Color.RED);
		stubFrameResource(fatHandle + "#f5", oversize);

		HypitAssetService.ReferenceAnalysisOutcome fat = assets
				.analyzeReference(OWNER_A, projectId, fatAsset, UUID.randomUUID()).block(Duration.ofSeconds(90));
		assertThat(fat).isNotNull();
		assertThat(fat.analysis()).as("不冒充分析完成").isNull();
		assertThat(fat.notReadyReason()).as("按未就绪处理并说明帧上限").contains("帧");
		assertThat(fat.aiRunId()).as("未执行模型即无 ai_run").isNull();
		assertThat(providerReferenceAnalysisCalls()).as("综合末跳零新调用").isEqualTo(callsBefore + 1);
		assertThat(succeededAnalysisCount(fatSha)).as("零 reference.analyze 成功行").isZero();

		// 旧分析原样可重读。
		JsonNode old = rereadAnalysis(goodSha);
		assertThat(old.path("analysisId").asText()).isEqualTo(goodAnalysisId);
		assertThat(old.path("status").asText()).isEqualTo("SUCCEEDED");
	}

	// ── 种子与工具 ────────────────────────────────────────────────────────

	private UUID seedAsset(String sha256) {
		UUID assetId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_asset(id, project_id, resource_handle, role, origin_kind, sha256, mime_type,"
				+ " size_bytes, status) VALUES (CAST(:id AS uuid), CAST(:p AS uuid), :handle, 'reference', 'upload',"
				+ " :sha, 'video/mp4', 1024, 'ready')").bind("id", assetId.toString()).bind("p", projectId.toString())
				.bind("handle", "it://fix3-" + sha256.substring(0, 8) + ".mp4").bind("sha", sha256).then()
				.block(Duration.ofSeconds(10));
		return assetId;
	}

	private UUID createAnalyzeJob(UUID assetId) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'queued', CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER_A).bind("p", projectId.toString())
				.bind("cp", ("{\"intent\":\"analyze\",\"brief\":\"全片分析\",\"baseRevision\":2,\"inputs\":{},"
						+ "\"assetIds\":[\"" + assetId + "\"],"
						+ "\"scope\":{\"allowedTools\":[\"knowledge.search\",\"knowledge.read\",\"build.status\"]},"
						+ "\"actions\":[]}").replace("\n", " "))
				.then().block(Duration.ofSeconds(10));
		return jobId;
	}

	private JobRow awaitTerminal(UUID jobId, String state) throws InterruptedException {
		JobRow job = null;
		for (int i = 0; i < 80; i++) {
			job = jobs.findById(jobId).block(Duration.ofSeconds(10));
			if (job != null && state.equals(job.state())) {
				return job;
			}
			Thread.sleep(250);
		}
		return job;
	}

	private Map<String, Object> checkpointOf(UUID jobId) {
		return db.sql("SELECT checkpoint_json::text AS cp FROM hypit_job WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("cp", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
	}

	private int sidecarCount(String tool) {
		return SIDECAR.countRequestsMatching(
				postRequestedFor(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing(tool)).build())
				.getCount();
	}

	private List<JsonNode> sidecarPayloads(String tool) {
		return SIDECAR
				.findAll(postRequestedFor(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing(tool)))
				.stream().map(request -> {
					try {
						return JSON.readTree(request.getBodyAsString()).path("payload");
					} catch (Exception error) {
						throw new IllegalStateException(error);
					}
				}).toList();
	}

	/** 平台模型末跳替身捕获的综合请求：user 消息实际文本（C08 起为多模态 parts 数组，取 text parts）。 */
	private String qwenSynthesisUserContents() {
		return String.join("\n",
				QWEN.findAll(postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("分析师")))
						.stream().map(request -> {
							try {
								JsonNode messages = JSON.readTree(request.getBodyAsString()).path("messages");
								StringBuilder contents = new StringBuilder();
								for (JsonNode message : messages) {
									if (!"user".equals(message.path("role").asText())) {
										continue;
									}
									JsonNode content = message.path("content");
									if (content.isArray()) {
										for (JsonNode part : content) {
											if ("text".equals(part.path("type").asText())) {
												contents.append(part.path("text").asText()).append('\n');
											}
										}
									} else {
										contents.append(content.asText()).append('\n');
									}
								}
								return contents.toString();
							} catch (Exception error) {
								throw new IllegalStateException(error);
							}
						}).toList());
	}

	private JsonNode rereadAnalysis(String mediaHash) {
		String body = client().get()
				.uri("/api/hypit/projects/" + projectId + "/reference-analysis?mediaHash=" + mediaHash)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body).path("data");
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private int succeededAnalysisCount(String mediaHash) {
		Integer count = db
				.sql("SELECT COUNT(*) AS c FROM hypit_command WHERE action = 'reference.analyze'"
						+ " AND state = 'succeeded' AND target_key = :key")
				.bind("key", "analysis:" + mediaHash).map((row, meta) -> row.get("c", Integer.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	// ── TC-F3-07-01：真实 probe 与音轨分支 ────────────────────────────────

	@Test
	@DisplayName("TC-F3-07-01 有声12s真实wire：probe/frames/transcribe各一次、六中点[1,3,5,7,9,11]、SUCCEEDED可重读")
	void tc_f3_07_01_audio_real_wire_six_midpoints_and_reread() throws Exception {
		String sha = "aa11".repeat(16);
		UUID assetId = seedAsset(sha);
		stubProbe(12.0, true);
		stubFrames("it://fix3-" + sha.substring(0, 8) + ".mp4");
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubQwenSynthesisAndPlanner(assetId);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).as("有声全链收口").isNotNull();

		assertThat(sidecarCount("media.probe")).isEqualTo(1);
		assertThat(sidecarCount("media.frames")).isEqualTo(1);
		assertThat(sidecarCount("speech.transcribe")).isEqualTo(1);
		// 六中点显式 times：t=duration*(2i+1)/12 → 1/3/5/7/9/11。
		List<Double> requestedTimes = new java.util.ArrayList<>();
		sidecarPayloads("media.frames").get(0).path("times").forEach(time -> requestedTimes.add(time.asDouble()));
		assertThat(requestedTimes).containsExactly(1.0, 3.0, 5.0, 7.0, 9.0, 11.0);

		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("referenceAnalysisId"))).isNotEqualTo("null");
		assertThat(String.valueOf(checkpoint.get("referenceMediaHash"))).isEqualTo(sha);

		JsonNode reread = rereadAnalysis(sha);
		assertThat(reread.path("status").asText()).isEqualTo("SUCCEEDED");
		assertThat(reread.path("audioTrack").asText()).isEqualTo("PRESENT");
		assertThat(reread.path("durationSeconds").asDouble()).isEqualTo(12.0);
		assertThat(reread.path("gaps").size()).as("全覆盖零 gap").isZero();
	}

	@Test
	@DisplayName("TC-F3-07-01 无声12s：零转写调用（未打桩即必失败）、audioTrack=ABSENT、不伪造台词")
	void tc_f3_07_01_silent_zero_transcribe_calls() throws Exception {
		String sha = "bb22".repeat(16);
		UUID assetId = seedAsset(sha);
		stubProbe(12.0, false);
		stubFrames("it://fix3-" + sha.substring(0, 8) + ".mp4");
		// 有意不打 transcribe 桩：任何转写调用都会 404 → 命令失败 → 测试失败。
		stubQwenSynthesisAndPlanner(assetId);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).as("无声也收口（音轨不存在即证据）").isNotNull();

		assertThat(sidecarCount("speech.transcribe")).as("无声零转写调用").isZero();
		assertThat(sidecarCount("media.probe")).isEqualTo(1);
		assertThat(sidecarCount("media.frames")).isEqualTo(1);

		JsonNode reread = rereadAnalysis(sha);
		assertThat(reread.path("audioTrack").asText()).isEqualTo("ABSENT");
		assertThat(reread.path("status").asText()).as("无声+全覆盖=SUCCEEDED").isEqualTo("SUCCEEDED");
		assertThat(reread.path("analysisMarkdown").asText()).contains("audioTrack: ABSENT").doesNotContain("你好");
	}

	// ── TC-F3-07-02：逐词时间与空语音 ─────────────────────────────────────

	@Test
	@DisplayName("TC-F3-07-02 词样本换算秒按序进模型请求：你好0~1s、草场4~5s；不能读顶层text")
	void tc_f3_07_02_word_times_reach_model_request_in_order() throws Exception {
		String sha = "cc33".repeat(16);
		UUID assetId = seedAsset(sha);
		stubProbe(12.0, true);
		stubFrames("it://fix3-" + sha.substring(0, 8) + ".mp4");
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubQwenSynthesisAndPlanner(assetId);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).isNotNull();

		// 平台模型末跳替身捕获综合请求：词序保留、样本/16000 换算秒（0~1 与 4~5）。
		String synthesisContents = qwenSynthesisUserContents();
		assertThat(synthesisContents).contains("你好").contains("草场");
		assertThat(synthesisContents.indexOf("你好")).as("词序保留").isLessThan(synthesisContents.indexOf("草场"));
		assertThat(synthesisContents).as("16kHz 样本换算秒").contains("\"startSeconds\":0.0").contains("\"endSeconds\":1.0")
				.contains("\"startSeconds\":4.0").contains("\"endSeconds\":5.0");
		assertThat(synthesisContents).as("不存在可读的顶层 text（wire 无此字段）").doesNotContain("\"text\":\"开场介绍");
	}

	@Test
	@DisplayName("TC-F3-07-02 空passages=已测无语音：转写就绪、SUCCEEDED、无伪造台词")
	void tc_f3_07_02_empty_passages_is_measured_silence() throws Exception {
		String sha = "dd44".repeat(16);
		UUID assetId = seedAsset(sha);
		stubProbe(12.0, true);
		stubFrames("it://fix3-" + sha.substring(0, 8) + ".mp4");
		stubTranscribe("[]");
		stubQwenSynthesisAndPlanner(assetId);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).as("空语音不等于转写失败，链路照常收口").isNotNull();

		assertThat(sidecarCount("speech.transcribe")).as("有音轨必调用一次转写").isEqualTo(1);
		JsonNode reread = rereadAnalysis(sha);
		assertThat(reread.path("status").asText()).as("已测无语音是有效证据，SUCCEEDED").isEqualTo("SUCCEEDED");
		assertThat(reread.path("analysisMarkdown").asText()).as("无伪造台词").doesNotContain("你好");
	}

	// ── TC-F3-07-03：probe 与词边界拒绝 ───────────────────────────────────

	@Test
	@DisplayName("TC-F3-07-03 坏probe(duration=0)：waiting_input、无reference.analyze成功行、旧分析不变")
	void tc_f3_07_03_bad_probe_waits_and_keeps_old_analysis() throws Exception {
		// 旧分析：asset1（sha aa11…）先以合法 probe 完成 SUCCEEDED。
		String goodSha = "aa11".repeat(16);
		UUID goodAsset = seedAsset(goodSha);
		stubProbe(12.0, true);
		stubFrames("it://fix3-" + goodSha.substring(0, 8) + ".mp4");
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		stubQwenSynthesisAndPlanner(goodAsset);
		UUID goodJob = createAnalyzeJob(goodAsset);
		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(goodJob, "succeeded")).isNotNull();
		String goodAnalysisId = rereadAnalysis(goodSha).path("analysisId").asText();
		assertThat(goodAnalysisId).isNotEqualTo("");

		// 新素材 asset2 带 duration=0 的坏 probe：不得默认 0 秒/无声成功。
		String badSha = "ee55".repeat(16);
		stubProbe(0, true);
		stubFrames("it://fix3-" + badSha.substring(0, 8) + ".mp4");
		stubTranscribe(WORDS_HELLO_CAOCHANG);
		UUID badAsset = seedAsset(badSha);
		UUID badJob = createAnalyzeJob(badAsset);
		worker.runOnce().block(Duration.ofSeconds(90));

		JobRow badRow = awaitTerminal(badJob, "running");
		assertThat(badRow).as("坏 probe 不产终态成功").isNotNull();
		Map<String, Object> checkpoint = checkpointOf(badJob);
		assertThat(String.valueOf(checkpoint.get("phase"))).as("按未就绪处理").isEqualTo("waiting_input");
		assertThat(String.valueOf(checkpoint.get("blockedReason"))).contains("未就绪");
		assertThat(String.valueOf(checkpoint.get("referenceAnalysisId"))).as("未冒充分析完成").isEqualTo("null");
		assertThat(succeededAnalysisCount(badSha)).as("坏 probe 零 reference.analyze 成功行").isZero();

		// 旧分析不变：sha aa11 的 SUCCEEDED 分析原样可重读。
		JsonNode old = rereadAnalysis(goodSha);
		assertThat(old.path("analysisId").asText()).isEqualTo(goodAnalysisId);
		assertThat(old.path("status").asText()).isEqualTo("SUCCEEDED");
	}

	@Test
	@DisplayName("TC-F3-07-03 合法probe但词缺startSample：分析持久但WAITING_INPUT，不产SUCCEEDED")
	void tc_f3_07_03_missing_word_anchor_is_not_succeeded() throws Exception {
		String sha = "ff66".repeat(16);
		UUID assetId = seedAsset(sha);
		stubProbe(12.0, true);
		stubFrames("it://fix3-" + sha.substring(0, 8) + ".mp4");
		// 你好缺 startSample（缺口词）；草场锚点完整。
		stubTranscribe("""
				[{"words":[{"text":"你好"},{"text":"草场","startSample":64000,"endSampleExclusive":80000}]}]"""
				.replace("\n", ""));
		stubQwenSynthesisAndPlanner(assetId);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).isNotNull();

		JsonNode reread = rereadAnalysis(sha);
		assertThat(reread.path("status").asText()).as("缺词时间锚点=转写不完整，不得 SUCCEEDED").isEqualTo("WAITING_INPUT");
	}

	@Test
	@DisplayName("TC-F3-07-03 duration缺失/hasAudio缺失/hasVideo=false：同样waiting_input零成功分析")
	void tc_f3_07_03_more_invalid_probe_shapes_rejected() throws Exception {
		String sha = "ab77".repeat(16);
		UUID assetId = seedAsset(sha);
		// duration 缺失（wire 非法形状；否则 probe 合法）。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"commandId\":\"p\",\"state\":\"succeeded\",\"result\":{\"probe\":{\"hasVideo\":true,\"hasAudio\":true}}}")));
		UUID jobId = createAnalyzeJob(assetId);
		worker.runOnce().block(Duration.ofSeconds(90));

		assertThat(awaitTerminal(jobId, "running")).isNotNull();
		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("phase"))).isEqualTo("waiting_input");
		assertThat(String.valueOf(checkpoint.get("referenceAnalysisId"))).isEqualTo("null");
		assertThat(succeededAnalysisCount(sha)).isZero();
		assertThat(sidecarCount("speech.transcribe")).as("无效 probe 不进入转写").isZero();
	}

	private static String segmentSummaries(JsonNode analysis) {
		StringBuilder summaries = new StringBuilder();
		analysis.path("segments").forEach(segment -> summaries.append(segment.path("summary").asText()).append('\n'));
		return summaries.toString();
	}
}
