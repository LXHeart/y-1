package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * settings 端到端（schema 归一/数据治理）。锁定（任务书 #88 后语义）： ① 旧模型端点已删（404，任意登录态）； ② 存量脏行（含
 * features）经 GET 返回归一形态且不含 features，存储原值不动； ③ PUT 忽略请求 features（含坏值不再 400），存量
 * features 原样保留（preserve-on-write）； ④ feishu 已知键坏值仍 400 且不落库。
 */
@SuppressWarnings("unchecked")
class SettingsControllerIT extends IntelligenceItSupport {

	@Test
	void removedModelEndpointsReturnNotFound() {
		// 任务书 #88 C-01：旧模型端点已删——任意登录态（带/不带身份头）POST 一律 404（路由匹配先于鉴权）
		String account = UUID.randomUUID().toString();
		seedUser(account);
		String identity = sign(account, null);

		client().post().uri("/api/settings/analysis/models").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("feature", "video")).exchange().expectStatus()
				.isNotFound();
		client().post().uri("/api/settings/analysis/models").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("feature", "video")).exchange().expectStatus().isNotFound();

		client().post().uri("/api/settings/analysis/verify-model").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("feature", "video", "model", "m1")).exchange()
				.expectStatus().isNotFound();
		client().post().uri("/api/settings/analysis/verify-model").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("feature", "video", "model", "m1")).exchange().expectStatus().isNotFound();
	}

	@Test
	void legacyJunkRowIsServedNormalizedAndMasked() {
		String account = UUID.randomUUID().toString();
		seedUser(account);
		db.sql("""
				INSERT INTO user_settings (id, user_id, settings_type, settings_json)
				VALUES (:id, CAST(:uid AS uuid), 'analysis', CAST(:json AS jsonb))
				""").bind("id", UUID.randomUUID()).bind("uid", account).bind("json", """
				{"features":{"video":{"provider":"qwen","apiKey":"sk-test-1234567890abcd",
				  "evil":"junk","number":42},"ghost":{"apiKey":"sk-y"}},
				 "integrations":{"feishu":{"appId":"cli_x","extra":"junk"}},
				 "topJunk":"drop"}
				""").then().block();

		// 任务书 #88：GET 响应不含 features（存量行有也不可见）；feishu 归一掩码照旧
		Map<String, Object> response = client().get().uri("/api/settings/analysis")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data = (Map<String, Object>) response.get("data");
		assertThat(data).doesNotContainKey("features");
		assertThat(data).doesNotContainKey("topJunk");
		Map<String, Object> integrations = (Map<String, Object>) data.get("integrations");
		Map<String, Object> feishu = (Map<String, Object>) integrations.get("feishu");
		assertThat(feishu).containsOnlyKeys("appId");
		assertThat(feishu.get("appId")).isEqualTo("cli_x");

		// 存储仍含原值（读路径归一不改写存储，掩码只作用于响应）。
		String stored = db.sql("SELECT settings_json::text FROM user_settings WHERE user_id = CAST(:uid AS uuid)")
				.bind("uid", account).map(r -> r.get(0, String.class)).one().block();
		assertThat(stored).contains("sk-test-1234567890abcd").contains("evil");
	}

	@Test
	void updateIgnoresRequestFeaturesAndPreservesStoredOnes() {
		String account = UUID.randomUUID().toString();
		seedUser(account);
		db.sql("""
				INSERT INTO user_settings (id, user_id, settings_type, settings_json)
				VALUES (:id, CAST(:uid AS uuid), 'analysis', CAST(:json AS jsonb))
				""").bind("id", UUID.randomUUID()).bind("uid", account).bind("json", """
				{"features":{"video":{"provider":"qwen","apiKey":"sk-keep-1234","legacyJunk":"x"}},
				 "integrations":{"feishu":{"appId":"cli_old"}}}
				""").then().block();

		// 旧客户端形态：features 含坏值（枚举外 provider + 私网 baseUrl——原 400）+ feishu 更新
		Map<String, Object> video = new LinkedHashMap<>();
		video.put("apiKey", "sk-attack-9999");
		video.put("provider", "openai");
		video.put("baseUrl", "http://127.0.0.1:8080/v1");
		Map<String, Object> body = Map.of("features", Map.of("video", video), "integrations",
				Map.of("feishu", Map.of("appId", "cli_new")));

		Map<String, Object> response = client().put().uri("/api/settings/analysis")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		Map<String, Object> data = (Map<String, Object>) response.get("data");
		assertThat(data).doesNotContainKey("features");
		Map<String, Object> feishu = (Map<String, Object>) ((Map<String, Object>) data.get("integrations"))
				.get("feishu");
		assertThat(feishu.get("appId")).isEqualTo("cli_new");

		// 存储断言：存量 features 原样保留（preserve-on-write），请求中的任何新值未落库
		String stored = db.sql("SELECT settings_json::text FROM user_settings WHERE user_id = CAST(:uid AS uuid)")
				.bind("uid", account).map(r -> r.get(0, String.class)).one().block();
		assertThat(stored).contains("sk-keep-1234").contains("legacyJunk");
		assertThat(stored).doesNotContain("sk-attack-9999");
		assertThat(stored).doesNotContain("127.0.0.1");
		assertThat(stored).contains("cli_new");
	}

	@Test
	void feishuBadValuesStillRejected() {
		String account = UUID.randomUUID().toString();
		seedUser(account);

		client().put().uri("/api/settings/analysis").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("integrations", Map.of("feishu", Map.of("appId", "x".repeat(257))))).exchange()
				.expectStatus().isBadRequest();

		// 400 不落库
		Long rows = db.sql("SELECT count(*) FROM user_settings WHERE user_id = CAST(:uid AS uuid)").bind("uid", account)
				.map(r -> r.get(0, Long.class)).one().block();
		assertThat(rows).isZero();
	}

	private void seedUser(String account) {
		db.sql("""
				INSERT INTO app_users (id, email, password_hash)
				VALUES (CAST(:uid AS uuid), :email, 'test-hash')
				ON CONFLICT (id) DO NOTHING
				""").bind("uid", account).bind("email", account + "@test.local").then().block();
	}
}
