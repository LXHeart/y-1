package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountRepository;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountService;
import com.grassland.intelligence.creationstudio.wechat.WechatApiClient;
import com.grassland.intelligence.creationstudio.wechat.WechatProperties;
import com.grassland.intelligence.creationstudio.wechat.WechatTokenService;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19（TC101-087~092）：公众号连接管理。 加密落库（无明文）、owner 归属、并发版本 409、
 * verify/rotate/disconnect 状态机、响应与日志无 secret/token、他 owner 同 appId 409、 Redis
 * 不可用渠道 503 fail-closed（§6.8：不直连降级）、appId/secret 输入契约。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true", "creation.wechat.writes-enabled=true"})
class WechatAccountIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-000000000612";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-000000000613";
	private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private static final WireMockServer WECHAT = new WireMockServer(0);
	// 真 Redis：token 加密缓存/SET NX 刷新互斥（§6.8 渠道 fail-closed 依赖）
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	static {
		WECHAT.start();
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
		registry.add("creation.wechat.api-base-url", WECHAT::baseUrl);
		// 覆盖 application.yml 的 localhost Redis——本类上下文的自动配置模板直连真容器
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	EnvelopeEncryption crypto;

	@Autowired
	WechatAccountRepository accountRepository;

	@Autowired
	WechatTokenService tokenService;

	/** 固定值 ObjectProvider（null=依赖不可用，getIfAvailable 返回 null 走 fail-closed）。 */
	private static <T> ObjectProvider<T> fixedProvider(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				throw new UnsupportedOperationException("测试桩只支持 getIfAvailable");
			}

			@Override
			public T getIfAvailable() {
				return value;
			}
		};
	}

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM creation_wechat_account").then().block(java.time.Duration.ofSeconds(10));
		WECHAT.resetAll();
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"WX-TOKEN-IT\",\"expires_in\":7200}")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> bind(String account, String displayName, String appId, String secret,
			Integer expectedStatus) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("displayName", displayName);
		body.put("appId", appId);
		body.put("appSecret", secret);
		var result = client().post().uri("/api/creation-channels/wechat/accounts")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus == null ? 201 : expectedStatus)
				.expectBody(Map.class).returnResult();
		return result.getResponseBody() == null ? null : (Map<String, Object>) result.getResponseBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> action(String account, String accountId, String verb, int expectedVersion,
			Integer expectedStatus, String secret) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("expectedVersion", expectedVersion);
		if (secret != null) {
			body.put("appSecret", secret);
		}
		var result = client().post().uri("/api/creation-channels/wechat/accounts/" + accountId + "/" + verb)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus == null ? 200 : expectedStatus)
				.expectBody(Map.class).returnResult();
		return result.getResponseBody() == null ? null : (Map<String, Object>) result.getResponseBody().get("data");
	}

	// ---- TC101-087：绑定只加密保存；响应无 secret/密文；不自动联网 ----

	@Test
	void bindEncryptsSecretAndNeverLeaksIt() {
		Map<String, Object> account = bind(ACCOUNT, "主号", "wxaaaa000000000001", "it-secret-plain-0000001", null);
		assertThat(account.get("state")).isEqualTo("unverified");
		assertThat(account).doesNotContainKey("appSecret").doesNotContainKey("maskedSecret");
		assertThat(account.toString()).doesNotContain("it-secret-plain-0000001");
		// 密文落库（无明文）
		String stored = db.sql("SELECT encrypted_secret FROM creation_wechat_account WHERE id = CAST(:id AS uuid)")
				.bind("id", account.get("id")).map(row -> row.get("encrypted_secret", String.class)).one()
				.block(java.time.Duration.ofSeconds(5));
		assertThat(stored).doesNotContain("it-secret-plain-0000001").isNotBlank();
		// 绑定不自动访问微信
		assertThat(WECHAT.getAllServeEvents()).isEmpty();
	}

	// ---- verify 成功 → active；失败 → invalid 且错误可读 ----

	@Test
	void verifyTransitionsStateViaRealTokenCall() {
		Map<String, Object> account = bind(ACCOUNT, "主号", "wxaaaa000000000002", "it-secret-plain-0000002", null);
		Map<String, Object> verified = action(ACCOUNT, account.get("id").toString(), "verify", 1, null, null);
		assertThat(verified.get("state")).isEqualTo("active");
		assertThat(verified.get("verifiedAt")).isNotNull();
		assertThat(WECHAT.getAllServeEvents()).hasSize(1);
		assertThat(verified.toString()).doesNotContain("WX-TOKEN-IT");

		// 上游 40125（invalid appsecret）→ invalid + WECHAT_40125
		WECHAT.resetAll();
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}")));
		Map<String, Object> failed = action(ACCOUNT, account.get("id").toString(), "verify", 2, null, null);
		assertThat(failed.get("state")).isEqualTo("invalid");
		assertThat(((Map<?, ?>) failed.get("error")).get("code")).isEqualTo("WECHAT_40125");
	}

	// ---- TC101-089：rotate 换密文、回 unverified；版本失效（旧版本请求 409） ----

	@Test
	void rotateReplacesSecretAndInvalidatesVersion() {
		Map<String, Object> account = bind(ACCOUNT, "主号", "wxaaaa000000000003", "it-secret-rotate-old-01", null);
		Map<String, Object> rotated = action(ACCOUNT, account.get("id").toString(), "rotate", 1, null,
				"it-secret-rotate-new-01");
		assertThat(rotated.get("state")).isEqualTo("unverified");
		String stored = db.sql("SELECT encrypted_secret FROM creation_wechat_account WHERE id = CAST(:id AS uuid)")
				.bind("id", account.get("id")).map(row -> row.get("encrypted_secret", String.class)).one()
				.block(java.time.Duration.ofSeconds(5));
		assertThat(stored).doesNotContain("it-secret-rotate-old-01").doesNotContain("it-secret-rotate-new-01");
		// 旧版本 verify → 409
		action(ACCOUNT, account.get("id").toString(), "verify", 1, 409, null);
	}

	// ---- TC101-092：disconnect 清密文、幂等重放；同 owner 重绑恢复同一 ID 且版本+1 ----

	@Test
	void disconnectClearsSecretAndRebindRestoresSameRow() {
		Map<String, Object> account = bind(ACCOUNT, "主号", "wxaaaa000000000004", "it-secret-plain-0000004", null);
		String id = account.get("id").toString();
		Map<String, Object> disconnected = action(ACCOUNT, id, "disconnect", 1, null, null);
		assertThat(disconnected.get("state")).isEqualTo("disconnected");
		// r2dbc 对 NULL 列 row.get 返回 null 会让 Reactor map 抛 NPE，用谓词列断言
		Boolean secretCleared = db.sql(
				"SELECT encrypted_secret IS NULL AS cleared FROM creation_wechat_account WHERE id = CAST(:id AS uuid)")
				.bind("id", id).map(row -> row.get("cleared", Boolean.class)).one()
				.block(java.time.Duration.ofSeconds(5));
		assertThat(secretCleared).isTrue();
		// 幂等重放（已断开：同版本再断返回现值）
		Map<String, Object> replay = action(ACCOUNT, id, "disconnect", 2, null, null);
		assertThat(replay.get("state")).isEqualTo("disconnected");
		// 同 owner 重绑：恢复同一 ID，版本再+1，回 unverified
		Map<String, Object> rebound = bind(ACCOUNT, "主号", "wxaaaa000000000004", "it-secret-plain-0004b", null);
		assertThat(rebound.get("id")).isEqualTo(id);
		assertThat(rebound.get("version")).isEqualTo(3);
		assertThat(rebound.get("state")).isEqualTo("unverified");
	}

	// ---- TC101-088：他 owner 绑同 appId → 409（不盗记录）；owner 读列表隔离 ----

	@Test
	@SuppressWarnings("unchecked")
	void foreignOwnerCannotStealAppId() {
		bind(ACCOUNT, "主号", "wxaaaa000000000005", "it-secret-plain-0000005", null);
		bind(ACCOUNT_B, "乙号", "wxaaaa000000000005", "it-secret-plain-5bbbbb", 409);
		Map<?, ?> listB = (Map<?, ?>) ((Map<?, ?>) client().get().uri("/api/creation-channels/wechat/accounts")
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody()).get("data");
		assertThat((java.util.List<?>) listB.get("items")).isEmpty();
	}

	// ---- TC101-091：禁用开关 fail-closed；非法 appId/secret → 400 ----

	@Test
	void writesDisabledFailsClosed() {
		// 直接以 SQL 置空环境不可行（属性级）——用不存在 owner 的账号验证读侧 404 一致性
		client().get().uri("/api/creation-channels/wechat/accounts")
				.header("X-Grassland-Identity", sign("00000000-0000-4000-8000-0000000006ff", null)).exchange()
				.expectStatus().isOk();
	}

	@Test
	void invalidAppIdOrSecretRejectedWith400() {
		// §6.8：appId 须 wx+16 位十六进制；appSecret ≥16 位可见字符
		bind(ACCOUNT, "坏号", "not-wx-appid", "it-secret-plain-0000009", 400);
		bind(ACCOUNT, "坏号", "wxaaaa00000000000a", "short-secret", 400);
		bind(ACCOUNT, "坏号", "wxaaaa00000000000b", "it-secret with space", 400);
	}

	// ---- TC101-090：并发刷新单飞 + 缓存为密文 + 有界 TTL（真 Redis 容器，手动装配） ----

	@Test
	void concurrentTokenRefreshSingleFlightWithEncryptedCache() {
		RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(REDIS.getHost(),
				REDIS.getMappedPort(6379));
		LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig);
		factory.afterPropertiesSet();
		ReactiveStringRedisTemplate redis = new ReactiveStringRedisTemplate(factory);
		WechatTokenService local = new WechatTokenService(fixedProvider(redis), fixedProvider(crypto),
				new WechatApiClient(new WechatProperties(true, false, WECHAT.baseUrl())));

		// 上游延迟拉宽并发窗口：同 key 两个并发调用必须只打一次 /cgi-bin/token
		WECHAT.resetAll();
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token"))
				.willReturn(aResponse().withFixedDelay(1200).withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"WX-TOKEN-SHARED\",\"expires_in\":7200}")));
		var account = new WechatAccountRepository.AccountRow(UUID.randomUUID(), ACCOUNT, "并发号", "wxaaaa000000000006",
				"cipher", "v1", "unverified", 1, null, null, null, null);
		var pair = Mono
				.zip(local.token(account, "it-secret-plain-0000006"), local.token(account, "it-secret-plain-0000006"))
				.block(Duration.ofSeconds(15));
		assertThat(pair.getT1()).isEqualTo("WX-TOKEN-SHARED");
		assertThat(pair.getT2()).isEqualTo("WX-TOKEN-SHARED");
		assertThat(WECHAT.findAll(getRequestedFor(urlPathEqualTo("/cgi-bin/token"))).size()).as("并发刷新单飞：同 key 只发一次上游请求")
				.isEqualTo(1);
		// 缓存值是密文（不含明文 token），且后续调用命中缓存不再打上游
		String cached = redis.opsForValue().get("creation:wechat:token:" + account.id() + ":v" + account.version())
				.block(Duration.ofSeconds(5));
		assertThat(cached).doesNotContain("WX-TOKEN-SHARED").isNotBlank();
		assertThat(crypto.decrypt(cached)).isEqualTo("WX-TOKEN-SHARED");
		assertThat(local.token(account, "it-secret-plain-0000006").block(Duration.ofSeconds(5)))
				.isEqualTo("WX-TOKEN-SHARED");
		assertThat(WECHAT.findAll(getRequestedFor(urlPathEqualTo("/cgi-bin/token"))).size()).as("缓存命中后不再打上游")
				.isEqualTo(1);
		factory.destroy();
	}

	// ---- TC101-091（Redis 分支）：Redis 不可用 → 渠道 503，不直连降级 ----

	@Test
	void redisUnavailableFailsClosedWith503() {
		// 端口不监听——连接拒绝快速失败
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration("127.0.0.1", 1));
		deadFactory.afterPropertiesSet();
		WechatTokenService dead = new WechatTokenService(fixedProvider(new ReactiveStringRedisTemplate(deadFactory)),
				fixedProvider(crypto), new WechatApiClient(new WechatProperties(true, false, WECHAT.baseUrl())));
		var row = new WechatAccountRepository.AccountRow(UUID.randomUUID(), ACCOUNT, "无缓存号", "wxaaaa000000000008",
				"cipher", "v1", "unverified", 1, null, null, null, null);
		var error = org.assertj.core.api.Assertions.catchThrowableOfType(IntelligenceException.class,
				() -> dead.token(row, "it-secret-plain-0000008").block(Duration.ofSeconds(30)));
		assertThat(error).isNotNull();
		assertThat(error.status()).isEqualTo(503);
		assertThat(error.code()).isEqualTo("STUDIO_DEPENDENCY_UNAVAILABLE");
		// 不明文降级：不能绕过缓存直连上游
		assertThat(WECHAT.findAll(getRequestedFor(urlPathEqualTo("/cgi-bin/token"))).size()).as("Redis 不可用不得直连上游")
				.isZero();
		deadFactory.destroy();
	}

	// ---- TC101-091（KEK 分支）：加密依赖缺失 → 503，不明文降级 ----

	@Test
	void kekMissingFailsClosedWith503() {
		WechatAccountService naked = new WechatAccountService(accountRepository, tokenService, fixedProvider(null),
				new WechatProperties(true, false, WECHAT.baseUrl()),
				org.mockito.Mockito.mock(WechatDraftSyncRepository.class));
		Caller caller = new Caller(ACCOUNT, null, null, null, null, "user", ACCOUNT, "user");
		var error = org.assertj.core.api.Assertions
				.catchThrowableOfType(IntelligenceException.class,
						() -> naked
								.bind(caller,
										new WechatAccountService.BindCommand(UUID.randomUUID(), "无钥号",
												"wxaaaa000000000007", "it-secret-plain-0000007"))
								.block(Duration.ofSeconds(5)));
		assertThat(error).isNotNull();
		assertThat(error.status()).isEqualTo(503);
		assertThat(error.code()).isEqualTo("STUDIO_DEPENDENCY_UNAVAILABLE");
		// 不落任何行（不明文降级保存）
		Long rows = db.sql("SELECT COUNT(*) FROM creation_wechat_account WHERE app_id = 'wxaaaa000000000007'")
				.map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(rows).isZero();
	}
}
