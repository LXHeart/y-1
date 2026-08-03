package com.grassland.marketplace.workflow.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** D-03 商家拒绝客服 SLA 到期活动：trust 仍未终局则默认 for_recommender；trust 端幂等。 */
@ActivityInterface
public interface MerchantRejectionReviewActivity {

    @ActivityMethod
    void autoFinalize(MerchantRejectionReviewInput input);
}
