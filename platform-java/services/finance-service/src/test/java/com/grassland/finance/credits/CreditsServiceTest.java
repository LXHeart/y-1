package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.ConsumeOperation;
import com.grassland.finance.credits.CreditsRepository.ExistingOperation;
import com.grassland.finance.credits.CreditsService.MutationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link CreditsService} 单测：dedup 预检、余额不足 402、23505 并发冲突重读、映射口径。
 * TransactionalOperator 打桩为透传（不启真事务），Repo 用 Mockito。端到端 SQL 行为由
 * {@code CreditsControllerIT} 覆盖。
 */
@SuppressWarnings("unchecked")
class CreditsServiceTest {

	private CreditsRepository repo;
	private TransactionalOperator tx;
	private CreditsService service;

	@BeforeEach
	void setUp() {
		repo = mock(CreditsRepository.class);
		tx = mock(TransactionalOperator.class);
		// 透传：不包真事务，让 repo 桩顺序组合
		when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
		// ensureAccount 默认完成
		when(repo.ensureAccount(anyString())).thenReturn(Mono.empty());
		service = new CreditsService(repo, tx);
	}

	@Test
	void consumeUpdatesBalanceAndInsertsConsumeTxn() {
		String acct = "acct-1";
		when(repo.lockOrCreateConsumeOperation(acct, "comedy_generation", "op-1", "open"))
				.thenReturn(Mono.just(operation("op-1", acct, "comedy_generation", "open", null, null, true)));
		when(repo.consumeOne(acct)).thenReturn(Mono.just(new CreditsAccount(acct, 4, 5, 1)));
		when(repo.insertTransaction(eq(acct), eq(-1), eq(4), eq("consume"), eq("comedy_generation"), eq(null),
				eq("op-1"))).thenReturn(Mono.just("txn-1"));
		when(repo.markConsumeOperationConsumed("op-1", "txn-1", 4)).thenReturn(Mono.just(true));

		StepVerifier.create(service.consume(acct, "comedy_generation", "op-1")).assertNext(r -> {
			assertThat(r.balance()).isEqualTo(4);
			assertThat(r.transactionId()).isEqualTo("txn-1");
			assertThat(r.deduplicated()).isFalse();
		}).verifyComplete();
	}

	@Test
	void consumeDeduplicatesWhenOperationIdExists() {
		String acct = "acct-2";
		when(repo.lockOrCreateConsumeOperation(acct, "article_generation", "op-2", "open"))
				.thenReturn(Mono.just(operation("op-2", acct, "article_generation", "consumed", "txn-prev", 3, false)));

		StepVerifier.create(service.consume(acct, "article_generation", "op-2")).assertNext(r -> {
			assertThat(r.balance()).isEqualTo(3);
			assertThat(r.transactionId()).isEqualTo("txn-prev");
			assertThat(r.deduplicated()).isTrue();
		}).verifyComplete();
		// 命中既有行 → 不改余额、不插流水
		verify(repo, never()).consumeOne(anyString());
		verify(repo, never()).insertTransaction(anyString(), anyInt(), anyInt(), anyString(), any(), any(), any());
	}

	@Test
	void consumeIsInsufficientWhenBalanceZero() {
		String acct = "acct-3";
		when(repo.lockOrCreateConsumeOperation(acct, "video_analysis", "op-3", "open"))
				.thenReturn(Mono.just(operation("op-3", acct, "video_analysis", "open", null, null, true)));
		when(repo.consumeOne(acct)).thenReturn(Mono.empty()); // 0 行 = 余额不足

		StepVerifier.create(service.consume(acct, "video_analysis", "op-3")).verifyErrorSatisfies(
				e -> assertThat(e).isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class,
						fe -> assertThat(fe.status()).isEqualTo(402)));
	}

	@Test
	void lateConsumeIsRejectedWhenCompensationWonTheFence() {
		String acct = "acct-4";
		when(repo.lockOrCreateConsumeOperation(acct, "image_analysis", "op-4", "open"))
				.thenReturn(Mono.just(operation("op-4", acct, "image_analysis", "compensated", null, null, false)));

		StepVerifier.create(service.consume(acct, "image_analysis", "op-4"))
				.verifyErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
						com.grassland.finance.security.FinanceException.class,
						exception -> assertThat(exception.status()).isEqualTo(409)));
		verify(repo, never()).consumeOne(anyString());
	}

	@Test
	void refundMapsBalanceAndCallsCreditAccount() {
		String acct = "acct-5";
		String operationId = "admin-refund-c-5";
		when(repo.findOperation(operationId)).thenReturn(Mono.empty());
		// refund: deltaBalance=+1, deltaEarned=0, deltaSpent=-1
		when(repo.creditAccount(acct, 1, 0, -1)).thenReturn(Mono.just(new CreditsAccount(acct, 5, 5, 0)));
		when(repo.insertTransaction(eq(acct), eq(1), eq(5), eq("refund"), eq("comedy_generation"), eq("note"),
				eq(operationId))).thenReturn(Mono.just("txn-r"));

		MutationResult r = service.refund(acct, 1, "comedy_generation", "note", operationId).block();
		assertThat(r.balance()).isEqualTo(5);
		verify(repo).creditAccount(acct, 1, 0, -1);
	}

	@Test
	void awardMapsBalanceAndEarned() {
		String acct = "acct-6";
		when(repo.findOperation(null)).thenReturn(Mono.empty());
		// award: deltaBalance=+3, deltaEarned=+3, deltaSpent=0
		when(repo.creditAccount(acct, 3, 3, 0)).thenReturn(Mono.just(new CreditsAccount(acct, 3, 3, 0)));
		when(repo.insertTransaction(eq(acct), eq(3), eq(3), eq("reward"), eq(null), eq("注册赠送"), eq(null)))
				.thenReturn(Mono.just("txn-a"));

		MutationResult r = service.award(acct, 3, "注册赠送", null).block();
		assertThat(r.balance()).isEqualTo(3);
		verify(repo).creditAccount(acct, 3, 3, 0);
	}

	@Test
	void balancesDelegatesToRepoForNonEmptyIds() {
		List<String> ids = List.of("a-1", "a-2");
		when(repo.findAccounts(ids))
				.thenReturn(Flux.just(new CreditsAccount("a-1", 5, 5, 0), new CreditsAccount("a-2", 0, 0, 0)));

		StepVerifier.create(service.balances(ids).collectList()).assertNext(accounts -> assertThat(accounts).hasSize(2))
				.verifyComplete();
		verify(repo).findAccounts(ids);
	}

	@Test
	void balancesIsEmptyForEmptyInput() {
		StepVerifier.create(service.balances(List.of())).verifyComplete();
		verify(repo, never()).findAccounts(any());
	}

	@Test
	void adminAdjustPositiveCreditsBalanceAndEarnedWithOperatorAudit() {
		String acct = "acct-admin-1";
		String operator = "op-account-1";
		String operationId = "admin_adjust:uuid-1";
		when(repo.findOperation(operationId)).thenReturn(Mono.empty());
		// 正向：deltaBalance=+5, deltaEarned=+5, deltaSpent=0（对齐 award/reward 口径）
		when(repo.creditAccount(acct, 5, 5, 0)).thenReturn(Mono.just(new CreditsAccount(acct, 105, 105, 0)));
		when(repo.insertTransaction(eq(acct), eq(5), eq(105), eq("admin_adjust"), eq("admin_adjust"), eq("调增"),
				eq(operationId), eq(operator))).thenReturn(Mono.just("txn-adj-1"));

		MutationResult r = service.adminAdjust(acct, 5, operator, "调增", operationId).block();
		assertThat(r.balance()).isEqualTo(105);
		assertThat(r.transactionId()).isEqualTo("txn-adj-1");
		assertThat(r.deduplicated()).isFalse();
		verify(repo).creditAccount(acct, 5, 5, 0);
		verify(repo, never()).adminDebit(anyString(), anyInt());
	}

	@Test
	void adminAdjustNegativeDebitsBalanceOnlyWithoutTouchingTotals() {
		String acct = "acct-admin-2";
		String operationId = "admin_adjust:uuid-2";
		when(repo.findOperation(operationId)).thenReturn(Mono.empty());
		// 负向：只减 balance（earned/spent 均不动），走条件 UPDATE 而非 creditAccount
		when(repo.adminDebit(acct, 5)).thenReturn(Mono.just(new CreditsAccount(acct, 95, 100, 3)));
		when(repo.insertTransaction(eq(acct), eq(-5), eq(95), eq("admin_adjust"), eq("admin_adjust"), eq("调减"),
				eq(operationId), eq("op-account-2"))).thenReturn(Mono.just("txn-adj-2"));

		MutationResult r = service.adminAdjust(acct, -5, "op-account-2", "调减", operationId).block();
		assertThat(r.balance()).isEqualTo(95);
		verify(repo).adminDebit(acct, 5);
		verify(repo, never()).creditAccount(anyString(), anyInt(), anyInt(), anyInt());
	}

	@Test
	void adminAdjustRejectsZeroOutOfBoundsBadKeyBlankNoteAndMissingOperator() {
		String acct = "acct-admin-3";
		String operator = "op-account-3";

		// 逐条断言 400 且零副作用
		assertAdminAdjust400(acct, 0, operator, "note", "admin_adjust:uuid");
		assertAdminAdjust400(acct, 1_000_001, operator, "note", "admin_adjust:uuid");
		assertAdminAdjust400(acct, -1_000_001, operator, "note", "admin_adjust:uuid");
		assertAdminAdjust400(acct, Integer.MIN_VALUE, operator, "note", "admin_adjust:uuid");
		assertAdminAdjust400(acct, 5, operator, "note", "reward:uuid");
		assertAdminAdjust400(acct, 5, operator, "note", "admin_adjust:");
		assertAdminAdjust400(acct, 5, operator, "note", "admin_adjust:" + "x".repeat(60));
		assertAdminAdjust400(acct, 5, operator, null, "admin_adjust:uuid");
		assertAdminAdjust400(acct, 5, operator, " ", "admin_adjust:uuid");
		assertAdminAdjust400(acct, 5, operator, "n".repeat(201), "admin_adjust:uuid");
		assertAdminAdjust400(acct, 5, null, "note", "admin_adjust:uuid");
		assertAdminAdjust400(acct, 5, " ", "note", "admin_adjust:uuid");
		verify(repo, never()).findOperation(any());
		verify(repo, never()).creditAccount(anyString(), anyInt(), anyInt(), anyInt());
		verify(repo, never()).adminDebit(anyString(), anyInt());
	}

	private void assertAdminAdjust400(String acct, int amount, String operator, String note, String operationId) {
		StepVerifier.create(service.adminAdjust(acct, amount, operator, note, operationId)).verifyErrorSatisfies(
				e -> assertThat(e).isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class,
						fe -> assertThat(fe.status()).isEqualTo(400)));
	}

	@Test
	void adminAdjustDeduplicatesMatchingOperationAndRejectsBindingMismatch() {
		String acct = "acct-admin-4";
		String operationId = "admin_adjust:uuid-4";
		when(repo.findOperation(operationId))
				.thenReturn(Mono.just(new ExistingOperation("txn-prev", 95, acct, "admin_adjust", "admin_adjust", -5)));

		// 同键同账号同金额 → dedup 返回原结果，零写入
		StepVerifier.create(service.adminAdjust(acct, -5, "op-4", "调减", operationId)).assertNext(r -> {
			assertThat(r.balance()).isEqualTo(95);
			assertThat(r.transactionId()).isEqualTo("txn-prev");
			assertThat(r.deduplicated()).isTrue();
		}).verifyComplete();
		verify(repo, never()).adminDebit(anyString(), anyInt());

		// 同键异金额 → 409
		when(repo.findOperation(operationId))
				.thenReturn(Mono.just(new ExistingOperation("txn-prev", 95, acct, "admin_adjust", "admin_adjust", -5)));
		StepVerifier.create(service.adminAdjust(acct, -3, "op-4", "调减", operationId)).verifyErrorSatisfies(
				e -> assertThat(e).isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class,
						fe -> assertThat(fe.status()).isEqualTo(409)));

		// 同键异账号 → 409
		when(repo.findOperation(operationId)).thenReturn(
				Mono.just(new ExistingOperation("txn-prev", 95, "acct-other", "admin_adjust", "admin_adjust", -5)));
		StepVerifier.create(service.adminAdjust(acct, -5, "op-4", "调减", operationId)).verifyErrorSatisfies(
				e -> assertThat(e).isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class,
						fe -> assertThat(fe.status()).isEqualTo(409)));
	}

	@Test
	void adminAdjustInsufficientBalanceYields402WithoutTransaction() {
		String acct = "acct-admin-5";
		String operationId = "admin_adjust:uuid-5";
		when(repo.findOperation(operationId)).thenReturn(Mono.empty());
		when(repo.adminDebit(acct, 5)).thenReturn(Mono.empty());

		StepVerifier.create(service.adminAdjust(acct, -5, "op-5", "调减", operationId)).verifyErrorSatisfies(
				e -> assertThat(e).isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class, fe -> {
					assertThat(fe.status()).isEqualTo(402);
					assertThat(fe.getMessage()).isEqualTo("积分余额不足");
				}));
		verify(repo, never()).insertTransaction(anyString(), anyInt(), anyInt(), anyString(), any(), any(), any());
		verify(repo, never()).insertTransaction(anyString(), anyInt(), anyInt(), anyString(), any(), any(), any(),
				any());
	}

	private static ConsumeOperation operation(String operationId, String accountId, String feature, String state,
			String consumeTransactionId, Integer balanceAfter, boolean created) {
		return new ConsumeOperation(operationId, accountId, feature, state, consumeTransactionId, null, balanceAfter,
				created);
	}
}
