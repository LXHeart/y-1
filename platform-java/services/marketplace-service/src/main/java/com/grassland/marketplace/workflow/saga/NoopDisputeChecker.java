package com.grassland.marketplace.workflow.saga;

/**
 * 争议检查占位实现（草场 Epic 4 Slice 4F 引入；Epic 6 Slice 6A 后生产 bean 已由 {@link HttpDisputeChecker} 取代）。
 *
 * <p>保留为非 bean 工具类（无 {@code @Component}），供测试/手动场景显式 new 作「无争议」fallback。
 * 生产装配用 {@link HttpDisputeChecker}（唯一 DisputeChecker bean，经 trust HTTP 查真争议）。
 */
public class NoopDisputeChecker implements DisputeChecker {

    @Override
    public boolean hasOpenDispute(String organizationId, String engagementRef) {
        return false;
    }
}
