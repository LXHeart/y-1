package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 任务书 #66 C3 后端：分镜只读详情（画布数据源，附 grouping）+ 分组分支 PATCH 契约
 * （§3：归属校验/分支≥1/仅编辑期 draft）+ 镜头内容编辑 PUT（同快速模式字段）。
 */
@DisplayName("Storyboard canvas endpoints (C2/C3)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class StoryboardGroupingIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "43334333-4333-4333-4333-433343334333";
    private static final String OTHER = "44444444-4444-4444-4444-444444444444";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoStoryboardRepository storyboardRows;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(storage.presignDownload(anyString(), anyLong()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("GET 详情：shots+候选+质检分随行，grouping 初始为 null；PATCH 往返落库")
    void detailCarriesGroupingRoundTrip() {
        Seeded seeded = seedStoryboardWithTakes();

        java.util.Map<String, Object> body = client()
                .get().uri("/api/video-production/storyboards/{id}", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(java.util.Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.get("data");
        assertThat(data.get("grouping")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) data.get("shots");
        assertThat(shots).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> takes = (List<Map<String, Object>>) shots.get(0).get("takes");
        assertThat(takes).hasSize(1);
        assertThat(((Number) takes.get(0).get("score")).doubleValue()).isEqualTo(88.0);

        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "shots", List.of(Map.of("id", seeded.shot1(), "groupId", "g-open")),
                        "branches", List.of(
                                Map.of("id", "b1", "name", "主版本",
                                        "shotIds", List.of(seeded.shot1(), seeded.shot2())),
                                Map.of("id", "b2", "name", "精简版", "shotIds", List.of(seeded.shot1())))))
                .exchange().expectStatus().isOk();

        java.util.Map<String, Object> after = client()
                .get().uri("/api/video-production/storyboards/{id}", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(java.util.Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> afterData = (java.util.Map<String, Object>) after.get("data");
        System.out.println("DEBUG_GROUPING_VALUE=" + afterData.get("grouping"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> grouping =
                (java.util.Map<String, Object>) afterData.get("grouping");
        assertThat(grouping).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) grouping.get("branches");
        assertThat(branches.stream().map(branch -> String.valueOf(branch.get("name"))))
                .contains("主版本", "精简版");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupingShots = (List<Map<String, Object>>) grouping.get("shots");
        assertThat(groupingShots.get(0)).containsEntry("groupId", "g-open");
    }

    @Test
    @DisplayName("PATCH 校验：未知镜头 400、空分支 400、非属主 404；committed 409")
    void groupingValidationAndGates() {
        Seeded seeded = seedStoryboardWithTakes();

        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("branches", List.of(Map.of("id", "b", "name", "n",
                        "shotIds", List.of(UUID.randomUUID())))))
                .exchange().expectStatus().isBadRequest();

        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("branches", List.of()))
                .exchange().expectStatus().isBadRequest();

        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("branches", List.of(Map.of("id", "b", "name", "n",
                        "shotIds", List.of(seeded.shot1())))))
                .exchange().expectStatus().isNotFound();

        db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.storyboardId().toString()).then().block(Duration.ofSeconds(5));
        client().patch().uri("/api/video-production/storyboards/{id}/grouping", seeded.storyboardId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("branches", List.of(Map.of("id", "b", "name", "n",
                        "shotIds", List.of(seeded.shot1())))))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("镜头内容 PUT：编辑期改写生效并钳 4-6；committed 409；非属主 404")
    void shotContentEditGatesAndClamp() {
        Seeded seeded = seedStoryboardWithTakes();

        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "新画面", "narration", "新旁白", "plannedSeconds", 9))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.plannedSeconds").isEqualTo(6);

        String row = db.sql("SELECT visual || ':' || narration || ':' || planned_seconds AS v "
                        + "FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.shot1().toString())
                .map(r -> r.get("v", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(row).isEqualTo("新画面:新旁白:6");

        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "x"))
                .exchange().expectStatus().isNotFound();

        db.sql("UPDATE video_storyboard SET status='committed' WHERE id=CAST(:id AS uuid)")
                .bind("id", seeded.storyboardId().toString()).then().block(Duration.ofSeconds(5));
        client().put().uri("/api/video-production/shots/{id}/content", seeded.shot1())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("visual", "y"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------------- helpers ----------------

    private record Seeded(UUID storyboardId, UUID shot1, UUID shot2) {}

    private Seeded seedStoryboardWithTakes() {
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 20, CAST('{"images":[],"shopName":"店"}' AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        UUID shot1 = seedShot(storyboardId, 1);
        UUID shot2 = seedShot(storyboardId, 2);
        db.sql("""
                        INSERT INTO video_shot_take(shot_id, take_no, provider, model, status, attempts,
                            media_id, score, score_labels)
                        VALUES (CAST(:shot AS uuid), 1, 'sandbox', 'm', 'succeeded', 1,
                            CAST(:media AS uuid), 88, CAST('["画质偏低"]' AS jsonb))
                        """)
                .bind("shot", shot1.toString())
                .bind("media", UUID.randomUUID().toString())
                .then().block(Duration.ofSeconds(5));
        Mockito.when(storage.getObject(anyString())).thenReturn(new byte[] { 1 });
        return new Seeded(storyboardId, shot1, shot2);
    }

    private UUID seedShot(UUID storyboardId, int seq) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt, status)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', '旁白', 5, '固定机位', 0, 'p', 'ready')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
