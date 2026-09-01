package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #64 卡5：TTS worker sandbox 全链——免费执行环（feature=null）、纯 Java wav 归档、
 * cues 落行、时长解析值；旁白空与渠道未配置的降级分支。
 */
@DisplayName("TTS worker sandbox chain")
@org.springframework.test.context.TestPropertySource(properties = "ai.video-generation.worker-enabled=false")
class TtsWorkerIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "51515151-5151-5151-5151-515151515151";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    TtsWorker worker;

    @Autowired
    VideoShotAudioRepository audios;

    @Autowired
    VideoStoryboardRepository storyboards;

    @Autowired
    VideoShotRepository shots;

    private final List<Object[]> storedObjects = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new com.grassland.intelligence.credits.CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        storedObjects.clear();
        org.mockito.Mockito.doAnswer(invocation -> {
            storedObjects.add(new Object[] { invocation.getArgument(0),
                    invocation.getArgument(1), invocation.getArgument(2) });
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability='video_tts'").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE name='it-video-video_tts-sandbox'").then())
                .block(Duration.ofSeconds(10));
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES ('it-video-video_tts-sandbox', 'sandbox', 'https://sandbox.invalid', true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT 'video_tts', 'primary', 'sandbox', 'sandbox-tts-v1', cred.base_url, 'healthy',
                    true, 1, cred.id
                FROM cred
                """).then().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("sandbox 全链：免费 run 留痕、wav 归档、cues 与时长落行")
    void sandboxAudioFullChain() throws Exception {
        UUID storyboardId = seedStoryboard(ACCOUNT, "{\"images\":[\"AAAA\"]}");
        UUID shotId = seedShot(storyboardId, 1, "老王面馆，每天现熬骨汤");
        VideoShotAudio audio = audios.create(shotId, "sandbox", "sandbox-tts-v1")
                .block(Duration.ofSeconds(5));

        worker.process(audio).block(Duration.ofSeconds(30));

        VideoShotAudio done = audios.findByShot(shotId).block(Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(VideoShotAudio.STATUS_SUCCEEDED);
        assertThat(done.mediaId()).isNotNull();
        assertThat(done.runId()).isNotNull();
        // 「老王面馆，每天现熬骨汤」=10 字 → 2500ms
        assertThat(done.durationMs()).isEqualTo(2500);
        // cues 形态断言走解析（Jackson 输出的冒号后带空格）
        com.fasterxml.jackson.databind.JsonNode cues = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(done.cues());
        assertThat(cues.isArray()).isTrue();
        assertThat(cues.size()).isEqualTo(2);
        assertThat(cues.get(0).path("text").asText()).isEqualTo("老王面馆");
        assertThat(cues.get(0).path("startMs").asLong()).isZero();
        assertThat(cues.get(1).path("text").asText()).isEqualTo("每天现熬骨汤");
        // cues 覆盖全程
        assertThat(cues.get(1).path("endMs").asLong()).isEqualTo(2500);

        assertThat(storedObjects).hasSize(1);
        byte[] wav = (byte[]) storedObjects.getFirst()[1];
        assertThat(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(storedObjects.getFirst()[2]).isEqualTo("audio/wav");

        // 免费执行环留痕：ai_run completed、capability=video_tts
        String run = db.sql("SELECT capability || ':' || status AS run_state FROM ai_run "
                        + "WHERE id=CAST(:run AS uuid)")
                .bind("run", done.runId().toString())
                .map(row -> row.get("run_state", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(run).isEqualTo("video_tts:completed");
        // feature=null：不发生任何积分 consume（卡10 再断言零流水，这里先钉住无调用）
        verifyNoInteractions(credits);
    }

    @Test
    @DisplayName("旁白为空 → skipped，不进执行环、不触存储")
    void emptyNarrationSkips() {
        UUID storyboardId = seedStoryboard(ACCOUNT, "{\"images\":[\"AAAA\"]}");
        UUID shotId = seedShot(storyboardId, 1, "   ");
        VideoShotAudio audio = audios.create(shotId, "sandbox", "sandbox-tts-v1")
                .block(Duration.ofSeconds(5));

        worker.process(audio).block(Duration.ofSeconds(30));

        VideoShotAudio done = audios.findByShot(shotId).block(Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(VideoShotAudio.STATUS_SKIPPED);
        assertThat(done.runId()).isNull();
        assertThat(storedObjects).isEmpty();
    }

    @Test
    @DisplayName("TTS 渠道未配置（无控制面行）→ skipped 降级，不算失败")
    void unavailableChannelSkips() {
        db.sql("DELETE FROM platform_model_config WHERE capability='video_tts'").then()
                .then(db.sql("DELETE FROM platform_provider_credential WHERE name='it-video-video_tts-sandbox'").then())
                .block(Duration.ofSeconds(10));

        UUID storyboardId = seedStoryboard(ACCOUNT, "{\"images\":[\"AAAA\"]}");
        UUID shotId = seedShot(storyboardId, 1, "有旁白但无渠道");
        VideoShotAudio audio = audios.create(shotId, null, null).block(Duration.ofSeconds(5));

        worker.process(audio).block(Duration.ofSeconds(30));

        VideoShotAudio done = audios.findByShot(shotId).block(Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(VideoShotAudio.STATUS_SKIPPED);
        assertThat(done.errorCode()).isEqualTo("tts_unavailable");
        assertThat(storedObjects).isEmpty();
    }

    private UUID seedStoryboard(String accountId, String payload) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 30, CAST(:payload AS jsonb)) RETURNING id::text
                        """)
                .bind("account", accountId).bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', :narration, 5, '固定机位', 0, 'p')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq).bind("narration", narration)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
