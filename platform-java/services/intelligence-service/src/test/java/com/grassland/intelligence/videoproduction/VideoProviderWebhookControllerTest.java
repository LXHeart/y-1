package com.grassland.intelligence.videoproduction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class VideoProviderWebhookControllerTest {
    private static final String SECRET = "webhook-test-secret";
    private static final String PROVIDER = "minimax";
    private static final String EVENT_ID = "event-1";
    private static final String TASK_ID = "task-1";
    private static final String BODY = "{\"task_id\":\"task-1\",\"status\":\"processing\",\"progress\":40}";

    @Mock VideoProviderWebhookRepository inbox;
    @Mock VideoGenerationWorker worker;
    private VideoProviderWebhookController controller;
    private VideoGenerationJob job;

    @BeforeEach
    void setUp() {
        VideoGenerationProperties properties = new VideoGenerationProperties();
        properties.setWebhookSecret(SECRET);
        controller = new VideoProviderWebhookController(
                new VideoProviderWebhookVerifier(properties), inbox, worker);
        job = mock(VideoGenerationJob.class);
    }

    @Test
    void processesFirstValidEvent() {
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(BODY)))
                .assertNext(response -> org.assertj.core.api.Assertions.assertThat(response.getBody())
                        .containsEntry("success", true))
                .verifyComplete();

        verify(worker).processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.state() == VideoGenerationProvider.ProviderResult.State.PROCESSING
                                && result.progress() == 40
                                && result.errorCode() == null));
    }

    @Test
    void ignoresReplayAfterTaskOwnershipCheck() {
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(controller.receive(PROVIDER, exchange(BODY)))
                .expectNextCount(1).verifyComplete();

        verify(worker, never()).processWebhook(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownTaskDoesNotConsumeEventId() {
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(BODY)))
                .expectErrorMatches(error -> error instanceof IllegalArgumentException
                        && error.getMessage().contains("回调任务不存在"))
                .verify();

        verify(inbox, never()).claim(PROVIDER, EVENT_ID);
    }

    @Test
    void processingFailureReleasesEventForProviderRetry() {
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.error(new IllegalStateException("archive failed")));
        when(inbox.release(PROVIDER, EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(BODY)))
                .expectError(IllegalArgumentException.class).verify();

        verify(inbox).release(PROVIDER, EVENT_ID);
    }

    @Test
    void unknownProviderStatusIsNotTreatedAsProgress() {
        String body = "{\"task_id\":\"task-1\",\"status\":\"vendor_new_state\"}";
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(body)))
                .expectNextCount(1).verifyComplete();

        verify(worker).processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.state() == VideoGenerationProvider.ProviderResult.State.UNKNOWN
                                && "provider_unknown_status".equals(result.errorCode())));
    }

    @Test
    void mapsSeedanceNestedSuccessPayload() {
        String body = """
                {"id":"task-1","status":"succeeded","data":{"content":{
                  "video_url":"https://vendor/seedance.mp4"},"duration":7}}
                """;
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(body)))
                .expectNextCount(1).verifyComplete();

        ArgumentCaptor<VideoGenerationProvider.ProviderResult> result =
                ArgumentCaptor.forClass(VideoGenerationProvider.ProviderResult.class);
        verify(worker).processWebhook(org.mockito.ArgumentMatchers.eq(job), result.capture());
        org.assertj.core.api.Assertions.assertThat(result.getValue().state())
                .isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(result.getValue().resultUrl())
                .isEqualTo("https://vendor/seedance.mp4");
        org.assertj.core.api.Assertions.assertThat(result.getValue().durationSeconds()).isEqualTo(7);
    }

    @Test
    void mapsMinimaxNestedFailureAndAmericanCanceledStatus() {
        String body = """
                {"data":{"task_id":"task-1","status":"canceled","duration":5,
                  "base_resp":{"status_code":1008,"status_msg":"quota"}}}
                """;
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(body)))
                .expectNextCount(1).verifyComplete();

        verify(worker).processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.state() == VideoGenerationProvider.ProviderResult.State.FAILED
                                && result.durationSeconds() == 5
                                && "1008".equals(result.errorCode())
                                && "quota".equals(result.errorMessage())));
    }

    @Test
    void keepsSuccessWithoutDownloadUrlNonTerminalForPolling() {
        String body = """
                {"data":{"task_id":"task-1","status":"success","file_id":"file-1"}}
                """;
        when(inbox.findJob(PROVIDER, TASK_ID)).thenReturn(Mono.just(job));
        when(inbox.claim(PROVIDER, EVENT_ID)).thenReturn(Mono.just(true));
        when(worker.processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.receive(PROVIDER, exchange(body)))
                .expectNextCount(1).verifyComplete();

        verify(worker).processWebhook(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.state() == VideoGenerationProvider.ProviderResult.State.UNKNOWN
                                && result.resultUrl() == null
                                && "provider_result_pending".equals(result.errorCode())));
    }

    private static MockServerWebExchange exchange(String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = VideoProviderWebhookVerifier.sign(
                SECRET, timestamp + "." + EVENT_ID + "." + body);
        return MockServerWebExchange.from(MockServerHttpRequest.post(
                        "/api/video-production/webhooks/" + PROVIDER)
                .header("X-Video-Event-Id", EVENT_ID)
                .header("X-Video-Timestamp", timestamp)
                .header("X-Video-Signature", signature)
                .body(body));
    }
}
