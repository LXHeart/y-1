package com.grassland.intelligence.videoproduction;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.media.MediaLifecycleEvents;
import com.grassland.intelligence.speech.AudioDurationProbe;
import com.grassland.storage.ObjectStorageAdapter;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 配音 worker（任务书 #64 卡5）：claim {@code video_shot_audio} → 免费执行环
 * （capability=video_tts、feature=null 平台资助，照 ContentSafetyAiChecker 模板）→
 * provider submit/poll（跨领单周期，任务号落行）→ 音频归档私有存储（≤20MB、同 origin 校验）
 * → cues 落行 → settleSuccess。
 *
 * <p>不可达路径全部降级不阻断成片：旁白空 / TTS 渠道未配置 → {@code skipped}；预算闸拒绝 /
 * provider 失败 → failed + handleFailure（释放预留，卡8 合成按无配音继续）。
 * ExecutionContext 每 cycle 从行上句柄重建（video worker 同款），重启不悬空 run。
 */
@Component
public class TtsWorker {

    private static final Logger log = LoggerFactory.getLogger(TtsWorker.class);
    private static final long MAX_AUDIO_BYTES = 20L * 1024 * 1024;

    private final VideoShotAudioRepository audios;
    private final VideoShotRepository shots;
    private final VideoStoryboardRepository storyboards;
    private final VideoGenerationProviderResolver resolver;
    private final AiExecutionService executions;
    private final AiRunRepository runs;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final MediaReferenceRepository mediaRefs;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final AudioDurationProbe durationProbe;
    private final VideoGenerationProperties properties;
    private final WebClient client;

    public TtsWorker(VideoShotAudioRepository audios, VideoShotRepository shots,
            VideoStoryboardRepository storyboards, VideoGenerationProviderResolver resolver,
            AiExecutionService executions, AiRunRepository runs,
            PlatformConcurrencyLimiter concurrencyLimiter,
            ObjectProvider<ObjectStorageAdapter> storageProvider, MediaReferenceRepository mediaRefs,
            OutboxRepository outbox, TransactionalOperator transactions,
            AudioDurationProbe durationProbe, VideoGenerationProperties properties) {
        this.audios = audios;
        this.shots = shots;
        this.storyboards = storyboards;
        this.resolver = resolver;
        this.executions = executions;
        this.runs = runs;
        this.concurrencyLimiter = concurrencyLimiter;
        this.storageProvider = storageProvider;
        this.mediaRefs = mediaRefs;
        this.outbox = outbox;
        this.transactions = transactions;
        this.durationProbe = durationProbe;
        this.properties = properties;
        this.client = ManagedWebClientFactory
                .builder(TtsWorker.class, properties.getRequestTimeout(), (int) MAX_AUDIO_BYTES).build();
    }

    @Scheduled(fixedDelayString = "${ai.video-generation.poll-interval:3s}")
    public void dispatch() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        audios.claimBatch(properties.getBatchSize(), properties.getClaimLease())
                .flatMap(this::process)
                .onErrorContinue((error, value) -> log.warn("tts dispatch item failed value={}", value, error))
                .subscribe();
    }

    /** 部署调度器/测试以具体行驱动（与 VideoGenerationWorker.process 同款确定性入口）。 */
    public Mono<Void> process(VideoShotAudio audio) {
        if (audio.isSettled() || VideoShotAudio.STATUS_FAILED.equals(audio.status())) {
            return Mono.empty();
        }
        return shots.findById(audio.shotId())
                .flatMap(shot -> storyboards.findById(shot.storyboardId())
                        .flatMap(storyboard -> drive(audio, shot, storyboard)))
                .onErrorResume(error -> {
                    log.warn("tts audio processing failed audioId={}", audio.id(), error);
                    return audios.scheduleRetry(audio.id(), "tts_worker_error",
                            error.getMessage() == null ? "tts worker failed" : error.getMessage(),
                            java.time.Duration.ofSeconds(10)).then();
                });
    }

    private Mono<Void> drive(VideoShotAudio audio, VideoShot shot, VideoStoryboard storyboard) {
        String narration = shot.narration() == null ? "" : shot.narration().trim();
        if (narration.isEmpty()) {
            return skip(audio, "empty_narration");
        }
        if (audio.attempts() > properties.getMaxAttempts()) {
            return failTerminal(audio, storyboard, "tts_max_attempts", "TTS 超过最大重试次数");
        }
        return resolver.resolveTts().flatMap(resolution -> {
            if (!resolution.available()) {
                return skip(audio, "tts_unavailable");
            }
            if (audio.runId() == null) {
                return prepare(audio, shot, narration, storyboard, resolution);
            }
            return execute(audio, shot, storyboard, narration, resolution);
        });
    }

    /** 免费执行环 prepare（feature=null）；denied（预算闸等）落 failed——不阻断视频 take。 */
    private Mono<Void> prepare(VideoShotAudio audio, VideoShot shot, String narration,
            VideoStoryboard storyboard, VideoGenerationProviderResolver.TtsProviderResolution resolution) {
        int estimatedInput = narration.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return executions
                .prepareExecution(storyboard.accountId(), storyboard.organizationId(),
                        VideoGenerationProviderResolver.CAPABILITY_VIDEO_TTS, null, estimatedInput, 0, true)
                .flatMap(result -> {
                    if (!result.allowed()) {
                        return audios.markFailed(audio.id(), "tts_denied", result.denialReason()).then();
                    }
                    var budget = result.context().budgetReservation();
                    return audios
                            .attachRun(audio.id(), result.context().runId(), budget.budgetId(),
                                    budget.reservationDate(), budget.reservedCents())
                            .then(execute(reload(audio, result.context().runId(), budget.budgetId(),
                                    budget.reservationDate(), budget.reservedCents()), shot,
                                    storyboard, narration, resolution));
                });
    }

    private Mono<Void> execute(VideoShotAudio audio, VideoShot shot, VideoStoryboard storyboard,
            String narration, VideoGenerationProviderResolver.TtsProviderResolution resolution) {
        TtsProvider.TtsCommand command = new TtsProvider.TtsCommand(audio.id(),
                audio.model() == null ? resolution.model() : audio.model(), narration, null);
        Mono<TtsProvider.TtsResult> call = audio.providerTaskId() == null
                ? resolution.adapter().submit(command)
                : resolution.adapter().poll(audio.providerTaskId());
        return Mono.usingWhen(
                concurrencyLimiter.acquire(toProviderResolution(resolution)),
                lease -> call.flatMap(result -> switch (result.state()) {
                    case SUCCEEDED -> complete(audio, shot, storyboard, narration, resolution, result);
                    case FAILED -> fail(audio, storyboard, result.errorCode() == null ? "tts_provider_failed"
                            : result.errorCode(), result.errorMessage() == null ? "TTS provider 失败"
                                    : result.errorMessage());
                    case QUEUED -> audios
                            .updateProviderState(audio.id(), VideoShotAudio.STATUS_SUBMITTED,
                                    result.providerTaskId())
                            .then();
                    case PROCESSING -> audios
                            .updateProviderState(audio.id(), VideoShotAudio.STATUS_PROCESSING,
                                    result.providerTaskId())
                            .then();
                }),
                PlatformConcurrencyLimiter.Lease::release,
                (lease, error) -> lease.release(),
                PlatformConcurrencyLimiter.Lease::release);
    }

    private Mono<Void> complete(VideoShotAudio audio, VideoShot shot, VideoStoryboard storyboard,
            String narration, VideoGenerationProviderResolver.TtsProviderResolution resolution,
            TtsProvider.TtsResult result) {
        return fetchAudio(audio, resolution, result)
                .flatMap(audioBytes -> {
                    long durationMs = result.durationMs() != null ? result.durationMs()
                            : durationProbe.probe(audioBytes);
                    String cues = TtsCues.toJson(TtsCues.build(narration, durationMs));
                    return archive(audio, storyboard, audioBytes, durationMs)
                            .flatMap(reference -> audios
                                    .attachMedia(audio.id(), reference.id(), cues, (int) durationMs))
                            .then(settle(audio, storyboard, estimatedTokens(narration)));
                });
    }

    /** sandbox 无 URL（纯 Java 合成 wav + 解析时长）；真实 provider 下载（≤20MB、同 origin）。 */
    private Mono<byte[]> fetchAudio(VideoShotAudio audio,
            VideoGenerationProviderResolver.TtsProviderResolution resolution,
            TtsProvider.TtsResult result) {
        if (result.audioUrl() == null || result.audioUrl().isBlank()) {
            if (!"sandbox".equals(resolution.provider())) {
                return Mono.error(new IllegalStateException("TTS 成功响应缺少音频地址"));
            }
            int durationMs = result.durationMs() == null
                    ? SandboxTtsProvider.durationMsFor(null) : result.durationMs();
            return Mono.just(SandboxTtsProvider.sineWavBytes(durationMs));
        }
        validateOrigin(result.audioUrl(), resolution.baseUrl());
        return client.get().uri(result.audioUrl()).exchangeToMono(response -> {
            if (!response.statusCode().is2xxSuccessful()) {
                return Mono.error(new IllegalStateException("TTS 音频下载失败"));
            }
            long declared = response.headers().contentLength().orElse(-1L);
            if (declared > MAX_AUDIO_BYTES) {
                return Mono.error(new IllegalStateException("TTS 音频大小超出归档限制"));
            }
            return response.bodyToMono(byte[].class).timeout(properties.getRequestTimeout());
        }).flatMap(bytes -> bytes.length == 0 || bytes.length > MAX_AUDIO_BYTES
                ? Mono.error(new IllegalStateException("TTS 音频大小超出归档限制"))
                : Mono.just(bytes));
    }

    /** 一行一稳定 media 句柄（幂等）；音频不过多模态审核（isModeratedPurpose 不含 speech_audio）。 */
    private Mono<MediaReference> archive(VideoShotAudio audio, VideoStoryboard storyboard,
            byte[] bytes, long durationMs) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.error(new IllegalStateException("TTS 归档需要启用对象存储"));
        }
        UUID mediaId = audio.id();
        String key = "media/video_shot_audio/" + mediaId;
        String mime = sniffAudioMime(bytes);
        MediaReference reference = new MediaReference(mediaId, storyboard.accountId(),
                storyboard.organizationId(), MediaPurpose.SPEECH_AUDIO.db(), "video_shot_audio",
                audio.id().toString(), key, mime, bytes.length,
                VideoAssetArchiveService.VideoArchiveChecksums.sha256(bytes), "generated",
                MediaStatus.ACTIVE, Instant.now(), null, null);
        return Mono.fromRunnable(() -> storage.putObject(key, bytes, mime))
                .subscribeOn(Schedulers.boundedElastic())
                .then(transactions.transactional(mediaRefs.insert(reference)
                        .flatMap(active -> outbox.append(MediaLifecycleEvents.activated(active))
                                .thenReturn(active))));
    }

    private Mono<Void> settle(VideoShotAudio audio, VideoStoryboard storyboard, int tokens) {
        return rebuildContext(audio, storyboard)
                .flatMap(ctx -> executions.settleSuccess(ctx, tokens, 0, 0, 0).then())
                .onErrorResume(error -> {
                    log.warn("tts settle failed audioId={}", audio.id(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> skip(VideoShotAudio audio, String reason) {
        return failRunIfBilled(audio, null, reason)
                .then(audios.markSkipped(audio.id(), reason)).then();
    }

    private Mono<Void> fail(VideoShotAudio audio, VideoStoryboard storyboard, String code, String message) {
        return failRunIfBilled(audio, storyboard, message)
                .then(audios.markFailed(audio.id(), code, message)).then();
    }

    private Mono<Void> failTerminal(VideoShotAudio audio, VideoStoryboard storyboard, String code,
            String message) {
        return fail(audio, storyboard, code, message);
    }

    /** 免费分支失败路径：重建上下文走 handleFailure 释放预留（特征=null 无积分补偿，预算仍要释放）。 */
    private Mono<Void> failRunIfBilled(VideoShotAudio audio, VideoStoryboard storyboard, String reason) {
        if (!audio.isBilled()) {
            return Mono.empty();
        }
        return rebuildContext(audio, storyboard)
                .flatMap(ctx -> executions.handleFailure(ctx, reason == null ? "tts failed" : reason))
                .onErrorResume(error -> {
                    log.warn("tts run failure handling failed audioId={}", audio.id(), error);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<AiExecutionService.ExecutionContext> rebuildContext(VideoShotAudio audio,
            VideoStoryboard storyboard) {
        if (audio.runId() == null || storyboard == null) {
            return Mono.empty();
        }
        return runs.findById(audio.runId()).flatMap(run -> {
            var resolution = ProviderResolution.platform(null, audio.provider(),
                    null, audio.model(), 0, null);
            var budget = ModelBudgetService.BudgetCheckResult.allowed(
                    audio.budgetId(), audio.budgetReservationDate(), 0,
                    audio.reservedCents() == null ? 0 : audio.reservedCents());
            return Mono.just(new AiExecutionService.ExecutionContext(
                    audio.runId(), storyboard.organizationId(), storyboard.accountId(),
                    VideoGenerationProviderResolver.CAPABILITY_VIDEO_TTS, resolution, budget,
                    audio.id(), null, null, false, null, null, 0, 0,
                    run.creditsCentsPolicyVersion()));
        });
    }

    private static int estimatedTokens(String narration) {
        return narration.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String sniffAudioMime(byte[] bytes) {
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes.length >= 4 && bytes[0] == 'f' && bytes[1] == 't' && bytes[2] == 'y' && bytes[3] == 'p') {
            return "audio/mp4";
        }
        return "audio/mpeg";
    }

    private void validateOrigin(String value, String baseUrl) {
        try {
            URI actual = new URI(value);
            URI base = new URI(baseUrl == null ? "" : baseUrl);
            if (!("https".equalsIgnoreCase(actual.getScheme()) || "http".equalsIgnoreCase(actual.getScheme()))
                    || base.getHost() == null
                    || !actual.getHost().equalsIgnoreCase(base.getHost())) {
                throw new IllegalStateException("TTS 音频地址不在 provider origin 内");
            }
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("TTS 音频地址非法", error);
        }
    }

    private static ProviderResolution toProviderResolution(
            VideoGenerationProviderResolver.TtsProviderResolution resolution) {
        return ProviderResolution.platform(resolution.configId(), resolution.provider(),
                resolution.baseUrl(), resolution.model(), resolution.platformModelVersion(),
                resolution.maxConcurrency());
    }

    /** attachRun 后的行内视图（免一次回读：句柄已知的确定性重构）。 */
    /** attachRun 后的行内视图（免一次回读：句柄已知的确定性重构）。 */
    private static VideoShotAudio reload(VideoShotAudio audio, UUID runId, UUID budgetId,
            java.time.LocalDate reservationDate, Integer reservedCents) {
        return new VideoShotAudio(audio.id(), audio.shotId(), audio.provider(), audio.model(),
                audio.providerTaskId(), audio.status(), audio.attempts(), audio.mediaId(), audio.cues(),
                audio.durationMs(), runId, budgetId, reservationDate, reservedCents,
                audio.errorCode(), audio.errorMessage(), audio.nextAttemptAt(), audio.claimedUntil(),
                audio.claimToken(), audio.createdAt(), audio.updatedAt(), audio.completedAt());
    }
}
