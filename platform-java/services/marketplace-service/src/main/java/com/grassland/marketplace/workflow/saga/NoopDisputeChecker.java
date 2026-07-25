package com.grassland.marketplace.workflow.saga;

import org.springframework.stereotype.Component;

/**
 * 默认争议检查（草场 Epic 5 Slice 5A 占位）：恒返回 false（无开放争议）。
 *
 * <p>trust-service 落地后以其 {@link DisputeChecker} bean 替代——届时改本类为 {@code @ConditionalOnProperty} 开关或
 * 直接移除由 Trust 提供唯一实现。本 slice 仅占位，故用普通 {@code @Component}（不用 {@code @ConditionalOnMissingBean}，
 * 该注解对 component-scan 的 {@code @Component} 不可靠）。
 */
@Component
public class NoopDisputeChecker implements DisputeChecker {

    @Override
    public boolean hasOpenDispute(String engagementRef) {
        return false;
    }
}
