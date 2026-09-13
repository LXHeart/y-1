package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.ImageExecutionObserver;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService;
import com.grassland.intelligence.cardseries.CardSeriesOperationRepository;
import com.grassland.intelligence.creationstudio.visual.VisualExecutionBridge;
import com.grassland.intelligence.creationstudio.visual.VisualItemRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-08（V80、§6.6）：图像运行／结果持久绑定与恢复。 TC101-035～041 的本地部分——故障注入经 DB
 * 状态冻结与失败 observer（崩溃边界模拟），供应商经 WireMock 计数。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class VisualExecutionIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000040a";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-00000000040b";

	private static final byte[] PNG_1X1 = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	private static final WireMockServer IMAGE = new WireMockServer(0);
	static {
		IMAGE.start();
	}

	@Autowired
	private VisualExecutionBridge bridge;
	@Autowired
	private VisualItemRepository items;
	@Autowired
	private CardSeriesOperationRepository operations;
	@Autowired
	private IndependentImageGenerationService independent;
	@Autowired
	private com.grassland.intelligence.ai.run.AiRunRepository runs;

	private UUID operationId;
	private UUID attemptId;

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM creation_visual_item").then()
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM ai_run WHERE account_id IN (:a, :b)").bind("a", ACCOUNT).bind("b", ACCOUNT_B)
						.then())
				.block(java.time.Duration.ofSeconds(10));
		seedImageModel("openai-compatible", IMAGE.baseUrl() + "/v1");
		IMAGE.resetAll();
		stubUpstreamSuccess();
		var claim = operations
				.claimVisualJob(ACCOUNT, UUID.randomUUID().toString(), "digest", UUID.randomUUID(), UUID.randomUUID(),
						1, UUID.randomUUID(),
						VisualExecutionBridge.snapshotJson(ACCOUNT, null, "暖光门头特写，木质招牌与蒸汽", "1024x1024", "item-1"))
				.block(java.time.Duration.ofSeconds(10));
		operationId = claim.row().id();
		attemptId = UUID.randomUUID();
		items.insertItems(operationId,
				List.of(new VisualItemRepository.NewItem(attemptId, "item-1", 1, VisualItemRepository.STATE_QUEUED)))
				.block(java.time.Duration.ofSeconds(10));
	}

	private void stubUpstreamSuccess() {
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_1X1) + "\"}]}")));
	}

	private int upstreamCalls() {
		return IMAGE.getAllServeEvents().size();
	}

	private VisualItemRepository.ItemRow item() {
		return items.findById(attemptId).block(java.time.Duration.ofSeconds(10));
	}

	// ---- TC101-035：observer.prepared 持久化失败 → 上游调用 0，执行环正确收尾 ----

	@Test
	void preparedGateFailureBlocksUpstream() {
		ImageExecutionObserver failing = new ImageExecutionObserver() {
			@Override
			public Mono<Void> prepared(UUID runId) {
				return Mono.error(new IllegalStateException("run 绑定持久化失败（注入）"));
			}

			@Override
			public Mono<Void> generated(UUID runId, UUID mediaId) {
				return Mono.empty();
			}
		};
		var thrown = new AtomicInteger(0);
		independent
				.generate(new ArticleImageService.GenerateCommand("门头特写", "1024x1024", List.of()), ACCOUNT, null,
						com.grassland.intelligence.media.MediaPurpose.ARTICLE_GENERATED, UUID.randomUUID(), failing)
				.doOnError(error -> thrown.incrementAndGet()).onErrorComplete().block(java.time.Duration.ofSeconds(10));
		assertThat(thrown.get()).isEqualTo(1);
		assertThat(upstreamCalls()).isZero();
	}

	// ---- TC101-036：单一派发者 + 同键重放不再调用 ----

	@Test
	void singleDispatcherAndReplayMakesNoNewCalls() {
		var first = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(30));
		assertThat(first.state()).isEqualTo(VisualItemRepository.STATE_SUCCEEDED);
		assertThat(first.originalMediaId()).isNotNull();
		int callsAfterFirst = upstreamCalls();
		assertThat(callsAfterFirst).isEqualTo(1);

		// 第二个执行者（同 attempt 重放）：只读返回，不再派发
		var second = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(10));
		assertThat(second.state()).isEqualTo(VisualItemRepository.STATE_SUCCEEDED);
		assertThat(upstreamCalls()).isEqualTo(callsAfterFirst);
		// execution_operation_id 唯一：同 attempt 只有一个 run 绑定
		assertThat(item().runId()).isEqualTo(first.runId());
		long runCount = db.sql("SELECT count(*) AS c FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", item().executionOperationId().toString()).map((row, metadata) -> row.get("c", Long.class))
				.one().block(java.time.Duration.ofSeconds(10));
		assertThat(runCount).isEqualTo(1);
	}

	// ---- TC101-037：dispatching 后失联 → unknown，不自动重发 ----

	@Test
	void dispatchCrashBecomesUnknownWithoutRedispatch() {
		IMAGE.resetAll();
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withStatus(502).withHeader("Content-Type", "application/json").withBody("{}")));
		var result = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(30));
		assertThat(result.state()).isEqualTo(VisualItemRepository.STATE_UNKNOWN);
		assertThat(item().runId()).isNotNull();

		// 未知结果：再次执行不重新派发（无 acknowledgedUnknownAttemptIds——C101-10 API 层强制）
		var again = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(10));
		assertThat(again.state()).isEqualTo(VisualItemRepository.STATE_UNKNOWN);
		assertThat(upstreamCalls()).isEqualTo(1);
	}

	// ---- TC101-038：原图保存后结算中断 → 恢复只重放结算（同图同 run） ----

	@Test
	void settleRecoveryReusesSameRunAndImage() {
		var first = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(30));
		assertThat(first.state()).isEqualTo(VisualItemRepository.STATE_SUCCEEDED);
		UUID runId = first.runId();
		UUID mediaId = first.originalMediaId();
		int calls = upstreamCalls();

		// 冻结回崩溃边界：run 回 running、子项回 generated_unsettled（结算未完成态）
		db.sql("UPDATE ai_run SET status='running', actual_cents=NULL, completed_at=NULL WHERE id = CAST(:r AS uuid)")
				.bind("r", runId.toString()).then()
				.then(db.sql("UPDATE creation_visual_item SET state='generated_unsettled' WHERE id = CAST(:i AS uuid)")
						.bind("i", attemptId.toString()).then())
				.block(java.time.Duration.ofSeconds(10));

		var recovered = bridge.reconcile(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(30));
		assertThat(recovered.state()).isEqualTo(VisualItemRepository.STATE_SUCCEEDED);
		assertThat(recovered.runId()).isEqualTo(runId);
		assertThat(recovered.originalMediaId()).isEqualTo(mediaId);
		assertThat(upstreamCalls()).isEqualTo(calls);

		var run = runs.findById(runId).block(java.time.Duration.ofSeconds(10));
		assertThat(run.status()).isEqualTo("completed");
		assertThat(run.actualCents()).isNotNull();
		assertThat(item().state()).isEqualTo(VisualItemRepository.STATE_SUCCEEDED);
	}

	// ---- TC101-041：B 尝试按操作 ID 恢复 A 的运行 → 404 ----

	@Test
	void foreignAccountCannotAccess() {
		var status = bridge.executeItem(operationId, attemptId, ACCOUNT_B).map(ignored -> 200)
				.onErrorResume(IntelligenceException.class, error -> Mono.just(error.status()))
				.block(java.time.Duration.ofSeconds(10));
		assertThat(status).isEqualTo(404);
		assertThat(upstreamCalls()).isZero();
	}

	// ---- 决策 G：控制面无 image_generation 行 → 失败收口（不降独立 env） ----

	@Test
	void noPlatformModelFailsClosed() {
		db.sql("DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled = true").then()
				.block(java.time.Duration.ofSeconds(10));
		var result = bridge.executeItem(operationId, attemptId, ACCOUNT).block(java.time.Duration.ofSeconds(30));
		assertThat(result.state()).isEqualTo(VisualItemRepository.STATE_FAILED);
		assertThat(upstreamCalls()).isZero();
		assertThat(item().runId()).isNull();
	}

	// ---- helpers ----

	private void seedImageModel(String provider, String baseUrl) {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-exec-image");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-exec-image'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-exec-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-exec-image'").then())
				.then(db.sql(
						"DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled = true")
						.then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-exec-image', :provider, :baseUrl, :encrypted, 'v1', 'sk-***img', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    max_concurrency, health_status, enabled, version, credential_id)
				SELECT 'image_generation','primary',:provider,'it-image-model',:baseUrl,
				    NULL,'healthy',true,1,cred.id
				FROM cred
				""").bind("provider", provider).bind("baseUrl", baseUrl).bind("encrypted", encrypted).then()
				.block(java.time.Duration.ofSeconds(10));
	}
}
