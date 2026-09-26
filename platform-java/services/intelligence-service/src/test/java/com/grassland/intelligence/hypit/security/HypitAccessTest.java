package com.grassland.intelligence.hypit.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Hypit 归属/权限判定（任务书 #107-1 C107-03 / TC107-03-01 / E06/E07）。
 *
 * <p>
 * operator 名单按 HYPIT_OPERATOR_ACCOUNT_IDS 精确匹配；非 operator 写运行时 403
 * hypit_operator_required；未登录 401；工程归属校验在 C107-04 前以「未就绪」拒绝（不 伪造通过）。共享错误工厂的
 * code/status 与契约错误表一致。
 */
class HypitAccessTest {

	private static IntelligenceCallerResolver.Caller caller(String accountId) {
		return new IntelligenceCallerResolver.Caller(accountId, "recommender", null, null, null, "user", null, null);
	}

	private HypitAccessService access(String operators) {
		// C107-04 起归属校验接 hypit_project：单测以 Mockito 桩仓储，真实 SQL 语义由
		// HypitProjectIT（真 PostgreSQL）覆盖。
		HypitProjectRepository projects = org.mockito.Mockito.mock(HypitProjectRepository.class);
		return new HypitAccessService(new HypitProperties(false, "http://127.0.0.1:9240", "", operators), projects);
	}

	@Test
	void operatorListMatchesExactAccountIdsOnly() {
		HypitAccessService service = access(
				"11111111-1111-4111-8111-111111111111, 33333333-3333-4333-8333-333333333333");
		assertThat(service.isOperator(caller("11111111-1111-4111-8111-111111111111"))).isTrue();
		assertThat(service.isOperator(caller("22222222-2222-4222-8222-222222222222"))).isFalse();
		assertThat(service.isOperator(null)).isFalse();
		assertThat(service.isOperator(
				new IntelligenceCallerResolver.Caller(null, "recommender", null, null, null, "user", null, null)))
				.isFalse();
	}

	@Test
	void nonOperatorIsRejectedWithOperatorRequired() {
		HypitAccessService service = access("11111111-1111-4111-8111-111111111111");
		StepVerifier.create(service.requireOperator(caller("22222222-2222-4222-8222-222222222222")))
				.expectErrorSatisfies(error -> {
					IntelligenceException exception = (IntelligenceException) error;
					assertThat(exception.status()).isEqualTo(403);
					assertThat(exception.code()).isEqualTo("hypit_operator_required");
				}).verify();
		StepVerifier.create(service.requireOperator(caller("11111111-1111-4111-8111-111111111111"))).expectNextCount(1)
				.verifyComplete();
	}

	@Test
	void emptyOperatorListMeansNobodyMayWriteRuntime() {
		HypitAccessService service = access("");
		assertThat(service.isOperator(caller("11111111-1111-4111-8111-111111111111"))).isFalse();
	}

	@Test
	void projectOwnershipResolvesThroughRepositoryWith404NonLeakage() {
		HypitAccessService service = access("11111111-1111-4111-8111-111111111111");
		var owner = caller("22222222-2222-4222-8222-222222222222");
		var projectId = java.util.UUID.fromString("44444444-4444-4444-8444-444444444444");
		// C107-04：归属校验接 hypit_project.findOwnerStatus；非本人/不存在/已删除统一 404。
		var projects = org.mockito.Mockito.mock(HypitProjectRepository.class);
		org.mockito.Mockito.when(projects.findOwnerStatus(owner.accountId(), projectId))
				.thenReturn(reactor.core.publisher.Mono.just("ready"));
		var wired = new HypitAccessService(new HypitProperties(false, "http://127.0.0.1:9240", "", ""), projects);
		StepVerifier.create(wired.requireProjectOwner(owner, projectId.toString())).verifyComplete();
		org.mockito.Mockito.when(projects.findOwnerStatus(owner.accountId(), projectId))
				.thenReturn(reactor.core.publisher.Mono.empty());
		StepVerifier.create(wired.requireProjectOwner(owner, projectId.toString())).expectErrorSatisfies(error -> {
			IntelligenceException exception = (IntelligenceException) error;
			assertThat(exception.status()).isEqualTo(404);
			assertThat(exception.code()).isEqualTo("hypit_not_found");
		}).verify();
		org.mockito.Mockito.when(projects.findOwnerStatus(owner.accountId(), projectId))
				.thenReturn(reactor.core.publisher.Mono.just("deleted"));
		StepVerifier.create(wired.requireProjectOwner(owner, projectId.toString())).expectErrorSatisfies(error -> {
			IntelligenceException exception = (IntelligenceException) error;
			assertThat(exception.status()).isEqualTo(404);
		}).verify();
		// 未登录不是 404，而是 401（账户边界优先）
		StepVerifier.create(wired.requireProjectOwner(null, projectId.toString())).expectErrorSatisfies(error -> {
			IntelligenceException exception = (IntelligenceException) error;
			assertThat(exception.status()).isEqualTo(401);
			assertThat(exception.code()).isEqualTo("hypit_unauthenticated");
		}).verify();
	}

	@Test
	void sharedErrorFactoriesMatchContractStatuses() {
		assertThat(HypitAccessService.disabled().status()).isEqualTo(503);
		assertThat(HypitAccessService.disabled().code()).isEqualTo("hypit_disabled");
		assertThat(HypitAccessService.unavailable("X").status()).isEqualTo(503);
		assertThat(HypitAccessService.unavailable("X").code()).isEqualTo("hypit_backend_unavailable");
		assertThat(HypitAccessService.unauthenticated().status()).isEqualTo(401);
	}
}
