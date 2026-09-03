package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 任务书 #64 卡2：capabilities 动态化——video_generation / video_tts 经控制面解析， 无行 =
 * slideshow / 无配音；凭据解密失败与缺价目 fail-closed。
 *
 * <p>
 * 价目侧刻意用兜底种子里的既有模型（qwen-plus 单秒 30 分；sandbox-video-v1=1、
 * sandbox-tts-v1=0）：PriceTableService 缓存 invalidate 是异步回源，IT 直插价目行再断言会竞态。
 */
class VideoGenerationProviderResolverIT extends IntelligenceItSupport {

	@Autowired
	private VideoGenerationProviderResolver resolver;

	@BeforeEach
	void cleanVideoControlPlaneRows() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE capability IN " + "('video_generation','video_tts'))")
				.then()
				.then(db.sql(
						"DELETE FROM platform_model_config WHERE capability IN " + "('video_generation','video_tts')")
						.then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-video-%'").then())
				.block(Duration.ofSeconds(10));
	}

	@Test
	void noControlPlaneRowMeansSlideshowAndNoTts() {
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("slideshow").jsonPath("$.video.available").isEqualTo(false)
				.jsonPath("$.video.reason").isEqualTo("未配置视频生成模型").jsonPath("$.tts.available").isEqualTo(false)
				.jsonPath("$.tts.reason").isEqualTo("配音模型未配置")
				// 旧契约兼容镜像（卡4 前端改造完成后移除）
				.jsonPath("$.available").isEqualTo(false).jsonPath("$.reason").isEqualTo("未配置视频生成模型");
	}

	@Test
	void pricedSeedanceRowMakesVideoModeAvailable() {
		seedVideoModel("seedance", "qwen-plus", true);
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("video").jsonPath("$.video.available").isEqualTo(true)
				.jsonPath("$.video.provider").isEqualTo("seedance").jsonPath("$.video.model").isEqualTo("qwen-plus")
				.jsonPath("$.video.unitPriceCents").isEqualTo(30).jsonPath("$.tts.available").isEqualTo(false);
	}

	@Test
	void sandboxVideoRowIsPricedAtOneCentPerSecond() {
		seedVideoModel("sandbox", "sandbox-video-v1", false);
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("video").jsonPath("$.video.provider").isEqualTo("sandbox")
				.jsonPath("$.video.unitPriceCents").isEqualTo(1);
	}

	@Test
	void garbageCredentialCiphertextFailsClosed() {
		seedVideoModelWithRawKey("minimax", "qwen-plus", "not-an-envelope-ciphertext");
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("slideshow").jsonPath("$.video.available").isEqualTo(false)
				.jsonPath("$.video.reason").isNotEmpty();
	}

	@Test
	void vendorModelWithoutCredentialKeyFailsClosed() {
		// 凭据行存在但无密钥：非 sandbox provider 一律拒绝（决策 E——不拿空 bearer 打上游）
		seedVideoModel("minimax", "qwen-plus", false);
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.video.available").isEqualTo(false).jsonPath("$.video.reason")
				.isEqualTo("平台凭据缺失：该能力的凭据未配置密钥");
	}

	@Test
	void unpricedVideoModelIsUnavailable() {
		seedVideoModel("seedance", "it-video-unpriced-model", true);
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("slideshow").jsonPath("$.video.available").isEqualTo(false)
				.jsonPath("$.video.reason").isEqualTo("视频生成模型缺少价目配置: it-video-unpriced-model");
	}

	@Test
	void sandboxTtsRowMakesVoiceoverAvailable() {
		seedTtsModel("sandbox", "sandbox-tts-v1");
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.tts.available").isEqualTo(true).jsonPath("$.tts.model").isEqualTo("sandbox-tts-v1")
				.jsonPath("$.tts.reason").isEqualTo("");
	}

	@Test
	void resolverExposesAdapterForTaskCreation() {
		seedVideoModel("seedance", "qwen-plus", true);
		var resolution = resolver.resolveVideoGeneration().block(Duration.ofSeconds(10));
		org.assertj.core.api.Assertions.assertThat(resolution).isNotNull();
		org.assertj.core.api.Assertions.assertThat(resolution.available()).isTrue();
		org.assertj.core.api.Assertions.assertThat(resolution.plan().adapter())
				.isInstanceOf(SeedanceVideoGenerationProvider.class);
		org.assertj.core.api.Assertions.assertThat(resolution.plan().resolution().provider()).isEqualTo("seedance");
		org.assertj.core.api.Assertions.assertThat(resolution.plan().resolution().baseUrl()).isEqualTo(QWEN.baseUrl());
	}

	@Test
	void pricedXaiRowResolvesGrokAdapter() {
		seedVideoModel("xai", "qwen-plus", true);
		client().get().uri("/api/video-production/capabilities").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.mode").isEqualTo("video").jsonPath("$.video.available").isEqualTo(true)
				.jsonPath("$.video.provider").isEqualTo("xai").jsonPath("$.video.unitPriceCents").isEqualTo(30);
		var resolution = resolver.resolveVideoGeneration().block(Duration.ofSeconds(10));
		org.assertj.core.api.Assertions.assertThat(resolution.available()).isTrue();
		org.assertj.core.api.Assertions.assertThat(resolution.plan().adapter())
				.isInstanceOf(XaiVideoGenerationProvider.class);
	}

	/** 带（可解密的）密钥凭据 + capability 行；withKey=false 时凭据无密钥。 */
	private void seedVideoModel(String provider, String model, boolean withKey) {
		String encrypted = withKey ? encryptionProvider.getIfAvailable().encrypt("sk-it-video-key") : null;
		seedCapabilityRow("video_generation", provider, model, encrypted);
	}

	private void seedVideoModelWithRawKey(String provider, String model, String rawKey) {
		seedCapabilityRow("video_generation", provider, model, rawKey);
	}

	private void seedTtsModel(String provider, String model) {
		seedCapabilityRow("video_tts", provider, model, null);
	}

	private void seedCapabilityRow(String capability, String provider, String model, String encryptedKey) {
		String name = "it-video-" + capability + "-" + provider;
		String encryptedBound = encryptedKey == null ? "" : encryptedKey;
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES (:name, :provider, :baseUrl, CAST(NULLIF(:encrypted,'') AS text),
				        'v1', 'sk-***video', true)
				    RETURNING id, base_url
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT :capability, 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
				FROM cred
				""").bind("name", name).bind("provider", provider).bind("baseUrl", QWEN.baseUrl())
				.bind("encrypted", encryptedBound).bind("capability", capability).bind("model", model).then()
				.block(Duration.ofSeconds(10));
	}
}
