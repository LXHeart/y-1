package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 操作幂等 IT（任务书 #105B C105B-03 / TC105B-03-03）。
 *
 * <p>
 * 并发用<b>惰性 WebClient</b>（WebTestClient.exchange 是急切执行，zip 无法并行）两条并行请求 = 两个独立服务端
 * 事务：同 request 同 body 并发 create → 一个资源 id；同 request 异 body → 409
 * dh_request_conflict、原行不被 覆盖；并发 expectedVersion 更新 → 仅一版生效。E03（重复提交）边界在本 TC
 * 参数化。
 */
class DigitalHumanOperationIT extends IntelligenceItSupport {

	private static final String AVATAR_ID = "11111111-1111-4111-8111-111111111111";
	private static final String VOICE_ID = "preset-zh-natural-01";
	private static final UUID BACKEND_ID = UUID.fromString("aaaaaaa1-0000-4000-8000-000000000002");

	private static final ParameterizedTypeReference<java.util.Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
	};

	@Autowired
	private DatabaseClient db;

	private final String account = "dh-op-a-" + UUID.randomUUID();

	private WebClient web() {
		return WebClient.builder().baseUrl("http://localhost:" + port).build();
	}

	@BeforeEach
	void seedCatalogAndRenderConfig() {
		db.sql("DELETE FROM dh_profile_revision").then().then(db.sql("DELETE FROM dh_profile").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE capability = 'digital_human_render')").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-%')"
						+ " OR capability IN ('digital_human_render','voice','video_tts')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-%'").then())
				.then(db.sql("DELETE FROM dh_catalog").then()).block(Duration.ofSeconds(10));
		String credentialId = db
				.sql("INSERT INTO platform_provider_credential(name, provider, base_url, enabled)"
						+ " VALUES ('it-dh-render-cred', 'openai-compatible', :baseUrl, true) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).map(row -> row.get("id", String.class)).one()
				.block(Duration.ofSeconds(10));
		db.sql("INSERT INTO platform_model_config(id, capability, model_role, provider, model, base_url,"
				+ " health_status, enabled, version, credential_id) VALUES (CAST(:id AS uuid),"
				+ " 'digital_human_render', 'primary', 'openai-compatible', 'dh-render-it', :baseUrl,"
				+ " 'healthy', true, 1, CAST(:cred AS uuid))").bind("id", BACKEND_ID.toString())
				.bind("baseUrl", QWEN.baseUrl()).bind("cred", credentialId).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')").bind("config", """
						{"enabled":true,"newSessionsAllowed":true,"recordingEnabled":false,"customAvatarEnabled":false,
						 "allowedBackendIds":["%s"],
						 "avatars":[{"id":"%s","revision":1,"name":"草原少女","previewMediaId":"preset-avatar-1",
						   "source":"preset","state":"ready","compatibleBackendIds":["%s"]}],
						 "voices":[{"id":"%s","name":"自然女声","providerModelRef":"tts-zh-natural-01",
						   "compatibleBackendIds":["%s"],"enabled":true}]}
						""".formatted(BACKEND_ID, AVATAR_ID, BACKEND_ID, VOICE_ID, BACKEND_ID).replace("\n", "")).then()
				.block(Duration.ofSeconds(10));
	}

	// ---------- TC105B-03-03：响应丢失/并发 ----------

	@Test
	void tc105b_03_03_sameKeySameBodyConcurrentCreateYieldsSingleResource() {
		String requestId = UUID.randomUUID().toString();
		String body = body("并发角色", "同 body 人设", requestId);
		var responses = Mono.zip(postProfile(body), postProfile(body)).block(Duration.ofSeconds(20));
		// 双 2xx、同一资源 id（201/200 或双双 200）——无双资源、无双 receipt。
		assertThat(responses.getT1().status()).isBetween(200, 299);
		assertThat(responses.getT2().status()).isBetween(200, 299);
		assertThat(responses.getT1().body().get("id")).isEqualTo(responses.getT2().body().get("id"));
		assertThat(count("SELECT count(*) AS n FROM dh_profile")).isEqualTo(1L);
		assertThat(count("SELECT count(*) AS n FROM dh_operation WHERE kind = 'profile_create'")).isEqualTo(1L);
	}

	@Test
	void tc105b_03_03_sameKeyDifferentBodyConflicts() {
		String requestId = UUID.randomUUID().toString();
		postProfile(body("角色A", "人设A", requestId)).map(CallResult::status)
				.flatMap(status -> status >= 200 && status < 300
						? Mono.just(status)
						: Mono.error(new IllegalStateException("首建失败：" + status)))
				.block(Duration.ofSeconds(10));
		var conflict = postProfile(body("角色B", "人设B", requestId)).block(Duration.ofSeconds(10));
		assertThat(conflict.status()).isEqualTo(409);
		// 错误信封回退为整包解析：code=dh_request_conflict。
		assertThat(String.valueOf(conflict.body().get("code"))).contains("dh_request_conflict");
		// 原 Profile 不被异 body 覆盖。
		String storedName = db.sql("SELECT name FROM dh_profile WHERE owner_account_id = :o").bind("o", account)
				.map(row -> row.get("name", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(storedName).isEqualTo("角色A");
		assertThat(count("SELECT count(*) AS n FROM dh_profile")).isEqualTo(1L);
	}

	@Test
	void tc105b_03_03_concurrentExpectedVersionOnlyOneWins() {
		var created = postProfile(body("并发更新角色", "人设", UUID.randomUUID().toString())).block(Duration.ofSeconds(10));
		String createId = String.valueOf(created.body().get("id"));
		// 两个并发 PATCH 同 expectedVersion=1：一胜一 409；version 只 +1、revision 只 +1、旧输入不覆盖。
		var first = patchProfile(createId, "更新一", 1, UUID.randomUUID().toString());
		var second = patchProfile(createId, "更新二", 1, UUID.randomUUID().toString());
		var statuses = Mono.zip(first, second).map(tuple -> List.of(tuple.getT1(), tuple.getT2()))
				.block(Duration.ofSeconds(20));
		assertThat(statuses.stream().filter(status -> status == 200).count()).as("并发同版本更新只允许一个成功：{}", statuses)
				.isEqualTo(1L);
		assertThat(statuses).contains(409);
		Integer version = db.sql("SELECT version FROM dh_profile WHERE id = CAST(:id AS uuid)").bind("id", createId)
				.map(row -> row.get("version", Integer.class)).one().block(Duration.ofSeconds(10));
		assertThat(version).isEqualTo(2);
		assertThat(count(
				"SELECT count(*) AS n FROM dh_profile_revision WHERE profile_id = CAST('" + createId + "' AS uuid)"))
				.isEqualTo(2L);

		// API39：读回 profile_create 的 operation receipt（归属校验只读）。
		String operationId = db
				.sql("SELECT id::text FROM dh_operation WHERE kind = 'profile_create'" + " AND owner_account_id = :o")
				.bind("o", account).map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(10));
		client().get().uri("/api/digital-human/operations/" + operationId)
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.kind").isEqualTo("profile_create").jsonPath("$.data.state").isEqualTo("succeeded");
		// 他人读 operation → 404，不泄露存在性。
		client().get().uri("/api/digital-human/operations/" + operationId)
				.header("X-Grassland-Identity", sign("dh-op-b-" + UUID.randomUUID(), null)).exchange().expectStatus()
				.isNotFound();
	}

	// ---------- 助手（惰性 WebClient，订阅才发请求；exchangeToMono 不对 4xx 抛异常） ----------

	private record CallResult(int status, java.util.Map<String, Object> body) {
	}

	private Mono<CallResult> postProfile(String body) {
		return Mono
				.fromSupplier(() -> web().post().uri("/api/digital-human/profiles")
						.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(body))
				.flatMap(request -> request.exchangeToMono(response -> response.toEntity(String.class))
						.map(entity -> parse(entity.getStatusCode().value(), entity.getBody())));
	}

	private Mono<Integer> patchProfile(String profileId, String name, int expectedVersion, String requestId) {
		return Mono
				.fromSupplier(() -> web().patch().uri("/api/digital-human/profiles/" + profileId)
						.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(updateBody(name, expectedVersion, requestId)))
				.flatMap(request -> request.exchangeToMono(response -> response.toEntity(String.class))
						.map(entity -> entity.getStatusCode().value()));
	}

	private static CallResult parse(int status, String body) {
		try {
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> parsed = body == null || body.isBlank()
					? new java.util.HashMap<>()
					: new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, java.util.Map.class);
			Object inner = parsed.get("data");
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> data = inner instanceof java.util.Map
					? (java.util.Map<String, Object>) inner
					: parsed;
			return new CallResult(status, data);
		} catch (Exception failure) {
			return new CallResult(status, new java.util.HashMap<>());
		}
	}

	private static String body(String name, String persona, String requestId) {
		return """
				{"name":"%s","persona":"%s","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":1,"requestId":"%s"}
				""".formatted(name, persona, AVATAR_ID, VOICE_ID, requestId).replace("\n", "");
	}

	private static String updateBody(String name, int expectedVersion, String requestId) {
		return """
				{"name":"%s","persona":"更新人设","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":1,"expectedVersion":%d,"requestId":"%s"}
				""".formatted(name, AVATAR_ID, VOICE_ID, expectedVersion, requestId).replace("\n", "");
	}

	private Long count(String sql) {
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}
}
