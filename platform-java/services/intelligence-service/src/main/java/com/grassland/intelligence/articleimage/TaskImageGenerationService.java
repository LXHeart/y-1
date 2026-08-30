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
    private final com.grassland.intelligence.creationcontext.FrozenAiConfigResolver frozenAiConfigs;
    private final ImageGenerationConfig runtimeConfig;
    private final AiExecutionService executions;

    public TaskImageGenerationService(
            ArticleImageService images,
            GraphicTaskCreationContext creationContexts,
            FrozenImageGenerationConfigResolver frozenConfigs,
            com.grassland.intelligence.creationcontext.FrozenAiConfigResolver frozenAiConfigs,
            ImageGenerationConfig runtimeConfig,
            AiExecutionService executions) {
        this.images = images;
        this.creationContexts = creationContexts;
        this.frozenConfigs = frozenConfigs;
        this.frozenAiConfigs = frozenAiConfigs;
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
        // 任务书 #56：快照冻结了图像 BYOK 键则优先消费（轮换/禁用 409 fail-closed）；否则平台路径不变
        return frozenAiConfigs.resolveImageProvider(snapshot, snapshot.accountId())
                .flatMap(provider -> generateByok(command, snapshot, promptContext, purpose, provider))
                .switchIfEmpty(Mono.defer(() -> generatePlatform(command, snapshot, promptContext, purpose)));
    }

    private Mono<GeneratedImageWithTrace> generatePlatform(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            ChatMessage promptContext,
            MediaPurpose purpose) {
        // 任务书 #58 决策 G：平台路径 = 当前控制面 image_generation 行（漂移在 resolve 内 409 复查），
        // 价目按任务创建时冻结值；静态 env 端点已删，无行即 503。
        ArticleImageService.GenerateCommand frozenCommand = new ArticleImageService.GenerateCommand(
                promptContext.content() + "\n\n用户生图要求：\n" + command.prompt(),
                command.size(), command.images());
        return frozenConfigs.resolve(snapshot)
                .flatMap(execution -> {
                    ProviderResolution provider = ProviderResolution.platform(
                            execution.platform().configId(), execution.platform().provider(),
                            execution.platform().baseUrl(), execution.platform().model(),
                            execution.platform().version(), execution.platform().maxConcurrency(),
                            execution.platform().credentialEncryptedKey(), execution.platform().credentialVersion());
                    UUID operationId = UUID.randomUUID();
                    return executions.prepareMediaExecution(
                                    snapshot.accountId(), snapshot.organizationId(),
                                    "image_generation", null, provider, operationId,
                                    execution.pricing().unitPriceCents(), execution.pricing().pricingVersion(),
                                    snapshot.id())
                            .flatMap(result -> result.allowed()
                                    ? executePlatform(frozenCommand, snapshot, purpose, execution,
                                            provider, result.context())
                                    : Mono.error(denied(result.denialReason())));
                });
    }

    private Mono<GeneratedImageWithTrace> generateByok(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            ChatMessage promptContext,
            MediaPurpose purpose,
            ProviderResolution provider) {
        // 与平台分支同款冻结拼装：任务上下文 + 用户生图要求（BYOK 不改变任务语义）
        ArticleImageService.GenerateCommand frozenCommand = new ArticleImageService.GenerateCommand(
                promptContext.content() + "\n\n用户生图要求：\n" + command.prompt(),
                command.size(), command.images());
        UUID operationId = UUID.randomUUID();
        return executions.prepareMediaExecution(
                        snapshot.accountId(), snapshot.organizationId(),
                        "image_generation", null, provider, operationId,
                        0, runtimeConfig.pricingVersion(), snapshot.id())
                .flatMap(result -> result.allowed()
                        ? executeByok(frozenCommand, snapshot, purpose, provider, result.context())
                        : Mono.error(denied(result.denialReason())));
    }

    private Mono<GeneratedImageWithTrace> executeByok(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            MediaPurpose purpose,
            ProviderResolution provider,
            AiExecutionService.ExecutionContext context) {
        MediaOwner owner = new MediaOwner(snapshot.accountId(), snapshot.organizationId());
        ImageGenerationClient.Endpoint endpoint =
                ImageGenerationClient.Endpoint.of(provider, context.decryptedKey());
        return Mono.usingWhen(
                Mono.just(context),
                ignored -> images.generate(command, owner, purpose, endpoint)
                        .flatMap(result -> executions.settleSuccessWithCost(context, 0, 0, 0, 1, 0)
                                .thenReturn(new GeneratedImageWithTrace(
                                        result, context.runId(), context.provider().provider(),
                                        context.provider().model(), null))),
                ignored -> Mono.empty(),
                (ignored, error) -> executions.handleFailure(
                        context, error.getMessage() == null ? "image generation failed" : error.getMessage()).then(),
                ignored -> executions.handleCancellation(context).then());
    }

    private Mono<GeneratedImageWithTrace> executePlatform(
            ArticleImageService.GenerateCommand command,
            CreationContextSnapshot snapshot,
            MediaPurpose purpose,
            FrozenImageGenerationConfigResolver.ResolvedExecution execution,
            ProviderResolution provider,
            AiExecutionService.ExecutionContext context) {
        MediaOwner owner = new MediaOwner(
                snapshot.accountId(), snapshot.organizationId());
        ImageGenerationClient.Endpoint endpoint = ImageGenerationClient.Endpoint.of(
                provider, context.decryptedKey());
        return Mono.usingWhen(
                Mono.just(context),
                ignored -> images.generate(command, owner, purpose, endpoint)
                        .flatMap(result -> executions.settleSuccessWithCost(
                                        context, execution.pricing().unitPriceCents(), 0, 0, 1, 0)
                                .thenReturn(new GeneratedImageWithTrace(
                                        result, context.runId(), context.provider().provider(),
                                        context.provider().model(), execution.platform().version()))),
                ignored -> Mono.empty(),
                (ignored, error) -> executions.handleFailure(
                        context, error.getMessage() == null ? "image generation failed" : error.getMessage()).then(),
                ignored -> executions.handleCancellation(context).then());
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
