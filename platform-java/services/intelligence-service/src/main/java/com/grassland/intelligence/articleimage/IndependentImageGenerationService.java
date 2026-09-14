package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 独立模式生图（任务书 #56）：原先走 legacy 免费路径（无 ai_run、无预算闸），现接入执行环—— 按活动身份路由（D9：商家组织 BYOK
 * &gt; 平台；推荐官/消费者个人 BYOK &gt; 平台）。BYOK 零成本 不扣积分（D-11）；平台沿用静态 env 价目走 cents
 * 预算闸（image_generation 无积分键惯例不变）， 响应契约（/generated-images URL + media 登记 +
 * 多模态送审）与 legacy 路径一致。
 *
 * <p>
 * 2026-08-30 起（PRD §4.10 平台层）：resolveProvider 的平台分支即治理台控制面 image_generation 行
 * （带凭据与版本）。任务书 #58 决策 G 起控制面无行不再回落静态 env——503 引导治理台配置。
 */
@Service
public class IndependentImageGenerationService {
	private final ArticleImageService images;
	private final ByokRoutingService routing;
	private final ImageGenerationConfig runtimeConfig;
	private final AiExecutionService executions;

	public IndependentImageGenerationService(ArticleImageService images, ByokRoutingService routing,
			ImageGenerationConfig runtimeConfig, AiExecutionService executions) {
		this.images = images;
		this.routing = routing;
		this.runtimeConfig = runtimeConfig;
		this.executions = executions;
	}

	public Mono<GeneratedImageResponse> generate(ArticleImageService.GenerateCommand command, String accountId,
			String organizationId) {
		return generate(command, accountId, organizationId,
				com.grassland.intelligence.media.MediaPurpose.ARTICLE_GENERATED).map(Traced::response);
	}

	/** 任务书 #54（2026-08-30 重构）：带 purpose 与留痕元数据的完整形态——图卡等复用方调用。 */
	public Mono<Traced> generate(ArticleImageService.GenerateCommand command, String accountId, String organizationId,
			com.grassland.intelligence.media.MediaPurpose purpose) {
		return generate(command, accountId, organizationId, purpose, null, null);
	}

	/**
	 * 任务书 #101 C101-08（§6.6 运行拆分）：固定 executionOperationId 与 observer 的重载。
	 *
	 * <p>
	 * observer.prepared 失败阻止外部请求（供应商调用 0）；原图按确定性 key 保存后先 generated() 再结算；
	 * 媒体已保存而结算失败时<b>不</b>走 handleFailure——run 保持 running，子项停 generated_unsettled
	 * 由恢复 链路只重放结算（不重发供应商请求）。
	 */
	public Mono<Traced> generate(ArticleImageService.GenerateCommand command, String accountId, String organizationId,
			com.grassland.intelligence.media.MediaPurpose purpose, UUID executionOperationId,
			ImageExecutionObserver observer) {
		return routing.resolveProvider(organizationId, accountId, "image_generation", true).flatMap(provider -> {
			if (provider.isByok() || provider.isPlatform()) {
				// BYOK 命中，或治理台控制面 image_generation 行（resolveProvider 平台分支）
				return execute(command, accountId, organizationId, purpose, provider, executionOperationId, observer);
			}
			// 决策 G：控制面无行不再回落静态 env——按拒绝处理（no_platform_model →
			// 503 引导治理台配置；组织回退策略禁止则维持 403）
			return Mono.error(denied(provider.denialReason()));
		});
	}

	/** 独立生图留痕元数据（图卡 lineage 取首卡 run）。 */
	public record Traced(GeneratedImageResponse response, UUID aiRunId, String provider, String model) {
	}

	private Mono<Traced> execute(ArticleImageService.GenerateCommand command, String accountId, String organizationId,
			com.grassland.intelligence.media.MediaPurpose purpose, ProviderResolution provider,
			UUID executionOperationId, ImageExecutionObserver observer) {
		return generateFrozen(command, accountId, organizationId, purpose, provider,
				provider.isByok() ? 0 : runtimeConfig.unitPriceCents(), runtimeConfig.pricingVersion(),
				executionOperationId, observer);
	}

	/**
	 * Use the accepted quote's exact provider and price after the studio
	 * revalidates its fingerprint.
	 */
	public Mono<Traced> generateFrozen(ArticleImageService.GenerateCommand command, String accountId,
			String organizationId, MediaPurpose purpose, ProviderResolution provider, int unitPriceCents,
			String pricingVersion, UUID executionOperationId, ImageExecutionObserver observer) {
		boolean byok = provider.isByok();
		int estimatedCents = byok ? 0 : unitPriceCents;
		UUID operationId = executionOperationId == null ? UUID.randomUUID() : executionOperationId;
		return executions
				.prepareMediaExecution(accountId, organizationId, "image_generation", null, provider, operationId,
						estimatedCents, pricingVersion, null)
				.flatMap(result -> result.allowed()
						? executePrepared(command, purpose, provider, result.context(), executionOperationId, observer,
								estimatedCents)
						: Mono.error(denied(result.denialReason())));
	}

	private Mono<Traced> executePrepared(ArticleImageService.GenerateCommand command,
			com.grassland.intelligence.media.MediaPurpose purpose, ProviderResolution provider,
			AiExecutionService.ExecutionContext context, UUID executionOperationId, ImageExecutionObserver observer,
			int settleCents) {
		// BYOK/带凭据平台行都有解密明文；无凭据平台行在 prepare 阶段已 503（决策 E/G）
		ImageGenerationClient.Endpoint endpoint = ImageGenerationClient.Endpoint.of(provider, context.decryptedKey());
		MediaOwner owner = new MediaOwner(context.accountId(), context.organizationId());
		// 确定性原图媒体 ID：同一执行操作重放落同一对象（C101-08）。
		UUID deterministicMediaId = executionOperationId == null
				? null
				: UUID.nameUUIDFromBytes(
						("visual-original:" + executionOperationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		// 媒体已保存标记：结算失败不再 handleFailure（保留 run 供恢复结算）。
		AtomicBoolean mediaPersisted = new AtomicBoolean(false);
		Mono<Void> onPrepared = observer == null
				? Mono.empty()
				: observer.prepared(context.runId()).then(observer.reserved(context.runId(),
						context.budgetReservation() == null ? null : context.budgetReservation().budgetId(),
						context.budgetReservation() == null ? null : context.budgetReservation().reservationDate(),
						context.budgetReservation() == null ? 0 : context.budgetReservation().reservedCents()));
		return Mono.usingWhen(Mono.just(context), ignored -> onPrepared
				.then(images.generate(command, owner, purpose, endpoint, deterministicMediaId)).doOnNext(response -> {
					if (response.mediaId() != null) {
						mediaPersisted.set(true);
					}
				})
				.flatMap(response -> (observer == null || response.mediaId() == null
						? Mono.empty()
						: observer.generated(context.runId(), response.mediaId()))
						.then(executions.settleSuccessWithCost(context, settleCents, 0, 0, 1, 0)).thenReturn(new Traced(
								response, context.runId(), context.provider().provider(), context.provider().model()))),
				ignored -> Mono.empty(), (ignored, error) -> {
					if (mediaPersisted.get()) {
						// 原图已保存、结算未完成：保持 run running，恢复链路只重放结算（§6.6）。
						return Mono.empty();
					}
					return executions.handleFailure(context,
							error.getMessage() == null ? "image generation failed" : error.getMessage()).then();
				}, ignored -> mediaPersisted.get() ? Mono.empty() : executions.handleCancellation(context).then());
	}

	private static IntelligenceException denied(String reason) {
		if ("no_platform_model".equals(reason)) {
			return new IntelligenceException(503, "no_platform_model", "平台未配置图片生成模型，请到治理台配置");
		}
		return switch (reason) {
			case "own_key_missing" -> new IntelligenceException(422, "该能力未配置自有模型密钥，请在 AI 与治理中配置或切回平台统一模型");
			case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
				new IntelligenceException(402, "图片生成预算不足：" + reason);
			default -> new IntelligenceException(403, "图片生成执行被拒绝：" + reason);
		};
	}
}
