package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #56：图像 BYOK 执行消费。独立与任务生图均按活动身份路由——BYOK 命中（ai_run 留痕、
 * 零积分、端点来自密钥）、组织策略禁回退拒绝、密钥轮换后任务模式 409 fail-closed。
 * 任务书 #58 决策 G：平台路径=控制面行+凭据（无行/无凭据密钥均 fail-closed，静态 env 回落已删）。
 */
@DisplayName("图像 BYOK 执行消费（任务书 #56）")
class ImageByokIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "52525252-5252-5252-5252-525252525252";
    private static final String ORG = "53535353-5353-5353-5353-535353535353";
    private static final String BYOK_PROVIDER = "my-image-provider";
    private static final String BYOK_BASE_URL = "https://byok-image.example/v1";
    private static final String BYOK_MODEL = "byok-image-model";
    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @org.springframework.test.context.DynamicPropertySource
    static void byokProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // BYOK 密钥真实解密路径需要 KEK（AiRunControllerIT 同款测试 KEK）
        registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
    }

    @MockitoBean
    ImageGenerationClient generation;

    @MockitoBean
    com.grassland.intelligence.credits.CreditsClient credits;

    @Autowired
    ObjectProvider<EnvelopeEncryption> encryptionProvider;

    @Autowired
    ImageGenerationConfig runtimeConfig;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        reset(generation, credits);
        when(generation.generate(anyString(), anyString(), any())).thenReturn(Mono.just(new GeneratedImage(
                null, Base64.getEncoder().encodeToString("png-bytes".getBytes()), "风格锚")));
        for (String table : new String[] {
                "ai_provider_key", "ai_provider_preference", "ai_org_byok_policy",
                "platform_model_concurrency_slot", "platform_model_config",
                "intelligence_outbox", "ai_credit_compensation", "ai_run", "ai_model_budget",
                "creation_context_snapshot"}) {
            db.sql("DELETE FROM " + table).then().block();
        }
    }

    @Test
    @DisplayName("个人图像 BYOK 键：独立生图命中密钥端点，零积分，ai_run 按 0 成本留痕")
    void personalByokKeyDrivesIndependentGeneration() {
        seedPersonalKey(ACCOUNT, "sk-personal-image");

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "一张门店封面", "size", "1024x1024"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.imageUrl").value(
                        value -> assertThat(String.valueOf(value))
                                .startsWith("/api/article-generation/generated-images/"));

        ImageGenerationClient.Endpoint endpoint = capturedEndpoint();
        assertThat(endpoint.baseUrl()).isEqualTo(BYOK_BASE_URL);
        assertThat(endpoint.apiKey()).isEqualTo("sk-personal-image");
        assertThat(endpoint.model()).isEqualTo(BYOK_MODEL);

        Map<String, Object> audit = latestRun();
        assertThat(audit).containsEntry("provider", BYOK_PROVIDER)
                .containsEntry("model", BYOK_MODEL)
                .containsEntry("status", "completed")
                .containsEntry("cost", 0);
        verifyNoInteractions(credits);
    }

    @Test
    @DisplayName("组织图像 BYOK 键：商家身份独立生图命中，ai_run 记录组织归属")
    void orgByokKeyDrivesMerchantGeneration() {
        seedOrgKey(ORG, ACCOUNT, "sk-org-image");

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", signWithOrg(ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "组织键出图", "size", "1024x1024"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true);

        ImageGenerationClient.Endpoint endpoint = capturedEndpoint();
        assertThat(endpoint.apiKey()).isEqualTo("sk-org-image");
        Map<String, Object> audit = latestRun();
        assertThat(audit).containsEntry("provider", BYOK_PROVIDER)
                .containsEntry("cost", 0)
                .containsEntry("byok_org", ORG);
    }

    @Test
    @DisplayName("组织配键且回退策略禁止：图像请求被拒，不静默走平台")
    void orgPolicyDenyRejectsImageGeneration() {
        seedOrgKey(ORG, ACCOUNT, "sk-org-text"); // 组织有键（capability=image_generation）
        db.sql("INSERT INTO ai_org_byok_policy(organization_id, allow_platform_fallback, updated_by_account_id) "
                + "VALUES (:org, false, :account)")
                .bind("org", ORG).bind("account", ACCOUNT).then().block();
        // 该组织没有 image_generation 键 → 回退被策略拒绝
        db.sql("UPDATE ai_provider_key SET capability = 'text' WHERE organization_id = :org")
                .bind("org", ORG).then().block();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", signWithOrg(ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "应被拒绝", "size", "1024x1024"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.success").isEqualTo(false);

        verify(generation, org.mockito.Mockito.never()).generate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("治理台控制面 image_generation 行+凭据：独立生图用治理台模型与凭据密钥")
    void controlPlanePlatformModelDrivesGeneration() {
        String encryptedKey = encryptionProvider.getIfAvailable().encrypt("sk-cp-image-key");
        db.sql("""
                        WITH cred AS (
                            INSERT INTO platform_provider_credential(name, provider, base_url,
                                encrypted_key, key_version, masked_hint, enabled)
                            VALUES ('cp-image', 'openai-compatible', 'https://cp-image.example/v1',
                                :encryptedKey, 'v1', 'sk-***cp', true)
                            RETURNING id
                        )
                        INSERT INTO platform_model_config(capability, model_role, provider, model,
                            base_url, max_concurrency, health_status, enabled, version, credential_id)
                        SELECT 'image_generation','primary','cp-image','cp-image-model',
                            'https://cp-image.example/v1', 1, 'healthy', true, 1, cred.id
                        FROM cred
                        """)
                .bind("encryptedKey", encryptedKey).then().block();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "治理台模型出图", "size", "1024x1024"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true);

        ImageGenerationClient.Endpoint endpoint = capturedEndpoint();
        assertThat(endpoint.baseUrl()).isEqualTo("https://cp-image.example/v1");
        assertThat(endpoint.model()).isEqualTo("cp-image-model");
        // 决策 E/G：凭据密钥经执行环解密下传，env key 兜底已删
        assertThat(endpoint.apiKey()).isEqualTo("sk-cp-image-key");
        Map<String, Object> audit = latestRun();
        assertThat(audit).containsEntry("provider", "cp-image")
                .containsEntry("model", "cp-image-model")
                .containsEntry("cost", runtimeConfig.unitPriceCents());
    }

    @Test
    @DisplayName("任务书 #58：控制面无行且无 BYOK → 503 no_platform_model（静态 env 回落已删）")
    void noPlatformRowFailsClosed() {
        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "无平台模型应拒绝", "size", "1024x1792"))
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.success").isEqualTo(false);

        verify(generation, org.mockito.Mockito.never()).generate(anyString(), anyString(), any());
        verifyNoInteractions(credits);
    }

    @Test
    @DisplayName("任务书 #58 决策 E：控制面行无凭据密钥 → 503 平台凭据缺失（不回落 env key）")
    void platformRowWithoutCredentialKeyFailsClosed() {
        db.sql("""
                        INSERT INTO platform_model_config(capability, model_role, provider, model,
                            base_url, max_concurrency, health_status, enabled, version)
                        VALUES ('image_generation','primary','cp-image','cp-image-model',
                            'https://cp-image.example/v1', 1, 'healthy', true, 1)
                        """).then().block();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "无凭据密钥应拒绝", "size", "1024x1024"))
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.success").isEqualTo(false);

        verify(generation, org.mockito.Mockito.never()).generate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("任务模式 BYOK：快照冻结命中出图；密钥轮换后 409 fail-closed")
    void taskModeByokFreezesAndFailsClosedOnRotation() throws Exception {
        String keyId = seedPersonalKey(ACCOUNT, "sk-task-image");
        String updatedAt = db.sql("SELECT updated_at FROM ai_provider_key WHERE id=CAST(:id AS uuid)")
                .bind("id", keyId).map(row -> row.get("updated_at", OffsetDateTime.class))
                .one().block().toInstant().toString();
        String snapshotId = seedByokSnapshot(keyId, updatedAt);

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "prompt", "任务出图", "size", "1024x1024",
                        "taskMode", true, "contextSnapshotId", snapshotId,
                        "targetPlatform", "xiaohongshu"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true);

        ImageGenerationClient.Endpoint endpoint = capturedEndpoint();
        assertThat(endpoint.apiKey()).isEqualTo("sk-task-image");
        Map<String, Object> audit = latestRun();
        assertThat(audit).containsEntry("provider", BYOK_PROVIDER)
                .containsEntry("cost", 0)
                .containsEntry("snapshot", snapshotId);

        db.sql("UPDATE ai_provider_key SET key_version = 'v2', encrypted_key = 'rotated', updated_at = now() "
                + "WHERE id = CAST(:id AS uuid)").bind("id", keyId).then().block();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "prompt", "轮换后应拒绝", "size", "1024x1024",
                        "taskMode", true, "contextSnapshotId", snapshotId,
                        "targetPlatform", "xiaohongshu"))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    @DisplayName("平台预算闸：组织单次成本上限不足时 402，独立生图不再免费无闸")
    void platformRunGatedByOrgBudget() {
        // 任务书 #58：先种平台行+凭据（无行会先 503 no_platform_model，轮不到预算闸）
        String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-budget-image-key");
        db.sql("""
                        WITH cred AS (
                            INSERT INTO platform_provider_credential(name, provider, base_url,
                                encrypted_key, key_version, masked_hint, enabled)
                            VALUES ('budget-image', 'qwen', 'https://budget-image.example/v1',
                                :encrypted, 'v1', 'sk-***budget', true)
                            RETURNING id
                        )
                        INSERT INTO platform_model_config(capability, model_role, provider, model,
                            base_url, max_concurrency, health_status, enabled, version, credential_id)
                        SELECT 'image_generation','primary','qwen','wanx-v1','https://budget-image.example/v1',
                            1,'healthy',true,1,cred.id
                        FROM cred
                        """)
                .bind("encrypted", encrypted).then().block();
        db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_cents_per_run, enabled) "
                + "VALUES (:org, 'image_generation', 'platform', 1, true)")
                .bind("org", ORG).then().block();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", signWithOrg(ACCOUNT, ORG))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "预算内应拒绝", "size", "1024x1024"))
                .exchange().expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.success").isEqualTo(false);

        verify(generation, org.mockito.Mockito.never()).generate(anyString(), anyString(), any());
    }

    private ImageGenerationClient.Endpoint capturedEndpoint() {
        ArgumentCaptor<ImageGenerationClient.Endpoint> captor =
                ArgumentCaptor.forClass(ImageGenerationClient.Endpoint.class);
        verify(generation).generate(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private Map<String, Object> latestRun() {
        return db.sql("""
                        SELECT provider, model, status, actual_cents AS cost,
                               context_snapshot_id::text AS snapshot,
                               byok_organization_id AS byok_org
                        FROM ai_run ORDER BY started_at DESC LIMIT 1
                        """)
                .<Map<String, Object>>map((row, metadata) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("provider", row.get("provider", String.class));
                    values.put("model", row.get("model", String.class));
                    values.put("status", row.get("status", String.class));
                    values.put("cost", row.get("cost", Integer.class));
                    values.put("snapshot", row.get("snapshot", String.class));
                    values.put("byok_org", row.get("byok_org", String.class));
                    return values;
                }).one().block();
    }

    private String seedPersonalKey(String owner, String plaintext) {
        return seedKey(null, owner, plaintext);
    }

    private String seedOrgKey(String org, String owner, String plaintext) {
        return seedKey(org, owner, plaintext);
    }

    private String seedKey(String org, String owner, String plaintext) {
        String encrypted = encryptionProvider.getIfAvailable().encrypt(plaintext);
        // R2DBC 对 null bind 无法推断类型：个人键直接省略 organization_id 列（默认 NULL）
        if (org == null) {
            return db.sql("""
                            INSERT INTO ai_provider_key(owner_account_id, capability, provider,
                                base_url, model, encrypted_key, key_version, masked_hint, enabled)
                            VALUES (:owner, 'image_generation', :provider, :baseUrl, :model,
                                :encrypted, 'v1', 'sk-***byok', true)
                            RETURNING id::text
                            """)
                    .bind("owner", owner)
                    .bind("provider", BYOK_PROVIDER).bind("baseUrl", BYOK_BASE_URL)
                    .bind("model", BYOK_MODEL).bind("encrypted", encrypted)
                    .map(row -> row.get("id", String.class)).one().block();
        }
        return db.sql("""
                        INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, provider,
                            base_url, model, encrypted_key, key_version, masked_hint, enabled)
                        VALUES (:org, :owner, 'image_generation', :provider, :baseUrl, :model,
                            :encrypted, 'v1', 'sk-***byok', true)
                        RETURNING id::text
                        """)
                .bind("org", org).bind("owner", owner)
                .bind("provider", BYOK_PROVIDER).bind("baseUrl", BYOK_BASE_URL)
                .bind("model", BYOK_MODEL).bind("encrypted", encrypted)
                .map(row -> row.get("id", String.class)).one().block();
    }

    private String seedByokSnapshot(String keyId, String updatedAt) {
        return db.sql("""
                        INSERT INTO creation_context_snapshot(
                            account_id, organization_id, task_id, application_id, task_version,
                            platform_id, content_form_id, task_snapshot, platform_rules_snapshot,
                            material_snapshot, ai_config_snapshot)
                        VALUES (:account, NULL, :task, :application, 3, 'xiaohongshu', 'graphic',
                            '{"title":"图卡任务","requirements":{"mustInclude":["必须含门店招牌"]}}'::jsonb,
                            '{"version":"2026-08-30"}'::jsonb,
                            '{"items":[]}'::jsonb,
                            jsonb_build_object(
                                'resolutionType','PLATFORM','status','unavailable',
                                'imageGeneration', jsonb_build_object(
                                    'resolutionType','BYOK','configId',CAST(:keyId AS uuid),
                                    'provider',:provider,'model',:model,
                                    'keyVersion','v1','configUpdatedAt',:updatedAt)))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .bind("task", UUID.randomUUID().toString())
                .bind("application", UUID.randomUUID().toString())
                .bind("keyId", keyId)
                .bind("provider", BYOK_PROVIDER)
                .bind("model", BYOK_MODEL)
                .bind("updatedAt", updatedAt)
                .map(row -> row.get("id", String.class)).one().block();
    }
}
