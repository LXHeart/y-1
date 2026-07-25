package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * {@link SettlementActivityImpl} 单元测试（草场 Epic 5 Slice 5A / HLD 9.2）：Mockito 桩 repo/finance/disputes，
 * 验证窗口到期 captureSettlement 的重验（accepted+confirmed）、争议 seam、capture 映射、幂等。
 */
@ExtendWith(MockitoExtension.class)
class SettlementActivityImplTest {

    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";

    @Mock private TaskApplicationRepository apps;
    @Mock private OutboxRepository outbox;
    @Mock private FinanceEscrowClient finance;
    @Mock private DisputeChecker disputes;

    private SettlementActivityImpl activity;
    private SettlementInput input;

    @BeforeEach
    void setUp() {
        activity = new SettlementActivityImpl(apps, outbox, finance, disputes);
        input = new SettlementInput(APP_ID, "11111111-1111-1111-1111-111111111111",
                "33333333-3333-3333-3333-333333333333", ORG, 500L, 0L);
    }

    @Test
    void settledWhenAcceptedConfirmedNoDispute() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", Instant.now())));
        when(disputes.hasOpenDispute(APP_ID)).thenReturn(false);
        when(finance.capture(ORG, APP_ID)).thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(activity.captureSettlement(input).status()).isEqualTo("settled");
        verify(finance).capture(ORG, APP_ID);
    }

    @Test
    void heldWhenDisputeOpen() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", Instant.now())));
        when(disputes.hasOpenDispute(APP_ID)).thenReturn(true);
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome r = activity.captureSettlement(input);
        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("open_dispute");
        verify(finance, never()).capture(anyString(), anyString());
    }

    @Test
    void abortedWhenNotAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending", Instant.now())));
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
        verify(disputes, never()).hasOpenDispute(anyString());
    }

    @Test
    void abortedWhenNotConfirmed() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", null)));  // confirmedAt null
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
        verify(disputes, never()).hasOpenDispute(anyString());
    }

    @Test
    void abortedWhenAppMissing() {
        when(apps.findById(APP_ID)).thenReturn(Mono.empty());
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
    }

    private TaskApplication app(String status, Instant confirmedAt) {
        return new TaskApplication(APP_ID, "11111111-1111-1111-1111-111111111111",
                "55555555-5555-5555-5555-555555555555", status, null,
                "33333333-3333-3333-3333-333333333333", null, null, null, confirmedAt);
    }
}
