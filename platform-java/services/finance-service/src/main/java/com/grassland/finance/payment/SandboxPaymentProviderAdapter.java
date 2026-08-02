package com.grassland.finance.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@link PaymentProviderAdapter} 的 sandbox/stub 实现（ADR-D01：真实 PSP 接入前的默认实现）。
 *
 * <p>只把外部资金移动记进账本（{@code EXTERNAL:sandbox} posting，由 {@code LedgerService} 写），
 * <b>不真发任何外部调用</b>。选定合作方后新增真实 adapter 并置 {@code finance.psp.mode=<provider>}，
 * 本实现因 {@code @ConditionalOnProperty} 不再装配——无需改 {@code LedgerService} 调用点。
 *
 * <p>刻意用 {@code @ConditionalOnProperty} 而非 {@code @ConditionalOnMissingBean}：后者对本工程 component-scan
 * 不可靠（trust {@code NoopDisputeChecker} 的教训），属性开关更稳。
 */
@Component
@ConditionalOnProperty(name = "finance.psp.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentProviderAdapter implements PaymentProviderAdapter {

    public static final String CHANNEL = "sandbox";

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public Mono<Void> recordExternalMovement(ExternalMovement movement) {
        // sandbox：账本 EXTERNAL:sandbox posting 已是资金记录，无外部调用。
        return Mono.empty();
    }
}
