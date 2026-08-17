package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
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
    private final VideoTaskCreationContext creationContexts;
    private final FrozenVideoGenerationConfigResolver frozenConfigs;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoGenerationService(VideoGenerationProperties p, VideoGenerationProviderRegistry r,
                                  VideoGenerationJobRepository j, AiExecutionService e,
                                  VideoTaskCreationContext creationContexts,
                                  FrozenVideoGenerationConfigResolver frozenConfigs) {
        properties = p;
        registry = r;
        jobs = j;
        execution = e;
        this.creationContexts = creationContexts;
        this.frozenConfigs = frozenConfigs;
    }

    public Mono<VideoGenerationJob> create(String accountId, String orgId, VideoRequest request) {
        if (request == null || request.script() == null || request.script().isBlank()) {
            return Mono.error(new IllegalArgumentException("视频脚本不能为空"));
        }
        Mono<CreationParameters> parameters = request.isTaskMode()
                ? creationContexts.bind(request.contextSnapshotId(), accountId, request.targetPlatform())
                        .map(binding -> new CreationParameters(
                                frozenConfigs.resolve(binding.snapshot()),
                                binding.snapshot().organizationId(), binding.snapshot().id()))
                : Mono.fromCallable(() -> new CreationParameters(frozenConfigs.current(), orgId, null));
        return parameters.flatMap(params -> create(accountId, request, params));
    }

    private Mono<VideoGenerationJob> create(
            String accountId, VideoRequest request, CreationParameters params) {
        var config = params.config();
        int duration = request.durationSeconds() == null
                ? properties.getDefaultDurationSeconds() : request.durationSeconds();
        if (duration < 1 || duration > config.maxDurationSeconds()) {
            return Mono.error(new IllegalArgumentException("视频时长超出配置范围"));
        }
        String key = request.operationId() == null || request.operationId().isBlank()
                ? UUID.randomUUID().toString() : request.operationId();
        registry.require(config.provider());
        int estimated = Math.multiplyExact(duration, config.unitPriceCents());
        String payload;
        try { payload = mapper.writeValueAsString(request); } catch (Exception e) { return Mono.error(e); }
        return jobs.findByIdempotency(accountId, key).switchIfEmpty(Mono.defer(() ->
                jobs.create(accountId, params.organizationId(), key, params.contextSnapshotId(),
                        config.provider(), config.model(), payload, duration,
                        request.aspectRatio() == null ? "9:16" : request.aspectRatio(), config.pricingVersion(),
                        config.unitPriceCents(), estimated, config.platformModelVersion(), config.runtimeFingerprint())
                .switchIfEmpty(jobs.findByIdempotency(accountId, key))))
                .flatMap(job -> validateIdempotentJob(job, params.contextSnapshotId(), config))
                .flatMap(job -> job.runId() != null ? Mono.just(job) : execution.preparePlatformAsyncExecution(
                        accountId, params.organizationId(), "video_generation", CreditFeature.VIDEO_PRODUCTION_VIDEO,
                        ProviderResolution.platform(null, config.provider(), properties.getBaseUrl(), config.model(), config.platformModelVersion(), null),
                        job.id(), estimated, config.pricingVersion(), params.contextSnapshotId())
                        .flatMap(result -> {
                            if (!result.allowed()) {
                                return Mono.error(new IntelligenceException(402, result.denialReason()));
                            }
                            var b = result.context().budgetReservation();
                            return jobs.attachRun(job.id(), result.context().runId(), b.budgetId(),
                                            b.reservationDate(), b.reservedCents())
                                    .then(jobs.findById(job.id(), accountId));
                        }));
    }

    private Mono<VideoGenerationJob> validateIdempotentJob(
            VideoGenerationJob job, UUID snapshotId,
            FrozenVideoGenerationConfigResolver.Config config) {
        frozenConfigs.resolve(job);
        if (!java.util.Objects.equals(job.contextSnapshotId(), snapshotId)
                || !job.provider().equalsIgnoreCase(config.provider())
                || !job.model().equals(config.model())
                || !job.pricingVersion().equals(config.pricingVersion())
                || job.unitPriceCents() != config.unitPriceCents()
                || job.platformModelVersion() != config.platformModelVersion()) {
            return Mono.error(new IntelligenceException(409, "幂等键已绑定到其他创作上下文或视频配置"));
        }
        return Mono.just(job);
    }

    public Mono<VideoGenerationJob> get(UUID id, String accountId) {
        return jobs.findById(id, accountId);
    }

    public Flux<VideoGenerationJob> list(String accountId) {
        return jobs.findByAccount(accountId);
    }

    public Mono<Boolean> cancel(UUID id, String accountId) {
        return jobs.findById(id, accountId).flatMap(job -> {
            if (job.runId() == null) return jobs.cancel(id, accountId);
            if (job.providerTaskId() != null) return Mono.just(false);
            var provider = ProviderResolution.platform(null, job.provider(), properties.getBaseUrl(), job.model(), job.platformModelVersion(), null);
            var budget = ModelBudgetService.BudgetCheckResult.allowed(
                    job.budgetId(), job.budgetReservationDate(), 0,
                    job.reservedCostCents() == null ? 0 : job.reservedCostCents());
            // provider 提交前取消：handleFailure 释放预留而非记为消耗，因此不会继续占用组织预算。
            var ctx = new AiExecutionService.ExecutionContext(
                    job.runId(), job.organizationId(), job.accountId(), "video_generation",
                    provider, budget, job.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO,
                    true, null, job.pricingVersion(), 0, 0);
            return jobs.cancel(id, accountId).flatMap(cancelled -> cancelled
                    ? execution.handleFailure(ctx, "video job cancelled before provider submission").thenReturn(true)
                    : Mono.just(false));
        }).defaultIfEmpty(false);
    }

    private record CreationParameters(FrozenVideoGenerationConfigResolver.Config config,
                                      String organizationId, UUID contextSnapshotId) {}

    public record VideoRequest(
            String operationId, String script, java.util.List<String> images, String videoStyle,
            String shopName, String shopAddress, Integer durationSeconds, String aspectRatio,
            String targetPlatform, Boolean taskMode, UUID contextSnapshotId) {
        boolean isTaskMode() { return Boolean.TRUE.equals(taskMode); }
    }
}
