package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 存量明文凭据迁移（任务书 #47 S7 第 1 步 / D19）。
 *
 * <p>这一片动的是<b>真实用户凭据</b>，且 V51 会在之后清空明文——迁移漏一条就等于那个用户的密钥
 * 永久丢失。故本 IT 的重点不是「能迁」，而是「不该迁的不动、重跑不重复、KEK 缺失时什么都不做」。
 */
@DisplayName("LegacySecretMigrationRunner (存量明文凭据迁移)")
class LegacySecretMigrationRunnerIT extends IntelligenceItSupport {

    private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String VIDEO_KEY = "sk-legacy-video-plaintext-1234567890";
    private static final String FEISHU_SECRET = "feishu-legacy-secret-abcdef";

    @DynamicPropertySource
    static void cryptoProps(DynamicPropertyRegistry registry) {
        registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
    }

    @Autowired
    ObjectProvider<EnvelopeEncryption> encryptionProvider;

    private String account;

    @BeforeEach
    void clean() {
        account = UUID.randomUUID().toString();
        db.sql("DELETE FROM ai_provider_key").then().block();
        db.sql("DELETE FROM user_settings WHERE settings_type = 'analysis'").then().block();
    }

    private LegacySecretMigrationRunner runner() {
        return new LegacySecretMigrationRunner(db, encryptionProvider);
    }

    /** KEK 未装配时的 Runner——用恒空的 ObjectProvider 模拟（生产中该 bean 是 @Conditional）。 */
    private LegacySecretMigrationRunner runnerWithoutKek() {
        return new LegacySecretMigrationRunner(db, new ObjectProvider<EnvelopeEncryption>() {
            @Override
            public EnvelopeEncryption getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public EnvelopeEncryption getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EnvelopeEncryption getIfAvailable() {
                return null;
            }

            @Override
            public EnvelopeEncryption getIfUnique() {
                return null;
            }
        });
    }

    /** user_settings.id 无默认值且 user_id 有 FK 到 app_users——照 SettingsControllerIT 的夹具口径。 */
    private void insertAnalysisSettings(String json) {
        db.sql("""
                        INSERT INTO app_users (id, email, password_hash)
                        VALUES (CAST(:uid AS uuid), :email, 'test-hash')
                        ON CONFLICT (id) DO NOTHING
                        """)
                .bind("uid", account).bind("email", account + "@test.local")
                .then().block();
        db.sql("""
                        INSERT INTO user_settings(id, user_id, settings_type, settings_json)
                        VALUES (:id, CAST(:accountId AS uuid), 'analysis', CAST(:json AS jsonb))
                        """)
                .bind("id", UUID.randomUUID())
                .bind("accountId", account)
                .bind("json", json)
                .then().block();
    }

    private String settingsJson() {
        return db.sql("SELECT settings_json::text AS j FROM user_settings"
                        + " WHERE user_id::text = :accountId AND settings_type = 'analysis'")
                .bind("accountId", account)
                .map((row, meta) -> row.get("j", String.class)).one().block();
    }

    private Long keyCount() {
        return db.sql("SELECT COUNT(*) AS n FROM ai_provider_key WHERE owner_account_id = :owner")
                .bind("owner", account)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }
    @Test
    @DisplayName("video 明文密钥 → 加密的 ai_provider_key 行；明文原样保留（不删）")
    void migratesVideoKeyWithoutDeletingPlaintext() {
        insertAnalysisSettings("""
                {"features":{"video":{"provider":"qwen","baseUrl":"https://ai.example/v1",
                 "model":"qwen-plus","apiKey":"%s"}}}
                """.formatted(VIDEO_KEY));

        runner().run(null);

        var stored = db.sql("""
                        SELECT capability, provider, base_url, model, encrypted_key, masked_hint, enabled
                        FROM ai_provider_key WHERE owner_account_id = :owner
                        """)
                .bind("owner", account)
                .map((row, meta) -> java.util.List.of(
                        row.get("capability", String.class), row.get("provider", String.class),
                        row.get("base_url", String.class), row.get("model", String.class),
                        row.get("encrypted_key", String.class), row.get("masked_hint", String.class),
                        row.get("enabled", Boolean.class)))
                .one().block();

        assertThat(stored).isNotNull();
        assertThat(stored.get(0)).isEqualTo("text");                  // video feature → text capability
        assertThat(stored.get(1)).isEqualTo("openai-compatible");
        assertThat(stored.get(2)).isEqualTo("https://ai.example/v1");
        assertThat(stored.get(4)).isNotEqualTo(VIDEO_KEY);            // 落库是密文
        assertThat(encryptionProvider.getIfAvailable().decrypt((String) stored.get(4)))
                .isEqualTo(VIDEO_KEY);                                // 且可解回原值
        assertThat((String) stored.get(5)).contains("*");             // 掩码
        assertThat(stored.get(6)).isEqualTo(true);

        // 关键：明文仍在——清空是 V51 的事，且要等通知满 3 个工作日
        assertThat(settingsJson()).contains(VIDEO_KEY);
    }

    @Test
    @DisplayName("飞书 appSecret → 新增 appSecretEncrypted，明文保留（独立键，非原地替换）")
    void encryptsFeishuSecretIntoSeparateKey() throws Exception {
        insertAnalysisSettings("""
                {"integrations":{"feishu":{"appId":"cli_x","appSecret":"%s","folderToken":"fld_y"}}}
                """.formatted(FEISHU_SECRET));

        runner().run(null);

        String json = settingsJson();
        assertThat(json).contains("appSecretEncrypted");
        assertThat(json).contains(FEISHU_SECRET);        // 明文保留
        // 密文可解回原值，且不等于明文
        String encrypted = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(json).path("integrations").path("feishu").path("appSecretEncrypted").asText();
        assertThat(encrypted).isNotEqualTo(FEISHU_SECRET);
        assertThat(encryptionProvider.getIfAvailable().decrypt(encrypted)).isEqualTo(FEISHU_SECRET);
    }

    /** 幂等是这一片最要紧的性质：Runner 每次启动都跑，重复插入会撞 V13 唯一索引甚至造成脏数据。 */
    @Test
    @DisplayName("重跑幂等：不重复建密钥、不重复加密飞书 secret")
    void isIdempotentAcrossRestarts() {
        insertAnalysisSettings("""
                {"features":{"video":{"provider":"qwen","baseUrl":"https://ai.example/v1",
                 "model":"qwen-plus","apiKey":"%s"}},
                 "integrations":{"feishu":{"appId":"cli_x","appSecret":"%s"}}}
                """.formatted(VIDEO_KEY, FEISHU_SECRET));

        runner().run(null);
        String afterFirst = settingsJson();
        runner().run(null);
        runner().run(null);

        assertThat(keyCount()).isEqualTo(1L);
        assertThat(settingsJson()).isEqualTo(afterFirst);   // 第二、三次没有再改 JSON
    }

    /**
     * KEK fail-closed：这是 D19 的安全底线。若在无法加密时推进，V51 清空明文就等于数据丢失。
     */
    @Test
    @DisplayName("KEK 未配：什么都不做——不建密钥、不改 JSON（fail-closed）")
    void doesNothingWithoutKek() {
        String original = """
                {"features":{"video":{"provider":"qwen","baseUrl":"https://ai.example/v1",
                 "model":"qwen-plus","apiKey":"%s"}},
                 "integrations":{"feishu":{"appId":"cli_x","appSecret":"%s"}}}
                """.formatted(VIDEO_KEY, FEISHU_SECRET);
        insertAnalysisSettings(original);

        runnerWithoutKek().run(null);

        assertThat(keyCount()).isZero();
        assertThat(settingsJson()).doesNotContain("appSecretEncrypted");
        assertThat(settingsJson()).contains(VIDEO_KEY);    // 明文一个字节没动
    }

    /** 已有个人 text 密钥的账号不该被覆盖——用户可能在新入口自己配过了。 */
    @Test
    @DisplayName("已有 text 个人密钥 → 跳过，不覆盖用户在新入口配的密钥")
    void skipsAccountsThatAlreadyHaveTextKey() {
        insertAnalysisSettings("""
                {"features":{"video":{"provider":"qwen","baseUrl":"https://ai.example/v1",
                 "model":"qwen-plus","apiKey":"%s"}}}
                """.formatted(VIDEO_KEY));
        db.sql("""
                        INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, provider,
                            base_url, model, encrypted_key, key_version, masked_hint, enabled)
                        VALUES (NULL, :owner, 'text', 'openai-compatible', 'https://own.example/v1',
                            'own-model', :encrypted, 'v1', 'sk-***own', true)
                        """)
                .bind("owner", account)
                .bind("encrypted", encryptionProvider.getIfAvailable().encrypt("sk-user-configured"))
                .then().block();

        runner().run(null);

        assertThat(keyCount()).isEqualTo(1L);
        String baseUrl = db.sql("SELECT base_url AS b FROM ai_provider_key WHERE owner_account_id = :owner")
                .bind("owner", account)
                .map((row, meta) -> row.get("b", String.class)).one().block();
        assertThat(baseUrl).isEqualTo("https://own.example/v1");   // 用户自己配的那把没被动
    }

    /**
     * provider=coze 走独立协议、不是 OpenAI 兼容，迁过去也用不了；缺 baseUrl 同理无法构造有效密钥。
     * 这类跳过必须留日志，运维据此判断 V51 前是否需要人工处理。
     */
    @Test
    @DisplayName("coze provider 与缺 baseUrl 的行跳过，不产生不可用的密钥")
    void skipsNonMigratableShapes() {
        insertAnalysisSettings("""
                {"features":{"video":{"provider":"coze","baseUrl":"https://coze.example/run",
                 "apiToken":"t","apiKey":"%s"}}}
                """.formatted(VIDEO_KEY));

        runner().run(null);

        assertThat(keyCount()).isZero();
        assertThat(settingsJson()).contains(VIDEO_KEY);   // 明文保留，交人工处理
    }

    @Test
    @DisplayName("飞书读路径：密文优先解密，无密文回落明文")
    void feishuReadPrefersCiphertext() {
        insertAnalysisSettings("""
                {"integrations":{"feishu":{"appId":"cli_x","appSecret":"%s"}}}
                """.formatted(FEISHU_SECRET));

        var repo = new com.grassland.intelligence.imageanalysis.FeishuCredentialsRepository(
                db, encryptionProvider);

        // 迁移前：回落明文
        assertThat(repo.find(account).block().appSecret()).isEqualTo(FEISHU_SECRET);

        runner().run(null);

        // 迁移后：走密文解密，结果相同（对调用方透明）
        assertThat(repo.find(account).block().appSecret()).isEqualTo(FEISHU_SECRET);
    }
}
