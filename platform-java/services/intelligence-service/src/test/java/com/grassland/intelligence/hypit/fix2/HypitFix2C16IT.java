package com.grassland.intelligence.hypit.fix2;

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
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
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
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C107F2-16（107-fix-2 §6.7 / RULE-10 / F22、F06）：全片参考分析、转写与时间锚点。
 *
 * <p>
 * 真 PostgreSQL + WireMock sidecar（probe/frames/transcribe 真实工具命令）+ QWEN（分段综合与
 * planner；两形态按请求体区分）。合成 12 秒视频证据链：0/4/8 三段、音轨事实、锚点一致。
 *
 * <ul>
 * <li>TC-F2-16-01 三段全覆盖、锚点与实际帧一致、结果可刷新重读。</li>
 * <li>TC-F2-16-02 视觉综合能力缺失 → 明确 waiting/未就绪，真实工具链照跑，不冒充全片 ready。</li>
 * <li>TC-F2-16-03 无声视频 audioTrack=ABSENT 不伪造台词且零转写调用；截断分析 PROVISIONAL
 * （PARTIAL）带 gap 阻止直接生成。</li>
 * <li>TC-F2-16-04 换源素材（hash 变化）后旧分析被判失配，clone-plan 保存被拒需重新分析。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix2C16IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000016a";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	/** C107F3-08：分析链现经 W15.open 逐帧读 /internal/v1/resources/{handle}——兜底小 PNG。 */
	private static final byte[] FRAME_PNG = framePng();

	private static byte[] framePng() {
		try {
			java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 48,
					java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D graphics = image.createGraphics();
			graphics.setColor(java.awt.Color.GRAY);
			graphics.fillRect(0, 0, 64, 48);
			graphics.dispose();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static final String SYNTHESIS_OUTPUT = """
			{"segments":[
			 {"index":0,"startSeconds":0,"endSeconds":4,"summary":"开场：产品特写","evidence":[{"assetId":"__A__","sourceTimeSeconds":0.0,"note":"frame@0"}]},
			 {"index":1,"startSeconds":4,"endSeconds":8,"summary":"中段：对比演示","evidence":[{"assetId":"__A__","sourceTimeSeconds":4.0,"note":"frame@4"}]},
			 {"index":2,"startSeconds":8,"endSeconds":12,"summary":"结尾：行动号召","evidence":[{"assetId":"__A__","sourceTimeSeconds":8.0,"note":"frame@8"}]}],
			 "systems":[{"systemId":"ui-1","kind":"ui","name":"价格贴片","firstSeenSeconds":0,"lastSeenSeconds":12,"segmentIndexes":["0","1","2"]}],
			 "events":[{"kind":"cut","atSeconds":4.0,"trigger":null,"evidenceAsset":"__A__","inferred":false}],
			 "openQuestions":[]}
			""";

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	DatabaseClient db;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c16-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	private UUID projectId;

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		SIDECAR.resetRequests();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c16', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		attachPlatformTextRow();
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql(
						"DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
						.bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:o, 'system')").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
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

	/** 按 QWEN 目的地撤掉平台 text 行（TC-02：视觉综合能力缺失形态）。 */
	private void detachPlatformTextRow() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE base_url = :baseUrl)").bind("baseUrl", QWEN.baseUrl())
				.then().then(db.sql("DELETE FROM platform_model_config WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
	}

	/**
	 * sidecar 工具桩（C107F3-07 / TC-F3-07-01）：probe/frames/transcribe 的真实命令回执—— 字段即
	 * Node wire 真实形状（broker 不做扁平化适配，见契约 hypit-api.v1.json evidenceWire）： probe 嵌套
	 * {@code result.probe.{duration,hasVideo,hasAudio,width,height}}； frames 为
	 * {@code frames:[{handle,timestampSeconds}],totalTimes}（六中点，无 aspectRatio——
	 * 宽高比由 probe 派生）；transcribe 为 {@code passages[].words[] 16kHz 样本锚点}（无顶层 text）。
	 */
	private void stubMediaToolchain(String handle, double duration, boolean hasAudio) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"p\",\"state\":\"succeeded\",\"result\":{\"probe\":{\"duration\":"
								+ duration + ",\"hasVideo\":true,\"hasAudio\":" + hasAudio
								+ ",\"width\":1080,\"height\":1920}}}")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.frames"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"f\",\"state\":\"succeeded\",\"result\":{\"frames\":["
								+ "{\"handle\":\"" + handle + "#f1\",\"timestampSeconds\":1}," + "{\"handle\":\""
								+ handle + "#f3\",\"timestampSeconds\":3}," + "{\"handle\":\"" + handle
								+ "#f5\",\"timestampSeconds\":5}," + "{\"handle\":\"" + handle
								+ "#f7\",\"timestampSeconds\":7}," + "{\"handle\":\"" + handle
								+ "#f9\",\"timestampSeconds\":9}," + "{\"handle\":\"" + handle
								+ "#f11\",\"timestampSeconds\":11}" + "],\"totalTimes\":6}}")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("speech.transcribe"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"t\",\"state\":\"succeeded\",\"result\":{\"language\":\"zh\","
								+ "\"sampleFrames\":192000,\"durationSec\":12.0,\"extracted\":true,"
								+ "\"passages\":[{\"startSample\":0,\"endSampleExclusive\":32000,\"words\":["
								+ "{\"text\":\"开场\",\"startSample\":0,\"endSampleExclusive\":16000,\"score\":0.98},"
								+ "{\"text\":\"介绍\",\"startSample\":16000,\"endSampleExclusive\":32000},"
								+ "{\"text\":\"对比\",\"startSample\":64000,\"endSampleExclusive\":80000},"
								+ "{\"text\":\"号召\",\"startSample\":176000,\"endSampleExclusive\":192000}]}],"
								+ "\"diagnostics\":[],\"evidenceHandle\":\"res-fix2c16-speech-evidence\"}}")));
		// C107F3-08（RULE-008）：W14 经 W15.open 内部认证逐帧 GET 帧资源——兜底回执可解码 PNG。
		SIDECAR.stubFor(get(urlMatching("/internal/v1/resources/.*"))
				.willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(FRAME_PNG)));
	}

	/** QWEN 桩：分段综合（无「执行规划器」字样）与 planner（含）分开匹配。 */
	private void stubQwen(String synthesisForAsset, String plannerPlan) {
		String synthesis = synthesisForAsset == null ? SYNTHESIS_OUTPUT : synthesisForAsset;
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).withRequestBody(containing("分析师"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + JSON.valueToTree(synthesis).toString()
								+ "}}],\"usage\":{\"prompt_tokens\":10," + "\"completion_tokens\":100}}")));
		if (plannerPlan != null) {
			QWEN.stubFor(post(urlEqualTo("/chat/completions")).withRequestBody(containing("执行规划器"))
					.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
							.withBody("{\"choices\":[{\"message\":{\"content\":"
									+ JSON.valueToTree(plannerPlan).toString() + "}}],\"usage\":{\"prompt_tokens\":10,"
									+ "\"completion_tokens\":20}}")));
		}
	}

	private UUID seedAsset(String sha256, boolean ready) {
		UUID assetId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_asset(id, project_id, resource_handle, role, origin_kind, sha256, mime_type,"
				+ " size_bytes, status) VALUES (CAST(:id AS uuid), CAST(:p AS uuid), :handle, 'reference', 'upload',"
				+ " :sha, 'video/mp4', 1024, :status)").bind("id", assetId.toString()).bind("p", projectId.toString())
				.bind("handle", "it://ref-" + sha256.substring(0, 8) + ".mp4").bind("sha", sha256)
				.bind("status", ready ? "ready" : "processing").then().block(Duration.ofSeconds(10));
		return assetId;
	}

	private UUID createAnalyzeJob(UUID assetId) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'queued', CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString())
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

	private JsonNode getBody(String uri) {
		String body = client().get().uri(uri).header("X-Grassland-Identity", sign(OWNER, null)).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	// ── TC-F2-16-01：三段全覆盖 + 锚点一致 + 可刷新重读 ────────────────────
	@Test
	@DisplayName("TC-F2-16-01 12s 三段含音轨：真实 probe/抽帧/转写链，全覆盖锚点一致可重读")
	void fullCoverageThreeSegmentsWithConsistentAnchorsAndReread() throws Exception {
		String sha = "a".repeat(64);
		UUID assetId = seedAsset(sha, true);
		stubMediaToolchain("it://ref-" + sha.substring(0, 8) + ".mp4", 12.0, true);
		stubQwen(SYNTHESIS_OUTPUT.replace("__A__", assetId.toString()),
				"{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"render\",\"limit\":2}}]}");
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("三段全覆盖后分析 job 收口").isNotNull();

		// 真实工具链全部命中（不接受浏览器自报）。
		assertThat(sidecarCount("media.probe")).isEqualTo(1);
		assertThat(sidecarCount("media.frames")).isEqualTo(1);
		assertThat(sidecarCount("speech.transcribe")).isEqualTo(1);

		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("referenceAnalysisId"))).isNotEqualTo("null");
		assertThat(String.valueOf(checkpoint.get("referenceMediaHash"))).isEqualTo(sha);

		// 可刷新重读：GET 回读与生成时同一事实源（command result）。
		JsonNode reread = getBody("/api/hypit/projects/" + projectId + "/reference-analysis?mediaHash=" + sha)
				.path("data");
		assertThat(reread.path("analysisId").asText()).isEqualTo(String.valueOf(checkpoint.get("referenceAnalysisId")));
		assertThat(reread.path("status").asText()).isEqualTo("SUCCEEDED");
		assertThat(reread.path("segments").size()).as("0/4/8 三段").isEqualTo(3);
		assertThat(reread.path("gaps").size()).as("全覆盖零 gap").isZero();
		assertThat(reread.path("audioTrack").asText()).isEqualTo("PRESENT");
		// 锚点与实际帧一致：段边界恰为抽帧证据时间 0/4/8（12s 全覆盖）。
		assertThat(reread.path("segments").get(0).path("startSeconds").asDouble()).isEqualTo(0.0);
		assertThat(reread.path("segments").get(0).path("endSeconds").asDouble()).isEqualTo(4.0);
		assertThat(reread.path("segments").get(2).path("endSeconds").asDouble()).isEqualTo(12.0);
		assertThat(reread.path("analysisMarkdown").asText()).contains("audioTrack: PRESENT");
	}

	// ── TC-F2-16-02：视觉综合能力缺失 → 未就绪等待 ─────────────────────────
	@Test
	@DisplayName("TC-F2-16-02 视觉配置缺失：真实工具链照跑，waiting 不冒充全片 ready")
	void missingVisionCapabilityWaitsInsteadOfFakingReady() throws Exception {
		String sha = "b".repeat(64);
		UUID assetId = seedAsset(sha, true);
		stubMediaToolchain("it://ref-" + sha.substring(0, 8) + ".mp4", 12.0, true);
		stubQwen(null, null);
		detachPlatformTextRow(); // 视觉综合的平台模型缺失
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		// 明确未完成：state 保持 running 且 phase=waiting_input，blocked_reason 可行动。
		JobRow job = jobs.findById(jobId).block(Duration.ofSeconds(10));
		assertThat(job.state()).as("不返回全片 ready（非 succeeded）").isEqualTo("running");
		assertThat(String.valueOf(job.blockedReason())).contains("未就绪");
		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("phase"))).isEqualTo("waiting_input");
		assertThat(String.valueOf(checkpoint.get("referenceAnalysisId"))).as("未冒充分析完成").isEqualTo("null");
		// 真实 probe/抽帧/转写仍已执行（保留已完成部分）。
		assertThat(sidecarCount("media.probe")).isEqualTo(1);
		assertThat(sidecarCount("media.frames")).isEqualTo(1);
		assertThat(sidecarCount("speech.transcribe")).isEqualTo(1);
	}

	// ── TC-F2-16-03：无声不伪造台词 + 截断 PARTIAL ─────────────────────────
	@Test
	@DisplayName("TC-F2-16-03 无声 ABSENT 零转写调用不伪造台词；截断分析 PROVISIONAL 带 gap")
	void silentVideoAndTruncatedAnalysisAreHonest() throws Exception {
		String sha = "c".repeat(64);
		UUID assetId = seedAsset(sha, true);
		stubMediaToolchain("it://ref-" + sha.substring(0, 8) + ".mp4", 12.0, false); // 无音轨
		// 截断综合：只覆盖 0..4s（前 4 秒分析返回形态）。
		String truncatedJson = """
				{"segments":[{"index":0,"startSeconds":0,"endSeconds":4,"summary":"仅前 4 秒","evidence":[{"assetId":"%s","sourceTimeSeconds":0.0,"note":"frame@0"}]}],
				 "systems":[],"events":[],"openQuestions":[]}
				"""
				.formatted(assetId).replace("\n", " ");
		stubQwen(truncatedJson, null);
		UUID jobId = createAnalyzeJob(assetId);

		worker.runOnce().block(Duration.ofSeconds(90));
		Map<String, Object> checkpoint = checkpointOf(jobId);
		String analysisId = String.valueOf(checkpoint.get("referenceAnalysisId"));
		assertThat(analysisId).as("无声也有分析（音轨不存在即证据）").isNotEqualTo("null");

		assertThat(sidecarCount("speech.transcribe")).as("无音轨零转写调用").isZero();

		JsonNode reread = getBody("/api/hypit/projects/" + projectId + "/reference-analysis?mediaHash=" + sha)
				.path("data");
		assertThat(reread.path("audioTrack").asText()).as("无声以 ABSENT 明确，不静默").isEqualTo("ABSENT");
		assertThat(reread.path("status").asText()).as("截断分析 PARTIAL（PROVISIONAL），阻止直接生成").isEqualTo("PROVISIONAL");
		assertThat(reread.path("gaps").size()).as("4..12s 未覆盖如实带 gap").isEqualTo(1);
		assertThat(reread.path("gaps").get(0).path("startSeconds").asDouble()).isEqualTo(4.0);
		assertThat(reread.path("gaps").get(0).path("endSeconds").asDouble()).isEqualTo(12.0);
		assertThat(reread.path("analysisMarkdown").asText()).as("无声不伪造台词（无台词文本）").doesNotContain("开场介绍");
	}

	// ── TC-F2-16-04：换源素材旧分析失配 ────────────────────────────────────
	@Test
	@DisplayName("TC-F2-16-04 分析完成后换素材 hash：clone-plan 保存 409 需重新分析")
	void staleAnalysisRejectedAfterSourceSwap() throws Exception {
		String shaA = "d".repeat(64);
		UUID assetA = seedAsset(shaA, true);
		stubMediaToolchain("it://ref-" + shaA.substring(0, 8) + ".mp4", 12.0, true);
		stubQwen(SYNTHESIS_OUTPUT.replace("__A__", assetA.toString()),
				"{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"render\",\"limit\":2}}]}");
		UUID jobId = createAnalyzeJob(assetA);
		worker.runOnce().block(Duration.ofSeconds(90));
		assertThat(awaitTerminal(jobId, "succeeded")).isNotNull();
		Map<String, Object> checkpoint = checkpointOf(jobId);
		String analysisId = String.valueOf(checkpoint.get("referenceAnalysisId"));

		// 换源：插入新 reference 素材（hash=B）成为工程最新素材。
		seedAsset("e".repeat(64), true);

		// 携旧分析（mediaHash=A）请求生成方案 → 失配拒绝。
		String body = """
				{"requestId":"%s","analysisId":"%s","mediaHash":"%s","durationSeconds":12.0,"language":"zh",
				 "aspectRatio":"9:16","segments":[],"systems":[],"events":[],"gaps":[],"openQuestions":[],
				 "status":"SUCCEEDED","steps":[],"materialGaps":[]}
				""".formatted(UUID.randomUUID(), analysisId, shaA).replace("\n", " ");
		client().put().uri("/api/hypit/projects/" + projectId + "/clone-plan")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_analysis_mismatch");
	}
}
