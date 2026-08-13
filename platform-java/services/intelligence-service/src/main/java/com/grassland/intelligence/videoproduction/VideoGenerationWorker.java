package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.credits.CreditFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class VideoGenerationWorker {
    private final VideoGenerationProperties properties;
    private final VideoGenerationJobRepository jobs;
    private final VideoGenerationProviderRegistry registry;
    private final AiExecutionService execution;
    private final AiRunRepository runs;
    private final FrozenVideoGenerationConfigResolver frozenConfigs;
    private final VideoAssetArchiveService archives;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoGenerationWorker(VideoGenerationProperties p, VideoGenerationJobRepository j,
                                 VideoGenerationProviderRegistry r, AiExecutionService e,
                                 AiRunRepository runs, FrozenVideoGenerationConfigResolver frozenConfigs,
                                 VideoAssetArchiveService archives) {
        properties = p;
        jobs = j;
        registry = r;
        execution = e;
        this.runs = runs;
        this.frozenConfigs = frozenConfigs;
        this.archives = archives;
    }

    @Scheduled(fixedDelayString = "${ai.video-generation.poll-interval:3s}")
    public void dispatch() {
        if (!properties.isWorkerEnabled()) return;
        jobs.claimBatch(properties.getBatchSize(), properties.getClaimLease())
                .flatMap(this::process)
                .onErrorContinue((error, job) -> {})
                .subscribe();
    }

    /** Called by deployment schedulers/tests with a concrete job; intentionally public for deterministic testing. */
    public reactor.core.publisher.Mono<Void> process(VideoGenerationJob job) {
        if (isTerminal(job.status())) {
            return Mono.empty();
        }
        if (job.attemptCount() >= properties.getMaxAttempts()) {
            var timeout = new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.FAILED,
                    job.providerTaskId(), 100, null,
                    job.actualDurationSeconds() == null
                            ? job.requestedDurationSeconds() : job.actualDurationSeconds(),
                    "provider_timeout", "视频 provider 在最大重试次数内未完成");
            return fail(job, timeout).then(jobs.update(job.id(), timeout)).then();
        }
        FrozenVideoGenerationConfigResolver.Config config;
        try {
            config = frozenConfigs.resolve(job);
        } catch (RuntimeException error) {
            var failure = new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.FAILED, job.providerTaskId(),
                    100, null, job.requestedDurationSeconds(), "provider_config_drift",
                    error.getMessage());
            return fail(job, failure).then(jobs.update(job.id(), failure)).then();
        }
        VideoGenerationProvider provider = registry.require(config.provider());
        VideoGenerationService.VideoRequest input;
        try {
            input = mapper.readValue(job.inputPayload(), VideoGenerationService.VideoRequest.class);
        } catch (Exception error) {
            return reactor.core.publisher.Mono.error(error);
        }
        var command = new VideoGenerationProvider.ProviderCommand(
                job.id(), job.model(), input.script(),
                input.images() == null ? java.util.List.of() : input.images(),
                job.requestedDurationSeconds(), job.aspectRatio());
        reactor.core.publisher.Mono<VideoGenerationProvider.ProviderResult> result = job.providerTaskId() == null
                ? provider.submit(command) : provider.poll(job.providerTaskId(), job.requestedDurationSeconds());
        return result.flatMap(r -> switch (r.state()) {
            case SUCCEEDED -> completeOrReject(job, r);
            case FAILED -> fail(job, r).then(jobs.update(job.id(), r)).then();
            default -> jobs.update(job.id(), r).then();
        });
    }

    public reactor.core.publisher.Mono<Void> processWebhook(
            VideoGenerationJob job, VideoGenerationProvider.ProviderResult result) {
        if (isTerminal(job.status())) {
            return Mono.empty();
        }
        if (job.attemptCount() >= properties.getMaxAttempts()
                && result.state() != VideoGenerationProvider.ProviderResult.State.SUCCEEDED) {
            var timeout = new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.FAILED,
                    result.providerTaskId(), 100, null,
                    result.durationSeconds() == null ? job.requestedDurationSeconds() : result.durationSeconds(),
                    "provider_timeout", "视频 provider 在最大重试次数内未完成");
            return fail(job, timeout).then(jobs.update(job.id(), timeout)).then();
        }
        return switch (result.state()) {
            case SUCCEEDED -> completeOrReject(job, result);
            case FAILED -> fail(job, result).then(jobs.update(job.id(), result)).then();
            default -> jobs.update(job.id(), result).then();
        };
    }

    private reactor.core.publisher.Mono<Void> archiveAndSettle(
            VideoGenerationJob job, VideoGenerationProvider.ProviderResult result) {
        return archives.archive(job, result.resultUrl())
                .onErrorResume(error -> jobs.update(job.id(), new VideoGenerationProvider.ProviderResult(
                                VideoGenerationProvider.ProviderResult.State.PROCESSING,
                                result.providerTaskId(), result.progress(), null, result.durationSeconds(),
                                "archive_pending", error.getMessage()))
                        .then(Mono.error(new VideoArchivePendingException(error))))
                .flatMap(reference -> settle(job, result)
                        .then(jobs.setResultReference(job.id(), reference))
                        .flatMap(saved -> saved
                                ? jobs.update(job.id(), new VideoGenerationProvider.ProviderResult(
                                        result.state(), result.providerTaskId(), result.progress(), null,
                                        result.durationSeconds(), result.errorCode(), result.errorMessage()))
                                .flatMap(updated -> updated
                                        ? Mono.empty()
                                        : Mono.error(new IllegalStateException("视频 Job 状态更新失败")))
                                : Mono.error(new IllegalStateException("视频媒体引用写入失败"))))
                .then();
    }

    private Mono<Void> completeOrReject(
            VideoGenerationJob job, VideoGenerationProvider.ProviderResult result) {
        String validationError = validateUsage(job, result);
        if (validationError == null) {
            return archiveAndSettle(job, result);
        }
        var failure = new VideoGenerationProvider.ProviderResult(
                VideoGenerationProvider.ProviderResult.State.FAILED,
                result.providerTaskId(), 100, null, result.durationSeconds(),
                "invalid_provider_usage", validationError);
        return fail(job, failure).then(jobs.update(job.id(), failure)).then();
    }

    private String validateUsage(
            VideoGenerationJob job, VideoGenerationProvider.ProviderResult result) {
        int seconds = result.durationSeconds() == null
                ? job.requestedDurationSeconds() : result.durationSeconds();
        if (seconds < 1 || seconds > properties.getMaxDurationSeconds()) {
            return "provider 返回的视频时长超出配置范围";
        }
        try {
            Math.multiplyExact(seconds, job.unitPriceCents());
            return null;
        } catch (ArithmeticException error) {
            return "视频实际成本溢出";
        }
    }

    private static final class VideoArchivePendingException extends RuntimeException {
        private VideoArchivePendingException(Throwable cause) { super(cause); }
    }

    private static boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private reactor.core.publisher.Mono<Void> settle(VideoGenerationJob job, VideoGenerationProvider.ProviderResult r) {
        if (job.runId() == null) {
            return reactor.core.publisher.Mono.empty();
        }
        var resolution = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), null);
        var budget = budget(job);
        var ctx = executionContext(job, resolution, budget);
        int seconds = r.durationSeconds() == null ? job.requestedDurationSeconds() : r.durationSeconds();
        int cost;
        try {
            cost = Math.multiplyExact(seconds, job.unitPriceCents());
        } catch (ArithmeticException error) {
            return reactor.core.publisher.Mono.error(new IllegalStateException("视频实际成本溢出", error));
        }
        return runs.findById(job.runId()).flatMap(run -> "completed".equals(run.status())
                ? requireCostUpdate(job, cost)
                : execution.settleSuccessWithCost(ctx, cost, 0, 0, 0, seconds)
                        .then(requireCostUpdate(job, cost)));
    }

    private Mono<Void> requireCostUpdate(VideoGenerationJob job, int cost) {
        return jobs.setCost(job.id(), cost).flatMap(updated -> updated
                ? Mono.empty()
                : Mono.error(new IllegalStateException("视频实际成本写入失败")));
    }

    private reactor.core.publisher.Mono<Void> fail(VideoGenerationJob job, VideoGenerationProvider.ProviderResult r) {
        if (job.runId() == null) {
            return reactor.core.publisher.Mono.empty();
        }
        var resolution = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), null);
        var ctx = executionContext(job, resolution, budget(job));
        return execution.handleFailure(ctx, r.errorMessage() == null ? "video provider failed" : r.errorMessage()).then();
    }

    private static ModelBudgetService.BudgetCheckResult budget(VideoGenerationJob job) {
        return ModelBudgetService.BudgetCheckResult.allowed(
                job.budgetId(), job.budgetReservationDate(), 0,
                job.reservedCostCents() == null ? 0 : job.reservedCostCents());
    }

    private static AiExecutionService.ExecutionContext executionContext(
            VideoGenerationJob job, ProviderResolution resolution,
            ModelBudgetService.BudgetCheckResult budget) {
        return new AiExecutionService.ExecutionContext(
                job.runId(), job.organizationId(), job.accountId(), "video_generation",
                resolution, budget, job.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO,
                true, null, job.pricingVersion(), 0, 0);
    }
}
