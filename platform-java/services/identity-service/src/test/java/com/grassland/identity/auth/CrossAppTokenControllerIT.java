package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 任务书 #76 卡 A：跨应用一次性免登 token 全链 IT（自备 PG + Redis 容器）。 必备用例：签发需登录；核销成功建会话；同
 * token 二次核销 401；过期 401；核销后原会话不受影响。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossAppTokenControllerIT {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	/**
	 * Redis nonce 存储（先例：platform-identity-assertion RedisAssertionReplayGuardIT）。
	 */
	@Container
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@LocalServerPort
	private int port;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private com.grassland.identity.security.CookieSigner cookieSigner;
	@Autowired
	private ReactiveStringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
				+ "/" + postgres.getDatabaseName());
		r.add("spring.r2dbc.username", postgres::getUsername);
		r.add("spring.r2dbc.password", postgres::getPassword);
		r.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
		r.add("management.server.port", () -> "0");
		r.add("identity.session.secret", () -> "test-secret-32-chars-minimum!!!");
	}

	@BeforeAll
	static void schema() throws Exception {
		try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); var s = c.createStatement()) {
			s.execute(
					"CREATE TABLE app_users (id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL, display_name text, role text NOT NULL DEFAULT 'user', status text NOT NULL DEFAULT 'active', created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), last_login_at timestamptz)");
			s.execute(
					"CREATE TABLE session (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL)");
			s.execute(
					"CREATE TABLE IF NOT EXISTS account_flag (account_id uuid PRIMARY KEY, must_change_password boolean NOT NULL DEFAULT false, updated_at timestamptz NOT NULL DEFAULT now())");
			s.execute(
					"CREATE TABLE IF NOT EXISTS account_username (account_id uuid PRIMARY KEY, username text NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now())");
			// /api/auth/me 校验新会话时读 backend_role（V26 结构）
			s.execute(
					"CREATE TABLE IF NOT EXISTS backend_role (account_id uuid NOT NULL, role varchar(32) NOT NULL, granted_at timestamptz NOT NULL DEFAULT now(), granted_by uuid, PRIMARY KEY (account_id, role))");
			// 审计链在响应主链上（append 必须有表；V5 结构）
			s.execute(
					"CREATE TABLE identity_audit_log (id uuid PRIMARY KEY, account_id uuid NOT NULL, action varchar(32) NOT NULL, from_identity_type varchar(32), to_identity_type varchar(32), session_token text, device_id varchar(64), ip_address varchar(64), user_agent varchar(512), occurred_at timestamptz NOT NULL DEFAULT now(), detail json)");
		}
	}

	private WebTestClient client() {
		// responseTimeout 30s：与 LoginControllerIT/IdentityItSupport 同口径（CI 慢 runner 防护）
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30))
				.build();
	}

	@Test
	void issueRequiresLoginSession() {
		client().post().uri("/api/auth/cross-app-tokens").exchange().expectStatus().isUnauthorized().expectBody()
				.jsonPath("$.error").isEqualTo("请先登录");
	}

	@Test
	void exchangeWithoutTokenReturns401() {
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{}").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void exchangeWithMalformedTokenReturns401() {
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"../../etc/passwd\"}").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void exchangeSuspendedAccountReturns403() {
		// 停用账号无法通过签发端点拿 token（resolve 已 403）——场景是「签发后账号被停用」：
		// 直接写一条 nonce 绑定停用账号，核销必须 403 而不是建会话。
		String accountId = seedUser("suspended-xapp@example.com", "suspended");
		String token = seedNonce(accountId);
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"" + token + "\"}").exchange().expectStatus().isForbidden().expectBody()
				.jsonPath("$.error").isEqualTo("账号已停用，请联系商家管理员");
	}

	@Test
	void exchangeCreatesSessionCookieAndOriginalSessionSurvives() {
		String accountId = seedUser("xapp@example.com", "active");
		String originalCookie = signCookie(accountId);
		String token = issue(originalCookie);

		var result = client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"" + token + "\"}").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.success").isEqualTo(true).jsonPath("$.data.user.id").isEqualTo(accountId)
				.jsonPath("$.data.user.email").isEqualTo("xapp@example.com")
				.consumeWith(r -> assertThat(r.getResponseHeaders().getFirst("Set-Cookie")).contains("y1.sid=")
						.contains("HttpOnly").contains("SameSite=Lax"))
				.returnResult();
		String exchangedCookie = result.getResponseHeaders().getFirst("Set-Cookie").split(";", 2)[0];

		// 核销建出的新会话可访问 /api/auth/me
		client().get().uri("/api/auth/me").header("Cookie", exchangedCookie).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.user.id").isEqualTo(accountId);
		// 原会话不受影响（免登核销不吊销来源会话）
		client().get().uri("/api/auth/me").header("Cookie", "y1.sid=" + originalCookie).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.user.id").isEqualTo(accountId);
		// 审计两条（签发 + 核销），action 无 token 明文
		Long auditRows = db.sql(
				"SELECT count(*) AS n FROM identity_audit_log WHERE account_id = CAST(:id AS uuid) AND action LIKE 'cross_app_token%'")
				.bind("id", accountId).map((row) -> row.get("n", Long.class)).one().block();
		assertThat(auditRows).isEqualTo(2L);
	}

	@Test
	void tokenIsSingleUseReplayReturns401() {
		String accountId = seedUser("replay-xapp@example.com", "active");
		String token = issue(signCookie(accountId));
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"" + token + "\"}").exchange().expectStatus().isOk();
		// 同 token 二次核销 → 401（GETDEL 原子单次）
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"" + token + "\"}").exchange().expectStatus().isUnauthorized().expectBody()
				.jsonPath("$.error").isEqualTo("登录凭证无效或已过期，请重新从应用内跳转");
	}

	@Test
	void expiredTokenReturns401() {
		String accountId = seedUser("expired-xapp@example.com", "active");
		String token = issue(signCookie(accountId));
		// 确定性过期：直接把 nonce 的 TTL 压到 1ms 后等待（免依赖真实时钟 5 分钟）
		redisTemplate.expire("grassland:auth:cross-app-token:" + token, Duration.ofMillis(1)).block();
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		client().post().uri("/api/auth/cross-app-tokens/exchange").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"token\":\"" + token + "\"}").exchange().expectStatus().isUnauthorized();
	}

	private String issue(String cookie) {
		String body = client().post().uri("/api/auth/cross-app-tokens").header("Cookie", "y1.sid=" + cookie).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).contains("\"expiresInSeconds\":300");
		java.util.regex.Matcher matcher = TOKEN_JSON.matcher(body);
		assertThat(matcher.find()).as("issue response carries token: %s", body).isTrue();
		return matcher.group(1);
	}

	private static final java.util.regex.Pattern TOKEN_JSON = java.util.regex.Pattern
			.compile("\"token\":\"([A-Za-z0-9_-]{40,60})\"");

	/** 直写一条 nonce（模拟「签发后账号状态变化」与过期类场景，不经签发端点）。 */
	private String seedNonce(String accountId) {
		String token = CrossAppTokenStore.generate();
		redisTemplate.opsForValue().set("grassland:auth:cross-app-token:" + token, accountId, Duration.ofMinutes(5))
				.block();
		return token;
	}

	private String seedUser(String email, String status) {
		String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, "correct-pass".toCharArray());
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) VALUES (CAST(:id AS uuid), :email, :hash, 'Test User', 'user', :status)")
				.bind("id", id).bind("email", email).bind("hash", hash).bind("status", status).then().block();
		return id;
	}

	private String signCookie(String accountId) {
		String sid = UUID.randomUUID().toString();
		db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
				.bind("sid", sid).bind("sess", "{\"user\":{\"id\":\"" + accountId + "\"}}").then().block();
		return URLEncoder.encode("s:" + cookieSigner.sign(sid), StandardCharsets.UTF_8);
	}
}
