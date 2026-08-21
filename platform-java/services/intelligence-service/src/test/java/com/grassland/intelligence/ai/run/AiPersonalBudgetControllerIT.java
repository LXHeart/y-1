package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 个人 AI 预算自助管理（GL-P3-AI-001 登记项，对称组织版）：未设置=不限；自助读写（"u:"+accountId
 * 作用域）；预留/结算走同一机器——超限独立创作执行被拒（exceeds_daily_budget），组织预算互不干扰。
 */
@DisplayName("AiPersonalBudgetController (个人 AI 预算)")
class AiPersonalBudgetControllerIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "54545454-5454-5454-5454-545454545454";

	@Autowired
	private ModelBudgetService budgetService;

	@Autowired
	private AiModelBudgetRepository budgets;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM ai_model_budget").then().block();
	}

	@Test
	@DisplayName("未设置返回不限；自助设置后回读并计量；清空恢复不限")
	void selfServiceLifecycle() {
		client().get().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.configured").isEqualTo(false)
				.jsonPath("$.data.usage.measured").isEqualTo(false);

		client().put().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"expectedVersion":0,"maxTokensPerRun":100,"maxTokensDaily":1000,
						 "maxTokensMonthly":10000,"maxCentsPerRun":10,"maxCentsDaily":100,
						 "maxCentsMonthly":1000}
						""")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.configured").isEqualTo(true)
				.jsonPath("$.data.version").isEqualTo(1)
				.jsonPath("$.data.usage.dailyTokens").isEqualTo(0);

		// 越权读写不存在：别人的 me 就是自己的 u: 作用域（无 org 授权可伪造）
		client().get().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.configured").isEqualTo(true);

		// 清空（expectedVersion=1）恢复不限
		client().put().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"expectedVersion":1}
						""")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.configured").isEqualTo(false);
	}

	@Test
	@DisplayName("个人预算只拦独立创作：超限拒绝、限额内放行并结算、组织作用域互不干扰")
	void personalBudgetGovernsIndependentExecutions() {
		client().put().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"expectedVersion\":0,\"maxCentsDaily\":100}")
				.exchange().expectStatus().isOk();

		// 限额内：预留 → 结算（走 checkAndReserve 同路径）
		ModelBudgetService.BudgetCheckResult allowed = budgetService
				.checkAndReserve("u:" + ACCOUNT, "text", "platform", 10, 40).block();
		assertThat(allowed.allowed()).isTrue();
		assertThat(budgetService.settleReservation(allowed, 10, 40).block()).isTrue();

		// 超日限：拒绝 exceeds_daily_budget
		ModelBudgetService.BudgetCheckResult denied = budgetService
				.checkAndReserve("u:" + ACCOUNT, "text", "platform", 10, 80).block();
		assertThat(denied.allowed()).isFalse();
		assertThat(denied.denialReason()).isEqualTo("exceeds_daily_budget");

		// 组织作用域不受个人预算影响（org 预算未设置 = 不限）
		ModelBudgetService.BudgetCheckResult orgRun = budgetService
				.checkAndReserve("some-org-id", "text", "platform", 10, 100000).block();
		assertThat(orgRun.allowed()).isTrue();
	}

	@Test
	@DisplayName("契约：非单调上限 400；无断言 401")
	void contractGuards() {
		client().get().uri("/api/ai/me/budget").exchange().expectStatus().isUnauthorized();

		client().put().uri("/api/ai/me/budget")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"expectedVersion\":0,\"maxTokensDaily\":10,\"maxTokensMonthly\":5}")
				.exchange().expectStatus().isBadRequest();
	}
}
