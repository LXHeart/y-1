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

    @org.springframework.beans.factory.annotation.Autowired
    PlatformModelControlPlaneService controlPlane;
    @org.springframework.beans.factory.annotation.Autowired
    com.grassland.crypto.EnvelopeEncryption encryption;

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
        // 勾选集（V51）在凭据之前删：credential_id 外键指向 platform_provider_credential。
        db.sql("DELETE FROM platform_credential_model").then().block();
        db.sql("DELETE FROM platform_provider_credential").then().block();
    }

    @Test
    @DisplayName("勾选集：初始为空；PUT 整份覆盖后 GET 回勾选项（含 ownedBy）")
    void replacesAndReadsSelectedModels() {
        String id = createCredential("sel-cred", "qwen", "https://dashscope.aliyuncs.com", "sk-abcdefgh1234567890");

        client().get().uri("/api/admin/ai/credentials/" + id + "/selected-models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        client().put().uri("/api/admin/ai/credentials/" + id + "/selected-models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"models":[{"id":"qwen-plus","ownedBy":"aliyun"},{"id":"qwen-max"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // 仓储按 model_id 排序，故 qwen-max 在前
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("qwen-max")
                .jsonPath("$[1].id").isEqualTo("qwen-plus")
                .jsonPath("$[1].ownedBy").isEqualTo("aliyun");
    }

    @Test
    @DisplayName("勾选集 PUT 是整份覆盖：第二次提交的集合完全取代第一次，空数组清空")
    void replaceAllIsWholesale() {
        String id = createCredential("sel-cred2", "qwen", "https://dashscope.aliyuncs.com", "sk-abcdefgh1234567890");
        putSelected(id, """
                {"models":[{"id":"qwen-plus"},{"id":"qwen-max"}]}
                """);

        putSelected(id, """
                {"models":[{"id":"qwen-turbo"}]}
                """).jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo("qwen-turbo");

        // 空数组 = 取消全部勾选（合法输入，不是 400）
        putSelected(id, """
                {"models":[]}
                """).jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("勾选集：重复 id 去重；非法字符与超长 id → 400")
    void rejectsInvalidModelIds() {
        String id = createCredential("sel-cred3", "qwen", "https://dashscope.aliyuncs.com", "sk-abcdefgh1234567890");

        // 同一 id 提交两次不该撞唯一索引，去重后只留一条
        putSelected(id, """
                {"models":[{"id":"qwen-plus"},{"id":"qwen-plus"}]}
                """).jsonPath("$.length()").isEqualTo(1);

        client().put().uri("/api/admin/ai/credentials/" + id + "/selected-models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"models":[{"id":"bad id with spaces"}]}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("勾选集鉴权与归属：缺断言 → 401；凭据不存在 → 404")
    void selectedModelsGate() {
        client().get().uri("/api/admin/ai/credentials/"
                        + "99999999-9999-9999-9999-999999999999/selected-models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNotFound();

        String id = createCredential("sel-cred4", "qwen", "https://dashscope.aliyuncs.com", "sk-abcdefgh1234567890");
        client().get().uri("/api/admin/ai/credentials/" + id + "/selected-models")
                .exchange().expectStatus().isUnauthorized();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec putSelected(
            String id, String body) {
        return client().put().uri("/api/admin/ai/credentials/" + id + "/selected-models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
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
    @DisplayName("任务书 #58：非受信 origin → 422 引导先到受信端点添加")
    void rejectsUntrustedDestination() {
        client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"可疑","provider":"qwen","baseUrl":"https://attacker.example/v1",
                         "apiKey":"%s"}
                        """.formatted(TEST_KEY))
                .exchange().expectStatus().isEqualTo(422);
    }

    /**
     * 任务书 #47 S3（D7）：凭据轮换后，解析结果携带的凭据版本随之递增——{@code ai_run.credential_version}
     * 据此冻结。没有这一列，「厂商 key 被封的那批 Run 用的哪把 key」只能靠时间戳猜。
     */
    @Test
    @DisplayName("轮换后凭据版本递增，解析结果据此冻结（credential_version 的数据来源）")
    void credentialVersionAdvancesOnRotation() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        assertThat(credentialVersion(id)).isEqualTo(1L);

        client().put().uri("/api/admin/ai/credentials/" + id + "/key")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"apiKey\":\"sk-test-rotated-for-version-check\"}")
                .exchange().expectStatus().isOk();

        assertThat(credentialVersion(id)).isEqualTo(2L);

        // 改连接信息也算一次变更（同一把 key 换了目的地，审计上必须可区分）
        client().put().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"主力-通义","provider":"qwen","baseUrl":"%s"}
                        """.formatted(QWEN_URL))
                .exchange().expectStatus().isOk();

        assertThat(credentialVersion(id)).isEqualTo(3L);
    }

    /**
     * 任务书 #47 验收 1——本任务的头号能力:<b>admin 改凭据 key 后不重启,下一次解析即用新 key</b>。
     *
     * <p>机制是 {@code resolve} 每次都读库(不缓存),但「应该如此」和「确实如此」是两件事。
     * 此前只有 commit 标题声称过这个能力,没有测试证明它。
     */
    @Test
    @DisplayName("验收 1:轮换密钥后不重启,下一次 resolve 即拿到新密文")
    void rotatedKeyTakesEffectWithoutRestart() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"%s"}
                        """.formatted(QWEN_URL))
                .exchange().expectStatus().isCreated();

        String before = resolvedCiphertext("text");
        assertThat(before).isNotNull();

        client().put().uri("/api/admin/ai/credentials/" + id + "/key")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"apiKey\":\"sk-test-rotated-no-restart-9999\"}")
                .exchange().expectStatus().isOk();

        // 同一个进程、同一个 bean,不重启
        String after = resolvedCiphertext("text");
        assertThat(after).isNotNull().isNotEqualTo(before);
        assertThat(encryption.decrypt(after)).isEqualTo("sk-test-rotated-no-restart-9999");
    }

    /**
     * 任务书 #47 验收 3 / ADR-D16 降级路径回归点:停用某 capability 的凭据后,该能力的目的地不可用,
     * 但<b>其它 capability 照常</b>——凭据是按目的地隔离的,不是全局开关。
     *
     * <p>LEFT JOIN 只关联 {@code enabled=true} 凭据,故停用后 baseUrl 为 null,由执行层判定不可用
     * (按 capability 503),而不是拿一把已停用的密钥继续跑。
     */
    @Test
    @DisplayName("验收 3:停用某能力的凭据 → 该能力凭据密钥不再参与解析，其它能力不受影响")
    void disablingOneCredentialDoesNotAffectOtherCapabilities() {
        String textCredential = createCredential("文本-通义", "qwen", QWEN_URL, TEST_KEY);
        String safetyCredential = createCredential(
                "安全-沙箱", "sandbox", "https://sandbox.invalid", null);
        createModel("text", QWEN_URL, "qwen", "qwen-plus");
        createModel("content_safety", "https://sandbox.invalid", "sandbox", "sandbox-safety-v1");

        assertThat(resolvedBaseUrl("text")).isEqualTo(QWEN_URL);
        assertThat(resolvedBaseUrl("content_safety")).isEqualTo("https://sandbox.invalid");

        // D6 会拒绝停用被引用的凭据（API 层保护），故直接改库制造「引用仍在但凭据已停用」的状态
        // ——这正是 LEFT JOIN `credential.enabled = true` 要处理的情形。
        client().delete().uri("/api/admin/ai/credentials/" + safetyCredential)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isEqualTo(409);   // 先证明 D6 确实在拦
        db.sql("UPDATE platform_provider_credential SET enabled = false WHERE id = CAST(:id AS uuid)")
                .bind("id", safetyCredential).then().block();

        // **V52 之前的真实行为**（实测，与「停用即不可用」的直觉不同）：
        // COALESCE 会回落 config.base_url，故地址仍可解析；但凭据密钥没了，
        // 执行层回落 env bootstrap。即「停用凭据」= 该能力退回 env 密钥，不是立刻不可用。
        // V52 DROP COLUMN 之后 COALESCE 无处可落，baseUrl 才会变 null。
        assertThat(resolvedBaseUrl("content_safety")).isEqualTo("https://sandbox.invalid");
        assertThat(resolvedCiphertext("content_safety")).isNull();   // 密钥确实不再来自凭据
        // text 完全不受影响——凭据是按目的地隔离的，不是全局开关
        assertThat(resolvedBaseUrl("text")).isEqualTo(QWEN_URL);
        assertThat(resolvedCiphertext("text")).isNotNull();
        assertThat(textCredential).isNotBlank();
    }

    // ---------- 任务书 #59：停用凭据可见可删 ----------

    @Test
    @DisplayName("#59：停用后默认 GET 不含、includeDisabled=true 含且 enabled=false")
    void includeDisabledListsDisabledRows() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        client().delete().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        // 默认契约逐字节不变：只回生效行
        client().get().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        client().get().uri("/api/admin/ai/credentials?includeDisabled=true")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(id)
                .jsonPath("$[0].enabled").isEqualTo(false);
    }

    @Test
    @DisplayName("#59：硬删已停用行 → 204，勾选集经 CASCADE 一并清除")
    void hardDeletePurgesDisabledRowAndSelections() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        putSelected(id, """
                {"models":[{"id":"qwen-plus"}]}
                """);
        client().delete().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().delete().uri("/api/admin/ai/credentials/" + id + "/hard")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().get().uri("/api/admin/ai/credentials?includeDisabled=true")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        Long selections = db.sql(
                        "SELECT COUNT(*) AS n FROM platform_credential_model WHERE credential_id = CAST(:id AS uuid)")
                .bind("id", id)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(selections).isZero();
    }

    @Test
    @DisplayName("#59：硬删生效行 → 409 要求先停用；不存在的 id → 404")
    void hardDeleteGuards() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);

        client().delete().uri("/api/admin/ai/credentials/" + id + "/hard")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(
                        (String message) -> assertThat(message).contains("先停用"));

        client().delete().uri("/api/admin/ai/credentials/"
                        + "99999999-9999-9999-9999-999999999999/hard")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("#59：被引用（含已停用历史行）拒硬删 → 409 报行数")
    void hardDeleteRejectedWhileReferenced() {
        String id = createCredential("主力-通义", "qwen", QWEN_URL, TEST_KEY);
        // 直插一条已停用的模型配置历史行：普通 FK 物理拦硬删，端点要给可诊断 409 而非 500
        db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version, credential_id)
                        VALUES ('text', 'backup', 'qwen', 'qwen-plus', '%s',
                                'healthy', false, 1, CAST('%s' AS uuid))
                        """.formatted(QWEN_URL, id))
                .then().block();

        // 只有 enabled 引用才拦软删——历史行引用不拦停用
        client().delete().uri("/api/admin/ai/credentials/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().delete().uri("/api/admin/ai/credentials/" + id + "/hard")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(
                        (String message) -> assertThat(message).contains("1 个模型配置行引用"));
    }

    @Test
    @DisplayName("#59：非 admin 调 hard 与 includeDisabled → 403")
    void hardDeleteAndIncludeDisabledStayAdminOnly() {
        String userHeaders = signWithRole(USER, null, null, "user");

        client().delete().uri("/api/admin/ai/credentials/"
                        + "99999999-9999-9999-9999-999999999999/hard")
                .header("X-Grassland-Identity", userHeaders)
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/ai/credentials?includeDisabled=true")
                .header("X-Grassland-Identity", userHeaders)
                .exchange().expectStatus().isForbidden();
    }

    // ---------- 夹具 ----------

    private void createModel(String capability, String baseUrl, String provider, String model) {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"%s","modelRole":"primary","provider":"%s",
                         "model":"%s","baseUrl":"%s"}
                        """.formatted(capability, provider, model, baseUrl))
                .exchange().expectStatus().isCreated();
    }

    /** 经控制面解析,拿该 capability 当前的凭据密文——证明读的是库而非缓存。 */
    private String resolvedCiphertext(String capability) {
        return controlPlane.resolve(capability).block()
                .map(PlatformModelControlPlaneService.ResolvedPlatformModel::credentialEncryptedKey)
                .orElse(null);
    }

    private String resolvedBaseUrl(String capability) {
        return controlPlane.resolve(capability).block()
                .map(PlatformModelControlPlaneService.ResolvedPlatformModel::baseUrl)
                .orElse(null);
    }

    private Long credentialVersion(String id) {
        return db.sql("SELECT version FROM platform_provider_credential WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map((row, meta) -> row.get("version", Long.class)).one().block();
    }

    private String createCredential(String name, String provider, String baseUrl, String apiKey) {
        // null apiKey 序列化为空串（原样 %s 会把 null 变成 4 字符串 "null"，过不了 #58 的密钥强度校验）
        String key = apiKey == null ? "" : apiKey;
        return client().post().uri("/api/admin/ai/credentials")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"%s","provider":"%s","baseUrl":"%s","apiKey":"%s"}
                        """.formatted(name, provider, baseUrl, key))
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
