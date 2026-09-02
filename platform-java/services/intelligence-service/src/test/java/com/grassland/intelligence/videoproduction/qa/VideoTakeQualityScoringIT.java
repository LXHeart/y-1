package com.grassland.intelligence.videoproduction.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.VideoFrameExtractor;
import com.grassland.intelligence.videoproduction.VideoShot;
import com.grassland.intelligence.videoproduction.VideoShotRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboard;
import com.grassland.intelligence.videoproduction.VideoStoryboardRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #66 卡D1：候选质检评分三路（成功落分 / 解析失败放弃 / 未配置跳过）+ 推荐规则升级
 * （评分最高优先、无评分回退首个成功）+ 零积分流水断言（免费执行环 feature=null 平台资助）。
 */
@DisplayName("Take quality scoring (video_qa)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoTakeQualityScoringIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "62626262-6262-6262-6262-626262626262";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @MockitoBean
    TextCompletionClient textCompletion;

    @MockitoBean
    VideoFrameExtractor frameExtractor;

    @Autowired
    TakeQualityScoringService scoring;

    @Autowired
    VideoShotRepository shots;

    @Autowired
    VideoStoryboardRepository storyboards;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage, textCompletion, frameExtractor);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        objectStore.clear();
        Mockito.doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString()))
                .thenAnswer(invocation -> objectStore.get(invocation.getArgument(0)));
        when(frameExtractor.extract(any())).thenReturn(List.of(new byte[] { 1, 2, 3 }));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_qa'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url='https://video-qa.example.test'").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("评分成功：score/labels 落行、零积分流水；推荐=评分最高")
    void scoringPersistsAndRecommendationPicksHighest() {
        seedQaCapability();
        when(textCompletion.completeMessages(anyString(), anyString(), any(), anyString(),
                anyList(), anyInt(), anyBoolean(), any()))
                .thenReturn(Mono.just(new TextCompletionResult(
                        "```json\n{\"score\":85,\"labels\":[\"画质偏低\"]}\n```", 10, 5)));

        Seeded seeded = seedTaskWithTakes();
        driveScoring(seeded);

        Map<String, Object> row = takeRow(seeded.take1());
        assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(85.0);
        assertThat(String.valueOf(row.get("score_labels"))).contains("画质偏低");
        verify(credits, never()).consume(anyString(), any(), anyString());

        // 推荐规则：take1=85 分、take2 无评分 → 不换；反向（take2 高分）→ 换选
        db.sql("UPDATE video_shot_take SET score=90 WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.take2().toString()).then().block(Duration.ofSeconds(5));
        client().post().uri("/api/video-production/tasks/{id}/takes/select", seeded.taskId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isOk();
        String selection = db.sql("SELECT selection::text AS s FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.taskId().toString())
                .map(r -> r.get("s", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(selection).contains(seeded.take2().toString());
    }

    @Test
    @DisplayName("解析失败：放弃评分仅日志，行上无分；推荐回退首个成功")
    void unparseableOutputSkipsScoring() {
        seedQaCapability();
        when(textCompletion.completeMessages(anyString(), anyString(), any(), anyString(),
                anyList(), anyInt(), anyBoolean(), any()))
                .thenReturn(Mono.just(new TextCompletionResult("模型闲聊不是 JSON", 8, 3)));

        Seeded seeded = seedTaskWithTakes();
        driveScoring(seeded);

        Map<String, Object> row = takeRow(seeded.take1());
        assertThat(row.get("score")).isNull();
        verify(credits, never()).consume(anyString(), any(), anyString());

        client().post().uri("/api/video-production/tasks/{id}/takes/select", seeded.taskId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("useRecommended", true))
                .exchange().expectStatus().isOk();
        String selection = db.sql("SELECT selection::text AS s FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.taskId().toString())
                .map(r -> r.get("s", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(selection).contains(seeded.take1().toString());
    }

    @Test
    @DisplayName("未配置 video_qa：静默跳过，不调模型不留流水")
    void missingCapabilitySkipsScoring() {
        when(textCompletion.completeMessages(anyString(), anyString(), any(), anyString(),
                anyList(), anyInt(), anyBoolean(), any()))
                .thenReturn(Mono.just(new TextCompletionResult("{}", 1, 1)));

        Seeded seeded = seedTaskWithTakes();
        driveScoring(seeded);

        assertThat(takeRow(seeded.take1()).get("score")).isNull();
        verify(textCompletion, never()).completeMessages(anyString(), anyString(), any(),
                anyString(), anyList(), anyInt(), anyBoolean(), any());
        verify(credits, never()).consume(anyString(), any(), anyString());
    }

    // ---------------- helpers ----------------

    private void driveScoring(Seeded seeded) {
        VideoShot shot = shots.findById(seeded.shot1()).block(Duration.ofSeconds(5));
        VideoStoryboard storyboard = storyboards.findById(seeded.storyboardId())
                .block(Duration.ofSeconds(5));
        scoring.scoreOnce(seeded.take1(), seeded.take1Media(), shot, storyboard)
                .block(Duration.ofSeconds(20));
    }

    private record Seeded(UUID taskId, UUID storyboardId, UUID shot1, UUID take1, UUID take1Media,
            UUID take2) {}

    private Seeded seedTaskWithTakes() {
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 20, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .bind("payload", "{\"images\":[\"data:image/jpeg;base64,AAAA\"],\"shopName\":\"店\"}")
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        UUID shot1 = UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt, status)
                        VALUES (CAST(:sb AS uuid), 1, '画面', '旁白', 5, '固定机位', 1, 'p', 'ready')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString())
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        UUID taskId = UUID.randomUUID();
        db.sql("""
                        INSERT INTO video_production_task(id, storyboard_id, account_id, operation_id,
                            mode, phase, progress, target_duration_seconds, pricing_version,
                            unit_price_cents, estimated_cost_cents, provider, model)
                        VALUES (CAST(:id AS uuid), CAST(:sb AS uuid), :account, :operation,
                            'video', 'generating', 60, 20, 'v1', 1, 20, 'sandbox', 'm')
                        """)
                .bind("id", taskId.toString())
                .bind("sb", storyboardId.toString())
                .bind("account", ACCOUNT)
                .bind("operation", "qa-it-" + taskId)
                .then().block(Duration.ofSeconds(5));
        UUID take1Media = UUID.randomUUID();
        UUID take1 = seedTake(shot1, 1, take1Media);
        UUID take2 = seedTake(shot1, 2, UUID.randomUUID());
        return new Seeded(taskId, storyboardId, shot1, take1, take1Media, take2);
    }

    private UUID seedTake(UUID shotId, int takeNo, UUID mediaId) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot_take(shot_id, take_no, provider, model, status,
                            attempts, media_id)
                        VALUES (CAST(:shot AS uuid), :no, 'sandbox', 'm', 'succeeded', 1,
                            CAST(:media AS uuid)) RETURNING id::text
                        """)
                .bind("shot", shotId.toString())
                .bind("no", takeNo)
                .bind("media", mediaId.toString())
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private void seedQaCapability() {
        db.sql("""
                        WITH cred AS (
                            INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                            VALUES ('qa-scoring-it', 'sandbox', 'https://video-qa.example.test', true)
                            RETURNING id, base_url
                        )
                        INSERT INTO platform_model_config(capability, model_role, provider, model,
                            base_url, health_status, enabled, version, credential_id)
                        SELECT 'video_qa', 'primary', 'sandbox', 'sandbox-qa-v1', cred.base_url,
                            'healthy', true, 1, cred.id
                        FROM cred
                        """)
                .then().block(Duration.ofSeconds(10));
    }

    private Map<String, Object> takeRow(UUID takeId) {
        return db.sql("SELECT score::float8 AS score, score_labels::text AS score_labels "
                        + "FROM video_shot_take WHERE id=CAST(:id AS uuid)")
                .bind("id", takeId.toString())
                .map(row -> {
                    Map<String, Object> values = new java.util.HashMap<>();
                    values.put("score", row.get("score", Double.class));
                    values.put("score_labels", row.get("score_labels", String.class));
                    return values;
                })
                .one().block(Duration.ofSeconds(5));
    }
}
