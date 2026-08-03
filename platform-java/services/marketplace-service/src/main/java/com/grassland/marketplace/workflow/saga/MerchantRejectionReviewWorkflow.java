package com.grassland.marketplace.workflow.saga;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** D-03 商家拒绝转客服：等待客服 SLA，超时按系统核实结果默认结算。 */
@WorkflowInterface
public interface MerchantRejectionReviewWorkflow {

    @WorkflowMethod
    void run(MerchantRejectionReviewInput input);
}
