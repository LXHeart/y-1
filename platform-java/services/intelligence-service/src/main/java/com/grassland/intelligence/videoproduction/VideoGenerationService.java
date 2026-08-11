package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class VideoGenerationService {
    private final VideoGenerationProperties properties;
    private final VideoGenerationProviderRegistry registry;
    private final VideoGenerationJobRepository jobs;
    private final AiExecutionService execution;
    private final ObjectMapper mapper = new ObjectMapper();
    public VideoGenerationService(VideoGenerationProperties p, VideoGenerationProviderRegistry r, VideoGenerationJobRepository j, AiExecutionService e) { properties=p; registry=r; jobs=j; execution=e; }
    public Mono<VideoGenerationJob> create(String accountId, String orgId, VideoRequest request) {
        int duration = request.durationSeconds() == null ? properties.getDefaultDurationSeconds() : request.durationSeconds();
        if (duration < 1 || duration > properties.getMaxDurationSeconds()) return Mono.error(new IllegalArgumentException("视频时长超出配置范围"));
        String key = request.operationId() == null || request.operationId().isBlank() ? UUID.randomUUID().toString() : request.operationId();
        VideoGenerationProvider provider = registry.active();
        int estimated = Math.multiplyExact(duration, properties.getUnitPriceCents());
        String payload;
        try { payload = mapper.writeValueAsString(request); } catch (Exception e) { return Mono.error(e); }
        return jobs.findByIdempotency(accountId, key).switchIfEmpty(Mono.defer(() ->
                jobs.create(accountId, orgId, key, provider.id(), properties.getModel(), payload, duration,
                        request.aspectRatio() == null ? "9:16" : request.aspectRatio(), properties.getPricingVersion(),
                        properties.getUnitPriceCents(), estimated, properties.getPlatformModelVersion())
                .switchIfEmpty(jobs.findByIdempotency(accountId, key))))
                .flatMap(job -> job.runId() != null ? Mono.just(job) : execution.preparePlatformAsyncExecution(
                        accountId, orgId, "video_generation", CreditFeature.VIDEO_PRODUCTION_VIDEO,
                        ProviderResolution.platform(null, provider.id(), properties.getBaseUrl(), properties.getModel(), properties.getPlatformModelVersion(), properties.getMaxConcurrency()),
                        job.id(), estimated, properties.getPricingVersion())
                        .flatMap(result -> {
                            if (!result.allowed()) return Mono.error(new IntelligenceException(402, result.denialReason()));
                            var b = result.context().budgetReservation();
                            return jobs.attachRun(job.id(), result.context().runId(), b.budgetId(), b.reservationDate(), b.reservedCents()).then(jobs.findById(job.id(), accountId));
                        }));
    }
    public Mono<VideoGenerationJob> get(UUID id, String accountId) { return jobs.findById(id, accountId); }
    public Flux<VideoGenerationJob> list(String accountId) { return jobs.findByAccount(accountId); }
    public Mono<Boolean> cancel(UUID id, String accountId) {
        return jobs.findById(id, accountId).flatMap(job -> {
            if (job.runId() == null) return jobs.cancel(id, accountId);
            if (job.providerTaskId() != null) return Mono.just(false);
            var provider = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), properties.getMaxConcurrency());
            var budget = ModelBudgetService.BudgetCheckResult.allowed(job.budgetId(), job.budgetReservationDate(), 0, job.reservedCostCents() == null ? 0 : job.reservedCostCents());
            var ctx = new AiExecutionService.ExecutionContext(job.runId(), job.organizationId(), job.accountId(), "video_generation", provider, budget, job.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO, true, null, job.pricingVersion(), 0, 0);
            return jobs.cancel(id, accountId).flatMap(cancelled -> cancelled
                    ? execution.handleFailure(ctx, "video job cancelled before provider submission").thenReturn(true)
                    : Mono.just(false));
        }).defaultIfEmpty(false);
    }
    public record VideoRequest(String operationId, String script, java.util.List<String> images, String videoStyle, String shopName, String shopAddress, Integer durationSeconds, String aspectRatio) {}
}
