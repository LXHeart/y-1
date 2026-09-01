package com.grassland.intelligence;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;
import static com.grassland.identity.assertion.TestAssertionHelper.edgeBffSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * intelligence 集成测试公共基座（草场 intelligence Slice 1）。镜像 marketplace 基座。
 *
 * <p>
 * 单例 testcontainers
 * postgres（{@code intelligence.datasource.from-database-url=true} → Flyway 建
 * outbox）； WireMock 托管平台默认
 * Qwen（{@code /chat/completions}）；{@code identity-assertion} 注入 signer；
 * {@code intelligence.outbox.enabled=false} 关掉发布器（默认 kafka bootstrap
 * 解析不到会在事件循环线程阻塞， 饿死 WebFlux——各 IT 断言 outbox 表里的行，从不校验真实投递）。提供 {@link #sign}
 * 签断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntelligenceItSupport {

	// max_connections 提到 500（镜像默认 100）：本套件 50+ 个 IT 类、按配置差异缓存出
	// 十余个 Spring 上下文，每个 R2DBC 池默认占 ~10 连接——默认值下套件后段必然
	// "FATAL: too many clients already"（上下文起不来级联全挂，CI 与本地均可复现）。
	public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withCommand("postgres", "-c", "max_connections=500");
	public static final WireMockServer QWEN = new WireMockServer(0); // 0 = 随机端口，start 后取实际端口

	static {
		POSTGRES.start();
		createBootstrapPrerequisites();
		QWEN.start();
	}

	private static void createBootstrapPrerequisites() {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE app_users (
					    id uuid PRIMARY KEY,
					    email text NOT NULL UNIQUE,
					    password_hash text NOT NULL,
					    display_name text,
					    role text NOT NULL DEFAULT 'user',
					    status text NOT NULL DEFAULT 'active',
					    created_at timestamptz NOT NULL DEFAULT now(),
					    updated_at timestamptz NOT NULL DEFAULT now(),
					    last_login_at timestamptz
					)
					""");
			statement.execute("""
					CREATE TABLE user_settings (
					    id uuid PRIMARY KEY,
					    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
					    settings_type text NOT NULL,
					    settings_json jsonb NOT NULL,
					    version integer NOT NULL DEFAULT 1,
					    created_at timestamptz NOT NULL DEFAULT now(),
					    updated_at timestamptz NOT NULL DEFAULT now(),
					    CONSTRAINT user_settings_type_check
					        CHECK (settings_type IN ('analysis', 'homepage', 'image-review-style')),
					    CONSTRAINT user_settings_unique_user_type UNIQUE (user_id, settings_type)
					)
					""");
		} catch (Exception failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}

	@LocalServerPort
	protected int port;

	@Autowired
	protected IdentityAssertionSigner signer;

	@Autowired
	protected DatabaseClient db;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
				+ POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
		r.add("intelligence.datasource.from-database-url", () -> "true");
		r.add("DATABASE_URL", () -> dbUrl);
		r.add("management.server.port", () -> "0");
		r.add("identity-assertion.enabled", () -> "true");
		registerServiceKeyring(r, "intelligence");
		r.add("intelligence.outbox.enabled", () -> "false");
		r.add("ai.credit-compensation.enabled", () -> "false");
		// 素材 Embedding 索引 worker：IT 里静默（轮询间隔拉到 1h），各 IT 直接调 runOnce() 驱动确定性断言。
		r.add("ai.embedding-index.poll-interval-ms", () -> "3600000");
		// 任务书 #58：平台凭据信封加密落库——IT 共享测试 KEK（32 字节 0x00..0x1F 的 Base64，
		// 与各 BYOK/凭据 IT 同款常量，统一后无需每类重复声明）。
		r.add("crypto.kek.encoded",
				() -> "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
		// 未启 MinIO：回落 LocalGeneratedImageStore（本地卷），S3 自动配置与 S3GeneratedImageStore 不装配。
		r.add("object-storage.enabled", () -> "false");
		// 任务书 #58：ai.qwen.* 已删——测试的平台模型行各 IT 自行直插控制面表（带凭据密钥走 KEK）。
		r.add("ai.platform-model.allow-insecure-loopback", () -> "true");
	}

	@Autowired
	protected com.grassland.intelligence.ai.controlplane.TrustedOriginService trustedOrigins;

	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryptionProvider;

	/**
	 * 任务书 #58：平台 base-url 的 SSRF 校验只认受信 origin 表（控制面唯一真相源）——
	 * WireMock 的 localhost origin 也要登记（等价生产在治理台加端点），并刷新策略缓存。
	 * 基类 @BeforeEach 先于子类清理执行；子类若清 origin 表（PlatformTrustedOriginControllerIT）
	 * 也不会影响——那组用例不触 WireMock。
	 */
	@BeforeEach
	void trustWiremockPlatformOrigin() {
		db.sql("INSERT INTO platform_trusted_origin(origin, label) "
				+ "VALUES (:origin, 'IT WireMock 平台端点') ON CONFLICT (origin) DO NOTHING")
				.bind("origin", QWEN.baseUrl())
				.then()
				.then(trustedOrigins.refresh())
				.block(java.time.Duration.ofSeconds(10));
	}

	/**
	 * 任务书 #58 决策 E：平台 text 行必须挂带密凭据（seeder 与 env 兜底已删）。单条 SQL 兼顾两种
	 * 现状：已有无凭据 text 行 → 补挂；完全没种行（原靠 seeder 的类）→ 建行。各 IT 在自己的
	 * cleanAndSeed <b>末尾</b>调用（基类 @BeforeEach 先于子类清理，时机不对）。
	 *
	 * @return 生效 text/primary 行的配置 ID（没有则建出）
	 */
	protected String attachPlatformTextCredential() {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-platform-text-key");
		// 自清理：共享容器里上一用例留下的同名凭据会撞目的地唯一索引（其 text 行由各类自清或下行删除）
		// 自清理按「目的地」而非名字：共享容器里多个 IT 类都会在 QWEN.baseUrl() 建凭据，
		// 名字各不相同但目的地唯一索引只认 (provider, base_url)——不按目的地清就互相撞。
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl))")
				.bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-platform-text', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***it', true)
				    RETURNING id
				), attached AS (
				    UPDATE platform_model_config config
				    SET credential_id = cred.id
				    FROM cred
				    WHERE config.capability = 'text' AND config.enabled = true AND config.credential_id IS NULL
				    RETURNING config.id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1,cred.id
				FROM cred
				WHERE NOT EXISTS (SELECT 1 FROM attached)
				  AND NOT EXISTS (SELECT 1 FROM platform_model_config WHERE capability='text' AND enabled=true)
				""")
				.bind("baseUrl", QWEN.baseUrl())
				.bind("encrypted", encrypted)
				.then().block(java.time.Duration.ofSeconds(10));
		return db.sql("""
				SELECT id::text FROM platform_model_config
				WHERE capability='text' AND enabled=true
				ORDER BY version DESC LIMIT 1
				""")
				.map(row -> row.get("id", String.class))
				.one().block(java.time.Duration.ofSeconds(10));
	}

	/**
	 * 任务书 #58：为指定 capability 已种的无凭据行补挂带密凭据（只补挂不建行；名字按 capability
	 * 区分，避免跨能力标签唯一索引冲突）。content_safety 深检等非 text 能力用它。
	 */
	protected void attachPlatformCredentialTo(String capability) {
		String credName = "it-platform-" + capability;
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-" + credName + "-key");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl))")
				.bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES (:credName, 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***it', true)
				    RETURNING id
				)
				UPDATE platform_model_config config
				SET credential_id = cred.id
				FROM cred
				WHERE config.capability = :capability AND config.enabled = true AND config.credential_id IS NULL
				""")
				.bind("credName", credName)
				.bind("baseUrl", QWEN.baseUrl())
				.bind("encrypted", encrypted)
				.bind("capability", capability)
				.then().block(java.time.Duration.ofSeconds(10));
	}

	/** 任务书 #58：种带凭据的 image_generation 平台行（静态 env 回落已删；出图端点在 IT 里被 mock，
	 * 端点地址不参与真实出站，用独立假域名避开共享容器里的目的地唯一索引冲突）。 */
	protected void seedPlatformImageGenerationModel() {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-platform-image-key");
		// 先删引用行再删凭据（credential_id 外键），否则 DELETE 被拒（共享容器自清理）
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-platform-image'))")
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-platform-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-platform-image'").then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-platform-image', 'qwen', 'https://it-image.example/v1',
				        :encrypted, 'v1', 'sk-***img', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    max_concurrency, health_status, enabled, version, credential_id)
				SELECT 'image_generation','primary','qwen','it-image-model','https://it-image.example/v1',
				    NULL,'healthy',true,1,cred.id
				FROM cred
				WHERE NOT EXISTS (SELECT 1 FROM platform_model_config WHERE capability='image_generation' AND enabled=true)
				""")
				.bind("encrypted", encrypted)
				.then().block(java.time.Duration.ofSeconds(10));
	}

	/**
	 * 跨类残留自愈（共享容器）：各类只在<b>自己的</b> @BeforeEach 清理，最后一个用例的行要等
	 * 下一类来清——而下一类的清理顺序未必兼容外键链（video_generation_job→ai_run、
	 * ai_run→creation_context_snapshot），一旦撞上就级联 FK 失败。基类先行按依赖序清空这些
	 * 「每类必自种」的共享表，子类清理随后的重复 DELETE 是无害空操作。
	 */
	@BeforeEach
	void clearSharedAiRunDependencies() {
		// 任务书 #64：video_shot_audio / video_production_task 都挂 ai_run FK（RESTRICT），
		// 必须先于 ai_run 删除，否则跨类残留行让每个后续 IT 的清理连环 FK 失败。
		db.sql("DELETE FROM video_shot_audio").then()
				.then(db.sql("DELETE FROM video_production_task").then())
				.then(db.sql("DELETE FROM video_generation_job").then())
				.then(db.sql("DELETE FROM ai_credit_compensation").then())
				.then(db.sql("DELETE FROM ai_run").then())
				.block(java.time.Duration.ofSeconds(10));
	}

	protected WebTestClient client() {
		// responseTimeout 30s：默认 5s 在 CI 慢 runner + testcontainers 负载下偶发超时
		// （StorePublicProfileControllerIT 曾 1/524 flake）。
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
				.responseTimeout(java.time.Duration.ofSeconds(30)).build();
	}

	/** 签一个断言（org/tier/role 为 null——冒烟端点只需任意登录用户）。 */
	protected String sign(String accountId, String activeIdentityType) {
		return signWithRole(accountId, activeIdentityType, null, null);
	}

	/** 签带 role 的断言（GL-P3-AI-001：requireAdmin / 组织作用域测试用）。role 经 16 参构造器。 */
	protected String signWithRole(String accountId, String activeIdentityType, String organizationId, String role) {
		Instant now = Instant.now();
		return userSigner("edge-bff", "grassland-intelligence").sign(new IdentityAssertion(accountId,
				activeIdentityType, "sid-" + accountId, organizationId, null, "cookie-session", "level1", null, "r",
				"t", "grassland-intelligence", now, now.plusSeconds(60), null, null, role));
	}

	/** 平台管理员断言（role=admin）。 */
	protected String signAdmin(String accountId) {
		return signWithRole(accountId, null, null, "admin");
	}

	/** 组织成员断言（商家活动身份 + org）。 */
	protected String signWithOrg(String accountId, String organizationId) {
		return signWithRole(accountId, "merchant", organizationId, null);
	}
}
