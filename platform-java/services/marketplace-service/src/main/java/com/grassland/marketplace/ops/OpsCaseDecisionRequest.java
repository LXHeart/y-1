package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;

/**
 * 审批请求（GL-P1-OPS-001 Stage 1）。{@code approve} 必填 —— 省略时若默认放行，
 * 一个字段名拼错的请求就会变成「通过」，而通过意味着资金处置动作被解锁（Stage 2）。
 */
public record OpsCaseDecisionRequest(Long expectedVersion, Boolean approve, String note) {

    public long requireExpectedVersion() {
        return OpsCaseActionRequest.require(expectedVersion);
    }

    public boolean requireApprove() {
        if (approve == null) {
            throw new MarketplaceException(400, "approve 必填");
        }
        return approve;
    }
}
