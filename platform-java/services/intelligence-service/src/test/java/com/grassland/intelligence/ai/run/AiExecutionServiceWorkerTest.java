package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.creationcontext.FrozenAiConfigResolver;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsCentsPolicyProperties;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.reactive.TransactionCallback;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class AiExecutionServiceWorkerTest {

    @Mock
    ModelBudgetService budgetService;
    @Mock
    ByokRoutingService routingService;
    @Mock
    PriceTableService priceTableService;
    @Mock
    IntelligenceCallerResolver callers;
    @Mock
    CreditsClient credits;
    @Mock
    ObjectProvider<EnvelopeEncryption> encryptionProvider;
    @Mock
    OutboxRepository outbox;
    @Mock
    TransactionalOperator transactions;
    @Mock
    CreditCompensationRepository compensationRepository;
    @Mock
    CreditCompensationDispatcher compensationDispatcher;
    @Mock
    FrozenAiConfigResolver frozenAiConfigs;
    @Mock
    CreditsCentsPolicyProperties creditsCentsPolicy;
    @Mock
    CreditUsageSettlementRepository usageSettlements;
    @InjectMocks
    AiExecutionService execution;

    @Test
    void pricedPlatformRunReservesConvertedUsageUnderFrozenPolicy() {
        passthroughTransactions();
        when(creditsCentsPolicy.activeVersion()).thenReturn(Optional.of("money-v1"));
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://example.invalid", "priced-model", 4, null);
        UUID runId = UUID.randomUUID();
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(priceTableService.calculateCost("priced-model", 40, 0, 0, 0)).thenReturn(300);
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 40, 300))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 300)));
        when(budgetService.createRun(any())).thenReturn(Mono.just(runId));
        when(credits.reserveUsage(
                eq("acct-1"), eq(CreditFeature.AI_RUN_EMBEDDING), any(), eq(300L), eq("money-v1")))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        "acct-1", CreditFeature.AI_RUN_EMBEDDING, invocation.getArgument(2),
                        CreditCharge.Source.PAID, 7L, true, "money-v1", 300, 3)));

        AiExecutionService.ExecutionResult result = execution.prepareExecution(
                "acct-1", "org-1", "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                40, 0, true).block();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isTrue();
        assertThat(result.context().creditsCentsPolicyVersion()).isEqualTo("money-v1");
        assertThat(result.context().charge().reservedCredits()).isEqualTo(3);
        ArgumentCaptor<AiRun> run = ArgumentCaptor.forClass(AiRun.class);
        verify(budgetService).createRun(run.capture());
        assertThat(run.getValue().creditsCentsPolicyVersion()).isEqualTo("money-v1");
        verify(credits).reserveUsage(
                eq("acct-1"), eq(CreditFeature.AI_RUN_EMBEDDING),
                eq(run.getValue().operationId().toString()), eq(300L), eq("money-v1"));
        verify(credits, never()).consume(any(), any(), any());
    }

    @Test
    void pricedModelWithZeroEstimateStillCreatesUsageReservation() {
        passthroughTransactions();
        when(creditsCentsPolicy.activeVersion()).thenReturn(Optional.of("money-v1"));
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://example.invalid", "priced-model", 4, null);
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(priceTableService.calculateCost("priced-model", 0, 0, 0, 0)).thenReturn(0);
        when(priceTableService.isZeroPricedModel("priced-model")).thenReturn(false);
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 0, 0))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.allowed(null, null, 0, 0)));
        when(budgetService.createRun(any())).thenReturn(Mono.just(UUID.randomUUID()));
        when(credits.reserveUsage(
                eq("acct-1"), eq(CreditFeature.AI_RUN_EMBEDDING), any(), eq(0L), eq("money-v1")))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        "acct-1", CreditFeature.AI_RUN_EMBEDDING, invocation.getArgument(2),
                        CreditCharge.Source.PAID, 7L, true, "money-v1", 0, 0)));

        AiExecutionService.ExecutionResult result = execution.prepareExecution(
                "acct-1", "org-1", "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                0, 0, true).block();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isTrue();
        assertThat(result.context().creditsCentsPolicyVersion()).isEqualTo("money-v1");
        assertThat(result.context().creditCompensationRequired()).isTrue();
        verify(credits).reserveUsage(
                eq("acct-1"), eq(CreditFeature.AI_RUN_EMBEDDING), any(), eq(0L), eq("money-v1"));
    }

    @Test
    void zeroCostSandboxRunIsFreeWithoutReadingMoneyPolicy() {
        passthroughTransactions();
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 4, null);
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(priceTableService.calculateCost("sandbox-embedding-v1", 40, 0, 0, 0)).thenReturn(0);
        when(priceTableService.isZeroPricedModel("sandbox-embedding-v1")).thenReturn(true);
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 40, 0))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 0)));
        when(budgetService.createRun(any())).thenReturn(Mono.just(UUID.randomUUID()));

        AiExecutionService.ExecutionResult result = execution.prepareExecution(
                "acct-1", "org-1", "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                40, 0, true).block();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isTrue();
        assertThat(result.context().creditCompensationRequired()).isFalse();
        assertThat(result.context().creditsCentsPolicyVersion()).isNull();
        verify(creditsCentsPolicy, never()).activeVersion();
        verify(credits, never()).reserveUsage(any(), any(), any(), anyLong(), any());
        verify(credits, never()).consume(any(), any(), any());
    }

    @Test
    void byokRunNeverChargesPlatformCredits() {
        passthroughTransactions();
        ProviderResolution provider = ProviderResolution.byok(
                "openai-compatible", "https://api.example.invalid", "embed-v1", "ciphertext", "v1");
        EnvelopeEncryption encryption = mock(EnvelopeEncryption.class);
        when(encryptionProvider.getIfAvailable()).thenReturn(encryption);
        when(encryption.decrypt("ciphertext")).thenReturn("decrypted-key");
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(budgetService.checkAndReserve("org-1", "retrieval", "openai-compatible", 40, 0))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 0)));
        when(budgetService.createRun(any())).thenReturn(Mono.just(UUID.randomUUID()));

        AiExecutionService.ExecutionResult result = execution.prepareExecution(
                "acct-1", "org-1", "retrieval", CreditFeature.AI_RUN_EMBEDDING,
                40, 0, true).block();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isTrue();
        assertThat(result.context().charge()).isNull();
        assertThat(result.context().creditCompensationRequired()).isFalse();
        verify(credits, never()).reserveUsage(any(), any(), any(), anyLong(), any());
        verify(credits, never()).consume(any(), any(), any());
    }

    @Test
    void successfulPricedRunEnqueuesSettlementWithCompletionTransaction() {
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID runId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://example.invalid", "priced-model", 4, null);
        ModelBudgetService.BudgetCheckResult budget =
                ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 300);
        CreditCharge charge = new CreditCharge(
                "acct-1", CreditFeature.AI_RUN_EMBEDDING, operationId.toString(),
                CreditCharge.Source.PAID, 7L, true, "money-v1", 300, 3);
        var context = new AiExecutionService.ExecutionContext(
                runId, "org-1", "acct-1", "retrieval", provider, budget,
                operationId, charge, CreditFeature.AI_RUN_EMBEDDING, true,
                null, "v1", 40, 0, "money-v1");
        when(budgetService.completeRun(runId, 100, 20, 5, 0, 0)).thenReturn(Mono.just(true));
        when(budgetService.settleReservation(budget, 25, 100)).thenReturn(Mono.just(true));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(usageSettlements.enqueue(
                runId, operationId, "acct-1", "ai_run_embedding", "money-v1", 100))
                .thenReturn(Mono.empty());

        StepVerifier.create(execution.settleSuccessWithCost(context, 100, 20, 5, 0, 0))
                .expectNext(true)
                .verifyComplete();

        verify(usageSettlements).enqueue(
                runId, operationId, "acct-1", "ai_run_embedding", "money-v1", 100);
        verify(transactions).transactional(any(Mono.class));
    }

    @Test
    void userCancellationSettlesPricedReservationWithoutRefundingIt() {
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID runId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "qwen", "https://example.invalid", "priced-model", 4, null);
        ModelBudgetService.BudgetCheckResult budget =
                ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 300);
        CreditCharge charge = new CreditCharge(
                "acct-1", CreditFeature.AI_RUN_EMBEDDING, operationId.toString(),
                CreditCharge.Source.PAID, 7L, true, "money-v1", 300, 3);
        var context = new AiExecutionService.ExecutionContext(
                runId, "org-1", "acct-1", "retrieval", provider, budget,
                operationId, charge, CreditFeature.AI_RUN_EMBEDDING, true,
                null, "v1", 40, 0, "money-v1");
        when(budgetService.cancelRun(runId)).thenReturn(Mono.just(true));
        when(budgetService.releaseReservation(budget)).thenReturn(Mono.just(true));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(usageSettlements.enqueue(
                runId, operationId, "acct-1", "ai_run_embedding", "money-v1", 300))
                .thenReturn(Mono.empty());

        StepVerifier.create(execution.handleCancellation(context))
                .expectNext(true)
                .verifyComplete();

        verify(usageSettlements).enqueue(
                runId, operationId, "acct-1", "ai_run_embedding", "money-v1", 300);
        verify(compensationRepository, never()).enqueue(any(), any(), any(), any(), any());
        verify(credits, never()).refund(any(), any());
    }

    @Test
    void workerPreparationUsesExplicitOwnershipWithoutResolvingHttpCaller() {
        passthroughTransactions();
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 4, null);
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 40, 0))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.allowed(null, null, 40, 0)));
        when(priceTableService.calculateCost("sandbox-embedding-v1", 40, 0, 0, 0)).thenReturn(0);
        when(budgetService.createRun(any())).thenReturn(Mono.just(UUID.randomUUID()));

        StepVerifier.create(execution.prepareExecution(
                        "acct-1", "org-1", "retrieval", null, 40, 0, true))
                .assertNext(result -> assertThat(result.allowed()).isTrue())
                .verifyComplete();

        verify(routingService).resolveProvider("org-1", "acct-1", "retrieval", true);
        verify(budgetService).checkAndReserve("org-1", "retrieval", "platform", 40, 0);
        verify(callers, never()).resolve(any());
        verify(credits, never()).consume(eq("acct-1"), any(), any());
    }

    @Test
    void cancellationDuringCommittedPreparationHandoffCancelsRunAndReleasesBudget() throws Exception {
        UUID budgetId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ModelBudgetService.BudgetCheckResult reservation = ModelBudgetService.BudgetCheckResult.allowed(
                budgetId, LocalDate.now(), 40, 100);
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 4, null);
        CountDownLatch chargeHandoffEntered = new CountDownLatch(1);
        when(creditsCentsPolicy.activeVersion()).thenReturn(Optional.of("money-v1"));
        doAnswer(invocation -> Flux.from((Publisher<?>)
                        invocation.getArgument(0, TransactionCallback.class).doInTransaction(null)))
                .when(transactions).execute(any());
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 40, 100))
                .thenReturn(Mono.just(reservation));
        when(priceTableService.calculateCost("sandbox-embedding-v1", 40, 0, 0, 0)).thenReturn(100);
        when(budgetService.createRun(any())).thenReturn(Mono.just(runId));
        when(credits.reserveUsage(
                eq("acct-1"), eq(CreditFeature.AI_RUN_EMBEDDING), any(), eq(100L), eq("money-v1")))
                .thenReturn(Mono.defer(() -> {
                    chargeHandoffEntered.countDown();
                    return Mono.never();
                }));
        when(budgetService.cancelRun(runId)).thenReturn(Mono.just(true));
        when(budgetService.releaseReservation(reservation)).thenReturn(Mono.just(true));
        when(compensationRepository.enqueue(
                eq(runId), any(UUID.class), eq("acct-1"), eq("ai_run_embedding"), any()))
                .thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(compensationDispatcher.dispatchRun(runId)).thenReturn(Mono.empty());

        var subscription = execution.prepareExecution(
                        "acct-1", "org-1", "retrieval", CreditFeature.AI_RUN_EMBEDDING, 40, 0, true)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> { }, ignored -> { });
        assertThat(chargeHandoffEntered.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        verify(budgetService, timeout(2_000)).cancelRun(runId);
        verify(budgetService, timeout(2_000)).releaseReservation(reservation);
    }

    @Test
    void deniedPreparationDoesNotAttemptRunCleanup() {
        passthroughTransactions();
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 4, null);
        when(routingService.resolveProvider("org-1", "acct-1", "retrieval", true))
                .thenReturn(Mono.just(provider));
        when(budgetService.checkAndReserve("org-1", "retrieval", "platform", 40, 0))
                .thenReturn(Mono.just(ModelBudgetService.BudgetCheckResult.denied("exceeds_daily_budget")));
        when(priceTableService.calculateCost("sandbox-embedding-v1", 40, 0, 0, 0)).thenReturn(0);

        StepVerifier.create(execution.prepareExecution(
                        "acct-1", "org-1", "retrieval", null, 40, 0, true))
                .assertNext(result -> {
                    assertThat(result.allowed()).isFalse();
                    assertThat(result.denialReason()).isEqualTo("exceeds_daily_budget");
                })
                .verifyComplete();

        verify(budgetService, never()).cancelRun(any());
        verify(budgetService, never()).releaseReservation(any());
        verify(outbox, never()).append(any());
    }

    private void passthroughTransactions() {
        when(transactions.execute(any())).thenAnswer(invocation -> Flux.from((Publisher<?>)
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null)));
    }
}
