package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class SpeechTranscriptionServiceTest {

    private static final String ACCOUNT_ID = "speech-owner";
    private static final byte[] WAV = new byte[] {
            'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E',
            'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
            64, 31, 0, 0, (byte) 128, 62, 0, 0, 2, 0, 16, 0,
            'd', 'a', 't', 'a', 0, 0, 0, 0
    };

    @Mock
    IntelligenceCallerResolver callers;
    @Mock
    MediaReferenceRepository mediaReferences;
    @Mock
    SpeechTranscriptionRepository transcriptions;
    @Mock
    ObjectProvider<ObjectStorageAdapter> storageProvider;
    @Mock
    ObjectStorageAdapter storage;
    @Mock
    AudioDurationProbe durationProbe;
    @Mock
    AiExecutionService executions;
    @Mock
    PlatformConcurrencyLimiter concurrencyLimiter;
    @Mock
    SpeechProviderRegistry providers;
    @Mock
    TransactionalOperator transactions;
    @InjectMocks
    SpeechTranscriptionService service;

    @Test
    void cancellationDuringAllowedPreparationHandoffFinalizesRun() throws Exception {
        UUID mediaId = UUID.randomUUID();
        MediaReference media = new MediaReference(
                mediaId, ACCOUNT_ID, null, MediaPurpose.SPEECH_AUDIO.db(), null, null,
                "media/speech.wav", null, "audio/wav", WAV.length, MediaChecksums.sha256(WAV),
                "upload", MediaStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(60), null);
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-speech-v1", 1, null);
        AiExecutionService.ExecutionContext context = org.mockito.Mockito.mock(
                AiExecutionService.ExecutionContext.class);
        when(context.provider()).thenReturn(provider);
        AiExecutionService.ExecutionResult prepared = spy(AiExecutionService.ExecutionResult.allowed(context));
        CountDownLatch mapperEntered = new CountDownLatch(1);
        CountDownLatch releaseMapper = new CountDownLatch(1);
        AtomicInteger contextCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (contextCalls.incrementAndGet() == 1) {
                mapperEntered.countDown();
                assertThat(releaseMapper.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return context;
        }).when(prepared).context();

        when(callers.requireUser(any())).thenReturn(Mono.just(
                new Caller(ACCOUNT_ID, null, "session", null, null, "user", null, null)));
        when(mediaReferences.findById(mediaId)).thenReturn(Mono.just(media));
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(storage.getObject(media.objectKey())).thenReturn(WAV.clone());
        when(durationProbe.probe(any(byte[].class))).thenReturn(12_000L);
        when(transcriptions.createProcessing(any())).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0, SpeechTranscription.class)));
        when(executions.prepareExecution(
                eq(ACCOUNT_ID), isNull(), eq("voice"), eq(CreditFeature.AI_RUN_VOICE),
                eq(0), eq(0), eq(0), eq(12), eq(true)))
                .thenReturn(Mono.just(prepared).publishOn(Schedulers.boundedElastic()));
        when(concurrencyLimiter.acquire(provider)).thenReturn(Mono.never());
        when(transcriptions.markFailed(any(UUID.class), eq("execution_cancelled")))
                .thenReturn(Mono.just(true));
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.handleFailure(eq(context), anyString())).thenReturn(Mono.just(true));

        var subscription = service.create(
                        MockServerHttpRequest.post("/api/speech/transcriptions").build(), mediaId, "auto")
                .subscribe(ignored -> { }, ignored -> { });
        assertThat(mapperEntered.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        verify(transcriptions, timeout(2_000)).markFailed(any(UUID.class), eq("execution_cancelled"));
        releaseMapper.countDown();

        verify(executions, timeout(2_000)).handleFailure(
                context, "speech transcription failed: execution_cancelled");
    }
}
