package com.grassland.intelligence.media;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;
import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** 任务书 #42 Stage 1：门店媒体代开票据 + 批量换 URL 端点（四重过滤子集语义）端到端验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoreMediaEndpointsIT {

	/** 多模态审核走平台 Qwen（ai.qwen.*）；默认不打桩（unmatched 404 → advisory 未审）。 */
	static final WireMockServer QWEN = new WireMockServer(0);

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
	private static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
			.withCommand("server", "/data").withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "minioadmin")
			.withEnv("MINIO_ROOT_PASSWORD", "minioadmin").waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

	private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
	/** 8 字节任意内容：store_media 的 confirm 只校验 size + MIME 字符串，无 magic-byte 校验。 */
	private static final byte[] MP4 = new byte[]{'m', 'p', '4', '-', 't', 'e', 's', 't'};
	/** 抽帧桩的假 jpeg 帧字节（IT 只断言送审形态，真实抽帧见 VideoFrameExtractorTest）。 */
	private static final byte[] FRAME = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3};

	/** 视频帧抽取桩：避免 IT 依赖宿主 ffmpeg（服务镜像/CI 内置，但 IT 保持环境无关）。 */
	@MockitoBean
	private VideoFrameExtractor frameExtractor;

	static {
		QWEN.start();
		POSTGRES.start();
		MINIO.start();
	}

	private final ObjectMapper mapper = new ObjectMapper();

	@Autowired
	private org.springframework.r2dbc.core.DatabaseClient db;

	@Autowired
	private com.grassland.intelligence.ai.controlplane.TrustedOriginService trustedOrigins;

	@Autowired
	private org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryptionProvider;

	@BeforeEach
	void resetQwen() {
		QWEN.resetAll();
		try {
			// 任务书 #58：受信端点登记 + 带凭据 text 行（seeder/env 兜底已删，审核链依赖平台 text）
			db.sql("INSERT INTO platform_trusted_origin(origin, label) "
					+ "VALUES (:origin, 'IT WireMock 平台端点') ON CONFLICT (origin) DO NOTHING")
					.bind("origin", QWEN.baseUrl()).then()
					.then(trustedOrigins.refresh()).block(java.time.Duration.ofSeconds(10));
			String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-store-media-text-key");
			db.sql("""
					WITH cred AS (
					    INSERT INTO platform_provider_credential(name, provider, base_url,
					        encrypted_key, key_version, masked_hint, enabled)
					    VALUES ('store-media-text', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***sm', true)
					    RETURNING id
					)
					INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
					    health_status, enabled, version, credential_id)
					SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1,cred.id
					FROM cred
					WHERE NOT EXISTS (SELECT 1 FROM platform_model_config WHERE capability='text' AND enabled=true)
					""")
					.bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted)
					.then().block(java.time.Duration.ofSeconds(10));
		} catch (RuntimeException error) {
			// 容器尚未就绪等瞬态：首个用例真正起跑时上下文已就位；静默与既有 best-effort 姿态一致
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
				+ POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
		String minioUrl = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
		r.add("intelligence.datasource.from-database-url", () -> "true");
		r.add("DATABASE_URL", () -> dbUrl);
		r.add("management.server.port", () -> "0");
		r.add("identity-assertion.enabled", () -> "true");
		registerServiceKeyring(r, "intelligence");
		r.add("intelligence.outbox.enabled", () -> "false");
		// 卡A1：Temporal 内存 test server（不依赖 temporal 容器）。
		r.add("spring.temporal.test-server.enabled", () -> "true");
		// 任务书 #58：ai.qwen.* 已删——审核用平台 text 行在 setup 里直插（带凭据密钥，KEK 见下）
		r.add("crypto.kek.encoded",
				() -> "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
		// WireMock 是 http://localhost → 放行环回明文（与 IntelligenceItSupport 同口径）
		r.add("ai.platform-model.allow-insecure-loopback", () -> "true");
		r.add("object-storage.enabled", () -> "true");
		r.add("object-storage.endpoint", () -> minioUrl);
		r.add("object-storage.public-base-url", () -> minioUrl);
		r.add("object-storage.access-key", () -> "minioadmin");
		r.add("object-storage.secret-key", () -> "minioadmin");
		r.add("object-storage.bucket", () -> "grassland-store-media-it");
		r.add("object-storage.auto-create-bucket", () -> "true");
		r.add("media.cleanup-interval-ms", () -> "3600000");
		// 多资产用例需要放宽 owner 配额（MediaControllerIT 刻意压到 1 测配额）。
		r.add("media.max-objects-per-owner", () -> "100");
		r.add("media.max-total-bytes-per-owner", () -> "209715200");
		r.add("article-images.generated.cleanup-interval-ms", () -> "3600000");
	}

	@LocalServerPort
	private int port;

	@Autowired
	private IdentityAssertionSigner signer;

	@Autowired
	private MediaReferenceRepository mediaRefs;

	@Autowired
	private StoreMediaModerationRepository storeMediaModeration;

	/** #42 D2/D12：两端点缺断言 401；浏览器（终端用户）会话直连与非 identity 服务 principal 一律 403。 */
	@Test
	void storeMediaEndpointsRequireIdentityServiceAssertion() {
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		Map<String, Object> ticketBody = Map.of("ownerAccountId", "acct-" + UUID.randomUUID(), "storeId", storeId,
				"contentType", "image/png", "sizeBytes", PNG.length);
		Map<String, Object> downloadBody = Map.of("storeId", storeId, "mediaIds",
				List.of(UUID.randomUUID().toString()));

		// 缺断言 → 401
		client().post().uri("/api/media/store-media-upload-tickets").bodyValue(ticketBody).exchange().expectStatus()
				.isUnauthorized();
		client().post().uri("/api/media/store-media-download-urls").bodyValue(downloadBody).exchange().expectStatus()
				.isUnauthorized();

		// 浏览器会话（终端用户断言）直连 → 403
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", sign("acct-" + UUID.randomUUID(), org)).bodyValue(ticketBody).exchange()
				.expectStatus().isForbidden();
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", sign("acct-" + UUID.randomUUID(), org)).bodyValue(downloadBody)
				.exchange().expectStatus().isForbidden();

		// 非 identity 服务 principal → 403
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "marketplace")).bodyValue(ticketBody).exchange()
				.expectStatus().isForbidden();
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "marketplace")).bodyValue(downloadBody).exchange()
				.expectStatus().isForbidden();
	}

	/**
	 * #42 Stage 1：票据落 pending 行锚定
	 * domain_type='store'/domain_id=storeId/organization_id=断言 org；confirm 后 org
	 * 归属不变。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void storeMediaTicketCreatesStoreScopedPendingRowAndConfirmKeepsAssertionOrg() throws Exception {
		String owner = "acct-" + UUID.randomUUID();
		String assertionOrg = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();

		Map<String, Object> envelope = client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(assertionOrg, "identity"))
				.bodyValue(Map.of("ownerAccountId", owner, "storeId", storeId, "contentType", "image/png", "sizeBytes",
						PNG.length))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		assertThat(envelope).isNotNull();
		Map<String, Object> ticket = (Map<String, Object>) envelope.get("data");
		UUID mediaId = UUID.fromString((String) ticket.get("id"));
		assertThat((String) ticket.get("uploadUrl")).startsWith("http");

		MediaReference pending = mediaRefs.findById(mediaId).block();
		assertThat(pending).isNotNull();
		assertThat(pending.ownerAccountId()).isEqualTo(owner);
		assertThat(pending.organizationId()).isEqualTo(assertionOrg);
		assertThat(pending.purpose()).isEqualTo("store_media");
		assertThat(pending.domainType()).isEqualTo("store");
		assertThat(pending.domainId()).isEqualTo(storeId);
		assertThat(pending.status()).isEqualTo(MediaStatus.PENDING);

		put(URI.create((String) ticket.get("uploadUrl")), PNG, "image/png");
		// 用户当前活动组织可以不同；资产 org 归属由开票时的服务断言决定。
		client().post().uri("/api/media/{id}/confirm", mediaId)
				.header("X-Grassland-Identity", sign(owner, UUID.randomUUID().toString())).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.status").isEqualTo("active").jsonPath("$.data.purpose")
				.isEqualTo("store_media").jsonPath("$.data.organizationId").isEqualTo(assertionOrg)
				.jsonPath("$.data.domainType").isEqualTo("store").jsonPath("$.data.domainId").isEqualTo(storeId);
	}

	/**
	 * 换 URL 快乐路径：confirm 过的门店图片可得 presigned GET，真下载还原字节；图片 URL 内联（无 disposition
	 * 覆盖）。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void storeMediaDownloadUrlServesConfirmedImageInline() throws Exception {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "image/png", PNG);

		Map<String, Object> envelope = client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", storeId, "mediaIds", List.of(mediaId.toString()))).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		assertThat(envelope).isNotNull();
		Map<String, Object> data = (Map<String, Object>) envelope.get("data");
		List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
		assertThat(items).hasSize(1);
		Map<String, Object> item = items.get(0);
		assertThat(item.get("id")).isEqualTo(mediaId.toString());
		assertThat(item.get("mimeType")).isEqualTo("image/png");
		assertThat(item.get("sizeBytes")).isEqualTo(PNG.length);
		// 永久资产（开票未带 TTL）：expiresAt 为资产 TTL 口径，此处为空。
		assertThat(item.get("expiresAt")).isNull();
		URI downloadUrl = URI.create((String) item.get("downloadUrl"));
		assertThat(downloadUrl.toString()).doesNotContain("response-content-disposition");

		HttpResponse<byte[]> download = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(downloadUrl).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(download.statusCode()).isEqualTo(200);
		assertThat(download.body()).isEqualTo(PNG);
	}

	/**
	 * 宣传视频：URL 注入 attachment; filename=<id>.mp4（复用既有 downloadDisposition，视频带下载名）。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void storeMediaVideoDownloadUrlInjectsAttachmentDisposition() throws Exception {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "video/mp4", MP4);

		Map<String, Object> envelope = client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", storeId, "mediaIds", List.of(mediaId.toString()))).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) envelope.get("data"))
				.get("items");
		URI downloadUrl = URI.create((String) items.get(0).get("downloadUrl"));
		assertThat(downloadUrl.getRawQuery()).contains("response-content-disposition");
		assertThat(downloadUrl.getRawQuery()).contains("attachment");
		assertThat(downloadUrl.getRawQuery()).contains(mediaId + ".mp4");

		HttpResponse<byte[]> download = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(downloadUrl).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(download.statusCode()).isEqualTo(200);
		assertThat(download.body()).isEqualTo(MP4);
	}

	/** 四重过滤①：他 org 的 store_media 资产不在返回子集。 */
	@Test
	void downloadUrlsExcludeMediaFromOtherOrganization() {
		String owner = "acct-" + UUID.randomUUID();
		String storeId = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		UUID live = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, null, "image/png");
		UUID foreignOrg = insertStoreMedia(owner, UUID.randomUUID().toString(), storeId, MediaStatus.ACTIVE, null,
				"image/png");

		List<String> ids = requestDownloadIds(org, storeId, List.of(live, foreignOrg));
		assertThat(ids).containsExactly(live.toString());
	}

	/** 四重过滤②：domain_id 不是请求 storeId 的资产不在返回子集。 */
	@Test
	void downloadUrlsExcludeMediaFromOtherStore() {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID live = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, null, "image/png");
		UUID otherStore = insertStoreMedia(owner, org, UUID.randomUUID().toString(), MediaStatus.ACTIVE, null,
				"image/png");

		List<String> ids = requestDownloadIds(org, storeId, List.of(live, otherStore));
		assertThat(ids).containsExactly(live.toString());
	}

	/** 四重过滤③：非 active（pending/deleted 等）资产不在返回子集——未 confirm 的票据不该放出 URL。 */
	@Test
	void downloadUrlsExcludeInactiveMedia() {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID live = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, null, "image/png");
		UUID pending = insertStoreMedia(owner, org, storeId, MediaStatus.PENDING, null, "image/png");
		UUID deleted = insertStoreMedia(owner, org, storeId, MediaStatus.DELETED, null, "image/png");

		List<String> ids = requestDownloadIds(org, storeId, List.of(live, pending, deleted));
		assertThat(ids).containsExactly(live.toString());
	}

	/** 四重过滤④：已过资产 TTL 的 active 媒体不在返回子集。 */
	@Test
	void downloadUrlsExcludeExpiredMedia() {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID live = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, null, "image/png");
		UUID expired = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, Instant.now().minusSeconds(30),
				"image/png");

		List<String> ids = requestDownloadIds(org, storeId, List.of(live, expired));
		assertThat(ids).containsExactly(live.toString());
	}

	/** 四重过滤附带：非 store_media 用途即便 org/store 锚全对也不放行（purpose 谓词）。 */
	@Test
	void downloadUrlsExcludeNonStoreMediaPurpose() {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID live = insertStoreMedia(owner, org, storeId, MediaStatus.ACTIVE, null, "image/png");
		UUID foreignPurpose = insertMedia(owner, org, "user_upload", MediaStatus.ACTIVE, null, "store", storeId);

		List<String> ids = requestDownloadIds(org, storeId, List.of(live, foreignPurpose));
		assertThat(ids).containsExactly(live.toString());
	}

	/** #42 D7：MIME 白名单（图片∪视频）之外的类型与分型大小帽（图片 10MB / 视频 20MB）在开票时就拒。 */
	@Test
	void storeMediaTicketRejectsUnsupportedMimeAndTypeSpecificSizeCaps() {
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		String owner = "acct-" + UUID.randomUUID();

		// 白名单外 MIME（音频/PDF/GIF）→ 400
		for (String mime : List.of("audio/mpeg", "application/pdf", "image/gif")) {
			client().post().uri("/api/media/store-media-upload-tickets")
					.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId",
							owner, "storeId", storeId, "contentType", mime, "sizeBytes", PNG.length))
					.exchange().expectStatus().isBadRequest();
		}

		// 图片超 10MB 帽 → 400；恰好 10MB 可开票
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId", owner,
						"storeId", storeId, "contentType", "image/png", "sizeBytes", 10L * 1024 * 1024 + 1))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId", owner,
						"storeId", storeId, "contentType", "image/jpeg", "sizeBytes", 10L * 1024 * 1024))
				.exchange().expectStatus().isOk();

		// 视频超 20MB 帽 → 400；图片帽不适用于视频（15MB 视频可开票）
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId", owner,
						"storeId", storeId, "contentType", "video/mp4", "sizeBytes", 20L * 1024 * 1024 + 1))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId", owner,
						"storeId", storeId, "contentType", "video/quicktime", "sizeBytes", 15L * 1024 * 1024))
				.exchange().expectStatus().isOk();

		// sizeBytes 缺失/为零 → 400
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("ownerAccountId", owner, "storeId", storeId, "contentType", "image/png")).exchange()
				.expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(
						Map.of("ownerAccountId", owner, "storeId", storeId, "contentType", "image/png", "sizeBytes", 0))
				.exchange().expectStatus().isBadRequest();
	}

	/** 请求形态校验：storeId 非 UUID、mediaIds 空/非法 UUID/去重后 >50 一律 400。 */
	@Test
	void storeMediaEndpointsRejectMalformedRequests() {
		String org = UUID.randomUUID().toString();
		String owner = "acct-" + UUID.randomUUID();

		// 票据端点：storeId 缺失/非 UUID → 400
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("ownerAccountId", owner, "contentType", "image/png", "sizeBytes", PNG.length))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("ownerAccountId", owner,
						"storeId", "not-a-uuid", "contentType", "image/png", "sizeBytes", PNG.length))
				.exchange().expectStatus().isBadRequest();

		// 换 URL 端点：storeId 非 UUID → 400
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", "not-a-uuid", "mediaIds", List.of(UUID.randomUUID().toString())))
				.exchange().expectStatus().isBadRequest();
		// mediaIds 缺失/空 → 400
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", UUID.randomUUID().toString())).exchange().expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", UUID.randomUUID().toString(), "mediaIds", List.of())).exchange()
				.expectStatus().isBadRequest();
		// mediaIds 含非法 UUID → 400
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("storeId",
						UUID.randomUUID().toString(), "mediaIds", List.of(UUID.randomUUID().toString(), "oops")))
				.exchange().expectStatus().isBadRequest();
		// 去重后 >50 → 400（重复 id 去重后恰好 50 个可放行）
		List<String> fifty = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			fifty.add(UUID.randomUUID().toString());
		}
		List<String> fiftyOne = new ArrayList<>(fifty);
		fiftyOne.add(UUID.randomUUID().toString());
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", UUID.randomUUID().toString(), "mediaIds", fiftyOne)).exchange()
				.expectStatus().isBadRequest();
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity")).bodyValue(Map.of("storeId",
						UUID.randomUUID().toString(), "mediaIds", List.of(fifty.get(0), fifty.get(0))))
				.exchange().expectStatus().isOk();
		List<String> duplicatedFifty = new ArrayList<>(fifty);
		duplicatedFifty.addAll(fifty.subList(0, 10));
		client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", UUID.randomUUID().toString(), "mediaIds", duplicatedFifty)).exchange()
				.expectStatus().isOk();
	}

	/** 缺口清偿之五：confirm 门店图片触发多模态审核，pass 结论落库并随 confirm 响应返回。 */
	@Test
	@SuppressWarnings("unchecked")
	void confirmRunsMultimodalModerationAndReturnsVerdict() throws Exception {
		stubQwenModeration("pass");
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();

		Map<String, Object> envelope = client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("ownerAccountId", owner, "storeId", storeId, "contentType", "image/png", "sizeBytes",
						PNG.length))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> ticket = (Map<String, Object>) envelope.get("data");
		UUID mediaId = UUID.fromString((String) ticket.get("id"));
		put(URI.create((String) ticket.get("uploadUrl")), PNG, "image/png");

		client().post().uri("/api/media/{id}/confirm", mediaId).header("X-Grassland-Identity", sign(owner, org))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("active")
				.jsonPath("$.data.moderation.status").isEqualTo("pass");

		var row = storeMediaModeration.find(mediaId).block();
		assertThat(row).isNotNull();
		assertThat(row.status()).isEqualTo("pass");
		// 图片以 data URL 形态送审（多模态 vision 输入）
		QWEN.verify(com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/chat/completions"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("data:image/png;base64,")));
	}

	/** blocked 结论：公开换 URL 端点过滤该媒体（四重过滤 → 五重），绑定链路同样 fail-closed。 */
	@Test
	void blockedModerationExcludesMediaFromPublicDownloads() throws Exception {
		stubQwenModeration("blocked");
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "image/png", PNG);

		var row = storeMediaModeration.find(mediaId).block();
		assertThat(row).isNotNull();
		assertThat(row.status()).isEqualTo("blocked");

		List<String> ids = requestDownloadIds(org, storeId, List.of(mediaId));
		assertThat(ids).isEmpty();
	}

	/**
	 * 视频按帧送审（遗留清偿）：confirm 后抽帧逐张 data URL 送审（IT 桩掉 ffmpeg 抽帧， 抽帧本身的契约由
	 * {@link VideoFrameExtractorTest} 真 ffmpeg 覆盖）。
	 */
	@Test
	void videoConfirmModeratesExtractedFrames() throws Exception {
		stubQwenModeration("pass");
		when(frameExtractor.extract(any())).thenReturn(List.of(FRAME, FRAME, FRAME));
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "video/mp4", MP4);

		var row = storeMediaModeration.find(mediaId).block();
		assertThat(row).isNotNull();
		assertThat(row.status()).isEqualTo("pass");
		// 视频未被整体拦截（pass），公开端点照常放行
		List<String> ids = requestDownloadIds(org, storeId, List.of(mediaId));
		assertThat(ids).containsExactly(mediaId.toString());
		QWEN.verify(com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/chat/completions"))
				.withRequestBody(
						com.github.tomakehurst.wiremock.client.WireMock.containing("data:image/jpeg;base64,")));
	}

	/** 抽帧失败（ffmpeg 不可用/非视频字节）：与模型不可用同口径 advisory 降级——无行、不拦截。 */
	@Test
	void videoFrameExtractionFailureDegradesAdvisively() throws Exception {
		when(frameExtractor.extract(any()))
				.thenThrow(new com.grassland.intelligence.security.IntelligenceException(502, "媒体处理失败"));
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "video/mp4", MP4);

		assertThat(storeMediaModeration.find(mediaId).block()).isNull();
		List<String> ids = requestDownloadIds(org, storeId, List.of(mediaId));
		assertThat(ids).containsExactly(mediaId.toString());
	}

	/** 审核模型不可用（Qwen 404）：advisory 降级——confirm 仍成功、无审核行、公开端点不拦截。 */
	@Test
	void moderationUnavailableDegradesAdvisively() throws Exception {
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "image/png", PNG);

		assertThat(storeMediaModeration.find(mediaId).block()).isNull();
		List<String> ids = requestDownloadIds(org, storeId, List.of(mediaId));
		assertThat(ids).containsExactly(mediaId.toString());
	}

	/**
	 * 人工复核队列闭环（遗留清偿）：模型输出不可解析 → review 行进队列（带 findings 与预览 URL）→ 审核员 approve → pass
	 * 恢复公开展示；旧 expectedModeratedAt 重放被 409 拒。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void reviewQueueListsAndDecidesUnparseableRows() throws Exception {
		stubQwenRawContent("这不是 JSON，模型闲聊");
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "image/png", PNG);

		var row = storeMediaModeration.find(mediaId).block();
		assertThat(row).isNotNull();
		assertThat(row.status()).isEqualTo("review");

		Map<String, Object> envelope = client().get().uri("/api/admin/store-media-moderation")
				.header("X-Grassland-Identity", signWithRole("reviewer-1", null, "content_reviewer")).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data = (Map<String, Object>) envelope.get("data");
		assertThat(data.get("status")).isEqualTo("review");
		List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
		var queued = items.stream().filter(item -> mediaId.toString().equals(item.get("mediaId"))).findFirst();
		assertThat(queued).isPresent();
		assertThat(queued.get().get("storeId")).isEqualTo(storeId);
		assertThat((List<Map<String, Object>>) queued.get().get("findings")).isNotEmpty();
		assertThat((String) queued.get().get("downloadUrl")).isNotBlank();

		client().post().uri("/api/admin/store-media-moderation/{id}/review", mediaId)
				.header("X-Grassland-Identity", signWithRole("reviewer-1", null, "content_reviewer"))
				.bodyValue(Map.of("decision", "approve", "expectedModeratedAt", row.moderatedAt().toString()))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("pass");

		// pass 恢复公开展示
		assertThat(requestDownloadIds(org, storeId, List.of(mediaId))).containsExactly(mediaId.toString());
		// 旧 expectedModeratedAt（裁决后已刷新）重放 → 409
		client().post().uri("/api/admin/store-media-moderation/{id}/review", mediaId)
				.header("X-Grassland-Identity", signWithRole("reviewer-1", null, "content_reviewer"))
				.bodyValue(Map.of("decision", "approve", "expectedModeratedAt", row.moderatedAt().toString()))
				.exchange().expectStatus().isEqualTo(409);
	}

	/** 人工驳回：review → blocked 拦截公开展示，且驳回必须带原因。 */
	@Test
	void reviewRejectBlocksMediaAndRequiresNote() throws Exception {
		stubQwenRawContent("又不是 JSON");
		String owner = "acct-" + UUID.randomUUID();
		String org = UUID.randomUUID().toString();
		String storeId = UUID.randomUUID().toString();
		UUID mediaId = createConfirmedStoreMedia(owner, org, storeId, "image/png", PNG);
		var row = storeMediaModeration.find(mediaId).block();
		assertThat(row).isNotNull();

		// 无 note 驳回 → 400
		client().post().uri("/api/admin/store-media-moderation/{id}/review", mediaId)
				.header("X-Grassland-Identity", signWithRole("reviewer-2", null, "content_reviewer"))
				.bodyValue(Map.of("decision", "reject", "expectedModeratedAt", row.moderatedAt().toString())).exchange()
				.expectStatus().isBadRequest();

		client().post().uri("/api/admin/store-media-moderation/{id}/review", mediaId)
				.header("X-Grassland-Identity", signWithRole("reviewer-2", null, "content_reviewer"))
				.bodyValue(Map.of("decision", "reject", "note", "画面含违禁品暗示", "expectedModeratedAt",
						row.moderatedAt().toString()))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("blocked");

		assertThat(requestDownloadIds(org, storeId, List.of(mediaId))).isEmpty();
	}

	/** 复核端点门禁与校验：未登录 401、普通用户 403、status 非法 400、未知媒体 404。 */
	@Test
	void reviewQueueGuardsAndValidation() throws Exception {
		client().get().uri("/api/admin/store-media-moderation").exchange().expectStatus().isUnauthorized();
		client().get().uri("/api/admin/store-media-moderation").header("X-Grassland-Identity", sign("acct-plain", null))
				.exchange().expectStatus().isForbidden();
		client().get().uri("/api/admin/store-media-moderation?status=oops")
				.header("X-Grassland-Identity", signWithRole("reviewer-3", null, "content_reviewer")).exchange()
				.expectStatus().isBadRequest();
		client().post().uri("/api/admin/store-media-moderation/{id}/review", UUID.randomUUID())
				.header("X-Grassland-Identity", signWithRole("reviewer-3", null, "content_reviewer"))
				.bodyValue(Map.of("decision", "approve", "expectedModeratedAt", Instant.now().toString())).exchange()
				.expectStatus().isNotFound();
	}

	private void stubQwenModeration(String verdict) throws Exception {
		Map<String, Object> content = new java.util.LinkedHashMap<>();
		content.put("verdict", verdict);
		content.put("findings",
				verdict.equals("pass")
						? List.of()
						: List.of(Map.of("category", "pornographic", "severity", "high", "advice", "画面含违规内容")));
		Map<String, Object> response = Map.of("id", "chatcmpl-moderation", "choices",
				List.of(Map.of("message", Map.of("content", mapper.writeValueAsString(content)))),
				"usage", Map.of("prompt_tokens", 10, "completion_tokens", 5));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(response))));
	}

	/** 桩模型返回任意原始文本（不可解析 → review，人工复核队列入口态）。 */
	private void stubQwenRawContent(String rawContent) throws Exception {
		Map<String, Object> response = Map.of("id", "chatcmpl-moderation", "choices",
				List.of(Map.of("message", Map.of("content", rawContent))),
				"usage", Map.of("prompt_tokens", 10, "completion_tokens", 5));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(response))));
	}

	/** identity 服务断言三步上传并 confirm 一条门店媒体，返回 media id。 */
	private UUID createConfirmedStoreMedia(String owner, String org, String storeId, String contentType, byte[] content)
			throws Exception {
		Map<String, Object> envelope = client().post().uri("/api/media/store-media-upload-tickets")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("ownerAccountId", owner, "storeId", storeId, "contentType", contentType, "sizeBytes",
						content.length))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> ticket = (Map<String, Object>) envelope.get("data");
		UUID mediaId = UUID.fromString((String) ticket.get("id"));
		put(URI.create((String) ticket.get("uploadUrl")), content, contentType);
		client().post().uri("/api/media/{id}/confirm", mediaId).header("X-Grassland-Identity", sign(owner, org))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("active");
		return mediaId;
	}

	/** 请求批量换 URL 并返回响应 items 中的 id 列表（子集语义断言用）。 */
	@SuppressWarnings("unchecked")
	private List<String> requestDownloadIds(String org, String storeId, List<UUID> mediaIds) {
		Map<String, Object> envelope = client().post().uri("/api/media/store-media-download-urls")
				.header("X-Grassland-Identity", signService(org, "identity"))
				.bodyValue(Map.of("storeId", storeId, "mediaIds", mediaIds.stream().map(UUID::toString).toList()))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		assertThat(envelope).isNotNull();
		Map<String, Object> data = (Map<String, Object>) envelope.get("data");
		List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
		return items.stream().map(item -> (String) item.get("id")).toList();
	}

	/** 直接落一条门店媒体行（绕过上传/配额），构造四重过滤边缘态。 */
	private UUID insertStoreMedia(String owner, String org, String storeId, MediaStatus status, Instant expiresAt,
			String mimeType) {
		return insertMedia(owner, org, "store_media", status, expiresAt, "store", storeId, mimeType);
	}

	private UUID insertMedia(String owner, String org, String purpose, MediaStatus status, Instant expiresAt,
			String domainType, String domainId) {
		return insertMedia(owner, org, purpose, status, expiresAt, domainType, domainId, "image/png");
	}

	private UUID insertMedia(String owner, String org, String purpose, MediaStatus status, Instant expiresAt,
			String domainType, String domainId, String mimeType) {
		UUID id = UUID.randomUUID();
		MediaReference ref = new MediaReference(id, owner, org, purpose, domainType, domainId,
				"media/" + purpose + "/" + id, null, mimeType, PNG.length, MediaChecksums.sha256(PNG), "upload", status,
				Instant.now().minusSeconds(120), expiresAt, null);
		mediaRefs.insert(ref).block();
		return id;
	}

	private void put(URI uploadUrl, byte[] content, String contentType) throws Exception {
		HttpResponse<Void> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(uploadUrl).header("Content-Type", contentType)
						.PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
						HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).isEqualTo(200);
	}

	private WebTestClient client() {
		// responseTimeout 30s：CI 慢 runner 负载口径（IntelligenceItSupport 同款）。
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
				.responseTimeout(java.time.Duration.ofSeconds(30)).build();
	}

	private String sign(String accountId, String organizationId) {
		return signWithRole(accountId, organizationId, null);
	}

	/** 带平台角色的用户断言（16 参构造器，对齐 IntelligenceItSupport.signWithRole 口径）。 */
	private String signWithRole(String accountId, String organizationId, String role) {
		Instant now = Instant.now();
		return userSigner("edge-bff", "grassland-intelligence").sign(new IdentityAssertion(accountId, "merchant",
				"sid-" + accountId, organizationId, null, "cookie-session", "level1", null, "request", "trace",
				"grassland-intelligence", now, now.plusSeconds(60), null, null, role));
	}

	private String signService(String organizationId, String principal) {
		Instant now = Instant.now();
		return serviceSigner(principal, "grassland-intelligence").sign(new IdentityAssertion("service:" + principal,
				null, null, organizationId, null, "service", "internal", null, "request", "trace",
				"grassland-intelligence", now, now.plusSeconds(30), "service", principal));
	}
}
