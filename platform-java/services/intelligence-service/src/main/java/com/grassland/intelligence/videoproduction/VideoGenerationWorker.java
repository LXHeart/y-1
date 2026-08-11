package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.credits.CreditFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VideoGenerationWorker {
    private final VideoGenerationProperties properties;
    private final VideoGenerationJobRepository jobs;
    private final VideoGenerationProviderRegistry registry;
    private final AiExecutionService execution;
    private final AiRunRepository runs;
    private final ObjectMapper mapper = new ObjectMapper();
    public VideoGenerationWorker(VideoGenerationProperties p, VideoGenerationJobRepository j, VideoGenerationProviderRegistry r, AiExecutionService e, AiRunRepository runs) { properties=p; jobs=j; registry=r; execution=e; this.runs=runs; }

    @Scheduled(fixedDelayString = "${ai.video-generation.poll-interval:3s}")
    public void dispatch() {
        if (!properties.isWorkerEnabled()) return;
        jobs.claimBatch(properties.getBatchSize(), properties.getClaimLease()).flatMap(this::process).onErrorContinue((e, x) -> {}).subscribe();
    }

    /** Called by deployment schedulers/tests with a concrete job; intentionally public for deterministic testing. */
    public reactor.core.publisher.Mono<Void> process(VideoGenerationJob job) {
        VideoGenerationProvider provider = registry.active();
        VideoGenerationService.VideoRequest input;
        try { input = mapper.readValue(job.inputPayload(), VideoGenerationService.VideoRequest.class); }
        catch (Exception e) { return reactor.core.publisher.Mono.error(e); }
        var command = new VideoGenerationProvider.ProviderCommand(job.id(), job.model(), input.script(), input.images() == null ? java.util.List.of() : input.images(), job.requestedDurationSeconds(), job.aspectRatio());
        reactor.core.publisher.Mono<VideoGenerationProvider.ProviderResult> result = job.providerTaskId() == null
                ? provider.submit(command) : provider.poll(job.providerTaskId(), job.requestedDurationSeconds());
        return result.flatMap(r -> switch (r.state()) {
            case SUCCEEDED -> settle(job, r).then(jobs.update(job.id(), r)).then();
            case FAILED -> fail(job, r).then(jobs.update(job.id(), r)).then();
            default -> jobs.update(job.id(), r).then();
        });
    }

    private reactor.core.publisher.Mono<Void> settle(VideoGenerationJob job, VideoGenerationProvider.ProviderResult r) {
        if (job.runId() == null) return reactor.core.publisher.Mono.empty();
        var resolution = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), properties.getMaxConcurrency());
        var budget = ModelBudgetService.BudgetCheckResult.allowed(job.budgetId(), job.budgetReservationDate(), 0, job.reservedCostCents() == null ? 0 : job.reservedCostCents());
        var ctx = new AiExecutionService.ExecutionContext(job.runId(), job.organizationId(), job.accountId(), "video_generation", resolution, budget, job.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO, true, null, job.pricingVersion(), 0, 0);
        int seconds = r.durationSeconds() == null ? job.requestedDurationSeconds() : r.durationSeconds();
        int cost = Math.multiplyExact(seconds, job.unitPriceCents());
        return runs.findById(job.runId()).flatMap(run -> "completed".equals(run.status())
                ? jobs.setCost(job.id(), cost).then()
                : execution.settleSuccessWithCost(ctx, cost, 0, 0, 0, seconds)
                        .then(jobs.setCost(job.id(), cost)).then());
    }
    private reactor.core.publisher.Mono<Void> fail(VideoGenerationJob job, VideoGenerationProvider.ProviderResult r) {
        if (job.runId() == null) return reactor.core.publisher.Mono.empty();
        var resolution = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), properties.getMaxConcurrency());
        var budget = ModelBudgetService.BudgetCheckResult.allowed(job.budgetId(), job.budgetReservationDate(), 0, job.reservedCostCents() == null ? 0 : job.reservedCostCents());
        var ctx = new AiExecutionService.ExecutionContext(job.runId(), job.organizationId(), job.accountId(), "video_generation", resolution, budget, job.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO, true, null, job.pricingVersion(), 0, 0);
        return execution.handleFailure(ctx, r.errorMessage() == null ? "video provider failed" : r.errorMessage()).then();
    }
}
