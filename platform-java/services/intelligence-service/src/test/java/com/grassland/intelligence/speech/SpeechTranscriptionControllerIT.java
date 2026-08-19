package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SpeechTranscriptionControllerIT extends IntelligenceItSupport {

    private static final String OWNER = "speech-owner";
    private static final String OTHER_OWNER = "speech-other-owner";
    private static final String OBJECT_KEY = "media/speech_audio/internal-object-key-secret.wav";
    private static final byte[] WAV = new byte[] {
            'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E',
            'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
            64, 31, 0, 0, (byte) 128, 62, 0, 0, 2, 0, 16, 0,
            'd', 'a', 't', 'a', 0, 0, 0, 0
    };

    @Autowired
    private MediaReferenceRepository mediaReferences;

    @MockitoBean
    private ObjectStorageAdapter storage;

    @MockitoBean
    private AudioDurationProbe durationProbe;

    @MockitoBean
    private CreditsClient credits;

    @MockitoSpyBean
    private SandboxSpeechRecognitionProvider sandbox;

    @MockitoSpyBean
    private SpeechTranscriptionRepository transcriptions;

    @MockitoSpyBean
    private AiExecutionService executions;

    @MockitoSpyBean
    private PlatformConcurrencyLimiter concurrencyLimiter;

    @Autowired
    private SpeechTranscriptionService service;

    @BeforeEach
    void setUp() {
        reset(storage, durationProbe, credits, sandbox);
        db.sql("DELETE FROM speech_transcription").then().block();
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM ai_provider_key").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        db.sql("DELETE FROM media_reference WHERE purpose IN ('speech_audio','user_upload')").then().block();
        seedVoiceModel("sandbox");
        when(storage.getObject(anyString())).thenReturn(WAV.clone());
        when(durationProbe.probe(any(byte[].class))).thenReturn(12_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postCreatesCompletedTranscriptionAndOwnerCanReadIt() {
        UUID mediaId = activeSpeechMedia(OWNER);

        Map<String, Object> envelope = post(OWNER, mediaId, "zh-CN")
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(envelope).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) envelope.get("data");
        assertThat(created)
                .containsEntry("mediaId", mediaId.toString())
                .containsEntry("language", "zh-CN")
                .containsEntry("detectedLanguage", "zh-CN")
                .containsEntry("durationMs", 12_000)
                .containsEntry("status", "completed")
                .containsEntry("provider", "sandbox")
                .containsEntry("model", "sandbox-speech-v1")
                .containsEntry("modelVersion", 1)
                .containsEntry("sandbox", true);
        assertThat(created.get("text")).asString().startsWith("[Sandbox]");
        assertThat(created.get("aiRunId")).isNotNull();
        assertThat(created.get("createdAt")).isNotNull();
        assertThat(created.get("updatedAt")).isNotNull();
        assertThat(created.get("completedAt")).isNotNull();
        assertThat(created).doesNotContainKeys("objectKey", "checksum", "audio", "bytes", "baseUrl");

        UUID transcriptionId = UUID.fromString((String) created.get("id"));
        client().get().uri("/api/speech/transcriptions/{id}", transcriptionId)
                .header("X-Grassland-Identity", sign(OWNER, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.id").isEqualTo(transcriptionId.toString())
                .jsonPath("$.data.mediaId").isEqualTo(mediaId.toString())
                .jsonPath("$.data.text").value(value -> assertThat(value.toString()).startsWith("[Sandbox]"))
                .jsonPath("$.data.objectKey").doesNotExist()
                .jsonPath("$.data.checksum").doesNotExist();

        client().get().uri("/api/speech/transcriptions/{id}", transcriptionId)
                .header("X-Grassland-Identity", sign(OTHER_OWNER, null))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void authenticationIsRequiredForPostAndGet() {
        UUID mediaId = activeSpeechMedia(OWNER);

        client().post().uri("/api/speech/transcriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId, "language", "auto"))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/speech/transcriptions/{id}", UUID.randomUUID())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void unusableOrWrongPurposeMediaIsAlwaysNotFound() {
        UUID wrongPurpose = media(OWNER, MediaPurpose.USER_UPLOAD.db(), MediaStatus.ACTIVE, null, null);
        UUID pending = media(OWNER, MediaPurpose.SPEECH_AUDIO.db(), MediaStatus.PENDING, null, null);
        UUID expired = media(OWNER, MediaPurpose.SPEECH_AUDIO.db(), MediaStatus.ACTIVE,
                Instant.now().minusSeconds(1), null);
        UUID deleted = media(OWNER, MediaPurpose.SPEECH_AUDIO.db(), MediaStatus.DELETED,
                null, Instant.now());

        for (UUID mediaId : new UUID[] {wrongPurpose, pending, expired, deleted}) {
            post(OWNER, mediaId, "auto").expectStatus().isNotFound();
        }
        post(OTHER_OWNER, activeSpeechMedia(OWNER), "auto").expectStatus().isNotFound();
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void languageWhitelistAndAutoDefaultAreEnforced() {
        UUID mediaId = activeSpeechMedia(OWNER);

        post(OWNER, mediaId, "fr-FR").expectStatus().isBadRequest();
        post(OWNER, mediaId, "   ")
                .expectStatus().isCreated().expectBody()
                .jsonPath("$.data.language").isEqualTo("auto");
        client().post().uri("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(OWNER, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"mediaId\":\"" + mediaId + "\",\"language\":null}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.language").isEqualTo("auto");
    }

    @Test
    void serverProbedDurationOverFifteenMinutesIsRejected() {
        UUID mediaId = activeSpeechMedia(OWNER);
        when(durationProbe.probe(any(byte[].class))).thenReturn(900_001L);

        post(OWNER, mediaId, "en-US").expectStatus().isBadRequest();
        assertThat(count("speech_transcription")).isZero();
        assertThat(count("ai_run")).isZero();
    }

    @Test
    void missingPlatformModelReturnsStableServiceUnavailableCode() {
        UUID mediaId = activeSpeechMedia(OWNER);
        db.sql("DELETE FROM platform_model_config WHERE capability='voice'").then().block();

        post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("no_platform_model");
        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("no_platform_model");
        assertThat(count("ai_run")).isZero();
    }

    @Test
    void prepareFailureFinalizesProcessingTranscription() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(Mono.error(new IllegalStateException("prepare-secret")))
                .when(executions).prepareExecution(
                        eq(OWNER), nullable(String.class), eq("voice"), isNull(),
                        eq(0), eq(0), eq(0), eq(12), eq(true));

        String response = post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(response).contains("speech_provider_failed").doesNotContain("prepare-secret");
        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("provider_failure");
        assertThat(count("ai_run")).isZero();
    }

    @Test
    void prepareCancellationFinalizesProcessingTranscription() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(Mono.never()).when(executions).prepareExecution(
                eq(OWNER), nullable(String.class), eq("voice"), isNull(),
                eq(0), eq(0), eq(0), eq(12), eq(true));

        var subscription = service.create(request(OWNER), mediaId, "auto")
                .subscribe(ignored -> { }, ignored -> { });
        awaitSpeechStatus("processing");
        subscription.dispose();

        verify(transcriptions, timeout(2_000)).markFailed(any(), eq("execution_cancelled"));
        awaitSpeechStatus("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("execution_cancelled");
        assertThat(count("ai_run")).isZero();
    }

    @Test
    void unsupportedConfiguredProviderFailsTranscriptionAndRun() {
        UUID mediaId = activeSpeechMedia(OWNER);
        db.sql("UPDATE platform_model_config SET provider='unsupported' WHERE capability='voice' AND enabled=true")
                .then().block();

        post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("unsupported_provider");

        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("unsupported_provider");
        assertThat(singleString("SELECT status FROM ai_run LIMIT 1")).isEqualTo("failed");
    }

    @Test
    void concurrencyLimitReturnsStablePublicCode() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(Mono.error(new IntelligenceException(
                        429, "平台模型当前并发已满，请稍后重试")))
                .when(concurrencyLimiter).acquire(any());

        post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("concurrency_unavailable")
                .jsonPath("$.error").isEqualTo("语音识别并发已满，请稍后重试");

        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("concurrency_unavailable");
        assertThat(singleString("SELECT status FROM ai_run LIMIT 1")).isEqualTo("failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationDuringPostRunFailureCleanupFinalizesSpeechRunAndOutbox() throws Exception {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(Mono.error(new IllegalStateException("provider-secret")))
                .when(sandbox).transcribe(any());
        CountDownLatch cleanupSubscribed = new CountDownLatch(1);
        Sinks.One<Void> continueCleanup = Sinks.one();
        doAnswer(invocation -> continueCleanup.asMono()
                        .doOnSubscribe(ignored -> cleanupSubscribed.countDown())
                        .then((Mono<Boolean>) invocation.callRealMethod()))
                .when(transcriptions).markFailed(any(), anyString());

        var subscription = service.create(request(OWNER), mediaId, "auto")
                .subscribe(ignored -> { }, ignored -> { });
        assertThat(cleanupSubscribed.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        continueCleanup.tryEmitEmpty();

        awaitSpeechStatus("failed");
        awaitRunStatus("failed");
        assertThat(singleLong("SELECT COUNT(*) FROM intelligence_outbox "
                + "WHERE event_type='AiRunFailed'")).isEqualTo(1L);
    }

    @Test
    void providerCancellationFailsSpeechRunAndReleasesLease() {
        UUID mediaId = activeSpeechMedia(OWNER);
        db.sql("UPDATE platform_model_config SET max_concurrency=1 "
                        + "WHERE capability='voice' AND enabled=true").then().block();
        db.sql("INSERT INTO platform_model_concurrency_slot(config_id, slot_no) "
                        + "SELECT id, 1 FROM platform_model_config WHERE capability='voice' AND enabled=true")
                .then().block();
        doReturn(Mono.never()).when(sandbox).transcribe(any());

        var subscription = service.create(request(OWNER), mediaId, "auto")
                .subscribe(ignored -> { }, ignored -> { });
        awaitSpeechStatus("processing");
        awaitRunStatus("running");
        verify(sandbox, timeout(5_000)).transcribe(any());
        subscription.dispose();

        awaitSpeechStatus("failed");
        awaitRunStatus("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("execution_cancelled");
        assertThat(singleLong("SELECT COUNT(*) FROM platform_model_concurrency_slot "
                + "WHERE lease_token IS NOT NULL")).isZero();
        assertThat(singleLong("SELECT COUNT(*) FROM intelligence_outbox "
                + "WHERE event_type='AiRunFailed'")).isEqualTo(1L);
    }

    @Test
    void completionFailureRollsBackSpeechAndRunSuccessBeforeFinalizingFailure() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(Mono.error(new IllegalStateException("completion-secret")))
                .when(transcriptions).markCompleted(any());

        String response = post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(response).contains("speech_provider_failed").doesNotContain("completion-secret");
        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
        assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                .isEqualTo("provider_failure");
        assertThat(db.sql("SELECT transcript_text IS NULL AS cleared FROM speech_transcription LIMIT 1")
                .map(row -> row.get("cleared", Boolean.class)).one().block()).isTrue();
        assertThat(singleString("SELECT status FROM ai_run LIMIT 1")).isEqualTo("failed");
        assertThat(singleLong("SELECT COUNT(*) FROM intelligence_outbox "
                + "WHERE event_type='AiRunCompleted'")).isZero();
        assertThat(singleLong("SELECT COUNT(*) FROM intelligence_outbox "
                + "WHERE event_type='AiRunFailed'")).isEqualTo(1L);
    }

    @Test
    void zeroCostSuccessStillCompletesRunWithoutCredits() {
        UUID mediaId = activeSpeechMedia(OWNER);

        post(OWNER, mediaId, "en-US").expectStatus().isCreated();

        Map<String, Object> run = db.sql("""
                        SELECT status, capability, provider, model, actual_cents,
                               input_tokens, output_tokens
                        FROM ai_run LIMIT 1
                        """)
                .map((row, metadata) -> Map.<String, Object>of(
                        "status", row.get("status", String.class),
                        "capability", row.get("capability", String.class),
                        "provider", row.get("provider", String.class),
                        "model", row.get("model", String.class),
                        "actualCents", row.get("actual_cents", Integer.class),
                        "inputTokens", row.get("input_tokens", Integer.class),
                        "outputTokens", row.get("output_tokens", Integer.class)))
                .one().block();
        assertThat(run).containsEntry("status", "completed")
                .containsEntry("capability", "voice")
                .containsEntry("provider", "sandbox")
                .containsEntry("model", "sandbox-speech-v1")
                .containsEntry("actualCents", 0)
                .containsEntry("inputTokens", 0)
                .containsEntry("outputTokens", 0);
        verifyNoInteractions(credits);
    }

    @Test
    void providerFailureStoresOnlyStableFailureAndLeaksNoSensitivePayload() {
        UUID mediaId = activeSpeechMedia(OWNER);
        String rawProviderBody = "provider-body-secret-987";
        doReturn(reactor.core.publisher.Mono.error(
                        new IntelligenceException(502, "provider_raw_body", rawProviderBody)))
                .when(sandbox).transcribe(any());
        Logger logger = (Logger) LoggerFactory.getLogger(SpeechTranscriptionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String response = post(OWNER, mediaId, "auto")
                    .expectStatus().isEqualTo(502)
                    .expectBody(String.class)
                    .returnResult().getResponseBody();

            assertThat(response).contains("\"success\":false", "speech_provider_failed")
                    .doesNotContain(rawProviderBody, OBJECT_KEY, "RIFF");
            assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
            assertThat(singleString("SELECT failure_code FROM speech_transcription LIMIT 1"))
                    .isEqualTo("provider_failure");
            Boolean transcriptCleared = db.sql(
                            "SELECT transcript_text IS NULL AS cleared FROM speech_transcription LIMIT 1")
                    .map(row -> row.get("cleared", Boolean.class)).one().block();
            assertThat(transcriptCleared).isTrue();
            assertThat(singleString("SELECT status FROM ai_run LIMIT 1")).isEqualTo("failed");
            assertThat(singleString("SELECT failure_reason FROM ai_run LIMIT 1"))
                    .doesNotContain(rawProviderBody, OBJECT_KEY, "RIFF");

            String logs = appender.list.stream()
                    .map(event -> event.getFormattedMessage()
                            + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).doesNotContain(rawProviderBody, OBJECT_KEY, "RIFF");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void storageFailureDoesNotExposeObjectKeyOrAdapterError() {
        UUID mediaId = activeSpeechMedia(OWNER);
        when(storage.getObject(anyString())).thenThrow(
                new IntelligenceException(500, "storage_raw_error", OBJECT_KEY));

        String response = post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(response).contains("speech_media_unavailable")
                .doesNotContain("storage_raw_error", OBJECT_KEY);
        assertThat(count("speech_transcription")).isZero();
        assertThat(count("ai_run")).isZero();
    }

    @Test
    void failedTranscriptionStillFailsRunWhenTranscriptionFailureUpdateErrors() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(reactor.core.publisher.Mono.error(new IllegalStateException("database-secret")))
                .when(transcriptions).markFailed(any(), anyString());
        doReturn(reactor.core.publisher.Mono.error(new IllegalStateException("provider-secret")))
                .when(sandbox).transcribe(any());

        String response = post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(response).contains("speech_provider_failed")
                .doesNotContain("database-secret", "provider-secret");
        verify(executions).handleFailure(any(AiExecutionService.ExecutionContext.class), anyString());
        assertThat(singleString("SELECT status FROM ai_run LIMIT 1")).isEqualTo("failed");
    }

    @Test
    void failedTranscriptionStatePersistsWhenRunFailureUpdateErrors() {
        UUID mediaId = activeSpeechMedia(OWNER);
        doReturn(reactor.core.publisher.Mono.error(new IllegalStateException("run-database-secret")))
                .when(executions).handleFailure(any(AiExecutionService.ExecutionContext.class), anyString());
        doReturn(reactor.core.publisher.Mono.error(new IllegalStateException("provider-secret")))
                .when(sandbox).transcribe(any());

        String response = post(OWNER, mediaId, "auto")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(response).contains("speech_provider_failed")
                .doesNotContain("run-database-secret", "provider-secret");
        verify(executions).handleFailure(any(AiExecutionService.ExecutionContext.class), anyString());
        assertThat(singleString("SELECT status FROM speech_transcription LIMIT 1")).isEqualTo("failed");
    }

    @Test
    void listReturnsRecentOwnedTranscriptionsWithTextOnlyForCompleted() {
        UUID mediaId = activeSpeechMedia(OWNER);
        UUID completedId = UUID.randomUUID();
        insertRow(completedId, mediaId, OWNER, "completed", "转写完成的文本", Instant.now().minusSeconds(120));
        UUID processingId = UUID.randomUUID();
        insertRow(processingId, mediaId, OWNER, "processing", null, Instant.now());
        insertRow(UUID.randomUUID(), mediaId, OTHER_OWNER, "completed", "别人的转写", Instant.now());

        client().get().uri("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(OWNER, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].id").isEqualTo(processingId.toString())
                .jsonPath("$.data.items[1].id").isEqualTo(completedId.toString())
                .jsonPath("$.data.items[1].transcriptText").isEqualTo("转写完成的文本")
                .jsonPath("$.data.items[0].transcriptText").doesNotExist()
                .jsonPath("$.data.items[0].mediaReferenceId").isEqualTo(mediaId.toString())
                .jsonPath("$.data.items[0].durationMs").isEqualTo(12_000)
                .jsonPath("$.data.items[0].status").isEqualTo("processing");

        client().get().uri("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(OTHER_OWNER, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].transcriptText").isEqualTo("别人的转写");
    }

    @Test
    void listCapsAtTwentyMostRecentRows() {
        UUID mediaId = activeSpeechMedia(OWNER);
        for (int i = 0; i < 22; i++) {
            insertRow(UUID.randomUUID(), mediaId, OWNER, "processing", null,
                    Instant.now().minusSeconds(100 - i));
        }

        client().get().uri("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(OWNER, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(20);
    }

    @Test
    void listRequiresAuthentication() {
        client().get().uri("/api/speech/transcriptions")
                .exchange().expectStatus().isUnauthorized();
    }

    private void insertRow(UUID id, UUID mediaId, String owner, String status,
                           String transcriptText, Instant createdAt) {
        boolean completed = "completed".equals(status);
        String sql = completed ? """
                        INSERT INTO speech_transcription (
                            id, media_reference_id, owner_account_id, requested_language,
                            duration_ms, status, transcript_text, provider, model,
                            ai_run_id, completed_at, created_at, updated_at)
                        VALUES (
                            CAST(:id AS uuid), CAST(:mediaId AS uuid), :owner, 'zh-CN',
                            12000, 'completed', :text, 'sandbox', 'sandbox-speech-v1',
                            gen_random_uuid(), :createdAt, :createdAt, :createdAt)
                        """ : """
                        INSERT INTO speech_transcription (
                            id, media_reference_id, owner_account_id, requested_language,
                            duration_ms, status, transcript_text, created_at, updated_at)
                        VALUES (
                            CAST(:id AS uuid), CAST(:mediaId AS uuid), :owner, 'zh-CN',
                            12000, 'processing', NULL, :createdAt, :createdAt)
                        """;
        var spec = db.sql(sql)
                .bind("id", id.toString())
                .bind("mediaId", mediaId.toString())
                .bind("owner", owner)
                .bind("createdAt", createdAt);
        if (completed) {
            spec = spec.bind("text", transcriptText);
        }
        spec.then().block();
    }

    private WebTestClient.ResponseSpec post(String accountId, UUID mediaId, String language) {
        return client().post().uri("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(accountId, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId, "language", language))
                .exchange();
    }

    private MockServerHttpRequest request(String accountId) {
        return MockServerHttpRequest.post("/api/speech/transcriptions")
                .header("X-Grassland-Identity", sign(accountId, null))
                .build();
    }

    private void awaitSpeechStatus(String status) {
        awaitStatus("speech_transcription", status);
    }

    private void awaitRunStatus(String status) {
        awaitStatus("ai_run", status);
    }

    private void awaitStatus(String table, String status) {
        Mono.defer(() -> db.sql("SELECT status FROM " + table + " ORDER BY updated_at DESC LIMIT 1")
                        .map(row -> row.get("status", String.class)).one())
                .filter(status::equals)
                .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(25)))
                .block(Duration.ofSeconds(5));
        assertThat(singleString("SELECT status FROM " + table + " ORDER BY updated_at DESC LIMIT 1"))
                .isEqualTo(status);
    }

    private UUID activeSpeechMedia(String owner) {
        return media(owner, MediaPurpose.SPEECH_AUDIO.db(), MediaStatus.ACTIVE,
                Instant.now().plusSeconds(3600), null);
    }

    private UUID media(String owner, String purpose, MediaStatus status, Instant expiresAt, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        mediaReferences.insert(new MediaReference(
                id, owner, null, purpose, null, null, OBJECT_KEY + "-" + id, null,
                "audio/wav", WAV.length, MediaChecksums.sha256(WAV), "upload", status,
                Instant.now(), expiresAt, deletedAt)).block();
        return id;
    }

    private void seedVoiceModel(String provider) {
        db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('voice','primary',:provider,'sandbox-speech-v1',
                                'https://sandbox.invalid','healthy',true,1)
                        """)
                .bind("provider", provider).then().block();
    }

    private long count(String table) {
        return db.sql("SELECT COUNT(*) AS n FROM " + table)
                .map(row -> row.get("n", Long.class)).one().block();
    }

    private String singleString(String sql) {
        return db.sql(sql).map(row -> row.get(0, String.class)).one().block();
    }

    private long singleLong(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one().block();
    }
}
