package com.grassland.intelligence.digitalhuman;

import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRepository.ProfileRevisionInput;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 个人鉴权/开关/域错误映射 IT（任务书 #105B C105B-02 / TC105B-02-01～04）。
 *
 * <p>
 * 覆盖：商家身份进 AI 仍个人归属（无 org 穿透）；跨账号/匿名统一 404/401 不泄露存在性； 关闭态拒绝新建但收尾 CAS
 * 不被全域开关误阻断；假 header/service principal/上游异常脱敏。
 */
class DigitalHumanAuthorizationIT extends IntelligenceItSupport {

	@Autowired
	private DigitalHumanAuthorization authorization;

	@Autowired
	private DigitalHumanPolicy policy;

	@Autowired
	private DigitalHumanRepository repository;

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanExceptionHandler exceptionHandler;

	// ---------- TC105B-02-01：商家身份个人归属 ----------

	@Test
	void tc105b_02_01_merchantIdentityStaysPersonalScope() {
		String account = "dh-auth-a-" + UUID.randomUUID();
		String organizationId = UUID.randomUUID().toString();
		// A 有 merchant org 且以商家活动身份进入 AI。
		PersonalActor actor = authorization.requirePersonal(requestWith(signWithOrg(account, organizationId)))
				.block(Duration.ofSeconds(10));
		assertThat(actor).isNotNull();
		assertThat(actor.accountId()).isEqualTo(account);
		// PersonalActor 只含 accountId：结构上不存在 organizationId 字段（无 org key/预算穿透通道）。
		assertThat(PersonalActor.class.getRecordComponents()).hasSize(1);
		assertThat(PersonalActor.class.getRecordComponents()[0].getName()).isEqualTo("accountId");

		// 新建个人对象：owner=A，行内无组织归属列值可提交。
		UUID profileId = UUID.randomUUID();
		var row = repository
				.insertProfileWithRevision(profileId, actor.accountId(), "创作助手", new ProfileRevisionInput("人设", "你好",
						DigitalHumanRecords.Tone.natural, UUID.randomUUID(), 1, "preset-zh-natural-01", 1))
				.block(Duration.ofSeconds(10));
		assertThat(row.ownerAccountId()).isEqualTo(account);
		assertThat(row.status()).isEqualTo(DigitalHumanRecords.ProfileStatus.active);
		cleanupProfile(account);
	}

	// ---------- TC105B-02-02：跨账号资源 ----------

	@Test
	void tc105b_02_02_crossAccountAndAnonymousAre404Or401() {
		String a = "dh-auth-a-" + UUID.randomUUID();
		String b = "dh-auth-b-" + UUID.randomUUID();
		PersonalActor actorA = authorization.requirePersonal(requestWith(sign(a, null))).block(Duration.ofSeconds(10));
		UUID profileId = UUID.randomUUID();
		var owned = repository
				.insertProfileWithRevision(profileId, a, "A 的角色", new ProfileRevisionInput("p", "g",
						DigitalHumanRecords.Tone.natural, UUID.randomUUID(), 1, "preset-zh-natural-01", 1))
				.block(Duration.ofSeconds(10));

		PersonalActor actorB = authorization.requirePersonal(requestWith(sign(b, null))).block(Duration.ofSeconds(10));
		// B 拿 A 的资源 id：GET/PATCH/DELETE 共用的 owner 复查 → 404 dh_not_found。
		IntelligenceException cross = assertIntelligenceError(
				() -> authorization.requireOwned(actorB, owned.ownerAccountId()).block(Duration.ofSeconds(10)));
		assertThat(cross.status()).isEqualTo(404);
		assertThat(cross.code()).isEqualTo("dh_not_found");
		// 不泄露存在性：A 访问不存在资源与 B 访问 A 资源同文案。
		IntelligenceException missing = assertIntelligenceError(
				() -> authorization.requireOwned(actorA, "someone-else").block(Duration.ofSeconds(10)));
		assertThat(missing.getMessage()).isEqualTo(cross.getMessage());

		// 匿名（无 header）：401 dh_auth_required，零业务写。
		IntelligenceException anonymous = assertIntelligenceError(
				() -> authorization.requirePersonal(MockServerHttpRequest.get("/api/digital-human/profiles").build())
						.block(Duration.ofSeconds(10)));
		assertThat(anonymous.status()).isEqualTo(401);
		assertThat(anonymous.code()).isEqualTo("dh_auth_required");
		cleanupProfile(a);
	}

	// ---------- TC105B-02-03：关闭与收尾 ----------

	@Test
	void tc105b_02_03_disabledBlocksNewSessionsButNotFinalization() {
		// 自清 singleton（共享容器：其它 DH 类可能留下 enabled 行；本用例 phase1 依赖空表）。
		db.sql("DELETE FROM dh_catalog").then().block(Duration.ofSeconds(5));
		// 无配置行：默认全关（重启/缺行不得默认开）。
		DigitalHumanPolicy.CatalogFlags deflated = policy.effectiveCatalog().block(Duration.ofSeconds(10));
		assertThat(deflated.enabled()).isFalse();
		assertThat(deflated.newSessionsAllowed()).isFalse();
		IntelligenceException disabled = assertIntelligenceError(
				() -> policy.requireNewSessionsAllowed().block(Duration.ofSeconds(10)));
		assertThat(disabled.status()).isEqualTo(404);
		assertThat(disabled.code()).isEqualTo("dh_feature_disabled");

		// 停止新建（enabled=true、newSessionsAllowed=false）同样拒绝新建。
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 3,"
				+ " '{\"enabled\":true,\"newSessionsAllowed\":false,\"recordingEnabled\":false,"
				+ "\"customAvatarEnabled\":false}', 'it')").then().block(Duration.ofSeconds(10));
		try {
			DigitalHumanPolicy.CatalogFlags flags = policy.effectiveCatalog().block(Duration.ofSeconds(10));
			assertThat(flags.enabled()).isTrue();
			assertThat(flags.newSessionsAllowed()).isFalse();
			IntelligenceException blocked = assertIntelligenceError(
					() -> policy.requireNewSessionsAllowed().block(Duration.ofSeconds(10)));
			assertThat(blocked.status()).isEqualTo(404);

			// 收尾不被全域开关误阻断：直插会话后 end CAS 链路（preparing→ending→ended）照常推进。
			String owner = "dh-auth-end-" + UUID.randomUUID();
			var session = repository
					.insertSession(new DigitalHumanRepository.SessionInsert(UUID.randomUUID(), owner, UUID.randomUUID(),
							1, "角色", "mock", UUID.randomUUID(), UUID.randomUUID(), "{\"v\":1}"))
					.block(Duration.ofSeconds(10));
			assertThat(session.state()).isEqualTo(DigitalHumanRecords.SessionState.preparing);
			assertThat(repository
					.compareAndSetSessionState(UUID.fromString(session.id()), owner,
							DigitalHumanRecords.SessionState.preparing, DigitalHumanRecords.SessionState.ending)
					.block(Duration.ofSeconds(10)).state()).isEqualTo(DigitalHumanRecords.SessionState.ending);
			assertThat(repository
					.compareAndSetSessionState(UUID.fromString(session.id()), owner,
							DigitalHumanRecords.SessionState.ending, DigitalHumanRecords.SessionState.ended)
					.block(Duration.ofSeconds(10)).state()).isEqualTo(DigitalHumanRecords.SessionState.ended);
			db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", owner).then()
					.block(Duration.ofSeconds(10));
		} finally {
			db.sql("DELETE FROM dh_catalog WHERE singleton_id = 1").then().block(Duration.ofSeconds(10));
		}
	}

	// ---------- TC105B-02-04：内部断言与错误 ----------

	@Test
	void tc105b_02_04_fakeHeadersAndSecretRedaction() {
		// 假 header（乱串）→ 401。
		IntelligenceException fake = assertIntelligenceError(
				() -> authorization.requirePersonal(requestWith("not-an-assertion")).block(Duration.ofSeconds(10)));
		assertThat(fake.status()).isEqualTo(401);
		assertThat(fake.code()).isEqualTo("dh_auth_required");

		// service principal 断言有效但不能冒充个人 → 403 dh_account_unavailable。
		IntelligenceException service = assertIntelligenceError(
				() -> authorization.requirePersonal(requestWith(serviceAssertion())).block(Duration.ofSeconds(10)));
		assertThat(service.status()).isEqualTo(403);
		assertThat(service.code()).isEqualTo("dh_account_unavailable");

		// 域错误信封：IntelligenceException → {success,error,code} + no-store。
		var mapped = exceptionHandler.handle(new IntelligenceException(409, "dh_lease_stale", "此会话已由另一页面接管。"));
		assertThat(mapped.getStatusCode().value()).isEqualTo(409);
		assertThat(mapped.getHeaders().getCacheControl()).isEqualTo("no-store");
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) mapped.getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(body.get("code")).isEqualTo("dh_lease_stale");

		// 上游异常带 key：兜底脱敏，错误体不含 secret/URL/路径。
		var redacted = exceptionHandler.handleUnexpected(new RuntimeException("provider 500 body=sk-live-abcdef123456 "
				+ "base_url=https://secret.example/v1 path=/var/run/dh/key.pem"));
		assertThat(redacted.getStatusCode().value()).isEqualTo(500);
		@SuppressWarnings("unchecked")
		Map<String, Object> leaked = (Map<String, Object>) redacted.getBody();
		assertThat(String.valueOf(leaked.get("error"))).doesNotContain("sk-live", "secret.example", "/var/run");
		assertThat(leaked.get("code")).isEqualTo("dh_runtime_unavailable");
	}

	// ---------- 工具 ----------

	private static org.springframework.http.server.reactive.ServerHttpRequest requestWith(String assertion) {
		return MockServerHttpRequest.get("/api/digital-human/profiles").header("X-Grassland-Identity", assertion)
				.build();
	}

	private String serviceAssertion() {
		Instant now = Instant.now();
		return serviceSigner("identity", "grassland-intelligence")
				.sign(new IdentityAssertion("service:identity", null, null, null, null, "service", "internal", null,
						"r", "t", "grassland-intelligence", now, now.plusSeconds(30), "service", "identity"));
	}

	private static IntelligenceException assertIntelligenceError(Runnable call) {
		try {
			call.run();
		} catch (IntelligenceException expected) {
			return expected;
		}
		throw new AssertionError("期望 IntelligenceException，实际正常返回");
	}

	private void cleanupProfile(String owner) {
		db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id = :o").bind("o", owner).then()
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id = :o").bind("o", owner).then())
				.block(Duration.ofSeconds(10));
	}
}
