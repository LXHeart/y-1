package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.DnsPinningResolver;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;

/**
 * AiProviderKeyController 集成测试（GL-P3-AI-001 Phase 1）。
 *
 * <p>测试完整的 HTTP 请求/响应流程，使用真实 testcontainers PostgreSQL + 真实信封加密。
 * KEK 经 {@code crypto.kek.encoded} 注入（无 KEK 时 controller fail-closed 不注册）；
 * 当前 BYOK 只支持个人作用域：即使断言带组织上下文，密钥也只归创建账号所有。
 */
@DisplayName("AiProviderKeyController")
@Import(AiProviderKeyControllerIT.DnsTestConfiguration.class)
class AiProviderKeyControllerIT extends IntelligenceItSupport {

    /** 32 字节 KEK（0x00..0x1F）的 Base64。 */
    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String TEST_ACCOUNT_ID = "test-account-" + UUID.randomUUID();
    private static final String TEST_ORG_ID = "test-org-" + UUID.randomUUID();
    private static final String TEST_API_KEY = "sk-test-real-key-1234567890abcdef";

    @DynamicPropertySource
    static void cryptoProps(DynamicPropertyRegistry registry) {
        registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
    }

    @BeforeEach
    void setup() {
        // 清理测试数据（如存在）
        db.sql("DELETE FROM ai_provider_key WHERE owner_account_id = :owner")
                .bind("owner", TEST_ACCOUNT_ID)
                .fetch().rowsUpdated()
                .block();
    }

    @Test
    @DisplayName("POST /api/ai/keys - 带组织上下文仍创建个人 BYOK 密钥")
    void createKey_withOrgContextStillCreatesPersonalKey() {
        client().post()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", "text",
                        "provider", "openai-compatible",
                        "baseUrl", "https://api.openai.com",
                        "model", "gpt-4",
                        "apiKey", TEST_API_KEY
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.organizationId").value(value -> assertThat(value).isNull())
                .jsonPath("$.capability").isEqualTo("text")
                .jsonPath("$.provider").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("https://api.openai.com")
                .jsonPath("$.model").isEqualTo("gpt-4")
                .jsonPath("$.maskedHint").value(v -> assertThat(v).asString().startsWith("sk-"))
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.encryptedKey").doesNotExist()  // 敏感字段不暴露
                .jsonPath("$.keyVersion").doesNotExist();

        Boolean personal = db.sql("SELECT organization_id IS NULL AS personal"
                        + " FROM ai_provider_key WHERE owner_account_id = :owner")
                .bind("owner", TEST_ACCOUNT_ID)
                .map((row, metadata) -> row.get("personal", Boolean.class))
                .one()
                .block();
        assertThat(personal).isTrue();
    }

    @Test
    @DisplayName("POST /api/ai/keys - 创建个人 BYOK 密钥（断言无 org → organizationId 为 null）")
    void createPersonalKey_withoutOrgId() {
        client().post()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", sign(TEST_ACCOUNT_ID, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", "image",
                        "provider", "qwen",
                        "baseUrl", "https://dashscope.aliyuncs.com",
                        "model", "wanx-v1",
                        "apiKey", TEST_API_KEY
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.organizationId").value(value -> assertThat(value).isNull())
                .jsonPath("$.capability").isEqualTo("image")
                .jsonPath("$.provider").isEqualTo("qwen");
    }

    @Test
    @DisplayName("POST /api/ai/keys - 无断言返回 401")
    void createKey_withoutAuth_returns401() {
        client().post()
                .uri("/api/ai/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", "text",
                        "provider", "openai-compatible",
                        "baseUrl", "https://api.openai.com",
                        "apiKey", TEST_API_KEY
                ))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("POST /api/ai/keys - 拒绝非 HTTPS、localhost 和 metadata URL")
    void createRejectsUnsafeBaseUrls() {
        for (String baseUrl : java.util.List.of(
                "http://api.example.com",
                "https://localhost",
                "https://169.254.169.254/latest/meta-data")) {
            client().post()
                    .uri("/api/ai/keys")
                    .header("X-Grassland-Identity", sign(TEST_ACCOUNT_ID, "merchant"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "capability", "text",
                            "provider", "openai-compatible",
                            "baseUrl", baseUrl,
                            "apiKey", TEST_API_KEY))
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        Long rows = db.sql("SELECT COUNT(*) AS n FROM ai_provider_key WHERE owner_account_id = :owner")
                .bind("owner", TEST_ACCOUNT_ID)
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("GET /api/ai/keys - 只列出用户的个人密钥")
    void listKeys_returnsUserKeys() {
        UUID createdId = createTestKey("text", "openai-compatible");
        db.sql("""
                INSERT INTO ai_provider_key(
                    organization_id, owner_account_id, capability, provider, base_url,
                    encrypted_key, key_version, masked_hint, enabled)
                VALUES (:org, :owner, 'image', 'legacy-org-provider', 'https://example.com',
                    'legacy-ciphertext', 'v1', 'legacy-***', true)
                """)
                .bind("org", TEST_ORG_ID)
                .bind("owner", TEST_ACCOUNT_ID)
                .then()
                .block();

        client().get()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(createdId.toString())
                .jsonPath("$[0].organizationId").value(value -> assertThat(value).isNull())
                .jsonPath("$[0].capability").isEqualTo("text");
    }

    @Test
    @DisplayName("GET /api/ai/keys/{id} - 获取密钥详情")
    void getKeyById_returnsDetails() {
        UUID createdId = createTestKey("text", "openai-compatible");

        client().get()
                .uri("/api/ai/keys/" + createdId)
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(createdId.toString())
                .jsonPath("$.capability").isEqualTo("text");
    }

    @Test
    @DisplayName("GET /api/ai/keys/{id} - 不存在的密钥返回 404")
    void getKeyById_notFound_returns404() {
        UUID randomId = UUID.randomUUID();

        client().get()
                .uri("/api/ai/keys/" + randomId)
                .header("X-Grassland-Identity", sign(TEST_ACCOUNT_ID, "recommender"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    @Test
    @DisplayName("PUT /api/ai/keys/{id} - 更新密钥配置")
    void updateKey_updatesConfig() {
        UUID createdId = createTestKey("text", "openai-compatible");

        client().put()
                .uri("/api/ai/keys/" + createdId)
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "baseUrl", "https://api.new-openai.com",
                        "model", "gpt-4-turbo"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(createdId.toString())
                .jsonPath("$.baseUrl").isEqualTo("https://api.new-openai.com")
                .jsonPath("$.model").isEqualTo("gpt-4-turbo");
    }

    @Test
    @DisplayName("PUT /api/ai/keys/{id} - 非安全 URL 返回 400 且不修改原记录")
    void updateRejectsUnsafeBaseUrlWithoutMutation() {
        UUID createdId = createTestKey("text", "openai-compatible");

        client().put()
                .uri("/api/ai/keys/" + createdId)
                .header("X-Grassland-Identity", sign(TEST_ACCOUNT_ID, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("baseUrl", "https://127.0.0.1", "model", "evil"))
                .exchange()
                .expectStatus().isBadRequest();

        Map<String, Object> stored = db.sql("SELECT base_url, model FROM ai_provider_key WHERE id = CAST(:id AS uuid)")
                .bind("id", createdId.toString()).fetch().one().block();
        assertThat(stored).containsEntry("base_url", "https://api.openai.com")
                .containsEntry("model", "gpt-4");
    }

    @Test
    @DisplayName("PUT /api/ai/keys/{id}/key - 轮换密钥")
    void rotateKey_rotatesKey() {
        UUID createdId = createTestKey("text", "openai-compatible");
        String newApiKey = "sk-test-new-rotated-key-9876543210";

        client().put()
                .uri("/api/ai/keys/" + createdId + "/key")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("apiKey", newApiKey))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(createdId.toString())
                .jsonPath("$.maskedHint").value(v -> assertThat(v).asString().startsWith("sk-"));

        Map<String, Object> stored = db.sql("SELECT encrypted_key, key_version FROM ai_provider_key"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", createdId.toString()).fetch().one().block();
        byte ciphertextVersion = Base64.getDecoder().decode((String) stored.get("encrypted_key"))[0];
        assertThat(stored.get("key_version")).isEqualTo("v" + Byte.toUnsignedInt(ciphertextVersion));
    }

    @Test
    @DisplayName("DELETE /api/ai/keys/{id} - 软删除密钥")
    void deleteKey_softDeletes() {
        UUID createdId = createTestKey("text", "openai-compatible");

        client().delete()
                .uri("/api/ai/keys/" + createdId)
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .exchange()
                .expectStatus().isNoContent();

        // 验证密钥已软删除（enabled=false）
        Boolean enabled = db.sql("SELECT enabled FROM ai_provider_key WHERE id = CAST(:id AS uuid)")
                .bind("id", createdId.toString())
                .map((r, meta) -> r.get("enabled", Boolean.class))
                .one()
                .block();

        assertThat(enabled).isFalse();
    }

    @Test
    @DisplayName("同一账号 + 同一能力 + 同一 provider 只能有一个有效个人密钥")
    void createDuplicateKey_returns409() {
        createTestKey("text", "openai-compatible");

        client().post()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", "text",
                        "provider", "openai-compatible",
                        "baseUrl", "https://api.openai.com",
                        "apiKey", "sk-another-key"
                ))
                .exchange()
                .expectStatus().isEqualTo(409);  // UNIQUE constraint violation → controller 转 409
    }

    @Test
    @DisplayName("同一账号同一能力不允许启用第二个 provider")
    void createDifferentProvider_returns409() {
        createTestKey("text", "openai-compatible");

        client().post()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", "text",
                        "provider", "qwen",
                        "baseUrl", "https://dashscope.aliyuncs.com",
                        "apiKey", "sk-qwen-key"
                ))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("历史组织密钥不可通过个人控制面读取、更新、轮换或删除")
    void legacyOrganizationKeyIsHiddenFromAllManagementEndpoints() {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO ai_provider_key(id, organization_id, owner_account_id, capability, provider,
                    base_url, encrypted_key, key_version, masked_hint, enabled)
                VALUES (CAST(:id AS uuid), :org, :owner, 'text', 'legacy-provider',
                    'https://api.example.com', 'legacy-ciphertext', 'v1', 'legacy-***', true)
                """)
                .bind("id", id.toString()).bind("org", TEST_ORG_ID).bind("owner", TEST_ACCOUNT_ID)
                .then().block();

        String identity = signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID);
        client().get().uri("/api/ai/keys/" + id).header("X-Grassland-Identity", identity)
                .exchange().expectStatus().isNotFound();
        client().put().uri("/api/ai/keys/" + id).header("X-Grassland-Identity", identity)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("baseUrl", "https://api.example.com", "model", "new"))
                .exchange().expectStatus().isNotFound();
        client().put().uri("/api/ai/keys/" + id + "/key").header("X-Grassland-Identity", identity)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("apiKey", "sk-new-key"))
                .exchange().expectStatus().isNotFound();
        client().delete().uri("/api/ai/keys/" + id).header("X-Grassland-Identity", identity)
                .exchange().expectStatus().isNotFound();

        Boolean enabled = db.sql("SELECT enabled FROM ai_provider_key WHERE id = CAST(:id AS uuid)")
                .bind("id", id.toString()).map((r, m) -> r.get("enabled", Boolean.class)).one().block();
        assertThat(enabled).isTrue();
    }

    @Test
    @DisplayName("更新不存在的密钥返回 404")
    void updateNonexistentKey_returns404() {
        UUID randomId = UUID.randomUUID();

        client().put()
                .uri("/api/ai/keys/" + randomId)
                .header("X-Grassland-Identity", sign(TEST_ACCOUNT_ID, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "baseUrl", "https://api.new-openai.com",
                        "model", "gpt-4-turbo"
                ))
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 辅助：以带组织上下文的断言创建个人测试密钥并返回 ID。 */
    private UUID createTestKey(String capability, String provider) {
        byte[] body = client().post()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "capability", capability,
                        "provider", provider,
                        "baseUrl", "https://api.openai.com",
                        "model", "gpt-4",
                        "apiKey", TEST_API_KEY
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return UUID.fromString(node.get("id").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
