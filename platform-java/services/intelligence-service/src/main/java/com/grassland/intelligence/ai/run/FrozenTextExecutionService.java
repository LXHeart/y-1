package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Executes a text run with the provider and model frozen in a creation-context
 * snapshot.
 */
@Service
public class FrozenTextExecutionService {
	private final AiExecutionService executions;
	private final TextCompletionClient textClient;
	private final PlatformConcurrencyLimiter concurrencyLimiter;

	// 任务书 #47 S2：不再注入 env 平台凭据——密钥统一由 ExecutionContext.decryptedKey 提供
	public FrozenTextExecutionService(AiExecutionService executions, TextCompletionClient textClient,
			PlatformConcurrencyLimiter concurrencyLimiter) {
		this.executions = executions;
		this.textClient = textClient;
		this.concurrencyLimiter = concurrencyLimiter;
	}

	public <T> Mono<T> execute(ServerWebExchange exchange, UUID snapshotId, List<ChatMessage> messages, int maxTokens,
			CreditFeature feature, Function<TextCompletionResult, T> transform) {
		int estimatedInputTokens = messages.stream().mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		return executions.prepareExecution(exchange, "text", feature, estimatedInputTokens, maxTokens, true, snapshotId)
				.flatMap(result -> result.allowed()
						? executePrepared(result.context(), messages, maxTokens, null, transform)
						: Mono.error(deniedException(result.denialReason())));
	}

	/**
	 * 独立模式（无任务快照）：与 {@link #executeTraced} 同执行环契约，供 legacy 聚合型文本流 （GL-P3-AI-001
	 * 尾巴：credits.consume + 手动退款迁到 AiExecutionService 单环——预算闸/ai_run
	 * 留痕/积分闭环一套机器）。快照为空 → 冻结配置回落控制面当前值。
	 */
	public <T> Mono<Traced<T>> executeIndependent(ServerWebExchange exchange, List<ChatMessage> messages, int maxTokens,
			CreditFeature feature, Function<TextCompletionResult, T> transform) {
		return executeIndependent(exchange, messages, maxTokens, feature, null, transform);
	}

	/** 同 {@link #executeIndependent}，支持超时覆写（视频分析等多模态长任务）。 */
	public <T> Mono<Traced<T>> executeIndependent(ServerWebExchange exchange, List<ChatMessage> messages, int maxTokens,
			CreditFeature feature, java.time.Duration timeout, Function<TextCompletionResult, T> transform) {
		int estimatedInputTokens = messages.stream().mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		return executions.prepareExecution(exchange, "text", feature, estimatedInputTokens, maxTokens, true, null)
				.flatMap(result -> result.allowed()
						? executeTracedPrepared(result.context(), messages, maxTokens, timeout, transform)
						: Mono.error(deniedException(result.denialReason())));
	}

	/**
	 * 独立模式批量（无任务快照）：多次有序完成合并为一次计费/留痕的 AI run——分段视频分析等 「一次扣费、多段调用」场景（与
	 * {@link #executeBatch} 的任务模式对称）。
	 */
	public <T> Mono<T> executeIndependentBatch(ServerWebExchange exchange, List<List<ChatMessage>> messageBatches,
			int maxTokensPerBatch, CreditFeature feature, java.time.Duration timeout,
			Function<List<TextCompletionResult>, T> transform) {
		if (messageBatches == null || messageBatches.isEmpty()) {
			return Mono.error(new IllegalArgumentException("批量 AI 执行至少需要一个请求"));
		}
		int estimatedInputTokens = messageBatches.stream().flatMap(List::stream)
				.mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		int estimatedOutputTokens = Math.multiplyExact(maxTokensPerBatch, messageBatches.size());
		return executions
				.prepareExecution(exchange, "text", feature, estimatedInputTokens, estimatedOutputTokens, true, null)
				.flatMap(result -> result.allowed()
						? executePreparedBatch(result.context(), messageBatches, maxTokensPerBatch, timeout, transform)
						: Mono.error(deniedException(result.denialReason())));
	}

	/** executeTraced 的已准备态内核（独立/任务共用：trace 元数据从 context 装配）。 */
	private <T> Mono<Traced<T>> executeTracedPrepared(AiExecutionService.ExecutionContext context,
			List<ChatMessage> messages, int maxTokens, java.time.Duration timeout,
			Function<TextCompletionResult, T> transform) {
		return executePrepared(context, messages, maxTokens, timeout, completion -> new Traced<>(
				transform.apply(completion), context.runId(), context.provider().provider(), context.provider().model(),
				context.provider().platformModelVersion() > 0 ? context.provider().platformModelVersion() : null,
				context.provider().isByok()));
	}

	/**
	 * Same execution contract as {@link #execute}, with immutable run/provider
	 * metadata for lineage.
	 */
	public <T> Mono<Traced<T>> executeTraced(ServerWebExchange exchange, UUID snapshotId, List<ChatMessage> messages,
			int maxTokens, CreditFeature feature, Function<TextCompletionResult, T> transform) {
		int estimatedInputTokens = messages.stream().mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		return executions.prepareExecution(exchange, "text", feature, estimatedInputTokens, maxTokens, true, snapshotId)
				.flatMap(result -> {
					if (!result.allowed())
						return Mono.error(deniedException(result.denialReason()));
					AiExecutionService.ExecutionContext context = result.context();
					return executePrepared(context, messages, maxTokens, null,
							completion -> new Traced<>(transform.apply(completion), context.runId(),
									context.provider().provider(), context.provider().model(),
									context.provider().platformModelVersion() > 0
											? context.provider().platformModelVersion()
											: null,
									context.provider().isByok()));
				});
	}

	/**
	 * 独立模式多轮管线执行器：在环内做逐轮完成调用（轮次间可相互依赖——prompt 由上一轮结果派生）。
	 */
	public interface IndependentStageExecutor {
		/** 管线内单轮完成调用（复用同一执行上下文与并发租约）。 */
		Mono<TextCompletionResult> completeRound(List<ChatMessage> messages);
	}

	/**
	 * 独立模式多轮管线（图片评价 draft→optimize→style-refine 等）：一次计费/留痕/退款包裹 N 个 相互依赖的完成调用——与
	 * {@link #executeBatch} 的「一次 AI run、多次调用」同构，但轮次消息可由 前一轮结果在运行时派生。usage
	 * 按轮次累计后一次结算；任一轮失败走环内失败退款。
	 */
	public <T> Mono<Traced<T>> executeIndependentPipeline(ServerWebExchange exchange, CreditFeature feature,
			int estimatedInputTokens, int estimatedOutputTokens, java.time.Duration timeout, int maxTokensPerRound,
			Function<IndependentStageExecutor, Mono<T>> pipeline) {
		return executions
				.prepareExecution(exchange, "text", feature, estimatedInputTokens, estimatedOutputTokens, true, null)
				.flatMap(result -> result.allowed()
						? executePipelinePrepared(result.context(), timeout, maxTokensPerRound, pipeline)
						: Mono.error(deniedException(result.denialReason())));
	}

	private <T> Mono<Traced<T>> executePipelinePrepared(AiExecutionService.ExecutionContext context,
			java.time.Duration timeout, int maxTokensPerRound, Function<IndependentStageExecutor, Mono<T>> pipeline) {
		// 任务书 #47 S2：平台凭据与 BYOK 统一走 decryptedKey——env 兜底已集中在 AiExecutionService
		String bearer = context.decryptedKey();
		return Mono.usingWhen(Mono.just(context),
				ignored -> Mono.usingWhen(concurrencyLimiter.acquire(context.provider()), lease -> {
					java.util.concurrent.atomic.AtomicLong inputTokens = new java.util.concurrent.atomic.AtomicLong();
					java.util.concurrent.atomic.AtomicLong outputTokens = new java.util.concurrent.atomic.AtomicLong();
					IndependentStageExecutor stages = messages -> textClient
							.completeMessages(context.provider().baseUrl(), bearer, context.provider().model(),
									messages, maxTokensPerRound, context.provider().isByok(), timeout)
							.map(completion -> executions.normalizeProviderUsage(context, completion))
							.doOnNext(completion -> {
								inputTokens.addAndGet(completion.inputTokens());
								outputTokens.addAndGet(completion.outputTokens());
							});
					return pipeline.apply(stages)
							.flatMap(value -> executions
									.settleSuccess(context, intValue(inputTokens), intValue(outputTokens), 0, 0)
									.thenReturn(new Traced<>(value, context.runId(), context.provider().provider(),
											context.provider().model(),
											context.provider().platformModelVersion() > 0
													? context.provider().platformModelVersion()
													: null,
											context.provider().isByok())));
				}, PlatformConcurrencyLimiter.Lease::release, (lease, error) -> lease.release(),
						PlatformConcurrencyLimiter.Lease::release),
				ignored -> Mono.empty(), (ignored, error) -> Mono.empty(),
				ignored -> executions.handleCancellation(context).then())
				.onErrorResume(error -> executions
						.handleFailure(context, error.getMessage() == null ? "AI run failed" : error.getMessage())
						.then(Mono.error(error)));
	}

	private static int intValue(java.util.concurrent.atomic.AtomicLong value) {
		long current = value.get();
		if (current < 0 || current > Integer.MAX_VALUE) {
			throw new IntelligenceException(502, "AI provider usage 超出支持范围");
		}
		return (int) current;
	}

	/**
	 * Executes several ordered completions as one frozen, billed and audited AI
	 * run.
	 */
	public <T> Mono<T> executeBatch(ServerWebExchange exchange, UUID snapshotId, List<List<ChatMessage>> messageBatches,
			int maxTokensPerBatch, CreditFeature feature, Function<List<TextCompletionResult>, T> transform) {
		if (messageBatches == null || messageBatches.isEmpty()) {
			return Mono.error(new IllegalArgumentException("批量 AI 执行至少需要一个请求"));
		}
		int estimatedInputTokens = messageBatches.stream().flatMap(List::stream)
				.mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		int estimatedOutputTokens = Math.multiplyExact(maxTokensPerBatch, messageBatches.size());
		return executions
				.prepareExecution(exchange, "text", feature, estimatedInputTokens, estimatedOutputTokens, true,
						snapshotId)
				.flatMap(result -> result.allowed()
						? executePreparedBatch(result.context(), messageBatches, maxTokensPerBatch, null, transform)
						: Mono.error(deniedException(result.denialReason())));
	}

	private <T> Mono<T> executePrepared(AiExecutionService.ExecutionContext context, List<ChatMessage> messages,
			int maxTokens, java.time.Duration timeout, Function<TextCompletionResult, T> transform) {
		// 任务书 #47 S2：同上，密钥来源收敛到 ExecutionContext
		String bearer = context.decryptedKey();
		return Mono
				.usingWhen(Mono.just(context), ignored -> Mono
						.usingWhen(concurrencyLimiter.acquire(context.provider()),
								lease -> textClient.completeMessages(context.provider().baseUrl(), bearer,
										context.provider().model(), messages, maxTokens, context.provider().isByok(),
										timeout),
								PlatformConcurrencyLimiter.Lease::release, (lease, error) -> lease.release(),
								PlatformConcurrencyLimiter.Lease::release)
						.map(completion -> executions.normalizeProviderUsage(context, completion))
						.map(completion -> new Transformed<>(completion, transform.apply(completion))).flatMap(
								transformed -> executions
										.settleSuccess(context, transformed.completion().inputTokens(),
												transformed.completion().outputTokens(), 0, 0)
										.thenReturn(transformed.value())),
						ignored -> Mono.empty(), (ignored, error) -> Mono.empty(),
						ignored -> executions.handleCancellation(context).then())
				.onErrorResume(error -> executions
						.handleFailure(context, error.getMessage() == null ? "AI run failed" : error.getMessage())
						.then(Mono.error(error)));
	}

	private <T> Mono<T> executePreparedBatch(AiExecutionService.ExecutionContext context,
			List<List<ChatMessage>> messageBatches, int maxTokens, java.time.Duration timeout,
			Function<List<TextCompletionResult>, T> transform) {
		// 任务书 #47 S2：同上，密钥来源收敛到 ExecutionContext
		String bearer = context.decryptedKey();
		return Mono
				.usingWhen(Mono.just(context), ignored -> Mono
						.usingWhen(concurrencyLimiter.acquire(context.provider()),
								lease -> Flux.fromIterable(messageBatches)
										.concatMap(messages -> textClient.completeMessages(context.provider().baseUrl(),
												bearer, context.provider().model(), messages, maxTokens,
												context.provider().isByok(), timeout))
										.collectList(),
								PlatformConcurrencyLimiter.Lease::release, (lease, error) -> lease.release(),
								PlatformConcurrencyLimiter.Lease::release)
						.map(completions -> normalizeBatchUsage(context, completions))
						.map(completions -> new TransformedBatch<>(completions, transform.apply(completions)))
						.flatMap(transformed -> executions
								.settleSuccess(context, sumUsage(transformed.completions(), true),
										sumUsage(transformed.completions(), false), 0, 0)
								.thenReturn(transformed.value())),
						ignored -> Mono.empty(), (ignored, error) -> Mono.empty(),
						ignored -> executions.handleCancellation(context).then())
				.onErrorResume(error -> executions
						.handleFailure(context, error.getMessage() == null ? "AI run failed" : error.getMessage())
						.then(Mono.error(error)));
	}

	private List<TextCompletionResult> normalizeBatchUsage(AiExecutionService.ExecutionContext context,
			List<TextCompletionResult> completions) {
		if (!context.provider().isByok())
			return completions;
		if (completions.isEmpty()) {
			throw new IllegalStateException("批量 AI 执行未返回任何结果");
		}
		int input = sumUsage(completions, true);
		int output = sumUsage(completions, false);
		TextCompletionResult aggregate = executions.normalizeProviderUsage(context,
				new TextCompletionResult("", input, output));
		int adjustment = aggregate.inputTokens() - input;
		if (adjustment == 0)
			return completions;
		java.util.ArrayList<TextCompletionResult> normalized = new java.util.ArrayList<>(completions);
		TextCompletionResult first = normalized.getFirst();
		normalized.set(0, new TextCompletionResult(first.content(), Math.addExact(first.inputTokens(), adjustment),
				first.outputTokens(), first.providerRunId()));
		return List.copyOf(normalized);
	}

	private static int sumUsage(List<TextCompletionResult> completions, boolean input) {
		int total = 0;
		for (TextCompletionResult completion : completions) {
			total = Math.addExact(total, input ? completion.inputTokens() : completion.outputTokens());
		}
		return total;
	}

	private static IntelligenceException deniedException(String reason) {
		return switch (reason) {
			case "insufficient_credits" -> new IntelligenceException(402, "积分不足");
			case "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
				new IntelligenceException(402, "已达模型预算上限：" + reason);
			case "unpriced_model" -> new IntelligenceException(503, "平台模型缺少价目配置");
			default -> new IntelligenceException(403, "执行被拒绝：" + reason);
		};
	}

	private static int estimatedMessageBytes(ChatMessage message) {
		int bytes = message.content() == null ? 0 : message.content().getBytes(StandardCharsets.UTF_8).length;
		if (message.parts() == null) {
			return bytes;
		}
		return bytes + message.parts().stream().mapToInt(part -> switch (part) {
			case com.grassland.intelligence.ai.ContentPart.Text text ->
				text.text().getBytes(StandardCharsets.UTF_8).length;
			case com.grassland.intelligence.ai.ContentPart.Image ignored -> 1024;
			case com.grassland.intelligence.ai.ContentPart.Video ignored -> 4096;
		}).sum();
	}

	private record Transformed<T>(TextCompletionResult completion, T value) {
	}

	private record TransformedBatch<T>(List<TextCompletionResult> completions, T value) {
	}

	public record Traced<T>(T value, UUID runId, String provider, String model, Integer platformModelVersion,
			boolean byok) {
	}
}
