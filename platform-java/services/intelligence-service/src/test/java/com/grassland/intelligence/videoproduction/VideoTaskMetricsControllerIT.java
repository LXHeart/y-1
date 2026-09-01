package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #65 卡7：治理台视频任务监控指标——权限（未登录 401 / 非 admin 403）与数值
 * （造数断言成功率/供应商分布/降级与重抽口径）。
 */
@DisplayName("Video task metrics admin endpoint")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoTaskMetricsControllerIT extends IntelligenceItSupport {

    private static final String ADMIN = "95959595-9595-9595-9595-959595959595";
    private static final String USER = "96969696-9696-9696-9696-969696969696";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoTaskMetricsService metricsService;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("权限：未登录 401；普通用户 403；admin 200")
    void permissionGates() {
        client().get().uri("/api/admin/video-production/metrics?window=7d")
                .exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/admin/video-production/metrics?window=7d")
                .header("X-Grassland-Identity", sign(USER, "recommender"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/video-production/metrics?window=7d")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.window").isEqualTo("7d");
    }

    @Test
    @DisplayName("数值：造数断言任务数/成功率/供应商分布/降级与重抽口径")
    void metricValuesMatchSeed() {
        // 3 个任务：sandbox 成功（15s 价 1 分/秒 → 收入 15）、seedance 失败、sandbox slideshow 成功重抽过
        UUID storyboardA = seedStoryboard("sb-a");
        UUID storyboardB = seedStoryboard("sb-b");
        UUID storyboardC = seedStoryboard("sb-c");
        UUID runA = seedRun("sandbox", 8);
        seedTask(storyboardA, "op-m1", "video", "succeeded", "sandbox", 15, 1, runA, 0, true);
        seedTask(storyboardB, "op-m2", "video", "failed", "seedance", null, null, null, 0, false);
        seedTask(storyboardC, "op-m3", "slideshow", "succeeded", "sandbox", 14, 1, null, 1, true);
        // B 的候选带 attempts>1（重试口径）；A 无 succeeded 配音（noVoice 口径）
        seedShotWithTake(storyboardB, 1, 2);

        client().get().uri("/api/admin/video-production/metrics?window=7d")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.taskCount").isEqualTo(3)
                .jsonPath("$.data.successRate").isEqualTo(0.6667)
                .jsonPath("$.data.cancelRate").isEqualTo(0.0)
                .jsonPath("$.data.costVsRevenue.costCents").isEqualTo(8)
                .jsonPath("$.data.costVsRevenue.revenueCents").isEqualTo(29)
                .jsonPath("$.data.degraded.slideshowRatio").isEqualTo(0.3333)
                .jsonPath("$.data.degraded.noVoiceRatio").isEqualTo(1.0)
                .jsonPath("$.data.retryRatio").isEqualTo(0.3333)
                .jsonPath("$.data.rerollRatio").isEqualTo(0.3333)
                .jsonPath("$.data.providers.length()").isEqualTo(2);

        // 窗口切换：30d 与 7d 同造数下口径一致（窗口只影响 created_at 过滤）
        client().get().uri("/api/admin/video-production/metrics?window=30d")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.window").isEqualTo("30d")
                .jsonPath("$.data.taskCount").isEqualTo(3);

        // 服务层直呼断言 providers 行内容（WebTestClient jsonPath 取嵌套数组元素较冗长）
        var metrics = metricsService.metrics("7d").block(Duration.ofSeconds(10));
        assertThat(metrics).isNotNull();
        var providers = (java.util.List<?>) metrics.get("providers");
        assertThat(providers).hasSize(2);
    }

    // ---------------- helpers ----------------

    private UUID seedStoryboard(String payloadTag) {
        String payload = "{\"images\":[\"data:image/png;base64,QUFB\"],\"shopName\":\"" + payloadTag + "\"}";
        return UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 15, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ADMIN).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedRun(String provider, int actualCents) {
        return UUID.fromString(db.sql("""
                        INSERT INTO ai_run(account_id, capability, provider, model, run_type,
                            budget_cents, actual_cents, status, operation_id)
                        VALUES (:account, 'video_generation', :provider, 'm', 'async', 10, :actual,
                            'completed', gen_random_uuid()) RETURNING id::text
                        """)
                .bind("account", ADMIN).bind("provider", provider).bind("actual", actualCents)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private void seedTask(UUID storyboardId, String operationId, String mode, String phase, String provider,
            Integer actualSeconds, Integer unitPrice, UUID runId, int recomposeSeq, boolean completed) {
        db.sql("""
                        INSERT INTO video_production_task(storyboard_id, account_id, operation_id, mode, phase,
                            progress, target_duration_seconds, pricing_version, unit_price_cents,
                            estimated_cost_cents, actual_cost_cents, actual_duration_seconds, provider, model,
                            run_id, recompose_seq, completed_at)
                        VALUES (CAST(:sb AS uuid), :account, :operation, :mode, :phase, 100, 15, 'v1',
                            COALESCE(:unit, 1), COALESCE(:unit, 1) * 15, :actualCost, :actualSeconds,
                            :provider, 'm', CAST(:run AS uuid), :recompose,
                            CASE WHEN :completed THEN now() ELSE NULL END)
                        """)
                .bind("sb", storyboardId.toString())
                .bind("account", ADMIN)
                .bind("operation", operationId)
                .bind("mode", mode)
                .bind("phase", phase)
                .bind("unit", nullable(unitPrice, Integer.class))
                .bind("actualCost", nullable(actualSeconds == null ? null : actualSeconds * (unitPrice == null ? 1 : unitPrice), Integer.class))
                .bind("actualSeconds", nullable(actualSeconds, Integer.class))
                .bind("provider", provider)
                .bind("run", nullable(runId == null ? null : runId.toString(), String.class))
                .bind("recompose", recomposeSeq)
                .bind("completed", completed)
                .then().block(Duration.ofSeconds(5));
    }

    /** 带 attempts>1 候选的镜头（重试口径）。 */
    private void seedShotWithTake(UUID storyboardId, int seq, int attempts) {
        UUID shotId = UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, 'v', 'n', 5, '固定机位', 0, 'p') RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        db.sql("""
                        INSERT INTO video_shot_take(shot_id, take_no, provider, model, status, attempts)
                        VALUES (CAST(:shot AS uuid), 1, 'seedance', 'm', 'failed', :attempts)
                        """)
                .bind("shot", shotId.toString()).bind("attempts", attempts)
                .then().block(Duration.ofSeconds(5));
    }
}
