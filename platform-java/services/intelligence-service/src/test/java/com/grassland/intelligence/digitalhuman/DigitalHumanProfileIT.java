package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 角色档案 IT（任务书 #105B C105B-03 / TC105B-03-01、02、04）。
 *
 * <p>
 * 覆盖：正常版本流（revision1/2 不可变、active=2、响应完整）；字段极限参数化（1/40/41 name、 4000/4001
 * persona 含 emoji，边界过、超界 422 不静默截断）；目录撤销（预设下架 422、旧 catalogVersion 409）与跨 owner
 * 游标 422、无信息泄漏。
 */
class DigitalHumanProfileIT extends IntelligenceItSupport {

	private static final String AVATAR_ID = "11111111-1111-4111-8111-111111111111";
	private static final String VOICE_ID = "preset-zh-natural-01";
	private static final UUID BACKEND_ID = UUID.fromString("aaaaaaa1-0000-4000-8000-000000000001");

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	@Autowired
	private DatabaseClient db;

	private final String account = "dh-profile-a-" + UUID.randomUUID();

	@BeforeEach
	void seedCatalogAndRenderConfig() {
		// 清本 IT 域：dh 档案/操作 + render 控制面行（共享容器自清理）。
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

		// render 控制面行 + 凭据（QWEN origin 由基类 @BeforeEach 自动受信并刷新）。
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

		// dh_catalog v1：预设形象/音色都兼容该 backend；allowedBackendIds 批准它。
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

	// ---------- TC105B-03-01：正常版本流 ----------

	@Test
	void tc105b_03_01_createUpdateGetFreezesRevisions() {
		String requestId = UUID.randomUUID().toString();
		byte[] raw = createBytes(requestId, "初版角色", "第一版人设", 201);
		String id = json(raw).path("data").path("id").asText();
		assertThat(json(raw).path("data").path("version").asInt()).isEqualTo(1);
		assertThat(json(raw).path("data").path("status").asText()).isEqualTo("active");
		assertThat(json(raw).path("data").path("catalogVersion").asInt()).isEqualTo(1);
		assertThat(json(raw).path("data").path("voiceId").asText()).isEqualTo(VOICE_ID);

		// 同键同体重放 → 200 原 Profile（响应丢失重试安全）。
		postProfile(requestId, "初版角色", "第一版人设", 200).jsonPath("$.data.id").isEqualTo(id);

		// update：全表单 + expectedVersion=1 → revision2；revision1 不可变。
		patchProfile(id, UUID.randomUUID().toString(), "第二版角色", "第二版人设🏁", 1, 200).jsonPath("$.data.version")
				.isEqualTo(2);
		Long revisions = revisionCount(id);
		assertThat(revisions).isEqualTo(2L);
		String rev1Persona = db
				.sql("SELECT persona FROM dh_profile_revision WHERE profile_id = CAST(:id AS uuid) AND revision = 1")
				.bind("id", id).map(row -> row.get("persona", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(rev1Persona).isEqualTo("第一版人设");

		// get 返回 active=2 且响应字段完整。
		client().get().uri("/api/digital-human/profiles/" + id).header("X-Grassland-Identity", sign(account, null))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.activeRevision").doesNotExist()
				.jsonPath("$.data.name").isEqualTo("第二版角色").jsonPath("$.data.persona").isEqualTo("第二版人设🏁")
				.jsonPath("$.data.version").isEqualTo(2);

		// 旧 expectedVersion 再更新 → 409，version 不再递增、无孤儿 revision。
		patchProfile(id, UUID.randomUUID().toString(), "第三版", "第三版人设", 1, 409);
		assertThat(revisionCount(id)).isEqualTo(2L);
	}

	// ---------- TC105B-03-02：字段极限（参数化：边界过/超界 422） ----------

	@ParameterizedTest(name = "[{index}] {0} len={1} → {2}")
	@CsvSource({"name, 1, 201", "name, 40, 201", "name, 41, 422", "persona, 4000, 201", "persona, 4001, 422"})
	void tc105b_03_02_fieldCodePointBoundaries(String field, int length, int expectedStatus) {
		String filler = "🎉汉"; // 含 emoji（代理对）：码点计数而非 UTF-16 长度
		StringBuilder value = new StringBuilder();
		while (value.codePointCount(0, value.length()) < length) {
			value.append(filler);
		}
		// 精确裁到目标码点数（不截断 UTF-16 半个代理对）。
		StringBuilder exact = new StringBuilder();
		int[] codePoints = value.codePoints().toArray();
		for (int i = 0; i < length; i++) {
			exact.appendCodePoint(codePoints[i]);
		}
		String name = field.equals("name") ? exact.toString() : "边界角色";
		String persona = field.equals("persona") ? exact.toString() : "人设";
		byte[] response = createBytes(UUID.randomUUID().toString(), name, persona, expectedStatus);
		if (expectedStatus == 201) {
			String id = response == null || response.length == 0
					? null
					: json(response).path("data").path("id").asText();
			// 不静默截断：落库 persona 与提交码点数一致。
			Integer stored = db
					.sql("SELECT length(persona) AS n FROM dh_profile_revision"
							+ " WHERE profile_id = CAST(:id AS uuid) AND revision = 1")
					.bind("id", id).map(row -> row.get("n", Integer.class)).one().block(Duration.ofSeconds(10));
			assertThat(stored).isEqualTo(field.equals("persona") ? length : 2);
		}
	}

	// ---------- TC105B-03-04：目录撤销/游标 ----------

	@Test
	void tc105b_03_04_catalogRevocationAndCursorIsolation() {
		// 旧 catalogVersion → 409 dh_configuration_changed。
		postProfileWithCatalogVersion(UUID.randomUUID().toString(), "角色", "人设", 0, 409);

		// 预设下架：presetAvatarStates 关闭 → create 422 dh_combination_unsupported。
		db.sql("UPDATE dh_catalog SET version = 2, config_json = jsonb_set(config_json,"
				+ " '{presetAvatarStates}', '[{\"id\":\"" + AVATAR_ID + "\",\"enabled\":false}]')").then()
				.block(Duration.ofSeconds(10));
		postProfileWithCatalogVersion(UUID.randomUUID().toString(), "角色", "人设", 1, 409); // 版本已到 2
		byte[] revoked = client().post().uri("/api/digital-human/profiles")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(profileBody("角色", "人设", 2, UUID.randomUUID().toString())).exchange().expectStatus()
				.isEqualTo(422).expectBody(byte[].class).returnResult().getResponseBody();
		// 错误信封 code 与 error 平级（K01：{success,error,code}）。
		assertThat(json(revoked).path("code").asText()).isEqualTo("dh_combination_unsupported");
		// 回滚目录到可用态再继续分页部分。
		db.sql("UPDATE dh_catalog SET version = 1,"
				+ " config_json = jsonb_set(config_json, '{presetAvatarStates}', '[]')").then()
				.block(Duration.ofSeconds(10));

		// 分页与跨 owner 游标：A 建满 3 个角色，取第一页 cursor；B 使用 A 的 cursor → 422。
		for (int i = 0; i < 3; i++) {
			postProfile(UUID.randomUUID().toString(), "角色" + i, "人设" + i, 201);
		}
		byte[] pageBody = client().get().uri("/api/digital-human/profiles?limit=2")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(json(pageBody).path("data").path("items").size()).isEqualTo(2);
		String nextCursor = json(pageBody).path("data").path("nextCursor").asText(null);
		assertThat(nextCursor).as("第一页应带 nextCursor").isNotNull();

		String other = "dh-profile-b-" + UUID.randomUUID();
		client().get().uri("/api/digital-human/profiles?limit=2&cursor=" + nextCursor)
				.header("X-Grassland-Identity", sign(other, null)).exchange().expectStatus().isEqualTo(422).expectBody()
				.jsonPath("$.code").isEqualTo("dh_invalid_input");
		// 伪造游标（乱串）→ 422，无 SQL 错误泄漏。
		client().get().uri("/api/digital-human/profiles?cursor=%22%27--")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isEqualTo(422);
		// limit 超界 → 422。
		client().get().uri("/api/digital-human/profiles?limit=101").header("X-Grassland-Identity", sign(account, null))
				.exchange().expectStatus().isEqualTo(422);
	}

	// ---------- TC105B-03-04 补充：控制面投影规则（K14.1：缺配置不兜底） ----------

	@ParameterizedTest(name = "[{index}] {0}")
	@org.junit.jupiter.params.provider.ValueSource(strings = {"no_rows", "disabled", "untrusted_origin",
			"primary_backup"})
	void tc105b_03_04_controlPlaneProjectionRules(String mode) {
		switch (mode) {
			case "no_rows" -> db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'")
					.then().block(Duration.ofSeconds(10));
			case "disabled" -> db.sql(
					"UPDATE platform_model_config SET enabled = false" + " WHERE capability = 'digital_human_render'")
					.then().block(Duration.ofSeconds(10));
			case "untrusted_origin" ->
				// 目的地换未受信 origin（credential 与 config 同步换，模拟配置被改到未登记端点）。
				db.sql("UPDATE platform_provider_credential SET base_url = 'https://render-untrusted.example/v1'"
						+ " WHERE name = 'it-dh-render-cred'").then()
						.then(db.sql("UPDATE platform_model_config SET base_url = 'https://render-untrusted.example/v1'"
								+ " WHERE capability = 'digital_human_render'").then())
						.block(Duration.ofSeconds(10));
			case "primary_backup" -> {
				// 补一行 backup（test_only：不在 allowedBackendIds）——主备并存且主行批准，组合仍可用。
				db.sql("""
						INSERT INTO platform_model_config(id, capability, model_role, provider, model, base_url,
						    health_status, enabled, version, credential_id)
						SELECT gen_random_uuid(), 'digital_human_render', 'backup', provider, 'dh-render-backup',
						       base_url, 'healthy', true, 1, credential_id
						FROM platform_model_config WHERE capability = 'digital_human_render' AND model_role = 'primary'
						""").then().block(Duration.ofSeconds(10));
			}
			default -> throw new IllegalArgumentException(mode);
		}
		int expected = mode.equals("primary_backup") ? 201 : 422;
		byte[] response = createBytes(UUID.randomUUID().toString(), "投影角色", "人设", expected);
		if (expected == 422) {
			// 全部 422 dh_combination_unsupported：无行/停用/未受信都不经 env 或 renderer-profile 兜底。
			assertThat(json(response).path("code").asText()).isEqualTo("dh_combination_unsupported");
		} else {
			assertThat(json(response).path("data").path("name").asText()).isEqualTo("投影角色");
			// 主备并存投影两个 backend：primary=approved（allowedBackendIds 批准）、backup=test_only。
			byte[] catalogBody = client().get().uri("/api/digital-human/catalog")
					.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
					.expectBody(byte[].class).returnResult().getResponseBody();
			assertThat(json(catalogBody).path("data").path("backends").size()).isEqualTo(2);
			assertThat(json(catalogBody).path("data").path("backends").findValuesAsText("state"))
					.containsExactlyInAnyOrder("approved", "test_only");
		}
	}

	// ---------- 请求助手 ----------

	private static com.fasterxml.jackson.databind.JsonNode json(byte[] body) {
		try {
			return JSON.readTree(body);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private byte[] createBytes(String requestId, String name, String persona, int expectedStatus) {
		return client().post().uri("/api/digital-human/profiles").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(profileBody(name, persona, 1, requestId)).exchange()
				.expectStatus().isEqualTo(expectedStatus).expectBody(byte[].class).returnResult().getResponseBody();
	}

	private WebTestClient.BodyContentSpec postProfile(String requestId, String name, String persona,
			int expectedStatus) {
		return postProfileRaw(requestId, name, persona, expectedStatus);
	}

	private WebTestClient.BodyContentSpec postProfileRaw(String requestId, String name, String persona,
			int expectedStatus) {
		return client().post().uri("/api/digital-human/profiles").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(profileBody(name, persona, 1, requestId)).exchange()
				.expectStatus().isEqualTo(expectedStatus).expectBody();
	}

	private WebTestClient.BodyContentSpec postProfileWithCatalogVersion(String requestId, String name, String persona,
			int catalogVersion, int expectedStatus) {
		return client().post().uri("/api/digital-human/profiles").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(profileBody(name, persona, catalogVersion, requestId)).exchange().expectStatus()
				.isEqualTo(expectedStatus).expectBody();
	}

	private static String profileBody(String name, String persona, int catalogVersion, String requestId) {
		return """
				{"name":"%s","persona":"%s","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":%d,"requestId":"%s"}
				""".formatted(name, persona, AVATAR_ID, VOICE_ID, catalogVersion, requestId).replace("\n", "");
	}

	private WebTestClient.BodyContentSpec patchProfile(String id, String requestId, String name, String persona,
			int expectedVersion, int expectedStatus) {
		return client().patch().uri("/api/digital-human/profiles/" + id)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"name":"%s","persona":"%s","greeting":"你好","tone":"natural","avatarId":"%s",
						 "voiceId":"%s","catalogVersion":1,"expectedVersion":%d,"requestId":"%s"}
						""".formatted(name, persona, AVATAR_ID, VOICE_ID, expectedVersion, requestId).replace("\n", ""))
				.exchange().expectStatus().isEqualTo(expectedStatus).expectBody();
	}

	private Long revisionCount(String profileId) {
		return db.sql("SELECT count(*) AS n FROM dh_profile_revision WHERE profile_id = CAST(:id AS uuid)")
				.bind("id", profileId).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}
}
