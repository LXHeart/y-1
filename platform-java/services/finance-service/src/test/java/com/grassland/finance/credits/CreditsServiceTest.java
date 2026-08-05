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
import com.grassland.finance.credits.CreditsRepository.ExistingOperation;
import com.grassland.finance.credits.CreditsService.MutationResult;
import io.r2dbc.spi.R2dbcNonTransientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link CreditsService} 单测：dedup 预检、余额不足 402、23505 并发冲突重读、映射口径。
 * TransactionalOperator 打桩为透传（不启真事务），Repo 用 Mockito。端到端 SQL 行为由 {@code CreditsControllerIT} 覆盖。
 */
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
        when(repo.findOperation("op-1")).thenReturn(Mono.empty());
        when(repo.consumeOne(acct)).thenReturn(Mono.just(new CreditsAccount(acct, 4, 5, 1)));
        when(repo.insertTransaction(eq(acct), eq(-1), eq(4), eq("consume"), eq("comedy_generation"), eq(null), eq("op-1")))
                .thenReturn(Mono.just("txn-1"));

        StepVerifier.create(service.consume(acct, "comedy_generation", "op-1"))
                .assertNext(r -> {
                    assertThat(r.balance()).isEqualTo(4);
                    assertThat(r.transactionId()).isEqualTo("txn-1");
                    assertThat(r.deduplicated()).isFalse();
                }).verifyComplete();
    }

    @Test
    void consumeDeduplicatesWhenOperationIdExists() {
        String acct = "acct-2";
        when(repo.findOperation("op-2")).thenReturn(Mono.just(new ExistingOperation("txn-prev", 3)));

        StepVerifier.create(service.consume(acct, "article_generation", "op-2"))
                .assertNext(r -> {
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
        when(repo.findOperation("op-3")).thenReturn(Mono.empty());
        when(repo.consumeOne(acct)).thenReturn(Mono.empty());   // 0 行 = 余额不足

        StepVerifier.create(service.consume(acct, "video_analysis", "op-3"))
                .verifyErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(com.grassland.finance.security.FinanceException.class,
                                fe -> assertThat(fe.status()).isEqualTo(402)));
    }

    @Test
    void concurrentUniqueConflictReReadsWinnerAsDedup() {
        // 预检未命中、插入时另一请求已写入同 operation_id → 23505 → 事务回滚 → 事务外重读胜者。
        String acct = "acct-4";
        when(repo.findOperation("op-4")).thenReturn(Mono.empty())
                .thenReturn(Mono.just(new ExistingOperation("txn-winner", 2)));
        when(repo.consumeOne(acct)).thenReturn(Mono.just(new CreditsAccount(acct, 2, 5, 3)));
        when(repo.insertTransaction(eq(acct), eq(-1), eq(2), eq("consume"), eq("image_analysis"), eq(null), eq("op-4")))
                .thenReturn(Mono.error(new DataIntegrityViolationException("uq",
                        new R2dbcNonTransientException("unique violation", "23505") {})));

        StepVerifier.create(service.consume(acct, "image_analysis", "op-4"))
                .assertNext(r -> {
                    assertThat(r.deduplicated()).isTrue();
                    assertThat(r.balance()).isEqualTo(2);
                    assertThat(r.transactionId()).isEqualTo("txn-winner");
                }).verifyComplete();
    }

    @Test
    void refundMapsBalanceAndCallsCreditAccount() {
        String acct = "acct-5";
        when(repo.findOperation("refund:c-5")).thenReturn(Mono.empty());
        // refund: deltaBalance=+1, deltaEarned=0, deltaSpent=-1
        when(repo.creditAccount(acct, 1, 0, -1)).thenReturn(Mono.just(new CreditsAccount(acct, 5, 5, 0)));
        when(repo.insertTransaction(eq(acct), eq(1), eq(5), eq("refund"), eq("comedy_generation"), eq("note"), eq("refund:c-5")))
                .thenReturn(Mono.just("txn-r"));

        MutationResult r = service.refund(acct, 1, "comedy_generation", "note", "refund:c-5").block();
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
}
