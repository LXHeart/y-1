package com.grassland.intelligence.hypit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Hypit 契约序列化/解析测试（任务书 #107-1 C107-03 / TC107-03-04 / K03）。
 *
 * <p>
 * 不起容器：直接绑定 controller 面验证信封、null、金额字符串、SSE id/Last-Event-ID 解析； Java/TS 双端共享
 * fixture 语义由 contracts/hypit-api.v1.json 冻结。
 */
class HypitContractTest {

	private final HypitDtos.Money money = HypitDtos.Money.of(new BigDecimal("12.345678"), "CNY", true, "hiapi");

	@Test
	void moneySerializesAsDecimalStringWithSixFractionDigits() {
		assertThat(money.amount()).isEqualTo("12.345678");
		assertThat(money.unknownReason()).isNull();
		assertThat(HypitDtos.Money.unknown("CNY", "hypihub", "未报价").amount()).isNull();
	}

	@Test
	void successAndErrorEnvelopesMatchTheContractShape() {
		Map<String, Object> success = HypitDtos.success(Map.of("enabled", false));
		assertThat(success).containsEntry("success", true).containsKey("data");
		Map<String, Object> failure = HypitDtos.failure("工程已更新，请先查看差异", "hypit_revision_conflict");
		assertThat(failure).containsEntry("success", false).containsEntry("error", "工程已更新，请先查看差异").containsEntry("code",
				"hypit_revision_conflict");
	}

	@Test
	void sseEventIdAndLastEventIdRoundTrip() {
		String id = HypitDtos.eventId("11111111-1111-4111-8111-111111111111", 42);
		assertThat(id).isEqualTo("11111111-1111-4111-8111-111111111111:42");
		assertThat(HypitDtos.parseLastEventId(id)).isEqualTo(42L);
		assertThat(HypitDtos.parseLastEventId(null)).isNull();
		assertThat(HypitDtos.parseLastEventId("")).isNull();
		assertThat(HypitDtos.parseLastEventId("garbage")).isNull();
		assertThat(HypitDtos.parseLastEventId("job:abc")).isNull();
		assertThat(HypitDtos.parseLastEventId("job:-3")).isNull();
		assertThat(HypitDtos.parseLastEventId("job:7")).isEqualTo(7L);
	}

	@Test
	void nullsStayNullAndEmptyListsStayEmptyInDtos() {
		HypitDtos.Output output = new HypitDtos.Output("id", "b", "final.video", null, "resource", null, "video/mp4",
				1024L, 1.5, Map.of(), "pending", null, java.util.List.of());
		assertThat(output.displayName()).isNull();
		assertThat(output.mediaId()).isNull();
		assertThat(output.dependencies()).isEmpty();
	}

	@Test
	void controllersBindWithoutContainer() {
		// 绑定面冒烟：controller 可实例化并被路由收录；capabilities 对登录用户 200
		// 且 disabled 如实为 false（TC107-03-03）。
		// bindToApplicationContext 需要 ReactiveWebApplicationContext（含 webHandler）；
		// 普通 AnnotationConfigApplicationContext 没有 WebFlux 基础设施。
		var context = new org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext();
		try {
			context.register(HypitTestConfig.class);
			context.refresh();
			WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
			client.get().uri("/api/hypit/capabilities").exchange().expectStatus().isOk().expectBody()
					.jsonPath("$.success").isEqualTo(true).jsonPath("$.data.enabled").isEqualTo(false)
					.jsonPath("$.data.templates").isArray();
			// 未登录（resolver 报 401）→ 401，不为探测绕过账户边界（K03）。
			// mock 以 X-Test-Anon 头模拟「无凭据」请求。
			client.get().uri("/api/hypit/jobs/11111111-1111-4111-8111-111111111111").header("X-Test-Anon", "1")
					.exchange().expectStatus().isEqualTo(401);
		} finally {
			context.close();
		}
	}

	/** 仅装配 Hypit 面所需的 mock：caller 解析成功返回固定账号；@EnableWebFlux 提供 webHandler。 */
	@org.springframework.context.annotation.Configuration
	@org.springframework.web.reactive.config.EnableWebFlux
	static class HypitTestConfig {

		private final com.grassland.intelligence.security.IntelligenceCallerResolver callers = org.mockito.Mockito
				.mock(com.grassland.intelligence.security.IntelligenceCallerResolver.class);

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.security.IntelligenceCallerResolver callers() {
			org.mockito.Mockito.when(callers.resolve(org.mockito.ArgumentMatchers.any())).thenAnswer(
					invocation -> Mono.just(new com.grassland.intelligence.security.IntelligenceCallerResolver.Caller(
							"22222222-2222-4222-8222-222222222222", "recommender", null, null, null, "user", null,
							null)));
			org.mockito.Mockito
					.when(callers.resolve(org.mockito.ArgumentMatchers
							.argThat(request -> request.getHeaders().getFirst("X-Test-Anon") != null)))
					.thenAnswer(invocation -> Mono
							.error(com.grassland.intelligence.hypit.security.HypitAccessService.unauthenticated()));
			return callers;
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.config.HypitProperties properties() {
			return new com.grassland.intelligence.hypit.config.HypitProperties(false, "http://127.0.0.1:9240", "", "");
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar(
				com.grassland.intelligence.hypit.config.HypitProperties properties) {
			return new com.grassland.intelligence.hypit.client.HypitSidecarClient(properties);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.security.HypitAccessService access(
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.project.HypitProjectRepository projects) {
			return new com.grassland.intelligence.hypit.security.HypitAccessService(properties, projects);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.project.HypitProjectRepository projectRepository() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.project.HypitProjectRepository.class);
		}

		@org.springframework.context.annotation.Bean
		HypitProjectController projectController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.project.HypitProjectService projects,
				com.grassland.intelligence.hypit.project.HypitChangesetService changesets,
				com.grassland.intelligence.hypit.job.HypitJobService jobService) {
			return new HypitProjectController(callers, access, properties, projects, changesets, jobService,
					org.mockito.Mockito.mock(com.grassland.intelligence.hypit.build.HypitResultService.class),
					org.mockito.Mockito.mock(com.grassland.intelligence.hypit.agent.HypitClonePlanService.class),
					org.mockito.Mockito.mock(com.grassland.intelligence.hypit.client.HypitSidecarClient.class),
					org.mockito.Mockito.mock(com.grassland.intelligence.hypit.variant.HypitVariantService.class),
					org.mockito.Mockito
							.mock(com.grassland.intelligence.hypit.template.HypitProjectPackageService.class));
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.project.HypitProjectService projectService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.project.HypitProjectService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.project.HypitChangesetService changesetService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.project.HypitChangesetService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.job.HypitJobService hypitJobService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.job.HypitJobService.class);
		}

		@org.springframework.context.annotation.Bean
		HypitAssetController assetController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.asset.HypitAssetService assets,
				com.grassland.intelligence.hypit.asset.HypitResourceService resources) {
			return new HypitAssetController(callers, access, properties, assets, resources);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.asset.HypitAssetService hypitAssetService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.asset.HypitAssetService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.asset.HypitResourceService hypitResourceService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.asset.HypitResourceService.class);
		}

		@org.springframework.context.annotation.Bean
		HypitJobController jobController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.job.HypitJobService jobService,
				com.grassland.intelligence.hypit.job.HypitJobActionRepository jobActions) {
			return new HypitJobController(callers, access, properties, jobService, jobActions);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.job.HypitJobActionRepository hypitJobActionRepository() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.job.HypitJobActionRepository.class);
		}

		@org.springframework.context.annotation.Bean
		HypitBuildController buildController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.execution.HypitGrantService grants,
				com.grassland.intelligence.hypit.build.HypitPlanService plans,
				com.grassland.intelligence.hypit.project.HypitProjectRepository projects,
				com.grassland.intelligence.hypit.build.HypitBuildService builds,
				com.grassland.intelligence.hypit.job.HypitJobService jobService,
				com.grassland.intelligence.hypit.build.HypitResultService results,
				com.grassland.intelligence.hypit.asset.HypitArchiveService archiveOps) {
			return new HypitBuildController(callers, access, properties, grants, plans, projects, builds, jobService,
					results, archiveOps);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.asset.HypitArchiveService hypitArchiveService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.asset.HypitArchiveService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.build.HypitBuildService hypitBuildService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.build.HypitBuildService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.build.HypitResultService hypitResultService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.build.HypitResultService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.build.HypitPlanService hypitPlanService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.build.HypitPlanService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.execution.HypitGrantService hypitGrantService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.execution.HypitGrantService.class);
		}

		@org.springframework.context.annotation.Bean
		HypitRuntimeController runtimeController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar,
				com.grassland.intelligence.hypit.runtime.HypitRuntimeService runtime,
				com.grassland.intelligence.hypit.build.HypitBuildService buildOps) {
			return new HypitRuntimeController(callers, access, properties, sidecar, runtime, buildOps);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.runtime.HypitRuntimeService hypitRuntimeService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.runtime.HypitRuntimeService.class);
		}

		@org.springframework.context.annotation.Bean
		HypitStudioController studioController(com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.preview.HypitPreviewService previews,
				com.grassland.intelligence.hypit.studio.HypitStudioSessionService sessions) {
			return new HypitStudioController(callers, access, properties, previews, sessions);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.studio.HypitStudioSessionService hypitStudioSessionService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.studio.HypitStudioSessionService.class);
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.preview.HypitPreviewService hypitPreviewService() {
			return org.mockito.Mockito.mock(com.grassland.intelligence.hypit.preview.HypitPreviewService.class);
		}

		@org.springframework.context.annotation.Bean
		HypitKnowledgeController knowledgeController(
				com.grassland.intelligence.security.IntelligenceCallerResolver callers,
				com.grassland.intelligence.hypit.security.HypitAccessService access,
				com.grassland.intelligence.hypit.config.HypitProperties properties,
				com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar,
				com.grassland.intelligence.hypit.agent.HypitKnowledgeService knowledge) {
			return new HypitKnowledgeController(callers, access, properties, sidecar, knowledge,
					org.mockito.Mockito.mock(com.grassland.intelligence.hypit.template.HypitTemplateService.class),
					org.mockito.Mockito
							.mock(com.grassland.intelligence.hypit.template.HypitProjectPackageService.class));
		}

		@org.springframework.context.annotation.Bean
		com.grassland.intelligence.hypit.agent.HypitKnowledgeService hypitKnowledgeService() {
			return new com.grassland.intelligence.hypit.agent.HypitKnowledgeService();
		}

		@org.springframework.context.annotation.Bean
		HypitExceptionHandler exceptionHandler() {
			return new HypitExceptionHandler();
		}
	}
}
