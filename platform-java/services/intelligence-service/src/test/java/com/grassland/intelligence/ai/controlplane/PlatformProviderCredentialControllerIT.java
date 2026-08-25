package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 平台通用凭据 admin CRUD（任务书 #47 S1；D1–D6）。
 *
 * <p>凭据把 base_url 与 encrypted_key 收进同一行（D2），platform_model_config 改挂 credential_id。
 * 掩码/轮换/软删复刻 {@code AiProviderKeyController}（D5）；引用中拒删（D6）。
 * KEK 经 {@code crypto.kek.encoded} 注入——未配 KEK 时写入 503 且不退化存明文（D8/验收 2）。
 */
@DisplayName("PlatformProviderCredentialController (admin CRUD)")
class PlatformProviderCredentialControllerIT extends IntelligenceItSupport {

    /** 32 字节 KEK（0x00..0x1F）的 Base64，与既有 BYOK IT 同款测试常量。 */
    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String ADMIN = "33333333-3333-3333-3333-333333333333";
    private static final String USER = "44444444-4444-4444-4444-444444444444";
    private static final String QWEN_URL = "https://dashscope.aliyuncs.com";
    private static final String TEST_KEY = "sk-test-credential-1234567890abcdef";

    @DynamicPropertySource
    static void cryptoProps(DynamicPropertyRegistry registry) {
        registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
    }

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM platform_model_config_history").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        db.sql("DELETE FROM platform_provider_credential").then().block();
    }
    @Test
    @DisplayName("创建 → 201 version=1；响应只回掩码，明文密钥不出响应体")
    void adminCreatesCredential() {
        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"主力-通义","provider":"qwen","baseUrl":"%s","apiKey":"%s"}
                        """.formatted(QWEN_URL, TEST_KEY))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("主力-通义")
                .jsonPath("$.provider").isEqualTo("qwen")
                .jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.hasKey").isEqualTo(true)
                .jsonPath("$.maskedHint").exists()
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.encryptedKey").doesNotExist();

        // 库里存的是密文，绝不是明文
        String stored = db.sql("SELECT encrypted_key FROM platform_provider_credential WHERE name = '主力-通义'")
                .map((row, meta) -> row.get("encrypted_key", String.class)).one().block();
        assertThat(stored).isNotNull().isNotEqualTo(TEST_KEY);
    }

    @Test
    @DisplayName("列表与详情只回掩码；同 (provider, baseUrl) 重复创建 → 409")
    void listMasksAndRejectsDuplicateDestination() {
        createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);

        client().get().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].maskedHint").exists()
                .jsonPath("$[0].apiKey").doesNotExist();

        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"另一个标签","provider":"qwen","baseUrl":"%s","apiKey":"%s"}
                        """.formatted(QWEN_URL, TEST_KEY))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("改连接信息用 PUT（不含密钥）→ version+1，密钥与掩码不变")
    void updateConnectionKeepsKey() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        String hintBefore = maskedHint(id);

        client().put().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"主力-通义（改名）","provider":"qwen","baseUrl":"%s"}
                        """.formatted(QWEN_URL))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("主力-通义（改名）")
                .jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.maskedHint").isEqualTo(hintBefore);
    }

    @Test
    @DisplayName("轮换走独立端点 → version+1，掩码随新密钥变化")
    void rotateKeyBumpsVersion() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        String hintBefore = maskedHint(id);

        client().put().uri("/api/admin/ai/credentials/" + id + "/key")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"apiKey\":\"sk-test-rotated-fedcba0987654321\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.hasKey").isEqualTo(true);

        assertThat(maskedHint(id)).isNotEqualTo(hintBefore);
    }

    @Test
    @DisplayName("sandbox 凭据可无密钥创建（hasKey=false），空密钥是一等状态")
    void sandboxCredentialWithoutKey() {
        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"内置沙箱","provider":"sandbox","baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.hasKey").isEqualTo(false)
                .jsonPath("$.maskedHint").doesNotExist();
    }
    @Test
    @DisplayName("引用中拒删 → 409 并报引用数（D6）；解除引用后软删 204")
    void refusesDeleteWhileReferenced() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);

        // 建一个指向该目的地的模型配置：写入侧应自动挂上 credential_id
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"%s"}
                        """.formatted(QWEN_URL))
                .exchange().expectStatus().isCreated();

        assertThat(referenceCount(id)).isEqualTo(1L);

        client().delete().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(
                        (String message) -> assertThat(message).contains("1"));

        // 停用模型配置后不再有 enabled 引用 → 可软删
        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().delete().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().get().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("模型配置写入侧自动挂 credential_id（V47 收 NOT NULL 的前提）")
    void modelConfigWriteLinksCredential() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"%s"}
                        """.formatted(QWEN_URL))
                .exchange().expectStatus().isCreated();

        // 未预先建凭据 → 写入侧 find-or-create 出一行
        Long credentials = db.sql("SELECT COUNT(*) AS n FROM platform_provider_credential")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(credentials).isEqualTo(1L);

        Long unlinked = db.sql(
                        "SELECT COUNT(*) AS n FROM platform_model_config WHERE credential_id IS NULL")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(unlinked).isZero();
    }

    @Test
    @DisplayName("鉴权：非 admin → 403；缺断言 → 401")
    void adminGate() {
        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signWithRole(USER, null, null, "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"越权","provider":"qwen","baseUrl":"%s","apiKey":"%s"}
                        """.formatted(QWEN_URL, TEST_KEY))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/ai/credentials")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("baseUrl 仍过受信目的地校验：非受信 origin → 400")
    void rejectsUntrustedDestination() {
        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"可疑","provider":"qwen","baseUrl":"https://attacker.example/v1",
                         "apiKey":"%s"}
                        """.formatted(TEST_KEY))
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- 夹具 ----------

    private String createCredential(String name, String provider, String baseUrl, String apiKey) {
        return client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"%s","provider":"%s","baseUrl":"%s","apiKey":"%s"}
                        """.formatted(name, provider, baseUrl, apiKey))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(PlatformProviderCredentialResponse.class)
                .returnResult().getResponseBody().id().toString();
    }

    private String maskedHint(String id) {
        return db.sql("SELECT masked_hint FROM platform_provider_credential WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map((row, meta) -> row.get("masked_hint", String.class)).one().block();
    }

    private Long referenceCount(String id) {
        return db.sql("""
                        SELECT COUNT(*) AS n FROM platform_model_config
                        WHERE credential_id = CAST(:id AS uuid) AND enabled = true
                        """)
                .bind("id", id)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }
}
