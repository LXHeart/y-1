package com.grassland.identity.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 注销准备编排 IT（任务书 #103 C103-08 / TC103-08-02/03/05）：检查 → preparing 意图 →
 * Intelligence 冻结 → 复查其余域 → 全绿软删进 retention。覆盖：prepare 409（活动任务）→ blocked
 * 且不软删；prepare 网络未知 → 请求保持 preparing、步骤退避、以原请求续跑收敛；冻结后复查出现新阻塞 → 同请求 release 后
 * blocked。
 */
class CompliancePreparationIT extends IdentityItSupport {

	@MockitoBean
	private ComplianceDomainClient domains;

	@Autowired
	private ComplianceService service;

	@BeforeEach
	void stubDomains() {
		when(domains.marketplaceCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
		when(domains.financeCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
		when(domains.trustCheck(anyString(), any())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
		when(domains.intelligenceCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()));
		when(domains.releaseIntelligence(anyString(), anyString())).thenReturn(Mono.just(true));
	}

	@Test
	void preparingBarrierRunsBeforeSoftDeleteAndRetention() {
		Seeded account = seedAccount("prep-" + UUID.randomUUID() + "@test.local");
		var prepared = new boolean[]{false};
		when(domains.prepareIntelligence(anyString(), anyString())).thenAnswer(inv -> {
			prepared[0] = true;
			return Mono.just(ComplianceDomainClient.PrepareResult.ok());
		});

		ComplianceService.ClosureOutcome outcome = service.requestClosure(account.accountId()).block();

		assertThat(prepared[0]).as("prepare 屏障先于软删执行").isTrue();
		assertThat(outcome.request().status()).isEqualTo("retention");
		Map<String, String> row = db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
				.bind("id", account.accountId()).map(r -> Map.of("status", r.get("status", String.class))).one()
				.block();
		assertThat(row.get("status")).isEqualTo("deleted");
		// 步骤回执：intelligence/prepare succeeded。
		Long steps = db
				.sql("SELECT COUNT(*)::bigint AS c FROM account_closure_step"
						+ " WHERE closure_request_id = CAST(:r AS uuid) AND domain = 'intelligence'"
						+ " AND step = 'prepare' AND state = 'succeeded'")
				.bind("r", outcome.request().id()).map(r -> r.get("c", Long.class)).one().block();
		assertThat(steps).isEqualTo(1L);
	}

	@Test
	void prepareBlockersKeepAccountAliveAndCloseAsBlocked() {
		Seeded account = seedAccount("prep-blocked-" + UUID.randomUUID() + "@test.local");
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(ComplianceDomainClient.PrepareResult.blockedOf("ai_run")));

		ComplianceService.ClosureOutcome outcome = service.requestClosure(account.accountId()).block();

		assertThat(outcome.request().status()).isEqualTo("blocked");
		assertThat(outcome.request().blockersJson()).contains("RUNNING_AI_JOB");
		Map<String, String> row = db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
				.bind("id", account.accountId()).map(r -> Map.of("status", r.get("status", String.class))).one()
				.block();
		assertThat(row.get("status")).isNotEqualTo("deleted");
	}

	@Test
	void prepareNetworkUnknownKeepsPreparingAndSameRequestRetriesToRetention() {
		Seeded account = seedAccount("prep-retry-" + UUID.randomUUID() + "@test.local");
		var calls = new int[]{0};
		when(domains.prepareIntelligence(anyString(), anyString())).thenAnswer(inv -> {
			calls[0]++;
			// 首次网络未知；重试（同请求）成功。
			if (calls[0] == 1) {
				return Mono.error(new IllegalStateException("intelligence unreachable"));
			}
			return Mono.just(ComplianceDomainClient.PrepareResult.ok());
		});

		ComplianceService.ClosureOutcome first = service.requestClosure(account.accountId()).block();
		assertThat(first.request().status()).isEqualTo("preparing");
		Map<String, String> user = db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
				.bind("id", account.accountId()).map(r -> Map.of("status", r.get("status", String.class))).one()
				.block();
		assertThat(user.get("status")).isNotEqualTo("deleted");
		Long retrySteps = db
				.sql("SELECT COUNT(*)::bigint AS c FROM account_closure_step"
						+ " WHERE closure_request_id = CAST(:r AS uuid) AND state = 'retry_wait'")
				.bind("r", first.request().id()).map(r -> r.get("c", Long.class)).one().block();
		assertThat(retrySteps).isEqualTo(1L);

		// worker 以原请求续跑 → 收敛 retention（不换 closureRequestId）。
		ComplianceService.ClosureOutcome retried = service.continueClosure(account.accountId(), first.request().id())
				.block();
		assertThat(retried.request().id()).isEqualTo(first.request().id());
		assertThat(retried.request().status()).isEqualTo("retention");
		assertThat(calls[0]).isEqualTo(2);
	}

	@Test
	void lateBlockerAfterFreezeReleasesBarrierAndBlocks() {
		Seeded account = seedAccount("prep-late-" + UUID.randomUUID() + "@test.local");
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(ComplianceDomainClient.PrepareResult.ok()));
		// 冻结后复查：marketplace 出现新阻塞（如新开放任务）。
		var checks = new int[]{0};
		when(domains.marketplaceCheck(anyString())).thenAnswer(inv -> {
			checks[0]++;
			return Mono.just(checks[0] <= 1
					? ComplianceModels.DomainCheck.empty()
					: new ComplianceModels.DomainCheck(List
							.of(new ComplianceModels.Blocker("marketplace", "ACTIVE_ENGAGEMENT", "有进行中合作", 1, null)),
							List.of()));
		});

		ComplianceService.ClosureOutcome outcome = service.requestClosure(account.accountId()).block();

		assertThat(outcome.request().status()).isEqualTo("blocked");
		Map<String, String> user = db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
				.bind("id", account.accountId()).map(r -> Map.of("status", r.get("status", String.class))).one()
				.block();
		assertThat(user.get("status")).isNotEqualTo("deleted");
	}
}
