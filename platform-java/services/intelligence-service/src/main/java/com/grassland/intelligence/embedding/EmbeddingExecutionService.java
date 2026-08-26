package com.grassland.intelligence.embedding;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Embedding 执行编排（任务书 #33）：统一走 retrieval 能力 Run（Sandbox 0 成本、不扣积分）、
 * 平台并发闸门、Provider 校验与结算。索引用直连账号准备；查询走 HTTP 断言。
 * Run 创建后的任何失败都在此闭环 handleFailure，调用方只需处理行状态与降级。
 */
@Service
public final class EmbeddingExecutionService {

    private static final Pattern STABLE_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final AiExecutionService executions;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final EmbeddingProviderRegistry providers;
    private final EmbeddingProviderProperties providerProperties;
    private final PlatformModelConfig platformDefaults;

    @Autowired
    public EmbeddingExecutionService(
            AiExecutionService executions,
            PlatformConcurrencyLimiter concurrencyLimiter,
            EmbeddingProviderRegistry providers,
            EmbeddingProviderProperties providerProperties,
            PlatformModelConfig platformDefaults) {
        this.executions = executions;
        this.concurrencyLimiter = concurrencyLimiter;
        this.providers = providers;
        this.providerProperties = providerProperties;
        this.platformDefaults = platformDefaults;
    }

    EmbeddingExecutionService(
            AiExecutionService executions,
            PlatformConcurrencyLimiter concurrencyLimiter,
            EmbeddingProviderRegistry providers) {
        this(executions, concurrencyLimiter, providers, null, null);
    }

    /** 索引路径：素材所有者的账号/组织直接准备（后台 worker，无 HTTP 交换）。 */
    public Mono<EmbeddingOutcome> embedForIndexing(String accountId, String organizationId, String normalizedText) {
        return execute(normalizedText, executions.prepareExecution(
                accountId, organizationId, "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                estimateInputTokens(normalizedText), 0, true));
    }

    /** 查询路径：经 HTTP 断言准备（走调用者身份/组织的路由与预算）。 */
    public Mono<EmbeddingOutcome> embedQuery(ServerWebExchange exchange, String normalizedText) {
        return execute(normalizedText, executions.prepareExecution(
                exchange, "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                estimateInputTokens(normalizedText), 0, true));
    }

    private Mono<EmbeddingOutcome> execute(String normalizedText, Mono<AiExecutionService.ExecutionResult> preparation) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Mono.error(new IllegalArgumentException("Embedding 输入文本不能为空"));
        }
        return Mono.usingWhen(
                preparation,
                prepared -> {
                    if (!prepared.allowed()) {
                        return Mono.error(deniedException(prepared.denialReason()));
                    }
                    return executePrepared(normalizedText, prepared.context());
                },
                ignored -> Mono.empty(),
                (prepared, error) -> finalizeRunFailure(prepared, error),
                prepared -> finalizeRunFailure(prepared, new IllegalStateException("execution_cancelled")));
    }

    private Mono<EmbeddingOutcome> executePrepared(
            String normalizedText, AiExecutionService.ExecutionContext context) {
        return Mono.usingWhen(
                concurrencyLimiter.acquire(context.provider()),
                lease -> Mono.defer(() -> {
                    EmbeddingProvider provider = providers.require(context.provider().provider());
                    EmbeddingProvider.Command command = new EmbeddingProvider.Command(
                            normalizedText, invocation(context));
                    // Provider 选择错误（unsupported_provider 等）原样透出；Provider 自身响应/异常统一脱敏。
                    return provider.embed(command)
                            .map(result -> requireValidVector(provider, result))
                            .map(validated -> new ValidatedEmbedding(
                                    validated.vector(), provider, normalizedUsage(context, validated.inputTokens()),
                                    provider.algorithmVersion(command), validated.sandbox()))
                            .flatMap(validated -> requireSettled(
                                    executions.settleSuccess(context, validated.inputTokens(), 0, 0, 0),
                                    context, validated))
                            .onErrorMap(EmbeddingExecutionService::sanitizeProviderError);
                }),
                PlatformConcurrencyLimiter.Lease::release,
                (lease, error) -> lease.release(),
                PlatformConcurrencyLimiter.Lease::release);
    }

    private EmbeddingProvider.Result requireValidVector(EmbeddingProvider provider, EmbeddingProvider.Result result) {
        if (result == null || result.vector() == null
                || result.vector().size() != provider.dimensions()
                || result.vector().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IntelligenceException(502, "provider_invalid_vector", "Embedding 向量维度或数值非法");
        }
        double norm = Math.sqrt(result.vector().stream().mapToDouble(v -> v * v).sum());
        if (!Double.isFinite(norm) || norm == 0.0) {
            throw new IntelligenceException(502, "provider_invalid_vector", "Embedding 向量范数非法");
        }
        return result;
    }

    /** 只放行编码过的 IntelligenceException；Provider 原始异常/消息不外泄。 */
    private static Throwable sanitizeProviderError(Throwable error) {
        if (error instanceof IntelligenceException) {
            return error;
        }
        return new IntelligenceException(502, "provider_failure", "Embedding服务调用失败");
    }

    private Mono<EmbeddingOutcome> requireSettled(
            Mono<Boolean> settlement,
            AiExecutionService.ExecutionContext context,
            ValidatedEmbedding validated) {
        return settlement.flatMap(settled -> settled
                ? Mono.just(new EmbeddingOutcome(
                        validated.vector(), context.provider(),
                        validated.algorithmVersion(), context.runId(),
                        validated.inputTokens(), validated.sandbox()))
                : Mono.error(new IllegalStateException("Embedding Run 结算失败")));
    }

    private Mono<Void> finalizeRunFailure(AiExecutionService.ExecutionResult prepared, Throwable error) {
        if (!prepared.allowed()) {
            return Mono.empty();
        }
        return executions.handleFailure(prepared.context(), "embedding failed: " + failureCode(error))
                .onErrorResume(cleanupError -> Mono.empty())
                .then();
    }

    private static int estimateInputTokens(String normalizedText) {
        return Math.max(1, normalizedText.trim().split("\\s+").length);
    }

    private ProviderInvocation invocation(AiExecutionService.ExecutionContext context) {
        if ("sandbox".equalsIgnoreCase(context.provider().provider())) {
            return null;
        }
        // 任务书 #47 S2：优先级 BYOK > 平台凭据自带密钥 > Embedding 专属配置 > env qwen。
        // 刻意不直接用 decryptedKey()——它在平台分支已含 env 兜底，会把专属配置顶掉。
        String bearer = context.provider().isByok() || context.provider().hasPlatformCredentialKey()
                ? context.decryptedKey()
                : configuredPlatformBearer(
                        context.provider().provider(), context.provider().baseUrl());
        try {
            return new ProviderInvocation(
                    context.provider().provider(), context.provider().baseUrl(),
                    context.provider().model(), bearer, context.provider().isByok());
        } catch (IllegalArgumentException error) {
            throw new IntelligenceException(
                    503, "provider_credentials_missing", "Embedding Provider 配置不完整");
        }
    }

    private String configuredPlatformBearer(String provider, String baseUrl) {
        String configured = providerProperties == null ? null : providerProperties.apiKey();
        if (providerProperties != null && !providerProperties.sandbox()
                && provider.equalsIgnoreCase(providerProperties.provider())
                && sameBaseUrl(baseUrl, providerProperties.baseUrl())
                && configured != null && !configured.isBlank()) {
            return configured;
        }
        return "qwen".equalsIgnoreCase(provider) && platformDefaults != null
                        && sameBaseUrl(baseUrl, platformDefaults.baseUrl())
                ? platformDefaults.apiKey() : null;
    }

    private static boolean sameBaseUrl(String left, String right) {
        return left != null && right != null
                && left.trim().replaceFirst("/+$", "")
                        .equals(right.trim().replaceFirst("/+$", ""));
    }

    private static int normalizedUsage(AiExecutionService.ExecutionContext context, int reportedInputTokens) {
        return context.provider().isByok()
                ? Math.max(reportedInputTokens, context.estimatedInputTokens())
                : reportedInputTokens;
    }

    private static String failureCode(Throwable error) {
        if (error instanceof IntelligenceException intelligence
                && intelligence.code() != null
                && STABLE_CODE.matcher(intelligence.code()).matches()) {
            return intelligence.code();
        }
        return "provider_failure";
    }

    private static IntelligenceException deniedException(String reason) {
        return switch (reason == null ? "" : reason) {
            case "no_platform_model" ->
                    new IntelligenceException(503, "no_platform_model", "平台未配置Embedding模型");
            case "unpriced_model" ->
                    new IntelligenceException(503, "unpriced_model", "Embedding模型缺少价目配置");
            case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
                    new IntelligenceException(402, reason, "已达Embedding预算上限");
            default -> new IntelligenceException(403, stableCode(reason, "execution_denied"), "Embedding执行被拒绝");
        };
    }

    private static String stableCode(String candidate, String fallback) {
        return candidate != null && STABLE_CODE.matcher(candidate).matches() ? candidate : fallback;
    }

    private record ValidatedEmbedding(
            List<Double> vector,
            EmbeddingProvider provider,
            int inputTokens,
            String algorithmVersion,
            boolean sandbox) {}

    /** 成功结果：向量 + 路由快照 + Run 元数据（用于索引行持久化与查询响应元数据）。 */
    public record EmbeddingOutcome(
            List<Double> vector,
            ProviderResolution provider,
            String algorithmVersion,
            UUID runId,
            int inputTokens,
            boolean sandbox) {}
}
