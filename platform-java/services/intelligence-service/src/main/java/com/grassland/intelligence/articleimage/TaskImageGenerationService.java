package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.creationcontext.GraphicTaskCreationContext;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Executes task-bound image generation with frozen provider metadata and AI-run audit. */
@Service
public class TaskImageGenerationService {
    private final ArticleImageService images;
    private final GraphicTaskCreationContext creationContexts;
    private final FrozenImageGenerationConfigResolver frozenConfigs;
    private final ImageGenerationConfig runtimeConfig;
    private final AiExecutionService executions;

    public TaskImageGenerationService(
            ArticleImageService images,
            GraphicTaskCreationContext creationContexts,
            FrozenImageGenerationConfigResolver frozenConfigs,
            ImageGenerationConfig runtimeConfig,
            AiExecutionService executions) {
        this.images = images;
        this.creationContexts = creationContexts;
        this.frozenConfigs = frozenConfigs;
        this.runtimeConfig = runtimeConfig;
        this.executions = executions;
    }

    public Mono<GeneratedImageResponse> generate(
            ArticleImageService.GenerateCommand command, String accountId,
            UUID snapshotId, String targetPlatform) {
        return creationContexts.bind(snapshotId, accountId, targetPlatform)
                .flatMap(binding -> generateForBoundContext(
                        command, binding.snapshot(), binding.promptContext(),
                        MediaPurpose.ARTICLE_GENERATED));
    }

    /** Executes image generation after the caller has validated a task snapshot for its workflow. */
    public Mono<GeneratedImageResponse> generateForBoundContext(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            ChatMessage promptContext,
            MediaPurpose purpose) {
        return generateForBoundContextTraced(command, snapshot, promptContext, purpose)
                .map(GeneratedImageWithTrace::response);
    }

    /** Internal traced variant; public endpoint response remains {@link GeneratedImageResponse}. */
    public Mono<GeneratedImageWithTrace> generateForBoundContextTraced(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            ChatMessage promptContext,
            MediaPurpose purpose) {
        FrozenImageGenerationConfigResolver.Config config = frozenConfigs.resolve(snapshot);
        ArticleImageService.GenerateCommand frozenCommand = new ArticleImageService.GenerateCommand(
                promptContext.content() + "\n\n用户生图要求：\n" + command.prompt(),
                command.size(), command.images());
        ProviderResolution provider = ProviderResolution.platform(
                null, config.provider(), runtimeConfig.baseUrl(), config.model(),
                config.platformModelVersion(), null);
        UUID operationId = UUID.randomUUID();
        return executions.preparePlatformAsyncExecution(
                        snapshot.accountId(), snapshot.organizationId(),
                        "image_generation", null, provider, operationId,
                        config.unitPriceCents(), config.pricingVersion(), snapshot.id())
                .flatMap(result -> result.allowed()
                        ? execute(frozenCommand, snapshot, purpose, config, result.context())
                        : Mono.error(denied(result.denialReason())));
    }

    private Mono<GeneratedImageWithTrace> execute(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            MediaPurpose purpose,
            FrozenImageGenerationConfigResolver.Config config,
            AiExecutionService.ExecutionContext context) {
        MediaOwner owner = new MediaOwner(
                snapshot.accountId(), snapshot.organizationId());
        return Mono.usingWhen(
                Mono.just(context),
                ignored -> generateImage(command, owner, purpose)
                        .flatMap(result -> executions.settleSuccessWithCost(
                                        context, config.unitPriceCents(), 0, 0, 1, 0)
                                .thenReturn(new GeneratedImageWithTrace(
                                        result, context.runId(), context.provider().provider(),
                                        context.provider().model(), config.platformModelVersion()))),
                ignored -> Mono.empty(),
                (ignored, error) -> executions.handleFailure(
                        context, error.getMessage() == null ? "image generation failed" : error.getMessage()).then(),
                ignored -> executions.handleCancellation(context).then());
    }

    private Mono<GeneratedImageResponse> generateImage(
            ArticleImageService.GenerateCommand command, MediaOwner owner, MediaPurpose purpose) {
        return purpose == MediaPurpose.ARTICLE_GENERATED
                ? images.generate(command, owner)
                : images.generate(command, owner, purpose);
    }

    private static IntelligenceException denied(String reason) {
        return switch (reason) {
            case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget",
                    "exceeds_monthly_budget" -> new IntelligenceException(402, "图片生成预算不足：" + reason);
            default -> new IntelligenceException(403, "图片生成执行被拒绝：" + reason);
        };
    }

    public record GeneratedImageWithTrace(
            GeneratedImageResponse response, UUID aiRunId, String provider,
            String model, Integer platformModelVersion) {}
}
