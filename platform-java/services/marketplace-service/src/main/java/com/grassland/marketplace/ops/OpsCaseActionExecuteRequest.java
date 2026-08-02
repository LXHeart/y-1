package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;

/**
 * 执行受限处置动作的请求（GL-P1-OPS-001 Stage 2）。
 *
 * <p>{@code operationId} <b>必填且由调用方生成</b>：服务端生成就没有幂等性可言 —— 网络重试时
 * 客户端拿不回同一个键，会重复执行下游资金动作。同 credits bridge 的口径。
 */
public record OpsCaseActionExecuteRequest(String action, String operationId) {

    /** 幂等键上限：足够放 UUID 或 `case:action:attempt` 这类可读键，又不至于被塞进整段 JSON。 */
    private static final int MAX_OPERATION_ID = 128;

    public String requireAction() {
        if (action == null || action.isBlank()) {
            throw new MarketplaceException(400, "action 必填");
        }
        return action;
    }

    public String requireOperationId() {
        if (operationId == null || operationId.isBlank()) {
            throw new MarketplaceException(400, "operationId 必填（幂等键，须由调用方生成）");
        }
        if (operationId.length() > MAX_OPERATION_ID) {
            throw new MarketplaceException(400, "operationId 过长");
        }
        return operationId;
    }
}
