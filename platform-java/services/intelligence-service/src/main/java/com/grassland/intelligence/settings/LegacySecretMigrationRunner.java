package com.grassland.intelligence.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

/**
 * 存量明文凭据迁移（任务书 #47 S7 第 1 步 / D19）。
 *
 * <p>{@code user_settings(settings_type='analysis')} 的 JSON 里存着明文密钥。本 Runner 把它们迁到
 * 信封加密的载体，<b>但不删任何明文</b>——清空是 V51 的事，且要等站内通知发出满 3 个工作日。
 * 两件事：
 * <ul>
 *   <li>{@code features.video.apiKey} → 信封加密的 {@code ai_provider_key} 行（capability=text）。
 *       它是 {@code AnalysisByokResolver} 唯一真有消费者的 feature
 *       （{@code VideoRecreationAdaptationService:78}）。</li>
 *   <li>{@code integrations.feishu.appSecret} → 同一 JSON 内新增 {@code appSecretEncrypted}。
 *       <b>刻意用独立键而非原地替换</b>：原地加密后读路径无法区分「已加密」与「还是明文」，
 *       试解密再回落是启发式的、不可靠。独立键让读路径优先取密文、回落明文，语义无歧义。</li>
 * </ul>
 *
 * <p>为什么不是 Flyway：加密需要 KEK 与 Java，纯 SQL 做不到。先例是
 * {@code PlatformModelConfigSeeder}（同为启动期 best-effort {@code ApplicationRunner} +
 * {@code @ConditionalOnProperty} 开关）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>幂等</b>：video 部分按「该账号已有 enabled 的 text 密钥」跳过；feishu 部分按
 *       「已有 appSecretEncrypted」跳过。可安全重跑，重启不产生重复行。</li>
 *   <li><b>KEK fail-closed</b>：KEK 未配时整体跳过，<b>既不迁移也不改动任何数据</b>。绝不能在无法
 *       加密的情况下推进——那会让 V51 清空时数据丢失。</li>
 *   <li><b>可观测</b>：逐条记 accountId 与结果，汇总记计数；<b>绝不记密钥值</b>（连掩码也不入日志，
 *       掩码只写 DB 的 masked_hint 列）。</li>
 * </ol>
 *
 * <p>SQL 直接写在本类而不建 repository：这是一次性迁移，其扫描查询不应沉淀成长期 API。
 * V51 上线并确认无残留后，本类连同开关一并删除。
 */
@Component
@ConditionalOnProperty(
        prefix = "settings.legacy-secret-migration", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LegacySecretMigrationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LegacySecretMigrationRunner.class);
    /** 与 Seeder 同口径：启动期不无限等待 DB。 */
    private static final Duration BLOCK = Duration.ofSeconds(30);

    private final DatabaseClient db;
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacySecretMigrationRunner(
            DatabaseClient db, ObjectProvider<EnvelopeEncryption> encryptionProvider) {
        this.db = db;
        this.encryptionProvider = encryptionProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
        if (crypto == null) {
            // fail-closed：不迁移也不改动。V51 的前置校验会因此不通过，从而阻止清空明文。
            logger.warn("Legacy secret migration skipped: CRYPTO_KEK_BASE64 not configured."
                    + " Plaintext secrets remain untouched; do NOT run V51 until this succeeds.");
            return;
        }
        try {
            MigrationReport report = new MigrationReport();
            for (LegacyRow row : loadCandidates()) {
                migrateVideoKey(crypto, row, report);
                migrateFeishuSecret(crypto, row, report);
            }
            logger.info("Legacy secret migration done: videoMigrated={} videoSkipped={}"
                            + " feishuMigrated={} feishuSkipped={} failed={}",
                    report.videoMigrated, report.videoSkipped,
                    report.feishuMigrated, report.feishuSkipped, report.failed);
        } catch (Exception e) {
            // best-effort：不阻断启动（同 Seeder）。失败即 V51 前置校验不通过，明文仍在，可重跑。
            logger.warn("Legacy secret migration aborted (plaintext untouched, safe to retry): {}",
                    e.getMessage());
        }
    }
    /** 候选行：含明文 video 密钥或明文飞书 appSecret 的 analysis 设置。 */
    private List<LegacyRow> loadCandidates() {
        List<LegacyRow> rows = db.sql("""
                        SELECT user_id::text AS account_id, settings_json::text AS settings_json
                        FROM user_settings
                        WHERE settings_type = 'analysis'
                          AND (settings_json->'features'->'video'->>'apiKey' IS NOT NULL
                            OR settings_json->'integrations'->'feishu'->>'appSecret' IS NOT NULL)
                        """)
                .map((r, meta) -> new LegacyRow(
                        r.get("account_id", String.class), r.get("settings_json", String.class)))
                .all()
                .collectList()
                .block(BLOCK);
        return rows == null ? List.of() : rows;
    }

    /**
     * {@code features.video.apiKey} → {@code ai_provider_key}（capability=text）。
     *
     * <p>幂等键是「该账号已有 enabled 的 text 个人密钥」——V13 的部分唯一索引也拦重复，但先查再插
     * 能给出准确的 skipped 计数，运维据此判断迁移是否真的覆盖了所有人。
     */
    private void migrateVideoKey(EnvelopeEncryption crypto, LegacyRow row, MigrationReport report) {
        try {
            JsonNode video = mapper.readTree(row.settingsJson()).path("features").path("video");
            String apiKey = text(video, "apiKey");
            if (apiKey == null) {
                return;
            }
            String baseUrl = text(video, "baseUrl");
            String model = text(video, "model");
            // provider=coze 走独立协议，不是 OpenAI 兼容，迁过去也用不了（AnalysisByokResolver 注释）
            String provider = text(video, "provider");
            if (baseUrl == null || "coze".equalsIgnoreCase(provider)) {
                logger.info("Legacy video key not migratable (account={} provider={} hasBaseUrl={})",
                        row.accountId(), provider, baseUrl != null);
                report.videoSkipped++;
                return;
            }
            Long existing = db.sql("""
                            SELECT COUNT(*) AS n FROM ai_provider_key
                            WHERE owner_account_id = :owner AND organization_id IS NULL
                              AND capability = 'text' AND enabled = true
                            """)
                    .bind("owner", row.accountId())
                    .map((r, meta) -> r.get("n", Long.class))
                    .one().block(BLOCK);
            if (existing != null && existing > 0) {
                report.videoSkipped++;
                return;
            }
            String encrypted = crypto.encrypt(apiKey);
            db.sql("""
                            INSERT INTO ai_provider_key(
                                organization_id, owner_account_id, capability, provider, base_url, model,
                                encrypted_key, key_version, masked_hint, enabled)
                            VALUES (
                                NULL, :owner, 'text', 'openai-compatible', :baseUrl, :model,
                                :encrypted, :keyVersion, :maskedHint, true)
                            """)
                    .bind("owner", row.accountId())
                    .bind("baseUrl", baseUrl)
                    .bind("model", model == null ? "" : model)
                    .bind("encrypted", encrypted)
                    .bind("keyVersion", crypto.keyVersion(encrypted))
                    .bind("maskedHint", MaskedKey.mask(apiKey))
                    .then().block(BLOCK);
            logger.info("Legacy video key migrated to ai_provider_key (account={})", row.accountId());
            report.videoMigrated++;
        } catch (Exception e) {
            logger.warn("Legacy video key migration failed (account={}): {}", row.accountId(), e.getMessage());
            report.failed++;
        }
    }

    /**
     * {@code integrations.feishu.appSecret} → 同 JSON 新增 {@code appSecretEncrypted}，明文保留。
     *
     * <p>只写新键、不动明文：本步骤之后读路径优先用密文，若迁移有遗漏仍可回落明文，
     * 直到 V51 确认无残留才清空。
     */
    private void migrateFeishuSecret(EnvelopeEncryption crypto, LegacyRow row, MigrationReport report) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(row.settingsJson());
            JsonNode integrations = root.path("integrations");
            if (!(integrations instanceof ObjectNode integrationsNode)) {
                return;
            }
            JsonNode feishu = integrationsNode.path("feishu");
            if (!(feishu instanceof ObjectNode feishuNode)) {
                return;
            }
            String appSecret = text(feishuNode, "appSecret");
            if (appSecret == null) {
                return;
            }
            if (text(feishuNode, "appSecretEncrypted") != null) {
                report.feishuSkipped++;   // 已迁移，重跑安全
                return;
            }
            feishuNode.put("appSecretEncrypted", crypto.encrypt(appSecret));
            db.sql("""
                            UPDATE user_settings SET settings_json = CAST(:json AS jsonb), updated_at = now()
                            WHERE user_id::text = :accountId AND settings_type = 'analysis'
                            """)
                    .bind("json", mapper.writeValueAsString(root))
                    .bind("accountId", row.accountId())
                    .then().block(BLOCK);
            logger.info("Legacy feishu appSecret encrypted alongside plaintext (account={})", row.accountId());
            report.feishuMigrated++;
        } catch (Exception e) {
            logger.warn("Legacy feishu secret migration failed (account={}): {}",
                    row.accountId(), e.getMessage());
            report.failed++;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private record LegacyRow(String accountId, String settingsJson) {
    }

    /** 计数器；只统计条数，不持有任何密钥内容。 */
    private static final class MigrationReport {
        private int videoMigrated;
        private int videoSkipped;
        private int feishuMigrated;
        private int feishuSkipped;
        private int failed;
    }
}
