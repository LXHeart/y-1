package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Task image generation")
class TaskImageGenerationIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "51515151-5151-5151-5151-515151515151";

    @MockitoBean
    ArticleImageService images;

    @MockitoBean
    CreditsClient credits;

    @Autowired
    FrozenImageGenerationConfigResolver frozenConfigs;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        reset(images, credits);
        when(images.generate(any(), any())).thenReturn(Mono.just(
                new GeneratedImageResponse(
                        "/api/article-generation/generated-images/frozen", "优化后")));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
    }

    @Test
    @DisplayName("task generation injects frozen context and persists image run audit")
    void generatesWithFrozenContext() throws Exception {
        String snapshotId = seedSnapshot();

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "prompt", "生成竖版门店封面",
                        "size", "1024x1792",
                        "taskMode", true,
                        "contextSnapshotId", snapshotId,
                        "targetPlatform", "xiaohongshu"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.imageUrl")
                .isEqualTo("/api/article-generation/generated-images/frozen");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArticleImageService.GenerateCommand> command =
                ArgumentCaptor.forClass(ArticleImageService.GenerateCommand.class);
        verify(images).generate(command.capture(), any());
        assertThat(command.getValue().prompt())
                .contains("必须展示新品包装")
                .contains("platformRules")
                .contains("material-7")
                .contains("生成竖版门店封面");
        Map<String, Object> audit = db.sql("""
                        SELECT context_snapshot_id::text AS snapshot_id, status,
                               images_generated, actual_cents
                        FROM ai_run ORDER BY started_at DESC LIMIT 1
                        """)
                .<Map<String, Object>>map((Row row, RowMetadata metadata) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("snapshotId", row.get("snapshot_id", String.class));
                    values.put("status", row.get("status", String.class));
                    values.put("images", row.get("images_generated", Integer.class));
                    values.put("cost", row.get("actual_cents", Integer.class));
                    return values;
                })
                .one().block();
        assertThat(audit).containsEntry("snapshotId", snapshotId)
                .containsEntry("status", "completed")
                .containsEntry("images", 1)
                .containsEntry("cost", frozenConfigs.current().unitPriceCents());
    }

    @Test
    @DisplayName("independent generation cannot smuggle task fields")
    void independentRequestRejectsTaskFields() {
        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "prompt", "独立图片",
                        "contextSnapshotId", UUID.randomUUID().toString()))
                .exchange().expectStatus().isBadRequest();
    }

    private String seedSnapshot() throws Exception {
        Map<String, Object> aiConfig = Map.of(
                "resolutionType", "PLATFORM",
                "status", "unavailable",
                "imageGeneration", frozenConfigs.snapshot());
        return db.sql("""
                        INSERT INTO creation_context_snapshot(
                            account_id, organization_id, task_id, application_id, task_version,
                            platform_id, content_form_id, task_snapshot, platform_rules_snapshot,
                            material_snapshot, ai_config_snapshot)
                        VALUES (:account,'org-image',:task,:application,7,'xiaohongshu','graphic',
                            '{"title":"新品任务","requirements":{"mustInclude":["必须展示新品包装"]}}'::jsonb,
                            '{"version":"2026-08-06","imageAspectRatios":["2:3"]}'::jsonb,
                            '{"items":[{"assetId":"material-7","version":3}]}'::jsonb,
                            CAST(:aiConfig AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .bind("task", UUID.randomUUID().toString())
                .bind("application", UUID.randomUUID().toString())
                .bind("aiConfig", mapper.writeValueAsString(aiConfig))
                .map(row -> row.get("id", String.class)).one().block();
    }
}
