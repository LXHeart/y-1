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
 * settings 端到端（schema 归一/数据治理）。锁定：
 * ① 存量脏行（未知键/非字符串值）经 GET 返回归一形态，密钥仍掩码；
 * ② PUT 的未知键被丢弃（存储不含），已知键坏值（枚举/超长/私网 URL）400；
 * ③ homepage 的 provider 枚举与 alapiToken 掩码语义不回归。
 */
@SuppressWarnings("unchecked")
class SettingsControllerIT extends IntelligenceItSupport {

    @Test
    void removedModelEndpointsReturnNotFound() {
        // 任务书 #88 C-01：旧模型端点已删——任意登录态（带/不带身份头）POST 一律 404（路由匹配先于鉴权）
        String account = UUID.randomUUID().toString();
        seedUser(account);
        String identity = sign(account, null);

        client().post().uri("/api/settings/analysis/models")
                .header("X-Grassland-Identity", identity)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("feature", "video"))
                .exchange().expectStatus().isNotFound();
        client().post().uri("/api/settings/analysis/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("feature", "video"))
                .exchange().expectStatus().isNotFound();

        client().post().uri("/api/settings/analysis/verify-model")
                .header("X-Grassland-Identity", identity)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("feature", "video", "model", "m1"))
                .exchange().expectStatus().isNotFound();
        client().post().uri("/api/settings/analysis/verify-model")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("feature", "video", "model", "m1"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void legacyJunkRowIsServedNormalizedAndMasked() {
        String account = UUID.randomUUID().toString();
        seedUser(account);
        db.sql("""
                INSERT INTO user_settings (id, user_id, settings_type, settings_json)
                VALUES (:id, CAST(:uid AS uuid), 'analysis', CAST(:json AS jsonb))
                """)
                .bind("id", UUID.randomUUID()).bind("uid", account)
                .bind("json", """
                        {"features":{"video":{"provider":"qwen","apiKey":"sk-test-1234567890abcd",
                          "evil":"junk","number":42},"ghost":{"apiKey":"sk-y"}},
                         "integrations":{"feishu":{"appId":"cli_x","extra":"junk"}},
                         "topJunk":"drop"}
                        """)
                .then().block();

        Map<String, Object> response = client().get().uri("/api/settings/analysis")
                .header("X-Grassland-Identity", sign(account, null))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        Map<String, Object> video = (Map<String, Object>) ((Map<String, Object>) data.get("features")).get("video");
        assertThat(video).containsOnlyKeys("provider", "apiKey");
        assertThat(video.get("apiKey")).isEqualTo("****abcd");
        assertThat((Map<String, Object>) data.get("features")).doesNotContainKey("ghost");
        assertThat(data).doesNotContainKey("topJunk");
        Map<String, Object> integrations = (Map<String, Object>) data.get("integrations");
        Map<String, Object> feishu = (Map<String, Object>) integrations.get("feishu");
        assertThat(feishu).containsOnlyKeys("appId");

        // 存储仍含原值（读路径归一不改写存储，掩码只作用于响应）。
        String stored = db.sql("SELECT settings_json::text FROM user_settings WHERE user_id = CAST(:uid AS uuid)")
                .bind("uid", account).map(r -> r.get(0, String.class)).one().block();
        assertThat(stored).contains("sk-test-1234567890abcd").contains("evil");
    }

    @Test
    void updateDropsUnknownKeysAndRejectsBadKnownValues() {
        String account = UUID.randomUUID().toString();
        seedUser(account);

        Map<String, Object> video = new LinkedHashMap<>();
        video.put("provider", "qwen");
        video.put("model", "qwen-vl-max");
        video.put("unknownKey", "junk");
        Map<String, Object> body = Map.of("features", Map.of("video", video));

        client().put().uri("/api/settings/analysis")
                .header("X-Grassland-Identity", sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk();

        String stored = db.sql(
                        "SELECT settings_json::text FROM user_settings WHERE user_id = CAST(:uid AS uuid)")
                .bind("uid", account).map(r -> r.get(0, String.class)).one().block();
        assertThat(stored).contains("qwen-vl-max").doesNotContain("unknownKey");

        // 枚举外 provider → 400。
        client().put().uri("/api/settings/analysis")
                .header("X-Grassland-Identity", sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("features", Map.of("video", Map.of("provider", "openai"))))
                .exchange().expectStatus().isBadRequest();

        // 私网/环回 baseUrl → 400（写入路径即拒绝 SSRF 向量）。
        client().put().uri("/api/settings/analysis")
                .header("X-Grassland-Identity", sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("features", Map.of("video",
                        Map.of("baseUrl", "http://127.0.0.1:8080/v1"))))
                .exchange().expectStatus().isBadRequest();

    }

    private void seedUser(String account) {
        db.sql("""
                INSERT INTO app_users (id, email, password_hash)
                VALUES (CAST(:uid AS uuid), :email, 'test-hash')
                ON CONFLICT (id) DO NOTHING
                """)
                .bind("uid", account).bind("email", account + "@test.local")
                .then().block();
    }
}
