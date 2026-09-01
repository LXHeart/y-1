package com.grassland.intelligence.videoproduction;

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
    private final VideoGenerationJobRepository jobs;
    private final AiExecutionService execution;

    public VideoGenerationService(VideoGenerationProperties p,
                                  VideoGenerationJobRepository j, AiExecutionService e) {
        properties = p;
        jobs = j;
        execution = e;
    }

    public Mono<VideoGenerationJob> create(String accountId, String orgId, VideoRequest request) {
        // 任务书 #64 卡6：video_generation_job 停写（§4.4）——分镜成片流程（POST /tasks）替代。
        // 旧 jobs 端点保留只读；存量未终结 job 由旧 worker 的冻结配置漂移路径失败并退款。
        return Mono.error(new IntelligenceException(410, "旧视频生成通道已下线，请使用分镜成片流程"));
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
