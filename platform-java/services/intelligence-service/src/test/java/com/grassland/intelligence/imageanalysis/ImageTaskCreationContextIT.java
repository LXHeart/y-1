package com.grassland.intelligence.imageanalysis;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Image task-mode creation context")
class ImageTaskCreationContextIT extends IntelligenceItSupport {
    private static final String ACCOUNT = "41414141-4141-4141-4141-414141414141";
    private static final String OTHER = "42424242-4242-4242-4242-424242424242";

    @MockitoBean
    CreditsClient credits;

    private final ObjectMapper mapper = new ObjectMapper();
    private String platformConfigId;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM creation_context_snapshot").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        platformConfigId = db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,9)
                        RETURNING id::text
                        """)
                .bind("baseUrl", QWEN.baseUrl())
                .map(row -> row.get("id", String.class)).one().block();
        QWEN.resetAll();
        // 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
        attachPlatformTextCredential();
    }

    @Test
    @DisplayName("task draft sends frozen rules with image parts and links the AI run")
    void taskDraftUsesFrozenContext() {
        String snapshotId = seedSnapshot(ACCOUNT, "dianping", "graphic");
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"content":"{\\"review\\":\\"冻结任务评价\\",\\"title\\":\\"探店\\",\\"tags\\":[\\"午市\\"]}"}}],
                 "usage":{"prompt_tokens":30,"completion_tokens":12}}
                """)));

        String body = postDraft(ACCOUNT, taskForm(snapshotId, "dianping", true))
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("冻结任务评价");
        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("必须展示午市套餐"))
                .withRequestBody(containing("platformRules"))
                .withRequestBody(containing("image_url")));
        String audit = db.sql("SELECT context_snapshot_id::text || ':' || status AS audit "
                        + "FROM ai_run ORDER BY started_at DESC LIMIT 1")
                .map(row -> row.get("audit", String.class)).one().block();
        assertThat(audit).isEqualTo(snapshotId + ":completed");
    }

    @Test
    @DisplayName("task image endpoints fail closed for invalid bindings")
    void taskImageFailsClosed() {
        postDraft(ACCOUNT, taskForm(null, "dianping", true))
                .expectStatus().isBadRequest();

        String foreign = seedSnapshot(OTHER, "dianping", "graphic");
        postDraft(ACCOUNT, taskForm(foreign, "dianping", true))
                .expectStatus().isForbidden();

        String mismatchedPlatform = seedSnapshot(ACCOUNT, "dianping", "graphic");
        postDraft(ACCOUNT, taskForm(mismatchedPlatform, "taobao", true))
                .expectStatus().isEqualTo(409);

        String wrongForm = seedSnapshot(ACCOUNT, "dianping", "video");
        postDraft(ACCOUNT, taskForm(wrongForm, "dianping", true))
                .expectStatus().isEqualTo(409);

        String independentSnapshot = seedSnapshot(ACCOUNT, "dianping", "graphic");
        postDraft(ACCOUNT, taskForm(independentSnapshot, "dianping", false))
                .expectStatus().isBadRequest();

        Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(runs).isZero();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postDraft(
            String account, org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> form) {
        return client().post().uri("/api/image-analysis/step/draft")
                .header("X-Grassland-Identity", sign(account, "recommender"))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(form).exchange();
    }

    private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> taskForm(
            String snapshotId, String platform, boolean taskMode) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("images", new ByteArrayResource(new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
        }) {
            @Override
            public String getFilename() {
                return "task.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        body.part("reviewLength", "100");
        body.part("platform", platform);
        if (taskMode) body.part("taskMode", "true");
        if (snapshotId != null) body.part("contextSnapshotId", snapshotId);
        return body.build();
    }

    private String seedSnapshot(String accountId, String platform, String contentForm) {
        Map<String, Object> aiConfig = new LinkedHashMap<>();
        aiConfig.put("resolutionType", "PLATFORM");
        aiConfig.put("configId", platformConfigId);
        aiConfig.put("provider", "qwen");
        aiConfig.put("model", "qwen-plus");
        aiConfig.put("platformModelVersion", 9);
        aiConfig.put("modelRole", "primary");
        try {
            return db.sql("""
                            INSERT INTO creation_context_snapshot(
                                account_id, task_id, application_id, task_version,
                                platform_id, content_form_id, task_snapshot,
                                platform_rules_snapshot, material_snapshot, ai_config_snapshot)
                            VALUES (:account,:task,:application,4,:platform,:contentForm,
                                '{"title":"探店任务","requirements":{"mustInclude":["必须展示午市套餐"]}}'::jsonb,
                                '{"version":"2026-08-06","titleMax":20}'::jsonb,
                                '{"items":[{"assetId":"material-1","version":2}]}'::jsonb,
                                CAST(:aiConfig AS jsonb))
                            RETURNING id::text
                            """)
                    .bind("account", accountId)
                    .bind("task", UUID.randomUUID().toString())
                    .bind("application", UUID.randomUUID().toString())
                    .bind("platform", platform)
                    .bind("contentForm", contentForm)
                    .bind("aiConfig", mapper.writeValueAsString(aiConfig))
                    .map(row -> row.get("id", String.class)).one().block();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
