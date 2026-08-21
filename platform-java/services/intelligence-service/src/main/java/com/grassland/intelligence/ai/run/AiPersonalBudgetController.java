package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 个人 AI 预算自助管理（GL-P3-AI-001 登记项，对称任务书 #37 组织版）：{@code GET/PUT /api/ai/me/budget}。
 *
 * <p>实现复用组织预算机制：个人预算行同样落在 {@code ai_model_budget}，作用域键为
 * {@code "u:" + accountId}（组织 id 为 UUID，与 {@code u:} 前缀天然无碰撞）——预留/结算/释放/
 * 阈值告警全部按 budgetId 走既有机器。语义：个人预算只约束**无组织上下文**的独立创作执行
 * （{@code AiExecutionService} 预留时 orgId 为空则查个人作用域）；组织内执行由组织预算约束。
 * 未设置个人预算 = 不限（与组织版一致）。
 */
@RestController
@RequestMapping("/api/ai/me/budget")
public class AiPersonalBudgetController {

	private final IntelligenceCallerResolver callers;
	private final AiModelBudgetRepository budgets;

	public AiPersonalBudgetController(IntelligenceCallerResolver callers, AiModelBudgetRepository budgets) {
		this.callers = callers;
		this.budgets = budgets;
	}

	/** 个人预算作用域键：与真实组织 id（UUID）命名空间隔离。 */
	static String personalScope(String accountId) {
		return "u:" + accountId;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> get(ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> budgets.findOrganizationBudget(personalScope(caller.accountId()))
						.map(AiPersonalBudgetController::toResponse)
						.defaultIfEmpty(unlimitedResponse()))
				.map(AiPersonalBudgetController::success);
	}

	@PutMapping
	public Mono<ResponseEntity<Map<String, Object>>> update(
			@RequestBody UpdateBudgetRequest body, ServerWebExchange exchange) {
		validate(body);
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> updateBudget(personalScope(caller.accountId()), body)
						.map(AiPersonalBudgetController::toResponse))
				.map(AiPersonalBudgetController::success);
	}

	private Mono<AiModelBudget> updateBudget(String scope, UpdateBudgetRequest body) {
		long expectedVersion = body.expectedVersion();
		if (body.allLimitsEmpty()) {
			if (expectedVersion == 0) {
				return budgets.findOrganizationBudget(scope)
						.flatMap(ignored -> AiPersonalBudgetController.<AiModelBudget>conflict())
						.switchIfEmpty(Mono.just(unlimitedBudget()));
			}
			return budgets.deleteOrganizationBudget(scope, expectedVersion)
					.filter(Boolean::booleanValue)
					.switchIfEmpty(Mono.defer(AiPersonalBudgetController::conflict))
					.then(Mono.just(unlimitedBudget()));
		}
		Mono<AiModelBudget> saved = expectedVersion == 0
				? budgets.createOrganizationBudget(scope,
						body.maxTokensPerRun(), body.maxTokensDaily(), body.maxTokensMonthly(),
						body.maxCentsPerRun(), body.maxCentsDaily(), body.maxCentsMonthly())
				: budgets.updateOrganizationBudget(scope, expectedVersion,
						body.maxTokensPerRun(), body.maxTokensDaily(), body.maxTokensMonthly(),
						body.maxCentsPerRun(), body.maxCentsDaily(), body.maxCentsMonthly());
		return saved.switchIfEmpty(Mono.defer(AiPersonalBudgetController::conflict));
	}

	/** 未配置个人预算的占位行（limits 全空 = 不限），仅用于统一响应装配。 */
	private static AiModelBudget unlimitedBudget() {
		return AiModelBudget.forCreate(null, AiModelBudgetRepository.ORGANIZATION_CAPABILITY,
				AiModelBudgetRepository.ORGANIZATION_PROVIDER, null, null, null, null, null, null);
	}

	private static void validate(UpdateBudgetRequest body) {
		if (body == null || body.expectedVersion() == null) {
			throw new IntelligenceException(400, "请求体或 expectedVersion 不能为空");
		}
		if (body.expectedVersion() < 0) {
			throw new IntelligenceException(400, "expectedVersion 不能为负数");
		}
		requireNonNegative("maxTokensPerRun", body.maxTokensPerRun());
		requireNonNegative("maxTokensDaily", body.maxTokensDaily());
		requireNonNegative("maxTokensMonthly", body.maxTokensMonthly());
		requireNonNegative("maxCentsPerRun", body.maxCentsPerRun());
		requireNonNegative("maxCentsDaily", body.maxCentsDaily());
		requireNonNegative("maxCentsMonthly", body.maxCentsMonthly());
		requireMonotonic("token", body.maxTokensPerRun(), body.maxTokensDaily(), body.maxTokensMonthly());
		requireMonotonic("金额", body.maxCentsPerRun(), body.maxCentsDaily(), body.maxCentsMonthly());
	}

	private static void requireNonNegative(String field, Number value) {
		if (value != null && value.longValue() < 0) {
			throw new IntelligenceException(400, field + " 不能为负数");
		}
	}

	/** 空值表示不限；在已设置的相邻层级间仍要求单次 ≤ 日 ≤ 月（对齐组织版口径）。 */
	private static void requireMonotonic(String unit, Number perRun, Number daily, Number monthly) {
		long previous = -1;
		for (Number value : new Number[]{perRun, daily, monthly}) {
			if (value == null) continue;
			long current = value.longValue();
			if (previous > current) {
				throw new IntelligenceException(400, unit + "上限必须满足单次 ≤ 每日 ≤ 每月");
			}
			previous = current;
		}
	}

	private static Map<String, Object> toResponse(AiModelBudget budget) {
		long dailyTokens = budget.needsDailyReset() ? 0 : value(budget.currentDailyTokens());
		long dailyCents = budget.needsDailyReset() ? 0 : value(budget.currentDailyCents());
		long monthlyTokens = budget.needsMonthlyReset() ? 0 : value(budget.currentMonthlyTokens());
		long monthlyCents = budget.needsMonthlyReset() ? 0 : value(budget.currentMonthlyCents());

		Map<String, Object> usage = new LinkedHashMap<>();
		usage.put("measured", true);
		usage.put("dailyTokens", dailyTokens);
		usage.put("dailyCents", dailyCents);
		usage.put("monthlyTokens", monthlyTokens);
		usage.put("monthlyCents", monthlyCents);

		Map<String, Object> data = limitsMap(budget);
		data.put("configured", budget.id() != null);
		data.put("version", budget.id() == null ? 0L : budget.version());
		data.put("usage", usage);
		data.put("overCurrentUsage",
				exceeds(budget.maxTokensDaily(), dailyTokens)
						|| exceeds(budget.maxTokensMonthly(), monthlyTokens)
						|| exceeds(budget.maxCentsDaily(), dailyCents)
						|| exceeds(budget.maxCentsMonthly(), monthlyCents));
		data.put("updatedAt", budget.updatedAt());
		return data;
	}

	private static Map<String, Object> unlimitedResponse() {
		Map<String, Object> usage = new LinkedHashMap<>();
		usage.put("measured", false);
		usage.put("dailyTokens", null);
		usage.put("dailyCents", null);
		usage.put("monthlyTokens", null);
		usage.put("monthlyCents", null);

		Map<String, Object> data = limitsMap(null);
		data.put("configured", false);
		data.put("version", 0L);
		data.put("usage", usage);
		data.put("overCurrentUsage", false);
		data.put("updatedAt", null);
		return data;
	}

	private static Map<String, Object> limitsMap(AiModelBudget budget) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("maxTokensPerRun", budget == null ? null : budget.maxTokensPerRun());
		data.put("maxTokensDaily", budget == null ? null : budget.maxTokensDaily());
		data.put("maxTokensMonthly", budget == null ? null : budget.maxTokensMonthly());
		data.put("maxCentsPerRun", budget == null ? null : budget.maxCentsPerRun());
		data.put("maxCentsDaily", budget == null ? null : budget.maxCentsDaily());
		data.put("maxCentsMonthly", budget == null ? null : budget.maxCentsMonthly());
		return data;
	}

	private static long value(Long value) {
		return value == null ? 0 : value;
	}

	private static boolean exceeds(Number limit, long usage) {
		return limit != null && usage > limit.longValue();
	}

	private static <T> Mono<T> conflict() {
		return Mono.error(new IntelligenceException(409, "AI 预算已被修改，请重新载入"));
	}

	private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
		return ResponseEntity.ok(Map.of("success", true, "data", data));
	}

	/** 契约对齐组织版 UpdateBudgetRequest。 */
	public record UpdateBudgetRequest(
			Long expectedVersion,
			Integer maxTokensPerRun,
			Long maxTokensDaily,
			Long maxTokensMonthly,
			Integer maxCentsPerRun,
			Long maxCentsDaily,
			Long maxCentsMonthly) {

		boolean allLimitsEmpty() {
			return maxTokensPerRun == null && maxTokensDaily == null && maxTokensMonthly == null
					&& maxCentsPerRun == null && maxCentsDaily == null && maxCentsMonthly == null;
		}
	}
}
