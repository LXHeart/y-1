package com.grassland.marketplace.ops;

/**
 * 收单请求（GL-P1-OPS-001 Stage 1）。{@code resolution} 记处置结果（如 {@code compensated} / {@code discarded}），
 * Stage 1 不校验取值 —— 真正的动作枚举随 Stage 2 的重试/补偿落地，届时由动作本身决定 resolution。
 */
public record OpsCaseResolveRequest(Long expectedVersion, String resolution, String note) {

    public long requireExpectedVersion() {
        return OpsCaseActionRequest.require(expectedVersion);
    }
}
