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
		// 未启 MinIO：回落 LocalGeneratedImageStore（本地卷），S3 自动配置与 S3GeneratedImageStore 不装配。
		r.add("object-storage.enabled", () -> "false");
		// 平台默认 Qwen 指向 WireMock：base-url 是主机名（localhost）→ SSRF 结构校验通过（IP 字面量才拒）。
		r.add("ai.qwen.base-url", QWEN::baseUrl);
		r.add("ai.qwen.api-key", () -> "sk-synthetic-intelligence-test-key");
		r.add("ai.qwen.model", () -> "qwen-plus");
		r.add("ai.platform-model.allow-insecure-loopback", () -> "true");
	}

	protected WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
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
