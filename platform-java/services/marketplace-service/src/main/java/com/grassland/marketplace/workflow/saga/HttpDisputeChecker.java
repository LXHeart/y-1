package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.workflow.TrustDisputeClient;
import org.springframework.stereotype.Component;

/**
 * 真 {@link DisputeChecker}（草场 Epic 6 Slice 6A）：经 {@link TrustDisputeClient} 同步 HTTP 查 trust 开放争议。
 *
 * <p>替代原 {@code NoopDisputeChecker}（已不注册为 bean）——本类是唯一 DisputeChecker {@code @Component}。
 * 在 Temporal activity（{@code SettlementActivityImpl}）线程内 {@code block()} 调用（活动线程可阻塞）；异常抛出由 Temporal 重试。
 */
@Component
public class HttpDisputeChecker implements DisputeChecker {

    private final TrustDisputeClient trust;

    public HttpDisputeChecker(TrustDisputeClient trust) {
        this.trust = trust;
    }

    @Override
    public boolean hasOpenDispute(String organizationId, String engagementRef) {
        return Boolean.TRUE.equals(trust.hasOpenDispute(organizationId, engagementRef).block());
    }
}
