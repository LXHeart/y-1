package com.grassland.intelligence.ai.controlplane;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 平台模型配置启动期 seed（GL-P3-AI-001）。
 *
 * <p>首次启动（{@code platform_model_config} 空）时，从 {@code ai.qwen.*} env 落一条 {@code text/primary/qwen}
 * 配置，使 env 部署平滑过渡到 DB 控制面——否则空表会让平台 text run 一律 {@code no_platform_model} 拒绝。
 * 幂等：仅当表空时 seed 一次。admin 之后可经 CRUD 修订/禁用。
 *
 * <p>注：env 凭据类 {@code com.grassland.intelligence.ai.PlatformModelConfig} 与本包实体同名，故此处用 FQN 引用 env 类。
 */
@Component
@ConditionalOnProperty(prefix = "ai.platform-model", name = "seed-on-startup", havingValue = "true", matchIfMissing = true)
public class PlatformModelConfigSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlatformModelConfigSeeder.class);
    private static final Duration BLOCK = Duration.ofSeconds(10);

    private final PlatformModelConfigRepository repository;
    private final com.grassland.intelligence.ai.PlatformModelConfig envDefaults;
    private final TransactionalOperator transactions;
    private final SpeechProviderProperties speech;
    private final EmbeddingProviderProperties embedding;

    @Autowired
    public PlatformModelConfigSeeder(
            PlatformModelConfigRepository repository,
            com.grassland.intelligence.ai.PlatformModelConfig envDefaults,
            TransactionalOperator transactions,
            SpeechProviderProperties speech,
            EmbeddingProviderProperties embedding) {
        this.repository = repository;
        this.envDefaults = envDefaults;
        this.transactions = transactions;
        this.speech = speech;
        this.embedding = embedding;
    }

    PlatformModelConfigSeeder(
            PlatformModelConfigRepository repository,
            com.grassland.intelligence.ai.PlatformModelConfig envDefaults,
            TransactionalOperator transactions) {
        this(repository, envDefaults, transactions, null, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = repository.count().block(BLOCK);
            if (count != null && count == 0) {
                PlatformModelConfig seed = new PlatformModelConfig(
                        null, "text", PlatformModelConfig.ROLE_PRIMARY, "qwen",
                        envDefaults.model(), envDefaults.baseUrl(), null,
                        PlatformModelConfig.HEALTH_HEALTHY, true, 1, null, null, null);
                transactions.transactional(repository.create(seed, "system")).block(BLOCK);
                logger.info("Seeded platform model config text/primary from ai.qwen.* env defaults");
            }
            // 任务书 #34 / ADR-D16 D1：content_safety 深检模型**可选** seed——env 显式提供时才种
            // （AI_CONTENT_SAFETY_MODEL 缺省不配置 → 控制面无该 capability → 深检降级为仅 L1）。
            seedContentSafety();
            seedCapability(
                    "voice",
                    speech == null || speech.sandbox() ? "sandbox" : speech.provider(),
                    speech == null || speech.sandbox() ? "sandbox-speech-v1" : speech.model(),
                    speech == null || speech.sandbox() ? "https://sandbox.invalid" : speech.baseUrl());
            seedCapability(
                    "retrieval",
                    embedding == null || embedding.sandbox() ? "sandbox" : embedding.provider(),
                    embedding == null || embedding.sandbox() ? "sandbox-embedding-v1" : embedding.model(),
                    embedding == null || embedding.sandbox() ? "https://sandbox.invalid" : embedding.baseUrl());
            seedSandboxCapability("image_edit", "sandbox-matting-v1");
        } catch (Exception e) {
            // best-effort：seed 失败（启动期 DB 不可达 / 测试用占位 DB）不阻断上下文启动；
            // admin 可经 CRUD 手动配置。生产 DB 真不可达时 Flyway 等会更早失败。
            logger.warn("Platform model config seed skipped (best-effort): {}", e.getMessage());
        }
    }

    /**
     * content_safety 可选 seed：{@code ai.platform-model.content-safety.model} 提供时按 env 种一行
     * primary（默认复用 ai.qwen.* 的 base-url/api-key 经 provider 解析）。缺省不种——生产默认 L1-only，
     * 运营经 admin CRUD 配置即开 L2 深检（ADR-D16 D1 降级路径）。
     */
    private void seedContentSafety() {
        try {
            String model = System.getenv("AI_CONTENT_SAFETY_MODEL");
            if (model == null || model.isBlank()) {
                return;
            }
            boolean exists = repository.findCurrent("content_safety",
                            PlatformModelConfig.ROLE_PRIMARY)
                    .hasElement().block(BLOCK);
            if (Boolean.TRUE.equals(exists)) {
                return;
            }
            PlatformModelConfig seed = new PlatformModelConfig(
                    null, "content_safety", PlatformModelConfig.ROLE_PRIMARY, "qwen",
                    model, envDefaults.baseUrl(), null,
                    PlatformModelConfig.HEALTH_HEALTHY, true, 1, null, null, null);
            transactions.transactional(repository.create(seed, "system")).block(BLOCK);
            logger.info("Seeded platform model config content_safety/primary (model={})", model);
        } catch (Exception e) {
            logger.warn("content_safety seed skipped (best-effort): {}", e.getMessage());
        }
    }

    private void seedSandboxCapability(String capability, String model) {
        seedCapability(capability, "sandbox", model, "https://sandbox.invalid");
    }

    private void seedCapability(String capability, String provider, String model, String baseUrl) {
        try {
            PlatformModelConfig current = repository.findCurrent(
                    capability, PlatformModelConfig.ROLE_PRIMARY).block(BLOCK);
            if (current != null && !("sandbox".equals(current.provider()) && !"sandbox".equals(provider))) {
                return;
            }
            PlatformModelConfig seed = new PlatformModelConfig(
                    null, capability, PlatformModelConfig.ROLE_PRIMARY, provider,
                    model, baseUrl, null,
                    PlatformModelConfig.HEALTH_HEALTHY, true, 1, null, null, null);
            if (current == null) {
                transactions.transactional(repository.create(seed, "system")).block(BLOCK);
                logger.info("Seeded platform model config {}/primary with provider={} model={}",
                        capability, provider, model);
            } else {
                transactions.transactional(repository.revise(
                        capability, PlatformModelConfig.ROLE_PRIMARY, seed, "system"))
                        .block(BLOCK);
                logger.info("Upgraded platform model config {}/primary from Sandbox to provider={} model={}",
                        capability, provider, model);
            }
        } catch (Exception e) {
            logger.warn("{} model seed skipped (best-effort): {}", capability, e.getMessage());
        }
    }
}
