package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.creationcontext.FrozenAiConfigResolver;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.event.OutboxRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.reactive.TransactionCallback;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    @InjectMocks
    AiExecutionService execution;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation -> Flux.from((Publisher<?>)
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null)));
    }

    @Test
    void workerPreparationUsesExplicitOwnershipWithoutResolvingHttpCaller() {
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
}
