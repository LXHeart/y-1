package com.grassland.identity.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
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

	@Autowired
	private ComplianceRepository repository;

	@Autowired
	private ComplianceProperties properties;

	@Autowired
	private org.springframework.transaction.reactive.TransactionalOperator transactions;

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
	void initialBlockerReturnsBlockedReceiptAndAuditWithoutPreparingId() {
		Seeded account = seedAccount("initial-blocker-" + UUID.randomUUID() + "@test.local");
		when(domains.marketplaceCheck(anyString())).thenReturn(Mono.just(new ComplianceModels.DomainCheck(
				List.of(new ComplianceModels.Blocker("marketplace", "ACTIVE_ENGAGEMENT", "有进行中合作", 1, null)),
				List.of())));
		var outcome = service.requestClosure(account.accountId()).block();
		assertThat(outcome.request().status()).isEqualTo("blocked");
		assertThat(repository.findAudit(account.accountId(), 10).collectList().block()).hasSize(1);
		verify(domains, never()).prepareIntelligence(anyString(), anyString());
	}

	@Test
	void crashedPreparationWithoutStepsIsLeasedAndOldClaimCannotResumeAfterTakeover() {
		Seeded account = seedAccount("prep-crash-" + UUID.randomUUID() + "@test.local");
		var request = transactions.transactional(repository.createPreparingClosure(account.accountId(), "[]")).block();
		UUID firstToken = UUID.randomUUID();
		var first = repository.claimPreparingClosures(100, firstToken, Duration.ofSeconds(60), 5)
				.filter(row -> row.id().equals(request.id())).single().block();
		assertThat(first.claimToken()).isEqualTo(firstToken.toString());
		assertThat(first.attemptCount()).isEqualTo(1);
		assertThat(repository.claimPreparingClosures(100, UUID.randomUUID(), Duration.ofSeconds(60), 5)
				.filter(row -> row.id().equals(request.id())).collectList().block()).isEmpty();
		db.sql("UPDATE account_closure_request SET claimed_until = now() - interval '1 second'"
				+ " WHERE id = CAST(:r AS uuid)").bind("r", request.id()).then().block();
		UUID secondToken = UUID.randomUUID();
		var second = repository.claimPreparingClosures(100, secondToken, Duration.ofSeconds(60), 5)
				.filter(row -> row.id().equals(request.id())).single().block();
		assertThat(second.claimToken()).isEqualTo(secondToken.toString());
		assertThat(second.attemptCount()).isEqualTo(2);
		assertThat(service.continueClaimedClosure(first).block()).isNull();
		verify(domains, never()).prepareIntelligence(anyString(), anyString());
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(ComplianceDomainClient.PrepareResult.ok()));
		var recovered = service.continueClaimedClosure(second).block();
		assertThat(recovered.request().id()).isEqualTo(request.id());
		assertThat(recovered.request().status()).isEqualTo("retention");
		assertThat(recovered.request().claimToken()).isNull();
		assertThat(recovered.request().attemptCount()).as("清理阶段有独立重试预算").isZero();
	}

	@Test
	void exhaustedPreparationFailureStopsAtReviewWithoutDeletingAccount() {
		Seeded account = seedAccount("prep-exhausted-" + UUID.randomUUID() + "@test.local");
		var request = transactions.transactional(repository.createPreparingClosure(account.accountId(), "[]")).block();
		db.sql("UPDATE account_closure_request SET attempt_count = :attempt WHERE id = CAST(:r AS uuid)")
				.bind("attempt", properties.maxAttempts() - 1).bind("r", request.id()).then().block();
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.error(new IllegalStateException("intelligence unreachable")));
		var outcome = service.continueClosure(account.accountId(), request.id()).block();
		assertThat(outcome.request().status()).isEqualTo("preparing");
		assertThat(outcome.request().attemptCount()).isEqualTo(properties.maxAttempts());
		assertThat(db.sql("SELECT state FROM account_closure_step WHERE closure_request_id = CAST(:r AS uuid)")
				.bind("r", request.id()).map(row -> row.get("state", String.class)).one().block())
				.isEqualTo("needs_review");
		assertThat(repository.claimPreparingClosure(request.id(), UUID.randomUUID(), Duration.ofSeconds(60),
				properties.maxAttempts()).block()).isNull();
		assertThat(repository
				.claimPreparingClosures(100, UUID.randomUUID(), Duration.ofSeconds(60), properties.maxAttempts())
				.filter(row -> row.id().equals(request.id())).collectList().block()).isEmpty();
		assertThat(db.sql("SELECT status FROM app_users WHERE id = CAST(:a AS uuid)").bind("a", account.accountId())
				.map(row -> row.get("status", String.class)).one().block()).isNotEqualTo("deleted");
	}

	@Test
	void expiredFinalPreparationLeaseWithoutStepsBecomesReviewAndActiveLeaseIsUntouched() {
		Seeded account = seedAccount("prep-final-crash-" + UUID.randomUUID() + "@test.local");
		var request = transactions.transactional(repository.createPreparingClosure(account.accountId(), "[]")).block();
		var claim = repository.claimPreparingClosure(request.id(), UUID.randomUUID(), Duration.ofSeconds(60), 1)
				.block();
		repository.markExhaustedPreparations(100, 1).block();
		assertThat(repository.findClosureById(request.id()).block().claimToken()).isEqualTo(claim.claimToken());
		db.sql("UPDATE account_closure_request SET claimed_until = now() - interval '1 second' WHERE id = CAST(:r AS uuid)")
				.bind("r", request.id()).then().block();
		repository.markExhaustedPreparations(100, 1).block();
		repository.markExhaustedPreparations(100, 1).block();
		var reviewed = repository.findClosureById(request.id()).block();
		assertThat(reviewed.claimToken()).isNull();
		assertThat(reviewed.status()).isEqualTo("preparing");
		assertThat(reviewed.errorCode()).isEqualTo("PREPARATION_RETRY_EXHAUSTED");
		assertThat(db.sql("SELECT state FROM account_closure_step WHERE closure_request_id = CAST(:r AS uuid)")
				.bind("r", request.id()).map(row -> row.get("state", String.class)).one().block())
				.isEqualTo("needs_review");
		assertThat(service.continueClaimedClosure(claim).block()).isNull();
		verify(domains, never()).prepareIntelligence(anyString(), anyString());
	}

	@Test
	void failedReleaseKeepsPreparingAndRetryBlocksSameRequest() {
		Seeded account = seedAccount("release-retry-" + UUID.randomUUID() + "@test.local");
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(ComplianceDomainClient.PrepareResult.ok()));
		var blocker = new ComplianceModels.DomainCheck(
				List.of(new ComplianceModels.Blocker("marketplace", "ACTIVE_ENGAGEMENT", "有进行中合作", 1, null)),
				List.of());
		when(domains.marketplaceCheck(anyString())).thenReturn(Mono.just(ComplianceModels.DomainCheck.empty()),
				Mono.just(blocker));
		when(domains.releaseIntelligence(anyString(), anyString()))
				.thenReturn(Mono.error(new IllegalStateException("release timeout")), Mono.just(true));
		var first = service.requestClosure(account.accountId()).block();
		assertThat(first.request().status()).isEqualTo("preparing");
		assertThat(first.request().claimToken()).isNull();
		var recovered = service.continueClosure(account.accountId(), first.request().id()).block();
		assertThat(recovered.request().id()).isEqualTo(first.request().id());
		assertThat(recovered.request().status()).isEqualTo("blocked");
		assertThat(db.sql("SELECT count(*) AS c FROM account_closure_request WHERE account_id = CAST(:a AS uuid)")
				.bind("a", account.accountId()).map(r -> r.get("c", Long.class)).one().block()).isEqualTo(1L);
	}

	@Test
	void concurrentRequestsCreateOnePreparingIntent() {
		Seeded account = seedAccount("parallel-prep-" + UUID.randomUUID() + "@test.local");
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(ComplianceDomainClient.PrepareResult.ok()));
		var outcomes = Mono
				.zip(service.requestClosure(account.accountId()), service.requestClosure(account.accountId())).block();
		assertThat(outcomes.getT1().request().id()).isEqualTo(outcomes.getT2().request().id());
		assertThat(repository.findActiveClosure(account.accountId()).block().status()).isEqualTo("retention");
		assertThat(db.sql("SELECT count(*) AS c FROM account_closure_request WHERE account_id = CAST(:a AS uuid)")
				.bind("a", account.accountId()).map(r -> r.get("c", Long.class)).one().block()).isEqualTo(1L);
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

	/**
	 * 任务书 #103 连带修复回归（V15 真实栈实锤）：claimDuePreparingClosures 的 RETURNING
	 * 列未加表前缀，UPDATE…FROM due 下 "id" 歧义（42702）——只要存在 preparing+retry_wait 真实数据
	 * worker 每 tick 必炸且注销永不推进。服务 seam 驱动 的用例测不到 repository 领取路径，这里用真实行直接打。
	 */
	@Test
	void claimDuePreparingClosuresWorksWithRetryWaitRows() {
		Seeded account = seedAccount("prep-claim-" + UUID.randomUUID() + "@test.local");
		when(domains.prepareIntelligence(anyString(), anyString()))
				.thenReturn(Mono.error(new IllegalStateException("intelligence unreachable")));
		ComplianceService.ClosureOutcome outcome = service.requestClosure(account.accountId()).block();
		assertThat(outcome.request().status()).isEqualTo("preparing");
		// retry_wait 步骤带退避 next_attempt_at，先拨到期再领取
		db.sql("UPDATE account_closure_step SET next_attempt_at = now() - interval '1 second'"
				+ " WHERE closure_request_id = CAST(:r AS uuid)").bind("r", outcome.request().id()).then().block();
		db.sql("UPDATE account_closure_request SET next_attempt_at = now() - interval '1 second'"
				+ " WHERE id = CAST(:r AS uuid)").bind("r", outcome.request().id()).then().block();
		// 领取到期 preparing 请求：修复前此处抛 BadSqlGrammar[column reference "id" is ambiguous]
		var claimed = repository.claimPreparingClosures(10, UUID.randomUUID(), java.time.Duration.ofSeconds(60), 5)
				.collectList().block();
		assertThat(claimed).isNotEmpty();
		assertThat(claimed).anySatisfy(row -> assertThat(row.id()).isEqualTo(outcome.request().id()));
	}
}
