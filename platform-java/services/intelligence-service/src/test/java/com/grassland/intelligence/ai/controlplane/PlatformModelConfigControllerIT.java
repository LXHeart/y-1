package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 平台模型配置 admin CRUD（GL-P3-AI-001 model-control-plane）。requireAdmin 门闩 + 版本化 + history。
 * controller 非 KEK 门控（无密钥），故本 IT 不注入 KEK。
 */
@DisplayName("PlatformModelConfigController (admin CRUD)")
class PlatformModelConfigControllerIT extends IntelligenceItSupport {

    private static final String ADMIN = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM platform_model_config_history").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        // 凭据表也要清：credentialCount() 断言依赖它，且 name 的部分唯一索引会让重跑撞冲突。
        // 必须在 config 之后——config.credential_id 有外键指向它。
        db.sql("DELETE FROM platform_provider_credential").then().block();
    }

    @Test
    @DisplayName("admin 创建 → 201 version=1；list 可见；history 落 create 行")
    void adminCreates() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.capability").isEqualTo("text")
                .jsonPath("$.modelRole").isEqualTo("primary")
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.healthStatus").isEqualTo("healthy");

        client().get().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("配置并发上限时创建对应数据库槽位")
    void createsConcurrencySlots() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com","maxConcurrency":2}
                        """)
                .exchange().expectStatus().isCreated();

        Long slots = db.sql("SELECT COUNT(*) AS n FROM platform_model_concurrency_slot")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(slots).isEqualTo(2);
    }

    @Test
    @DisplayName("拒绝异常大的并发上限")
    void rejectsExcessiveConcurrencyLimit() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com","maxConcurrency":1001}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("任务书 #58：非受信 origin → 422 引导先到受信端点添加")
    void rejectsUntrustedProviderDestination() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"openai-compatible",
                         "model":"gpt-4","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isEqualTo(422);

        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isEqualTo(422);

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("重复创建同 (capability,modelRole) → 409")
    void duplicateCreateConflict() {
        createPrimary();
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-turbo","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("admin 修订 → version+1；history 落 update 行")
    void adminRevisesBumpsVersion() {
        createPrimary();
        client().put().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"qwen","model":"qwen-max","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.model").isEqualTo("qwen-max");

        // version=1 被 disable，只剩 version=2 enabled
        client().get().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        assertThat(historyCount()).isEqualTo(2);  // create + update
    }

    @Test
    @DisplayName("admin 禁用 → 204；其后 GET → 404")
    void adminDisables() {
        createPrimary();
        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();
        client().get().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNotFound();
    }

    /**
     * 任务书 #47 S0：seeder 用 provider=sandbox 种 voice/retrieval/image_edit 三行
     * （{@code PlatformModelConfigSeeder.seedSandboxCapability}），但两个 Request 的
     * provider 正则曾是 {@code qwen|openai-compatible} —— admin 对这些行的任何 PUT 都 400，
     * 即运营改不动、也看不出「该能力其实跑在沙箱假数据上」。
     * {@code PlatformProviderPolicy.validate} 本就支持 sandbox，仅 bean validation 失同步。
     */
    @Test
    @DisplayName("sandbox provider 可创建与修订（正则与 PlatformProviderPolicy 对齐）")
    void adminManagesSandboxCapability() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_edit","modelRole":"primary","provider":"sandbox",
                         "model":"sandbox-matting-v1","baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.provider").isEqualTo("sandbox");

        // 修订：S0 之前这一步是 400（正则拒 sandbox），运营无法改动 seeder 种下的行
        client().put().uri("/api/admin/ai/models/image_edit/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"sandbox","model":"sandbox-matting-v2",
                         "baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.model").isEqualTo("sandbox-matting-v2");
    }

    @Test
    @DisplayName("sandbox provider 仍被钉死在内置地址（放宽正则未开洞）")
    void sandboxStillPinnedToBuiltInAddress() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_edit","modelRole":"primary","provider":"sandbox",
                         "model":"sandbox-matting-v1","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isBadRequest();

        // 未知 provider 仍被 bean validation 拒绝
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"anthropic",
                         "model":"claude","baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange().expectStatus().isBadRequest();

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("鉴权：非 admin → 403；缺断言 → 401")
    void adminGate() {
        // 非 admin（普通 user）
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signWithRole(USER, null, null, "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://x"}
                        """)
                .exchange().expectStatus().isForbidden();
        // 缺断言
        client().get().uri("/api/admin/ai/models")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("credentialId 给法：provider/baseUrl 不传，由凭据带出并落库")
    void createsFromCredentialId() {
        String credentialId = insertCredential("it-cred-openai", "openai-compatible", "https://api.openai.com/v1");

        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary",
                         "credentialId":"%s","model":"gpt-4"}
                        """.formatted(credentialId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                // provider/baseUrl 未在请求体出现，来自凭据
                .jsonPath("$.provider").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("https://api.openai.com/v1")
                .jsonPath("$.model").isEqualTo("gpt-4");

        // 配置行确实指向那条凭据，而非隐式新建的另一条
        assertThat(credentialIdOf("text", "primary")).isEqualTo(credentialId);
        assertThat(credentialCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("credentialId 优先于请求体自填的 provider/baseUrl（凭据是真相源）")
    void credentialIdWinsOverBodyFields() {
        String credentialId = insertCredential("it-cred-qwen", "qwen", "https://dashscope.aliyuncs.com");

        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","credentialId":"%s",
                         "provider":"openai-compatible","baseUrl":"https://api.openai.com",
                         "model":"qwen-plus"}
                        """.formatted(credentialId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("qwen")
                .jsonPath("$.baseUrl").isEqualTo("https://dashscope.aliyuncs.com");
    }

    @Test
    @DisplayName("credentialId 不存在 → 400（非 404：请求体字段无效）")
    void rejectsUnknownCredentialId() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary",
                         "credentialId":"99999999-9999-9999-9999-999999999999","model":"qwen-plus"}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("credentialId 与 provider/baseUrl 都不给 → 400")
    void rejectsMissingDestination() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","model":"qwen-plus"}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("capability 白名单：image_generation（2026-08-30 入控制面）→ 201；video/拼错 → 400")
    void gatesCapabilityWhitelist() {
        // image_generation 2026-08-30 起合法（PRD §4.10 平台层：治理台配平台图像模型）
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_generation","modelRole":"primary","provider":"qwen",
                         "model":"wanx-v1","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isCreated();

        // video_generation 仍走 MiniMax 专用异步链、控制面不解析 → 拒绝建死配置
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"video_generation","modelRole":"primary","provider":"minimax",
                         "model":"video-01","baseUrl":"https://api.minimax.chat"}
                        """)
                .exchange().expectStatus().isBadRequest();

        // 拼错的能力同样被拦——否则会建出一行永不被解析的死配置
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"txet","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT 也接受 credentialId：修订后 provider/baseUrl 来自新凭据")
    void revisesFromCredentialId() {
        createPrimary();
        String credentialId = insertCredential("it-cred-put", "openai-compatible", "https://api.openai.com/v1");

        client().put().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"credentialId":"%s","model":"gpt-4"}
                        """.formatted(credentialId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.provider").isEqualTo("openai-compatible")
                .jsonPath("$.baseUrl").isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("includeDisabled：默认只回生效行；=true 时含已停用的历史版本")
    void listIncludeDisabled() {
        createPrimary();
        // 修订一次 → 旧 v1 变 disabled，新 v2 生效
        client().put().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"qwen","model":"qwen-max","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isOk();

        // 默认：只有生效的 v2
        client().get().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].version").isEqualTo(2);

        // includeDisabled：两行，生效的排在前
        client().get().uri("/api/admin/ai/models?includeDisabled=true")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].enabled").isEqualTo(true)
                .jsonPath("$[0].version").isEqualTo(2)
                .jsonPath("$[1].enabled").isEqualTo(false)
                .jsonPath("$[1].version").isEqualTo(1);
    }

    @Test
    @DisplayName("停用后恢复：enabled 回 true，且 history 落 restore 行")
    void restoresDisabledRow() {
        createPrimary();
        String id = currentId("text", "primary");
        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        client().post().uri("/api/admin/ai/models/" + id + "/restore")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.enabled").isEqualTo(true);

        assertThat(historyCountOf("restore")).isEqualTo(1L);
    }

    @Test
    @DisplayName("恢复冲突：该能力+角色已有生效行 → 409，不静默顶掉线上配置")
    void restoreConflictsWithLiveRow() {
        createPrimary();
        String oldId = currentId("text", "primary");
        // 修订产生新生效行，oldId 变 disabled
        client().put().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"qwen","model":"qwen-max","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isOk();

        client().post().uri("/api/admin/ai/models/" + oldId + "/restore")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("删除：已停用行可硬删；生效中 → 409；删后 history 仍保留审计")
    void deletesOnlyDisabledRows() {
        createPrimary();
        String id = currentId("text", "primary");

        // 生效中不可删
        client().delete().uri("/api/admin/ai/models/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isEqualTo(409);

        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        long historyBefore = historyCount();
        client().delete().uri("/api/admin/ai/models/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        // 配置行没了，但 history 一条不少（按值存快照、无 FK）
        client().get().uri("/api/admin/ai/models?includeDisabled=true")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectBody().jsonPath("$.length()").isEqualTo(0);
        assertThat(historyCount()).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("删除带并发槽位的行不被 ON DELETE RESTRICT 挡住（同事务先删槽位）")
    void deletesRowWithConcurrencySlots() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen","model":"qwen-plus",
                         "baseUrl":"https://dashscope.aliyuncs.com","maxConcurrency":4}
                        """)
                .exchange().expectStatus().isCreated();
        String id = currentId("text", "primary");
        assertThat(slotCount()).isEqualTo(4L);

        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();
        client().delete().uri("/api/admin/ai/models/" + id)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();

        assertThat(slotCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("路由不撞车：单段 {id} 与两段 {capability}/{modelRole} 各自命中")
    void singleSegmentIdDoesNotShadowTwoSegmentRoute() {
        createPrimary();
        // 两段路径仍是「停用」语义（204），不会被 {id} 抢走后当成 uuid 解析而 400
        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();
        // 单段且非 uuid → 400（Spring 解析 UUID 失败），而不是误命中别的处理器
        client().delete().uri("/api/admin/ai/models/not-a-uuid")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isBadRequest();
    }

    private String currentId(String capability, String modelRole) {
        return db.sql("SELECT id::text AS id FROM platform_model_config "
                        + "WHERE capability = :capability AND model_role = :modelRole AND enabled = true")
                .bind("capability", capability)
                .bind("modelRole", modelRole)
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .block();
    }

    private Long historyCountOf(String changeType) {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_config_history WHERE change_type = :t")
                .bind("t", changeType)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }

    private Long slotCount() {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_concurrency_slot")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }

    private void createPrimary() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isCreated();
    }

    /** 直插一条无密钥凭据（本 IT 不注入 KEK，故不能走带密钥的 admin 端点）。 */
    private String insertCredential(String name, String provider, String baseUrl) {
        return db.sql("INSERT INTO platform_provider_credential(name, provider, base_url, enabled) "
                        + "VALUES (:name, :provider, :baseUrl, true) RETURNING id::text")
                .bind("name", name)
                .bind("provider", provider)
                .bind("baseUrl", baseUrl)
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .block();
    }

    private String credentialIdOf(String capability, String modelRole) {
        return db.sql("SELECT credential_id::text AS id FROM platform_model_config "
                        + "WHERE capability = :capability AND model_role = :modelRole AND enabled = true")
                .bind("capability", capability)
                .bind("modelRole", modelRole)
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .block();
    }

    private Long credentialCount() {
        return db.sql("SELECT COUNT(*) AS n FROM platform_provider_credential")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }

    private Long historyCount() {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_config_history")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }
}
