package com.grassland.intelligence.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 冒烟端点端到端（草场 intelligence Slice 1；GL-P3-AI-001 尾巴已迁执行环）：edge 断言 →
 * callerResolver → 执行环（预算/provider 解析/积分闭环）→ 平台 Qwen 非流式完成 → SSE 透传。 WireMock
 * 托管 Qwen（非流式 + usage），断言 401（无断言）+ 200 SSE（{@code text/event-stream} +
 * {@code X-Accel-Buffering: no} + 单帧 content + {@code [DONE]}）+ outbox 表已迁移。
 *
 * <p>
 * 本端点扣积分（{@link CreditFeature#INTELLIGENCE_SMOKE}），积分用 {@link MockitoBean}
 * 隔离（执行环真实走通后在此打桩 Finance 出口）；限流由 {@code SmokePreflightFilterTest} 单测覆盖。
 */
class SmokeControllerIT extends IntelligenceItSupport {

	@MockitoBean
	private CreditsClient credits;

	@BeforeEach
	void stubQwen() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		db.sql("DELETE FROM intelligence_outbox").then().block();
		db.sql("DELETE FROM ai_credit_compensation").then().block();
		db.sql("DELETE FROM ai_run").then().block();
		// 自种子平台 text/primary 配置指向 QWEN（不依赖其他测试类留下的状态，跨类隔离）
		db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
		db.sql("DELETE FROM platform_model_config").then().block();
		String platformConfigId = db
				.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, "
						+ "base_url, max_concurrency, health_status, enabled, version) "
						+ "VALUES ('text','primary','qwen','qwen-plus',:baseUrl,1,'healthy',true,1) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).map((row, meta) -> row.get("id", String.class)).one().block();
		db.sql("INSERT INTO platform_model_concurrency_slot(config_id, slot_no) VALUES (CAST(:id AS uuid), 1)")
				.bind("id", platformConfigId).then().block();
		QWEN.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":\"草场是内容平台\"}}],"
								+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":8}}")));
		// 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
		attachPlatformTextCredential();
	}

	@Test
	@DisplayName("无断言 → 401，不扣积分")
	void unauthenticatedRejected() {
		client().post().uri("/api/intelligence/smoke/chat").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
				.exchange().expectStatus().isUnauthorized();
		verify(credits, never()).consume(any(), any());
		verify(credits, never()).consume(any(), any(), any());
	}

	@Test
	@DisplayName("扣 intelligence_smoke 积分（冒烟不再免费；经执行环 3 参 consume）")
	void consumesSmokeCredit() {
		String accountId = UUID.randomUUID().toString();
		client().post().uri("/api/intelligence/smoke/chat")
				.header("X-Grassland-Identity", sign(accountId, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("prompt", "介绍草场")).exchange().expectStatus().isOk().expectBody().returnResult();

		verify(credits).consume(eq(accountId), eq(CreditFeature.INTELLIGENCE_SMOKE), any());
	}

	@Test
	@DisplayName("积分不足 → 402，不调用 Qwen 上游")
	void insufficientCreditsRejected() {
		when(credits.consume(any(), any(), any())).thenReturn(Mono.error(new InsufficientCreditsException()));
		QWEN.resetRequests();

		client().post().uri("/api/intelligence/smoke/chat")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("prompt", "介绍草场")).exchange().expectStatus()
				.isEqualTo(402);

		assertThat(QWEN.getAllServeEvents()).isEmpty();
	}

	@Test
	@DisplayName("有断言 → 200 SSE：执行环完成聚合后单帧 content + [DONE]，头部正确")
	void streamsAggregatedSse() {
		byte[] body = client().post().uri("/api/intelligence/smoke/chat")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("prompt", "介绍草场")).exchange().expectStatus()
				.isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectHeader()
				.valueEquals("X-Accel-Buffering", "no").expectBody().returnResult().getResponseBody();

		assertThat(new String(body, UTF_8)).isEqualTo("data: {\"content\":\"草场是内容平台\"}\n\n" + "data: [DONE]\n\n");
	}

	@Test
	@DisplayName("outbox 表已由 Flyway 建好（DB 地基通）")
	void outboxTableMigrated() {
		Integer count = db.sql("""
				SELECT COUNT(*)::int AS c
				FROM information_schema.tables
				WHERE table_schema = current_schema()
				  AND table_name = 'intelligence_outbox'
				""").map(r -> r.get("c", Integer.class)).one().block();
		assertThat(count).isEqualTo(1);
	}
}
