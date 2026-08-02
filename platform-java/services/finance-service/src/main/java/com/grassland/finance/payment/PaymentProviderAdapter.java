package com.grassland.finance.payment;

import reactor.core.publisher.Mono;

/**
 * 支付/存管供应商适配端口（HLD §12.1/§12.2 内部端口 {@code PaymentProviderAdapter}，ADR-D01）。
 *
 * <p>草场资金通过此 seam 与外部平台（银行存管 / PSP 分账）对接。<b>当前首期</b>只有
 * {@link SandboxPaymentProviderAdapter}（只写内部账本，不真发外部调用）；选定合作方后实现真实 adapter，
 * 在 {@link #recordExternalMovement(ExternalMovement)} 及下述扩展点接入 createPaymentIntent / payout /
 * refund / 对账文件。
 *
 * <p><b>供应商 DTO 停留在 adapter 层，不进入领域模型</b>（HLD §12.2）。
 *
 * <p>扩展点（HLD §12.2，真实 PSP 接入时补签名，不在首期范围）：
 * <pre>
 *   Mono&lt;ProviderPaymentSession&gt; createPaymentIntent(PaymentIntentCommand command);
 *   Mono&lt;RefundResult&gt;               refund(RefundCommand command);
 *   Mono&lt;TransferResult&gt;             createTransferOrSplit(TransferCommand command);
 *   Mono&lt;ReconciliationRecords&gt;      importReconciliation(String statementRef);
 * </pre>
 */
public interface PaymentProviderAdapter {

    /** 通道标识（账本 {@code EXTERNAL} 账户的 owner）；sandbox 返回 {@code "sandbox"}。 */
    String channel();

    /**
     * 记录一笔外部资金移动（资金入托管 / 出账提现）。
     *
     * <p>sandbox：no-op（账本的 {@code EXTERNAL:sandbox} posting 已是记录）。真实 PSP：发起并确认
     * {@code createPaymentIntent}（入账）或 {@code payout}（出账），返回供应商交易号供对账。
     * 调用方在账本记账的同一事务内调用，使「内部账本 + 外部发起」同生共死（真实 PSP 的异步回调另经 webhook 入账）。
     */
    Mono<Void> recordExternalMovement(ExternalMovement movement);
}
