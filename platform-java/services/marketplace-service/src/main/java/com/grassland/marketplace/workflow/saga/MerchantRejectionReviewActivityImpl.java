package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.workflow.TrustDisputeClient;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/** D-03 客服 SLA 到期活动实现。trust 端只对 merchant_rejection 生效，已终局时幂等 no-op。 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class MerchantRejectionReviewActivityImpl implements MerchantRejectionReviewActivity {

    private final TrustDisputeClient trust;

    public MerchantRejectionReviewActivityImpl(TrustDisputeClient trust) {
        this.trust = trust;
    }

    @Override
    public void autoFinalize(MerchantRejectionReviewInput input) {
        trust.autoFinalizeMerchantRejection(input.organizationId(), input.disputeId()).block();
    }
}
