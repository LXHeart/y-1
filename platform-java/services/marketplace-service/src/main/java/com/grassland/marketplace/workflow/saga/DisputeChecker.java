package com.grassland.marketplace.workflow.saga;

/**
 * 争议检查 seam（草场 Epic 5 Slice 5A / HLD 10.3：SettlementWindowWorkflow 窗口到期后查 Trust 开放争议）。
 *
 * <p>trust-service 尚未建，默认 {@link NoopDisputeChecker} 返回「无争议」；Trust 落地后提供真 bean
 * （{@code @ConditionalOnMissingBean(DisputeChecker.class)} 自动覆盖）。结算 capture 前重查争议（HLD 16）。
 */
public interface DisputeChecker {

    /** 该 engagement（applicationId）是否有未决争议阻止结算。{@code organizationId} 用于现签调 trust 的服务断言。 */
    boolean hasOpenDispute(String organizationId, String engagementRef);
}
