package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionContext;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionResult;
import com.grassland.intelligence.ai.run.AiRun;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.ai.run.ProviderKeyDecryptor;
import com.grassland.intelligence.ai.run.RealtimePreparation;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人阶段子调用状态机（任务书 #105D C105D-01 / 共享契约 K08、K08.1）。
 *
 * <p>
 * dh_invocation 是阶段（stt/llm/tts/preview/render）子调用的<b>唯一状态与经济事实</b>：登记幂等
 * （owner+resource+stage+segment 唯一，operation_id 复用不换键）；prepare 在数据库 CAS 领工作后经
 * {@link AiExecutionService#prepareRealtimeExecution} 完成 run 创建+绑定（同事务）；派发前持久
 * dispatched 标记；未派发取消走 cancel-before-provider 补偿、已派发主动 abort 按预留结算不退款；派发后结果不明 →
 * unknown 待核对，绝不重发推理。崩溃恢复按「绑定存在查原 run，绝不再生成第二个 run」执行。
 *
 * <p>
 * provider_snapshot/budget_snapshot
 * 只存无密钥字段（provider/model/baseUrl/配置版本/凭据版本；预算与
 * 政策句柄）；密文不落库，恢复时按冻结引用重解析并核对身份一致，凭据撤销/改版 → 409 dh_configuration_changed，不静默换模型。
 */
@Component
public class DigitalHumanInvocationService {

	/** preparing 进程租约（K08：CAS 领工作；过期且无 run 绑定才允许同键恢复 prepare）。 */
	public static final Duration PREPARING_LEASE = Duration.ofSeconds(60);

	// 服务内私有 JSON 实例（intelligence 无全局 ObjectMapper bean）。
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

	private final DigitalHumanInvocationRepository invocations;
	private final AiExecutionService aiExecution;
	private final ByokRoutingService routing;
	private final AiRunRepository runs;
	private final PriceTableService prices;
	private final ProviderKeyDecryptor keyDecryptor;

	public DigitalHumanInvocationService(DigitalHumanInvocationRepository invocations, AiExecutionService aiExecution,
			ByokRoutingService routing, AiRunRepository runs, PriceTableService prices,
			ProviderKeyDecryptor keyDecryptor) {
		this.invocations = invocations;
		this.aiExecution = aiExecution;
		this.routing = routing;
		this.runs = runs;
		this.prices = prices;
		this.keyDecryptor = keyDecryptor;
	}

	/** K08.1：stage → capability 固定映射。 */
	public static String capabilityFor(InvocationStage stage) {
		return switch (stage) {
			case llm -> "text";
			case stt -> "voice";
			case tts, preview -> "video_tts";
			case render -> "digital_human_render";
		};
	}

	/** K08.1：只有平台承担的 llm/stt 挂积分功能键；个人 own 与 tts/preview/render 一律 null（补贴/免扣）。 */
	public static CreditFeature creditFeatureFor(InvocationStage stage, ProviderResolution provider) {
		if (provider == null || !provider.isPlatform()) {
			return null;
		}
		return switch (stage) {
			case llm -> CreditFeature.AI_RUN_TEXT;
			case stt -> CreditFeature.AI_RUN_VOICE;
			case tts, preview, render -> null;
		};
	}

	/** K07.1 进程内类型：ExecutionContext 只在内存使用，不序列化、不入日志。 */
	public record PreparedInvocation(UUID invocationId, UUID resourceId, InvocationStage stage, UUID operationId,
			ExecutionContext context, Instant deadlineAt) {
	}

	/** 冻结 provider 的无密钥快照（provider_snapshot JSONB 载荷）。 */
	record ProviderSnapshot(String type, String provider, String model, String baseUrl, String platformConfigId,
			int platformModelVersion, Long credentialVersion, String byokOrganizationId, String priceTableVersion,
			int estimatedInputTokens, int estimatedOutputTokens, int estimatedSeconds) {
	}

	/** 预算快照（budget_snapshot JSONB 载荷；bind 后由 RealtimePreparedBinding 字段落库）。 */
	record BudgetSnapshot(String state, UUID runId, UUID budgetId, LocalDate reservationDate, int reservedTokens,
			int reservedCents, String priceTableVersion, String creditsCentsPolicyVersion, boolean chargeRequired,
			String feature, Integer estimatedInputTokens, Integer estimatedOutputTokens, Integer estimatedSeconds) {
	}

	/**
	 * 登记阶段调用（幂等）：同经济键已存在时读原行返回（operation_id 不换）。stage 形状按 K05 CHECK 前置校验（preview 不挂
	 * session/turn；render 挂 session 不挂 turn、segment=0；stt/llm/tts 双挂）。
	 */
	public Mono<InvocationRow> reserve(PersonalActor actor, UUID sessionId, UUID turnId, InvocationStage stage,
			UUID resourceId, int segmentIndex, ProviderResolution frozenProvider, String requestHash,
			Instant deadlineAt, int estimatedInputTokens, int estimatedOutputTokens, int estimatedSeconds) {
		Objects.requireNonNull(frozenProvider, "冻结 provider 必填");
		if (frozenProvider.isDenied()) {
			return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "模型配置不可用。"));
		}
		switch (stage) {
			case preview -> {
				if (sessionId != null || turnId != null) {
					return Mono.error(new IntelligenceException(422, "dh_invalid_input", "preview 不挂会话与轮次。"));
				}
			}
			case render -> {
				if (sessionId == null || turnId != null || segmentIndex != 0) {
					return Mono
							.error(new IntelligenceException(422, "dh_invalid_input", "render 挂会话、不挂轮次且 segment=0。"));
				}
			}
			default -> {
				if (sessionId == null || turnId == null) {
					return Mono.error(new IntelligenceException(422, "dh_invalid_input", "stt/llm/tts 必须挂会话与轮次。"));
				}
			}
		}
		String priceTableVersion = prices.currentVersionLabel();
		ProviderSnapshot snapshot = new ProviderSnapshot(frozenProvider.isPlatform() ? "PLATFORM" : "BYOK",
				frozenProvider.provider(), frozenProvider.model(), frozenProvider.baseUrl(),
				frozenProvider.platformConfigId() == null ? null : frozenProvider.platformConfigId().toString(),
				frozenProvider.platformModelVersion(), frozenProvider.credentialVersion(),
				frozenProvider.byokOrganizationId(), priceTableVersion, estimatedInputTokens, estimatedOutputTokens,
				estimatedSeconds);
		BudgetSnapshot initial = new BudgetSnapshot("reserved", null, null, null, 0, 0, priceTableVersion, null, false,
				null, estimatedInputTokens, estimatedOutputTokens, estimatedSeconds);
		InvocationRow row = new InvocationRow(UUID.randomUUID().toString(), actor.accountId(),
				sessionId == null ? null : sessionId.toString(), turnId == null ? null : turnId.toString(), stage,
				resourceId.toString(), segmentIndex, UUID.randomUUID().toString(), null, InvocationState.reserved,
				SettlementState.not_required, writeJson(snapshot), writeJson(initial), null, null, requestHash,
				deadlineAt, null, 1, null, null);
		return invocations.insert(row);
	}

	/**
	 * prepare（K08 状态机入口）：同 id 已 prepared 只回读取（按原 run/预算快照恢复上下文）；已 dispatch
	 * 不得重发（deadline 已过 → unknown 待核对）；reserved/preparing(过期未绑) 才领工作重新 prepare。
	 */
	public Mono<PreparedInvocation> prepare(UUID invocationId) {
		return invocations.findById(invocationId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(this::prepareRow);
	}

	private Mono<PreparedInvocation> prepareRow(InvocationRow row) {
		// dispatched 判定先于绑定检查：已派发的行必然已绑定 run，但不得按「绑定存在只回读」放行重发。
		switch (row.state()) {
			case dispatched -> {
				return dispatchedReentry(row);
			}
			case succeeded, failed, cancelled, unknown -> {
				return Mono.error(
						new IntelligenceException(409, "dh_invocation_terminal", "调用已进入终态：" + row.state().name()));
			}
			case prepared -> {
				return recoverPrepared(row);
			}
			default -> {
				if (row.aiRunId() != null) {
					// preparing/preparing 前的中间态不应带 run；防御性按绑定存在恢复。
					return recoverPrepared(row);
				}
				return claimAndPrepare(row);
			}
		}
	}

	/** dispatched 重入：deadline 已过（响应丢失/worker 重启）→ unknown 待核对；未过 → 确定性 409，均不重发。 */
	private Mono<PreparedInvocation> dispatchedReentry(InvocationRow row) {
		if (row.deadlineAt().isAfter(Instant.now())) {
			return Mono.error(new IntelligenceException(409, "dh_invocation_dispatched", "调用已派发，等待结果。"));
		}
		return invocations.markUnknown(UUID.fromString(row.id()))
				.then(Mono.error(new IntelligenceException(409, "dh_invocation_unknown", "派发结果不明，待核对。")));
	}

	private Mono<PreparedInvocation> claimAndPrepare(InvocationRow row) {
		ProviderSnapshot snapshot = providerSnapshot(row);
		Instant lease = Instant.now().plus(PREPARING_LEASE);
		return invocations.casPreparing(UUID.fromString(row.id()), lease)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_invocation_preparing", "另一工作进程正在准备该调用。")))
				.flatMap(claimed -> resolveFrozen(snapshot, claimed.ownerAccountId(), claimed.stage())
						.flatMap(resolution -> {
							CreditFeature feature = creditFeatureFor(claimed.stage(), resolution);
							RealtimePreparation command = new RealtimePreparation(
									UUID.fromString(claimed.operationId()), claimed.ownerAccountId(), null,
									capabilityFor(claimed.stage()), feature, resolution, snapshot.priceTableVersion(),
									snapshot.estimatedInputTokens(), snapshot.estimatedOutputTokens(),
									snapshot.estimatedSeconds());
							return aiExecution.prepareRealtimeExecution(command,
									binding -> invocations.bindPrepared(UUID.fromString(claimed.id()), binding.runId(),
											budgetSnapshotJson(binding, feature), lease));
						}).flatMap(result -> {
							if (!result.allowed()) {
								return Mono.error(denied(result.denialReason()));
							}
							return Mono.just(new PreparedInvocation(UUID.fromString(claimed.id()),
									UUID.fromString(claimed.resourceId()), claimed.stage(),
									UUID.fromString(claimed.operationId()), result.context(), claimed.deadlineAt()));
						}))
				// 同键前一次事务已提交（bind 冲突回滚了本次新 run）：转读原行恢复。
				.onErrorResume(IntelligenceException.class,
						e -> "dh_invocation_bound".equals(e.code())
								? invocations.findById(UUID.fromString(row.id())).flatMap(this::recoverPrepared)
								: Mono.error(e));
	}

	/**
	 * 只读重hydrated 上下文（worker 结算重放用）：不经状态机——已 succeeded/failed 的行也允许按持久 快照重建
	 * ExecutionContext；不做任何派发或状态迁移。
	 */
	public Mono<PreparedInvocation> rehydrate(UUID invocationId) {
		return invocations.findById(invocationId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(row -> recoverContext(row, null)
						.map(ctx -> new PreparedInvocation(invocationId, UUID.fromString(row.resourceId()), row.stage(),
								UUID.fromString(row.operationId()), ctx, row.deadlineAt())));
	}

	/** 派发前持久 dispatched 标记（prepared→dispatched 只允许一次），并返回派发用上下文。 */
	public Mono<PreparedInvocation> claimDispatch(UUID invocationId) {
		return invocations.findById(invocationId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。"))).flatMap(row -> {
					if (row.state() != InvocationState.prepared || row.aiRunId() == null) {
						return Mono.error(new IntelligenceException(409, "dh_invocation_state",
								"仅 prepared 可派发，当前：" + row.state().name()));
					}
					return invocations.claimDispatch(invocationId).flatMap(claimed -> recoverContext(claimed, null))
							.map(ctx -> new PreparedInvocation(invocationId, UUID.fromString(row.resourceId()),
									row.stage(), UUID.fromString(row.operationId()), ctx, row.deadlineAt()));
				});
	}

	/** 未派发取消（K08 表第 1 行）：原 cancel-before-provider 补偿（释放/退款既有预留），不调用 provider。 */
	public Mono<InvocationRow> cancelBeforeDispatch(UUID invocationId, ExecutionContext ctx) {
		return aiExecution.cancelBeforeProviderExecution(ctx).then(
				invocations.markTerminal(invocationId, InvocationState.cancelled, SettlementState.not_required, null));
	}

	/** 已派发主动 abort（K08 表第 2 行）：handleCancellation 按预留结算、不退款；前序成功调用不受影响。 */
	public Mono<InvocationRow> abortAfterDispatch(UUID invocationId, ExecutionContext ctx) {
		return aiExecution.handleCancellation(ctx)
				.then(invocations.markTerminal(invocationId, InvocationState.cancelled, SettlementState.settled, null));
	}

	/** 明确 provider 失败（K08 表第 3 行）：handleFailure 既有补偿；invocation 落 failed。 */
	public Mono<InvocationRow> fail(UUID invocationId, ExecutionContext ctx, String reason) {
		return aiExecution.handleFailure(ctx, reason).then(
				invocations.markTerminal(invocationId, InvocationState.failed, SettlementState.not_required, null));
	}

	/**
	 * 正常结束+完整 usage（K08 表第 4 行）：冻结价格实际核销；invocation 落 succeeded/settled。音频/渲染 秒按
	 * ceil(ms/1000) 映射既有 videoSeconds 槽位（capability 固定 voice/video_tts/render，DH
	 * 侧展示仍按 实际语音/渲染秒）。
	 */
	public Mono<Boolean> settleSuccess(UUID invocationId, ExecutionContext ctx, UsageUnits usage) {
		Objects.requireNonNull(usage, "usage 必填");
		int seconds = ceilSeconds(usage.audioInputMs(), usage.audioOutputMs(), usage.renderMs());
		return aiExecution
				.settleSuccess(ctx, usage.inputTokens() == null ? null : usage.inputTokens().intValue(),
						usage.outputTokens() == null ? null : usage.outputTokens().intValue(), 0, seconds)
				.flatMap(ok -> ok
						? invocations.markTerminal(invocationId, InvocationState.succeeded, SettlementState.settled,
								writeJson(usage)).thenReturn(true)
						: Mono.just(false));
	}

	// ---------- 恢复与重解析 ----------

	private Mono<PreparedInvocation> recoverPrepared(InvocationRow row) {
		return recoverContext(row, null)
				.map(ctx -> new PreparedInvocation(UUID.fromString(row.id()), UUID.fromString(row.resourceId()),
						row.stage(), UUID.fromString(row.operationId()), ctx, row.deadlineAt()));
	}

	/**
	 * 按持久事实恢复 ExecutionContext：ai_run（runId/能力）+ budget_snapshot（预留/政策句柄）+
	 * provider_snapshot（冻结引用重解析，身份不一致 → 409 dh_configuration_changed）。charge 在崩溃后
	 * 不可知（由补偿机制核销），进程内新鲜路径的 charge 只活在 prepare 返回的上下文里。
	 */
	private Mono<ExecutionContext> recoverContext(InvocationRow row, AiRun runOverride) {
		ProviderSnapshot snapshot = providerSnapshot(row);
		BudgetSnapshot budget;
		try {
			budget = JSON.readValue(row.budgetSnapshot(), BudgetSnapshot.class);
		} catch (Exception failure) {
			return Mono.error(new IntelligenceException(502, "dh_runtime_unavailable", "预算快照不可读。"));
		}
		Mono<AiRun> run = runOverride != null
				? Mono.just(runOverride)
				: runs.findByOperationIdAndOwner(UUID.fromString(row.operationId()), row.ownerAccountId());
		return run.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_invocation_bound", "调用已绑定执行记录，按原记录恢复。")))
				.flatMap(found -> resolveFrozen(snapshot, row.ownerAccountId(), row.stage())
						.map(resolution -> toContext(row, found, snapshot, budget, resolution)));
	}

	private ExecutionContext toContext(InvocationRow row, AiRun run, ProviderSnapshot snapshot, BudgetSnapshot budget,
			ProviderResolution resolution) {
		ModelBudgetService.BudgetCheckResult reservation = ModelBudgetService.BudgetCheckResult
				.allowed(budget.budgetId(), budget.reservationDate(), budget.reservedTokens(), budget.reservedCents());
		CreditFeature feature = budget.feature() == null ? null : CreditFeature.valueOf(budget.feature());
		int estIn = snapshot.estimatedInputTokens();
		int estOut = snapshot.estimatedOutputTokens();
		return new ExecutionContext(run.id(), null, row.ownerAccountId(), capabilityFor(row.stage()), resolution,
				reservation, UUID.fromString(row.operationId()), null, feature, budget.chargeRequired(),
				keyDecryptor.decryptIfNeeded(resolution), budget.priceTableVersion(), estIn, estOut,
				budget.creditsCentsPolicyVersion());
	}

	/**
	 * 冻结引用重解析：平台按 capability 取现行生效行并与快照逐项核对（provider/model/configId/版本/凭据 版本任一漂移 →
	 * 409，不静默切主备）；个人 BYOK 按账号+capability 取现行密钥（组织密钥不算个人 own）。
	 */
	private Mono<ProviderResolution> resolveFrozen(ProviderSnapshot snapshot, String accountId, InvocationStage stage) {
		String capability = capabilityFor(stage);
		if ("BYOK".equals(snapshot.type())) {
			return routing.resolveByokKey(null, accountId, capability)
					.map(key -> ProviderResolution.byok(key.provider(), key.baseUrl(), key.model(), key.encryptedKey(),
							key.keyVersion()))
					.filter(resolution -> matchesSnapshot(resolution, snapshot)).switchIfEmpty(
							Mono.error(new IntelligenceException(409, "dh_configuration_changed", "自有模型配置已变更，请重新确认。")));
		}
		return routing.resolvePlatform(capability).filter(resolution -> matchesSnapshot(resolution, snapshot))
				.switchIfEmpty(
						Mono.error(new IntelligenceException(409, "dh_configuration_changed", "平台模型配置已变更，请稍后再试。")));
	}

	private static boolean matchesSnapshot(ProviderResolution resolution, ProviderSnapshot snapshot) {
		return Objects.equals(resolution.provider(), snapshot.provider())
				&& Objects.equals(resolution.model(), snapshot.model())
				&& Objects.equals(
						resolution.platformConfigId() == null ? null : resolution.platformConfigId().toString(),
						snapshot.platformConfigId())
				&& resolution.platformModelVersion() == snapshot.platformModelVersion()
				&& Objects.equals(resolution.credentialVersion(), snapshot.credentialVersion());
	}

	private ProviderSnapshot providerSnapshot(InvocationRow row) {
		try {
			return JSON.readValue(row.providerSnapshot(), ProviderSnapshot.class);
		} catch (Exception failure) {
			throw new IntelligenceException(502, "dh_runtime_unavailable", "调用快照不可读。");
		}
	}

	private String budgetSnapshotJson(com.grassland.intelligence.ai.run.RealtimePreparedBinding binding,
			CreditFeature feature) {
		return writeJson(new BudgetSnapshot("prepared", binding.runId(), binding.budgetId(), binding.reservationDate(),
				binding.reservedTokens(), binding.reservedCents(), binding.priceTableVersion(),
				binding.creditsCentsPolicyVersion(), binding.chargeRequired(), feature == null ? null : feature.name(),
				null, null, null));
	}

	private String writeJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception failure) {
			throw new IntelligenceException(502, "dh_runtime_unavailable", "快照序列化失败。");
		}
	}

	private static int ceilSeconds(Long... millis) {
		long max = 0;
		for (Long value : millis) {
			if (value != null && value > max) {
				max = value;
			}
		}
		return (int) Math.ceil(max / 1000.0);
	}

	private static IntelligenceException denied(String denialReason) {
		return switch (denialReason == null ? "" : denialReason) {
			case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
				new IntelligenceException(402, denialReason, "积分或预算不足。");
			default -> new IntelligenceException(409, "dh_configuration_changed", "模型配置暂不可用。");
		};
	}
}
