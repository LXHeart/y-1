package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;

/**
 * 死信重投 / 弃置请求（GL-P1-OPS-001 Stage 2）。
 *
 * <p>{@code replay} 无默认值：默认重投会让误点变成一次真实的事件重放，默认弃置会静默丢消息。
 */
public record OpsDltActionRequest(Boolean replay, String operationId) {

    private static final int MAX_OPERATION_ID = 128;

    public boolean requireReplay() {
        if (replay == null) {
            throw new MarketplaceException(400, "replay 必填（true=重投，false=弃置）");
        }
        return replay;
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
