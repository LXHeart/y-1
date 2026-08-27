package com.grassland.identity.organization.subaccount;

/** 店长代建员工的审核决定（任务书 #48 D6）：approve=过审转 active；reject=拒绝转 rejected（终态）。 */
public record SubAccountReviewRequest(String decision) {

    public boolean isApprove() {
        return "approve".equalsIgnoreCase(decision);
    }
}
