package com.grassland.intelligence.credits;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import reactor.core.publisher.Mono;

/**
 * {@link CreditsClient} mock 的默认桩：consume 返回按入参构造的 {@link CreditCharge}，
 * refund 返回空 Mono。集中一处，避免 10 个 IT 各写一遍 charge 构造。
 */
public final class CreditsStubs {

    private CreditsStubs() {
    }

    /**
     * consume 成功（charge 由实际入参派生，便于断言 refund 用了同一 operationId）。
     *
     * <p>{@code feature} 允许为 null：用 {@code when(consume(any(), any()))} 覆盖既有桩时，
     * Mockito 会以 null 入参真实调用一次 mock，从而触发本默认 answer。
     */
    public static Mono<CreditCharge> charge(String accountId, CreditFeature feature) {
        String suffix = feature == null ? "unknown" : feature.key();
        return Mono.just(new CreditCharge(accountId, feature, "test-op-" + suffix));
    }

    /** 给 mock 装上 consume 成功 + refund 空实现的默认行为。 */
    public static void stubDefaults(CreditsClient credits) {
        when(credits.consume(any(), any())).thenAnswer(invocation ->
                charge(invocation.getArgument(0), invocation.getArgument(1)));
        when(credits.consume(any(), any(), any())).thenAnswer(invocation ->
                Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.reserveUsage(any(), any(), any(), anyLong(), any())).thenAnswer(invocation ->
                Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        CreditCharge.Source.PAID, null, true, invocation.getArgument(4),
                        invocation.getArgument(3), 1)));
        when(credits.settleUsage(any(), anyLong(), any())).thenAnswer(invocation -> {
            CreditCharge charge = invocation.getArgument(0);
            long actualCents = invocation.getArgument(1);
            return Mono.just(new CreditSettlement(
                    charge.accountId(), charge.feature(), charge.operationId(), charge.source(),
                    invocation.getArgument(2), charge.reservedCents(), charge.reservedCredits(),
                    actualCents, 1, 0, false));
        });
        when(credits.refund(any(), any())).thenReturn(Mono.empty());
        when(credits.compensate(any(), any())).thenReturn(Mono.empty());
    }
}
