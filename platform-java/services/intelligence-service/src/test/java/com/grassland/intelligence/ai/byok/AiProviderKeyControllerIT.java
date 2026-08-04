package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AiProviderKeyController 集成测试（GL-P3-AI-001 Phase 1）。
 *
 * <p>测试完整的 HTTP 请求/响应流程，使用真实 testcontainers PostgreSQL + 真实信封加密。
 * KEK 经 {@code crypto.kek.encoded} 注入（无 KEK 时 controller fail-closed 不注册）；
 * 组织上下文来自**断言**（{@code caller.organizationId()}），不由请求体提供。
 */
@DisplayName("AiProviderKeyController")
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
    @DisplayName("POST /api/ai/keys - 创建组织 BYOK 密钥成功返回 201（org 来自断言）")
    void createKey_returns201() {
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
                .jsonPath("$.organizationId").isEqualTo(TEST_ORG_ID)
                .jsonPath("$.capability").isEqualTo("text")
                .jsonPath("$.provider").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("https://api.openai.com")
                .jsonPath("$.model").isEqualTo("gpt-4")
                .jsonPath("$.maskedHint").value(v -> assertThat(v).asString().startsWith("sk-"))
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.encryptedKey").doesNotExist()  // 敏感字段不暴露
                .jsonPath("$.keyVersion").doesNotExist();
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
    @DisplayName("GET /api/ai/keys - 列出用户的所有密钥")
    void listKeys_returnsUserKeys() {
        UUID createdId = createTestKey("text", "openai-compatible");

        client().get()
                .uri("/api/ai/keys")
                .header("X-Grassland-Identity", signWithOrg(TEST_ACCOUNT_ID, TEST_ORG_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$[0].id").isEqualTo(createdId.toString())
                .jsonPath("$[0].organizationId").isEqualTo(TEST_ORG_ID)
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
    @DisplayName("PUT /api/ai/keys/{id}/key - 轮换密钥")
    void rotateKey_rotatesKey() {
        UUID createdId = createTestKey("text", "openai-compatible");
        String newApiKey = "sk-new-rotated-key-9876543210";

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
    @DisplayName("同一组织 + 同一能力 + 同一 provider 只能有一个有效密钥")
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
    @DisplayName("不同 provider 可以创建同一能力的密钥")
    void createDifferentProvider_succeeds() {
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
                .expectStatus().isCreated();
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

    /** 辅助：以组织断言创建测试密钥并返回 ID（从 {success 无关} 响应体的 id 字段提取）。 */
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
}
