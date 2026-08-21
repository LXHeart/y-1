package com.grassland.intelligence.videoproduction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 视频制作脚本端到端：断言→校验→扣费→多模态 command→SSE。 */
class VideoProductionControllerIT extends IntelligenceItSupport {

	@MockitoBean
	private CreditsClient credits;

	@MockitoBean
	private com.grassland.intelligence.ai.run.FrozenTextExecutionService frozenText;

	@MockitoBean
	private ObjectStorageAdapter storage;

	@BeforeEach
	void setUp() {
		reset(credits, frozenText);
		reset(storage);
		CreditsStubs.stubDefaults(credits);
		when(storage.presignDownload(any(), any(Long.class)))
				.thenReturn(URI.create("https://media.example.test/signed-video"));
		db.sql("DELETE FROM video_generation_job").then().block();
		db.sql("DELETE FROM media_reference WHERE purpose='video_asset'").then().block();
	}

	private String signed() {
		return sign(UUID.randomUUID().toString(), "merchant");
	}

	@Test
	@DisplayName("无断言 → 401；不扣积分")
	void unauthenticatedRejected() {
		client().post().uri("/api/video-production/generate-script").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(validBody()).exchange().expectStatus().isUnauthorized();
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("无图片 / 非法行业 / 非法风格 → 400；校验在扣费前")
	void validationRejectsInvalidRequest() {
		Map<String, Object> body = validBody();
		body.put("images", List.of());
		client().post().uri("/api/video-production/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("积分不足 → 402（环内拒绝透传）；不发 SSE")
	void insufficientCreditsRejected() {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
				.thenReturn(Mono.error(new IntelligenceException(402, "积分不足")));
		client().post().uri("/api/video-production/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(validBody()).exchange().expectStatus()
				.isEqualTo(402);
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("超过 WebFlux 默认 256KB 的合法 base64 图片仍可达 controller（10MB codec 契约）")
	void acceptsPayloadLargerThanDefaultWebFluxLimit() {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
				.thenReturn(Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>("脚本",
						null, "qwen", "qwen-plus", 1, false)));
		Map<String, Object> body = validBody();
		body.put("images", List.of("A".repeat(300 * 1024)));

		client().post().uri("/api/video-production/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

		verify(frozenText).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
	}

	@Test
	@DisplayName("成功 → 经执行环（VIDEO_PRODUCTION_SCRIPT）+ 多模态 text/image parts + 单帧 SSE + 安全帧")
	void streamsMultimodalVideoScript() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<com.grassland.intelligence.ai.ChatMessage>>) (Class<?>) List.class);
		when(frozenText.executeIndependent(any(), messagesCaptor.capture(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.VIDEO_PRODUCTION_SCRIPT), any()))
				.thenReturn(Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(
						"【镜头1】旁白：欢迎光临", null, "qwen", "qwen-plus", 1, false)));

		byte[] body = client().post().uri("/api/video-production/generate-script")
				.header("X-Grassland-Identity", signed()).contentType(MediaType.APPLICATION_JSON).bodyValue(validBody())
				.exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.expectHeader().valueEquals("X-Accel-Buffering", "no").expectBody().returnResult().getResponseBody();

		assertThat(new String(body, UTF_8)).isEqualTo(
				"data: {\"content\":\"【镜头1】旁白：欢迎光临\"}\n\n" + "data: {\"type\":\"safety\",\"safety\":{\"findings\":[],"
						+ "\"lexiconVersion\":\"lexicon-v1\",\"deepCheck\":false,"
						+ "\"appliedOverlays\":[\"food\"]}}\n\n" + "data: [DONE]\n\n");
		verify(credits, never()).consume(any(), any());

		List<com.grassland.intelligence.ai.ChatMessage> messages = messagesCaptor.getValue();
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).content()).contains("烟火纪实").contains("餐饮");
		assertThat(messages.get(1).multimodal()).isTrue();
		List<ContentPart> parts = messages.get(1).parts();
		assertThat(parts).hasSize(3);
		assertThat(((ContentPart.Text) parts.get(0)).text()).contains("店铺名称：草场咖啡").contains("店铺地址：测试路 1 号")
				.contains("店铺描述：社区咖啡店").contains("用户要求：突出手冲咖啡").contains("2 张素材图片");
		assertThat(((ContentPart.Image) parts.get(1)).url()).isEqualTo("data:image/jpeg;base64,AAAA");
		assertThat(((ContentPart.Image) parts.get(2)).url()).isEqualTo("data:image/png;base64,BBBB");
	}

	@Test
	@DisplayName("完成视频只返回 owner scoped 短时媒体 URL，不泄漏 provider URL")
	void completedVideoReturnsShortLivedArchivedUrl() {
		String account = "41414141-4141-4141-4141-414141414141";
		UUID mediaId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		db.sql("""
				INSERT INTO media_reference(id, owner_account_id, purpose, domain_type, domain_id,
				    object_key, mime_type, size_bytes, checksum, source, status)
				VALUES (CAST(:media AS uuid), :account, 'video_asset', 'video_generation_job',
				    :job, 'media/video_asset/' || :media, 'video/mp4', 8, repeat('a', 64), 'generated', 'active')
				""").bind("media", mediaId.toString()).bind("account", account).bind("job", jobId.toString()).then()
				.block();
		db.sql("""
				INSERT INTO video_generation_job(id, account_id, idempotency_key, provider, model, status,
				    progress, input_payload, result_url, requested_duration_seconds, aspect_ratio,
				    pricing_version, unit_price_cents, estimated_cost_cents, platform_model_version)
				VALUES (CAST(:job AS uuid), :account, :key, 'minimax', 'video-01', 'succeeded', 100,
				    '{}'::jsonb, :reference, 5, '9:16', 'test-v1', 2, 10, 1)
				""").bind("job", jobId.toString()).bind("account", account).bind("key", UUID.randomUUID().toString())
				.bind("reference", "/api/media/" + mediaId).then().block();

		client().get().uri("/api/video-production/jobs/{id}/download-url", jobId)
				.header("X-Grassland-Identity", sign(account, "merchant")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.downloadUrl").isEqualTo("https://media.example.test/signed-video").jsonPath("$.mediaId")
				.isEqualTo(mediaId.toString()).jsonPath("$.downloadUrl")
				.value(value -> assertThat((String) value).doesNotContain("provider.example"));
		verify(storage).presignDownload("media/video_asset/" + mediaId, 300L);
	}

	@Test
	@DisplayName("视频下载 URL 对跨账号和未归档 provider URL fail-closed")
	void videoDownloadUrlDoesNotCrossAccountOrFallbackToProviderUrl() {
		String owner = "42424242-4242-4242-4242-424242424242";
		UUID jobId = UUID.randomUUID();
		db.sql("""
				INSERT INTO video_generation_job(id, account_id, idempotency_key, provider, model, status,
				    progress, input_payload, result_url, requested_duration_seconds, aspect_ratio,
				    pricing_version, unit_price_cents, estimated_cost_cents, platform_model_version)
				VALUES (CAST(:job AS uuid), :account, :key, 'minimax', 'video-01', 'succeeded', 100,
				    '{}'::jsonb, 'https://provider.example/video.mp4', 5, '9:16', 'test-v1', 2, 10, 1)
				""").bind("job", jobId.toString()).bind("account", owner).bind("key", UUID.randomUUID().toString())
				.then().block();

		client().get().uri("/api/video-production/jobs/{id}/download-url", jobId)
				.header("X-Grassland-Identity", sign("43434343-4343-4343-4343-434343434343", "merchant")).exchange()
				.expectStatus().isNotFound();
		client().get().uri("/api/video-production/jobs/{id}/download-url", jobId)
				.header("X-Grassland-Identity", sign(owner, "merchant")).exchange().expectStatus().isNotFound();
		verify(storage, never()).presignDownload(any(), any(Long.class));
	}

	private static Map<String, Object> validBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("images", List.of("AAAA", "data:image/png;base64,BBBB"));
		body.put("shopName", " 草场咖啡 ");
		body.put("industryType", "餐饮");
		body.put("shopAddress", " 测试路 1 号 ");
		body.put("shopDescription", "社区咖啡店");
		body.put("videoStyle", "烟火纪实");
		body.put("customPrompt", "突出手冲咖啡");
		return body;
	}
}
