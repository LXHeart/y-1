package com.grassland.intelligence.ai.byok;

import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 组织级 BYOK 密钥管理（ADR-D17）集成测试：admin/owner 全 CRUD、member 与跨组织 404 隐藏、 组织维唯一
 * 409、密钥永不回显（只回掩码）。KEK fail-closed 条件与个人版同款。
 *
 * <p>
 * 任务书 #104 C104-03（R02 / §3 D02）：V87 窄例外——创建者进入注销任一阶段后，现任合法组织管理员
 * 仍可维护原组织密钥；个人屏障、INSERT、改归属与非白名单列修改全部维持拒绝。
 */
@DisplayName("AiOrgProviderKeyController (组织级 BYOK)")
@Import(AiOrgProviderKeyControllerIT.DnsTestConfiguration.class)
class AiOrgProviderKeyControllerIT extends IntelligenceItSupport {

	/** 32 字节 KEK（0x00..0x1F）的 Base64。 */
	private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private static final String ORG = "org-byok-" + UUID.randomUUID();
	private static final String OTHER_ORG = "org-byok-other-" + UUID.randomUUID();
	private static final String ADMIN = "admin-byok-" + UUID.randomUUID();
	private static final String MAINTAINER = "maintainer-byok-" + UUID.randomUUID();
	private static final String MEMBER = "member-byok-" + UUID.randomUUID();
	private static final String API_KEY = "sk-org-test-real-key-1234567890abcdef";

	@MockitoBean
	IdentityOrgAuthorizationClient orgAuthorization;

	@DynamicPropertySource
	static void cryptoProps(DynamicPropertyRegistry registry) {
		registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
	}

	@BeforeEach
	void clean() {
		// 按 org 与 owner 双口径清：C104-03 用例还会给 ADMIN 建个人密钥（组织键对个人
		// 列表不可见/个人维唯一索引都要求逐测归零，不能只按 org 清）。
		db.sql("DELETE FROM ai_provider_key WHERE organization_id IN (:org, :otherOrg)"
				+ " OR owner_account_id IN (:a, :m)").bind("org", ORG).bind("otherOrg", OTHER_ORG).bind("a", ADMIN)
				.bind("m", MAINTAINER).then().block();
		// C104-03 用例会把 ADMIN 推进到 frozen/erasing/erased；缺省 gate 行=active（V86 语义），
		// 逐测复位避免状态泄漏到后续用例的组织密钥创建。
		db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id IN (:a, :m)").bind("a", ADMIN)
				.bind("m", MAINTAINER).then().block();
		allowAdmin();
	}

	private void allowAdmin() {
		when(orgAuthorization.require(ADMIN, ORG, "admin")).thenReturn(Mono.empty());
	}

	private String createOrgKey(String capability) {
		return createOrgKeyAs(ADMIN, capability);
	}

	/** 以指定账号创建组织密钥（C104-03：创建者与维护者分离）。 */
	private String createOrgKeyAs(String account, String capability) {
		byte[] body = client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(account, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", capability, "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "model", "gpt-4", "apiKey", API_KEY))
				.exchange().expectStatus().isCreated().expectBody().jsonPath("$.maskedHint")
				.value(hint -> assertThat(String.valueOf(hint)).startsWith("sk-")).jsonPath("$.encryptedKey")
				.doesNotExist().returnResult().getResponseBody();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 以指定账号创建个人密钥并返回行 ID（C104-03 fixture）。 */
	private String createPersonalKeyAs(String account, String capability) {
		client().post().uri("/api/ai/keys").header("X-Grassland-Identity", sign(account, "merchant"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("capability", capability, "provider",
						"openai-compatible", "baseUrl", "https://api.openai.com", "apiKey", API_KEY))
				.exchange().expectStatus().isCreated();
		return db
				.sql("SELECT id::text AS id FROM ai_provider_key"
						+ " WHERE owner_account_id = :a AND organization_id IS NULL AND capability = :c")
				.bind("a", account).bind("c", capability).map((r, m) -> r.get("id", String.class)).one().block();
	}

	/** 直接写 lifecycle gate 行（fixture：账号已推进到某注销阶段）。 */
	private void setLifecycleState(String account, String state) {
		db.sql("INSERT INTO intelligence_account_lifecycle(account_id, state) VALUES (:a, :s)"
				+ " ON CONFLICT (account_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()")
				.bind("a", account).bind("s", state).then().block();
	}

	private Map<String, Object> keyRow(String id) {
		return db.sql("SELECT id::text AS id, organization_id, owner_account_id, capability, provider, enabled"
				+ " FROM ai_provider_key WHERE id = CAST(:id AS uuid)").bind("id", id).map((r, m) -> {
					Map<String, Object> row = new HashMap<>();
					row.put("id", r.get("id", String.class));
					row.put("organization_id", r.get("organization_id", String.class));
					row.put("owner_account_id", r.get("owner_account_id", String.class));
					row.put("capability", r.get("capability", String.class));
					row.put("provider", r.get("provider", String.class));
					row.put("enabled", r.get("enabled", Boolean.class));
					return row;
				}).one().block();
	}

	private String columnOf(String id, String column) {
		// R2DBC 的 map 不允许返回 null；可空列（如 model）的 NULL 归一为空串，断言侧按空串解释。
		return db.sql("SELECT " + column + " AS v FROM ai_provider_key WHERE id = CAST(:id AS uuid)").bind("id", id)
				.map((r, m) -> {
					String value = r.get("v", String.class);
					return value == null ? "" : value;
				}).one().block();
	}

	/** 固定白名单子句直更新（clause 为测试内字面量，不接外部输入）。 */
	private void sqlUpdate(String id, String setClause) {
		db.sql("UPDATE ai_provider_key " + setClause + " WHERE id = CAST(:id AS uuid)").bind("id", id).then().block();
	}

	/** 过期断言（E09：会话过期仍按 401 拒绝）。 */
	private String expiredSign(String accountId) {
		Instant now = Instant.now();
		return userSigner("edge-bff", "grassland-intelligence").sign(new IdentityAssertion(accountId, "merchant",
				"sid-" + accountId, null, null, "cookie-session", "level1", null, "r", "t", "grassland-intelligence",
				now.minusSeconds(120), now.minusSeconds(60), null, null, null));
	}

	@Test
	@DisplayName("admin 全生命周期：创建→列表→更新配置→轮换→停用；组织密文与掩码永不回显")
	void adminFullLifecycle() {
		String id = createOrgKey("text");

		client().get().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(id).jsonPath("$[0].organizationId")
				.isEqualTo(ORG).jsonPath("$[0].encryptedKey").doesNotExist();

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.model").isEqualTo("gpt-4o");

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id + "/key")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("apiKey", "sk-org-test-rotated-9876543210fedcba")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.maskedHint")
				.value(hint -> assertThat(String.valueOf(hint)).startsWith("sk-"));

		client().delete().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isNoContent();

		// 软删：管理台仍可见该行（enabled=false），但密文与掩码规则不变
		client().get().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.enabled").isEqualTo(false).jsonPath("$.encryptedKey").doesNotExist();
	}

	@Test
	@DisplayName("member 与跨组织访问统一 404「组织不存在」；组织不存在亦 404")
	void nonAdminAndForeignOrgHidden() {
		when(orgAuthorization.require(MEMBER, ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
		client().get().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(MEMBER, "merchant")).exchange().expectStatus().isNotFound();

		client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(MEMBER, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", "text", "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "apiKey", API_KEY))
				.exchange().expectStatus().isNotFound();

		when(orgAuthorization.require(ADMIN, OTHER_ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(404, "组织不存在")));
		client().get().uri("/api/ai/organizations/" + OTHER_ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange().expectStatus().isNotFound();
	}

	@Test
	@DisplayName("同组织同能力第二把有效密钥 → 409（V41 组织维唯一索引）")
	void duplicateActiveOrgKeyRejected() {
		createOrgKey("text");
		client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", "text", "provider", "openai-compatible", "baseUrl",
						"https://api.openai.com", "apiKey", API_KEY))
				.exchange().expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("组织密钥不落入个人作用域：个人列表/详情均不可见")
	void orgKeysInvisibleToPersonalEndpoints() {
		String id = createOrgKey("text");
		when(orgAuthorization.require(ADMIN, ORG, "admin")).thenReturn(Mono.empty());

		client().get().uri("/api/ai/keys").header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);

		client().get().uri("/api/ai/keys/" + id).header("X-Grassland-Identity", sign(ADMIN, "merchant")).exchange()
				.expectStatus().isNotFound();
	}

	// ---------- 任务书 #104 C104-03（R02 / §3 D02 / §7.3）：组织 BYOK 生命周期屏障纠正 ----------

	@ParameterizedTest
	@ValueSource(strings = {"frozen", "erasing", "erased"})
	@DisplayName("TC104-03-01：创建者注销任一阶段，现任管理员配置/轮换/停用全部成功且归属不变")
	void currentAdminMaintainsOrgKeyAfterCreatorClosure(String state) {
		String id = createOrgKeyAs(ADMIN, "text");
		String hintBefore = columnOf(id, "masked_hint");
		setLifecycleState(ADMIN, state);
		when(orgAuthorization.require(MAINTAINER, ORG, "admin")).thenReturn(Mono.empty());

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(MAINTAINER, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o-mini")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.model").isEqualTo("gpt-4o-mini");

		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id + "/key")
				.header("X-Grassland-Identity", sign(MAINTAINER, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("apiKey", "sk-org-test-rotated-abcdef0123456789")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.maskedHint")
				.value(hint -> assertThat(String.valueOf(hint)).startsWith("sk-"));
		assertThat(columnOf(id, "masked_hint")).isNotEqualTo(hintBefore);

		// D02：creator/id/org 不变（不把 owner 改成 B，审计保留）；轮换后密钥仍可用（enabled）。
		Map<String, Object> row = keyRow(id);
		assertThat(row.get("owner_account_id")).isEqualTo(ADMIN);
		assertThat(row.get("organization_id")).isEqualTo(ORG);
		assertThat(row.get("id")).isEqualTo(id);
		assertThat((Boolean) row.get("enabled")).isTrue();

		client().delete().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(MAINTAINER, "merchant")).exchange().expectStatus().isNoContent();
		assertThat((Boolean) keyRow(id).get("enabled")).isFalse();
	}

	@Test
	@DisplayName("TC104-03-02：A 非活动后个人维护/新增密钥/新任务/个人转org/改creator 均拒绝且数据不变")
	void closedCreatorCannotWritePersonalScopeOrInsertAnything() {
		String orgKeyId = createOrgKeyAs(ADMIN, "text");
		String personalId = createPersonalKeyAs(ADMIN, "text");
		setLifecycleState(ADMIN, "frozen");

		// 个人维护与新密钥（HTTP 入口被屏障拒绝；image 能力无唯一索引冲突，排除 409 歧义）
		client().put().uri("/api/ai/keys/" + personalId).header("X-Grassland-Identity", sign(ADMIN, "merchant"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-5")).exchange().expectStatus()
				.isEqualTo(409);
		client().post().uri("/api/ai/keys").header("X-Grassland-Identity", sign(ADMIN, "merchant"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("capability", "image", "provider", "qwen",
						"baseUrl", "https://dashscope.aliyuncs.com", "apiKey", API_KEY))
				.exchange().expectStatus().isEqualTo(409);
		client().post().uri("/api/ai/organizations/" + ORG + "/keys")
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("capability", "image", "provider", "qwen", "baseUrl",
						"https://dashscope.aliyuncs.com", "apiKey", API_KEY))
				.exchange().expectStatus().isEqualTo(409);

		// 新任务与越权 UPDATE（SQL 层断言屏障异常本身）
		assertThatThrownBy(() -> db.sql(
				"INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status, started_at)"
						+ " VALUES (gen_random_uuid(), :a, 'text', 'sandbox', 0, 'running', now())")
				.bind("a", ADMIN).then().block()).hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(personalId, "SET organization_id = '" + ORG + "'"))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(orgKeyId, "SET owner_account_id = '" + MAINTAINER + "'"))
				.hasMessageContaining("account_closure_barrier");

		// 数据不变：个人键仍个人且未改配置；组织键 creator 仍是 A；未新增任何行。
		Map<String, Object> personal = keyRow(personalId);
		assertThat(personal.get("organization_id")).isNull();
		assertThat(personal.get("owner_account_id")).isEqualTo(ADMIN);
		assertThat(keyRow(orgKeyId).get("owner_account_id")).isEqualTo(ADMIN);
		Long total = db.sql("SELECT count(*) AS n FROM ai_provider_key WHERE owner_account_id = :a").bind("a", ADMIN)
				.map((r, m) -> r.get("n", Long.class)).one().block();
		assertThat(total).isEqualTo(2L);
	}

	@Test
	@DisplayName("TC104-03-03：匿名/过期/C 跨组织/普通成员/被撤权 B 全拒绝，密文与配置不变")
	void maintenanceAuthMatrixStillEnforced() {
		String id = createOrgKeyAs(ADMIN, "text");
		String cipher = columnOf(id, "encrypted_key");

		// 匿名（E06）与过期会话（E09）→ 401
		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isUnauthorized();
		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
				.header("X-Grassland-Identity", expiredSign(MAINTAINER)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isUnauthorized();

		// 普通成员（403→404 隐藏）、跨组织（404）、被撤权的 B（403→404）
		when(orgAuthorization.require(MEMBER, ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
		when(orgAuthorization.require(MAINTAINER, ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
		when(orgAuthorization.require(ADMIN, OTHER_ORG, "admin"))
				.thenReturn(Mono.error(new IntelligenceException(404, "组织不存在")));
		for (String caller : new String[]{MEMBER, MAINTAINER}) {
			client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + id)
					.header("X-Grassland-Identity", sign(caller, "merchant")).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
					.isNotFound();
		}
		client().put().uri("/api/ai/organizations/" + OTHER_ORG + "/keys/" + id)
				.header("X-Grassland-Identity", sign(ADMIN, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isNotFound();

		assertThat(columnOf(id, "encrypted_key")).isEqualTo(cipher);
		assertThat(columnOf(id, "model")).isEqualTo("gpt-4");
	}

	@Test
	@DisplayName("TC104-03-04：七列白名单逐一放行；改归属/身份/其他列/重新启用一律拒绝")
	void updateWhitelistEnforcedAtColumnLevel() {
		String id = createOrgKeyAs(ADMIN, "text");
		setLifecycleState(ADMIN, "erased");

		sqlUpdate(id, "SET base_url = 'https://api.openai.com/v2'");
		sqlUpdate(id, "SET model = 'gpt-4o'");
		sqlUpdate(id, "SET encrypted_key = 'enc-fixture-v2', key_version = 'v2', masked_hint = 'sk-***v2'");
		sqlUpdate(id, "SET updated_at = now()");
		sqlUpdate(id, "SET enabled = false");

		assertThatThrownBy(() -> sqlUpdate(id, "SET enabled = true")).hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET capability = 'image_generation'"))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET provider = 'xai'")).hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET created_at = now()"))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET organization_id = '" + OTHER_ORG + "'"))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET owner_account_id = '" + MAINTAINER + "'"))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> sqlUpdate(id, "SET id = gen_random_uuid()"))
				.hasMessageContaining("account_closure_barrier");
		// 白名单列 + 非白名单列组合同样拒绝（不能借合法列夹带）
		assertThatThrownBy(() -> sqlUpdate(id, "SET base_url = 'https://api2.example', provider = 'xai'"))
				.hasMessageContaining("account_closure_barrier");

		Map<String, Object> row = keyRow(id);
		assertThat(row.get("owner_account_id")).isEqualTo(ADMIN);
		assertThat(row.get("organization_id")).isEqualTo(ORG);
		assertThat(row.get("capability")).isEqualTo("text");
		assertThat(row.get("provider")).isEqualTo("openai-compatible");
		assertThat((Boolean) row.get("enabled")).isFalse();
	}

	@Test
	@DisplayName("TC104-03-05：组织维护不依赖 creator gate 行；与个人屏障写并发无死锁")
	void orgMaintenanceIndependentOfOwnerGateRowAndConcurrentPersonalWrites() {
		String orgKeyId = createOrgKeyAs(ADMIN, "text");
		String personalId = createPersonalKeyAs(ADMIN, "text");
		when(orgAuthorization.require(MAINTAINER, ORG, "admin")).thenReturn(Mono.empty());

		// 「新 life 行尚不存在」：个人写会自动注册 gate 行（V86 语义），先清掉再验证
		// 组织维护路径——它不得依赖该行存在，也不得触碰/重建它。
		db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id = :a").bind("a", ADMIN).then().block();
		client().put().uri("/api/ai/organizations/" + ORG + "/keys/" + orgKeyId)
				.header("X-Grassland-Identity", sign(MAINTAINER, "merchant")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("baseUrl", "https://api.openai.com", "model", "gpt-4o")).exchange().expectStatus()
				.isOk();
		Long gateRows = db.sql("SELECT count(*) AS n FROM intelligence_account_lifecycle WHERE account_id = :a")
				.bind("a", ADMIN).map((r, m) -> r.get("n", Long.class)).one().block();
		assertThat(gateRows).as("组织维护不得触碰/创建 creator gate 行").isZero();

		// 冻结后并发：组织维护与注定失败的个人写交替竞争——全部按时完成（无锁序倒置死锁）。
		setLifecycleState(ADMIN, "erased");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> orgLoop = pool.submit(() -> {
				for (int i = 0; i < 6; i++) {
					sqlUpdate(orgKeyId, "SET model = 'concurrent-" + i + "'");
				}
			});
			Future<Integer> personalLoop = pool.submit(() -> {
				int rejections = 0;
				for (int i = 0; i < 6; i++) {
					try {
						sqlUpdate(personalId, "SET model = 'stale-" + i + "'");
					} catch (Exception expected) {
						assertThat(expected).hasMessageContaining("account_closure_barrier");
						rejections++;
					}
				}
				return rejections;
			});
			orgLoop.get(30, TimeUnit.SECONDS);
			assertThat(personalLoop.get(30, TimeUnit.SECONDS)).as("个人写 6 次全部被屏障拒绝").isEqualTo(6);
		} catch (Exception e) {
			throw new IllegalStateException("并发组织维护与个人屏障写出现死锁/超时", e);
		} finally {
			pool.shutdownNow();
		}
		assertThat(columnOf(orgKeyId, "model")).isEqualTo("concurrent-5");
		assertThat(columnOf(personalId, "model")).as("个人键的迟到写全部被拒（model 保持创建时的 NULL→空串归一）").isEmpty();
	}

	@TestConfiguration
	static class DnsTestConfiguration {
		@Bean
		@Primary
		DnsPinningResolver deterministicDnsPinningResolver() {
			return DnsPinningResolver.create(host -> {
				try {
					return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
				} catch (java.net.UnknownHostException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}
}
