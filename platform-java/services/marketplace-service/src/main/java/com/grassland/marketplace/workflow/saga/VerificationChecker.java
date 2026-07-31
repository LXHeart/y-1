package com.grassland.marketplace.workflow.saga;

/**
 * 履约核验检查 seam（Verification v1）：结算 capture 前重查该 engagement 是否有 {@code failed} 核验阻断。
 *
 * <p>镜像 {@link DisputeChecker}——与争议闸门<b>正交</b>，两者都过才 capture。只对 {@code status='failed'}
 * 阻断（inconclusive/passed/无记录 不阻断：商家手动 confirm/reject 自行决策，inconclusive 永不卡资金）。
 * 在 {@code SettlementActivityImpl.captureSettlement} 内 {@code block()} 调用（activity 线程可阻塞）。
 */
public interface VerificationChecker {

    /** 该 engagement（applicationId）是否有 failed 核验阻断结算。{@code organizationId} 预留给将来跨服务核验。 */
    boolean blocksSettlement(String organizationId, String engagementRef);
}
